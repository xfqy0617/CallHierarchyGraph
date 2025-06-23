package org.xfqy.callhierarchygraph.plugin

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory
import org.xfqy.callhierarchygraph.visualizer.CallHierarchyVisualizer

// ... other code in your class ...

class CallHierarchyGraphAction : AnAction("分析方法调用链...") { // 更新了文本

    private val consoleViewMap = mutableMapOf<Project, ConsoleView>()

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * 更新 Action 的状态。
     * 当右键点击的是一个类，或者在一个方法的内部/名称上时，启用 Action。
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)

        if (project == null || psiElement == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // 检查元素是否是 PsiClass 或 PsiMethod
        val psiClass = when (psiElement) {
            is PsiClass -> psiElement
            is PsiMethod -> psiElement.containingClass
            else -> null
        }

        // 如果能找到对应的类，并且这个类有方法，就启用 Action
        e.presentation.isEnabledAndVisible = psiClass != null && psiClass.methods.isNotEmpty()
    }


    /**
     * 执行 Action
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT) ?: return

        val targetClass: PsiClass?
        val preselectedMethod: PsiMethod?

        when (psiElement) {
            is PsiClass -> {
                targetClass = psiElement
                preselectedMethod = null
            }

            is PsiMethod -> {
                targetClass = psiElement.containingClass
                preselectedMethod = psiElement
            }

            else -> {
                val method = PsiTreeUtil.getParentOfType(psiElement, PsiMethod::class.java)
                if (method != null) {
                    targetClass = method.containingClass
                    preselectedMethod = method
                } else {
                    // 如果在方法体外但在类内部，尝试找到类
                    targetClass = PsiTreeUtil.getParentOfType(psiElement, PsiClass::class.java)
                    preselectedMethod = null
                }
            }
        }

        // 如果没有找到一个有效的类作为起点，则不执行任何操作
        if (targetClass == null || targetClass.methods.isEmpty()) {
            return
        }

        // [修改] 调用新的对话框构造函数，传入初始类和预选方法
        val dialog = SelectMethodsDialog(project, targetClass, preselectedMethod)

        if (dialog.showAndGet()) {
            val selectedMethods = dialog.getSelectedMethods()

            if (selectedMethods.isNotEmpty()) {
                val consoleView = getOrCreateConsole(project)
                consoleView.clear()
                runAnalysisInBackground(project, selectedMethods)
            }
        }
    }


    /**
     * [重构] 将分析逻辑封装到一个单独的方法中，以便在后台任务中调用
     */
    private fun runAnalysisInBackground(
        project: Project,
        methods: List<PsiMethod>,
        fileName: String = "call_hierarchy"
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "分析方法调用链", true) {
                override fun run(indicator: ProgressIndicator) {
                    // 1. 使用 StringBuilder 来高效地拼接所有结果
                    val finalResultBuilder = StringBuilder()

                    methods.forEachIndexed { index, targetMethod ->
                        // 在多个方法的分析结果之间添加分隔符
                        if (index > 0) {
                            finalResultBuilder.append("\n")
                        }

                        indicator.text = "正在分析: ${targetMethod.name}"

                        // 2. 将目标方法自身的信息先添加到结果中
                        finalResultBuilder.append(formatMethod(targetMethod)).append("\n")

                        // 3. 递归查找调用链，并获取结果字符串列表
                        val callChainLines = findAndPrintCallers(
                            targetMethod,
                            project,
                            1,
                            setOf(targetMethod),
                            indicator
                        )

                        // 4. 将调用链结果追加到总结果中
                        finalResultBuilder.append(callChainLines.joinToString("\n"))

                        // 检查任务是否被取消
                        indicator.checkCanceled()
                    }

                    // 5. todo ddd 在所有任务完成后，一次性打印到控制台
                    println(finalResultBuilder.toString())
                    val visualizer = CallHierarchyVisualizer(4)
                    visualizer.parseAndBuildGraph(finalResultBuilder.toString())
                    visualizer.renderGraph(fileName, false, "html")
                }
            }
        )
    }

    /**
     * [重构] 递归查找方法的调用者，并返回字符串列表
     *
     * @return 返回一个包含所有调用链行的字符串列表
     */
    private fun findAndPrintCallers(
        method: PsiMethod,
        project: Project,
        indentLevel: Int,
        path: Set<PsiMethod>,
        indicator: ProgressIndicator
    ): List<String> {
        indicator.checkCanceled()

        // 存放当前层级及所有子层级的结果
        val resultLines = mutableListOf<String>()

        val callingMethods = ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(method, searchScope).findAll()
            references
                .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
                .distinct()
        }

        for (caller in callingMethods) {
            val isInPath = ApplicationManager.getApplication().runReadAction<Boolean> { caller in path }
            if (isInPath) {
                val indent = " ".repeat(4 * indentLevel)
                // 将递归提示信息也加入结果列表
                resultLines.add("$indent[... Recursive call to ${formatMethod(caller)} ...]")
                continue
            }

            val indent = " ".repeat(4 * indentLevel)
            // 将当前调用者信息加入结果列表
            resultLines.add("$indent${formatMethod(caller)}")

            val newPath = ApplicationManager.getApplication().runReadAction<Set<PsiMethod>> { path + method }

            // 递归调用，并将其返回的子调用链结果全部添加到当前结果列表中
            val subCallLines = findAndPrintCallers(caller, project, indentLevel + 1, newPath, indicator)
            resultLines.addAll(subCallLines)
        }

        return resultLines
    }

// formatMethod 方法保持不变，它仍然返回单个格式化的字符串，这很适合被复用。
// ...
    /**
     * 格式化 PsiMethod 的输出 (修正版)
     */
    private fun formatMethod(method: PsiMethod): String {
        // [修正] 将整个函数体包裹在 runReadAction 中
        return ApplicationManager.getApplication().runReadAction<String> {
            val containingClass = method.containingClass

            val className: String
            if (containingClass is PsiAnonymousClass) {
                val contextMethod = PsiTreeUtil.getParentOfType(containingClass, PsiMethod::class.java, true)
                val contextDescription = if (contextMethod != null) {
                    val outerClassName = contextMethod.containingClass?.name ?: ""
                    " in ${contextMethod.name}() in $outerClassName"
                } else {
                    val outerClass =
                        PsiTreeUtil.getParentOfType(containingClass, com.intellij.psi.PsiClass::class.java, true)
                    if (outerClass != null) " in ${outerClass.name}" else ""
                }
                className = "Anonymous$contextDescription"
            } else {
                className = containingClass?.name ?: "UnknownClass"
            }

            val methodName = method.name
            val params = method.parameterList.parameters
                .joinToString(", ") { it.type.presentableText }

            val packageName = (method.containingFile as? PsiJavaFile)?.packageName
                ?: (JavaDirectoryService.getInstance()
                    .getPackage(method.containingFile.containingDirectory!!)?.qualifiedName ?: "")

            // lambda 表达式的最后一行是返回值
            "$className.$methodName($params)  ($packageName)"
        }
    }

    /**
     * 获取或创建一个新的控制台 Tool Window (保持不变)
     */
    private fun getOrCreateConsole(project: Project): ConsoleView {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindowId = "Method Callers"
        var toolWindow = toolWindowManager.getToolWindow(toolWindowId)

        val consoleView: ConsoleView
        if (toolWindow != null && consoleViewMap.containsKey(project)) {
            consoleView = consoleViewMap[project]!!
        } else {
            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow(toolWindowId) {
                    this.canCloseContent = true
                }
            }
            val consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            consoleView = consoleBuilder.console
            val content = ContentFactory.getInstance().createContent(consoleView.component, "调用链分析", false)
            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(content)
            consoleViewMap[project] = consoleView
        }

        toolWindow.show(null)
        return consoleView
    }
}
