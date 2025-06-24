package org.xfqy.callhierarchygraph.plugin

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory
import org.xfqy.callhierarchygraph.visualizer.CallHierarchyVisualizer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

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

            // [新增] 从对话框获取路径和文件名
            val outputPath = dialog.getOutputPath()
            val outputFilename = dialog.getOutputFilename()
            val selectedScope = dialog.getSelectedScope() // [新增] 获取选择的作用域

            // 简单的验证
            if (selectedMethods.isNotEmpty() && outputPath.isNotBlank() && outputFilename.isNotBlank()) {
                val consoleView = getOrCreateConsole(project)
                consoleView.clear()
                // [修改] 将新获取的值传递给后台任务
//                runAnalysisInBackground(project, selectedMethods, consoleView, outputPath, outputFilename)
                runAnalysisInBackground(project, selectedMethods, consoleView, outputPath, outputFilename, selectedScope)
            }
        }
    }


    /**
     * [重构] 将分析逻辑封装到一个单独的方法中，以便在后台任务中调用
     */
    private fun runAnalysisInBackground(
        project: Project,
        methods: List<PsiMethod>,
        consoleView: ConsoleView,
        outputPath: String,
        outputFilename: String,
        scope: AnalysisScope,
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
                            indicator,
                            scope
                        )

                        // 4. 将调用链结果追加到总结果中
                        finalResultBuilder.append(callChainLines.joinToString("\n"))

                        // 检查任务是否被取消
                        indicator.checkCanceled()
                    }

                    try {
                        val visualizer = CallHierarchyVisualizer(4)
                        visualizer.parseAndBuildGraph(finalResultBuilder.toString())
                        // [修改] 使用从对话框传入的参数
                        visualizer.renderGraph(outputFilename, true, "html", outputPath)
                        consoleView.print("图表已成功导出！\n 路径为:${outputPath}${File.separator}${outputFilename}", ConsoleViewContentType.SYSTEM_OUTPUT)
                    } catch (e: Exception) {
                        // 打印更详细的错误到控制台
                        val sw = StringWriter()
                        e.printStackTrace(PrintWriter(sw))
                        consoleView.print("图表导出失败: ${e.message}\n$sw", ConsoleViewContentType.ERROR_OUTPUT)
                    }

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
        indicator: ProgressIndicator,
        scope: AnalysisScope // [新增]
    ): List<String> {
        indicator.checkCanceled()
        val resultLines = mutableListOf<String>()

        // [重要] 核心过滤逻辑
        val callingMethods = ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(method, searchScope).findAll()

            val allCallers = references
                .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
                .distinct()

            // 如果作用域是 "ALL"，则不过滤
            if (scope == AnalysisScope.ALL) {
                return@runReadAction allCallers
            }

            // 否则，根据作用域进行过滤
            val projectFileIndex = ProjectFileIndex.getInstance(project)
            allCallers.filter { caller ->
                val virtualFile = caller.containingFile?.virtualFile ?: return@filter false
                val isInTestSources = projectFileIndex.isInTestSourceContent(virtualFile)

                when (scope) {
                    AnalysisScope.PRODUCTION -> !isInTestSources // 仅生产代码：保留不在测试源中的
                    AnalysisScope.TEST -> isInTestSources      // 仅测试代码：保留在测试源中的
                    AnalysisScope.ALL -> true                  // 全部：保留所有 (理论上不会到这里，但为了完整性)
                }
            }
        }

        // 后续的循环和递归逻辑完全不变
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

            // [修改] 将作用域参数继续传递给递归调用
            val subCallLines = findAndPrintCallers(caller, project, indentLevel + 1, newPath, indicator, scope)
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
