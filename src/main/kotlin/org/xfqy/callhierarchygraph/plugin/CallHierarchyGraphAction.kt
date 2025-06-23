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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory

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
                runAnalysisInBackground(project, selectedMethods, consoleView)
            }
        }
    }


    /**
     * 将分析逻辑封装到一个单独的方法中，以便在后台任务中调用
     */
    private fun runAnalysisInBackground(project: Project, methods: List<PsiMethod>, consoleView: ConsoleView) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "分析方法调用链", true) {
                override fun run(indicator: ProgressIndicator) {
                    methods.forEachIndexed { index, targetMethod ->
                        if (index > 0) {
                            consoleView.print("\n\n", ConsoleViewContentType.NORMAL_OUTPUT)
                        }

                        indicator.text = "正在分析: ${targetMethod.name}"
                        consoleView.print(formatMethod(targetMethod) + "\n", ConsoleViewContentType.NORMAL_OUTPUT)

                        // [修改] 初始调用，传入一个包含根方法的路径集合
                        // 这样可以防止 targetMethod 的直接调用者就是它自己的情况 (直接递归)
                        findAndPrintCallers(targetMethod, project, consoleView, 1, setOf(targetMethod), indicator)

                        indicator.checkCanceled()
                    }
                }
            }
        )
    }

    /**
     * 递归查找并打印方法的调用者 (修正版)
     */
    private fun findAndPrintCallers(
        method: PsiMethod,
        project: Project,
        consoleView: ConsoleView,
        indentLevel: Int,
        path: Set<PsiMethod>,
        indicator: ProgressIndicator
    ) {
        indicator.checkCanceled()

        // [修正] 将 PSI 搜索和处理操作包裹在 runReadAction 中
        val callingMethods = ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(method, searchScope).findAll()

            references
                .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
                .distinct()
        }

        // 现在 `callingMethods` 是一个普通的 List，可以安全地在后台线程中遍历
        for (caller in callingMethods) {
            // [修正] 检查循环依赖的逻辑也需要放在 readAction 中，因为它也访问 PSI
            val isInPath = ApplicationManager.getApplication().runReadAction<Boolean> { caller in path }
            if (isInPath) {
                val indent = " ".repeat(4 * indentLevel)
                // formatMethod 内部已经有 readAction，所以这里可以安全调用
                consoleView.print("$indent[... Recursive call to ${formatMethod(caller)} ...]\n", ConsoleViewContentType.ERROR_OUTPUT)
                continue
            }

            val indent = " ".repeat(4 * indentLevel)
            // formatMethod 内部已经有 readAction，所以这里可以安全调用
            consoleView.print("$indent${formatMethod(caller)}\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // 递归调用，传递新的路径
            // [修正] 创建新路径也需要 readAction
            val newPath = ApplicationManager.getApplication().runReadAction<Set<PsiMethod>> { path + method }
            findAndPrintCallers(caller, project, consoleView, indentLevel + 1, newPath, indicator)
        }
    }

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
                    val outerClass = PsiTreeUtil.getParentOfType(containingClass, com.intellij.psi.PsiClass::class.java, true)
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
                ?: (JavaDirectoryService.getInstance().getPackage(method.containingFile.containingDirectory!!)?.qualifiedName ?: "")

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
