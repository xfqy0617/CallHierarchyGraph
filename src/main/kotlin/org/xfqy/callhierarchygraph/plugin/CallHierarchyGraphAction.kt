package org.xfqy.callhierarchygraph.plugin

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory

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

        // 确定目标类和可能被预选中的方法
        val targetClass: PsiClass?
        val preselectedMethod: PsiMethod?

        when (psiElement) {
            is PsiClass -> {
                targetClass = psiElement
                preselectedMethod = null // 在类上点击，没有预选方法
            }
            is PsiMethod -> {
                targetClass = psiElement.containingClass
                preselectedMethod = psiElement // 在方法上点击，预选此方法
            }
            else -> {
                // 如果在编辑器的其他位置（例如方法体内部），尝试向上查找
                val method = PsiTreeUtil.getParentOfType(psiElement, PsiMethod::class.java)
                if (method != null) {
                    targetClass = method.containingClass
                    preselectedMethod = method
                } else {
                    // 实在找不到就返回
                    return
                }
            }
        }

        if (targetClass == null || targetClass.methods.isEmpty()) {
            return
        }

        // 创建并显示对话框，传入所有方法和预选中的方法
        val dialog = SelectMethodsDialog(project, targetClass.methods.toList(), preselectedMethod)

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
     * 递归查找并打印方法的调用者
     *
     * @param method        当前要查找调用者的方法
     * @param project       项目实例
     * @param consoleView   控制台视图
     * @param indentLevel   当前的缩进级别
     * @param path          从根方法到当前方法(不含)的调用路径，用于防止无限递归
     * @param indicator     进度指示器
     */
    private fun findAndPrintCallers(
        method: PsiMethod,
        project: Project,
        consoleView: ConsoleView,
        indentLevel: Int,
        path: Set<PsiMethod>, // [修改] 使用不可变的 Set，并重命名为 path
        indicator: ProgressIndicator
    ) {
        indicator.checkCanceled()

        val searchScope = GlobalSearchScope.projectScope(project)
        val references = ReferencesSearch.search(method, searchScope).findAll()

        val callingMethods = references
            .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
            .distinct()

        for (caller in callingMethods) {
            // [修改] 检查 'caller' 是否已经存在于当前路径中，以避免循环依赖
            if (caller in path) {
                // （可选）可以打印一条信息提示检测到了递归
                val indent = " ".repeat(4 * indentLevel)
                consoleView.print("$indent[... Recursive call to ${formatMethod(caller)} ...]\n", ConsoleViewContentType.ERROR_OUTPUT)
                continue // 跳过这个循环，防止无限递归
            }

            val indent = " ".repeat(4 * indentLevel)
            consoleView.print("$indent${formatMethod(caller)}\n", ConsoleViewContentType.NORMAL_OUTPUT)

            // [修改] 递归调用时，创建一个新的路径集合
            // 新路径 = 旧路径 + 当前正在分析的方法 (method)
            // 注意：不是 `path + caller`，因为 `path` 代表的是调用链，`method` 是被调用者
            findAndPrintCallers(caller, project, consoleView, indentLevel + 1, path + method, indicator)
        }
    }


    /**
     * 格式化 PsiMethod 的输出 (保持不变)
     */
    private fun formatMethod(method: PsiMethod): String {
        val className = method.containingClass?.name ?: "UnknownClass"
        val methodName = method.name
        val params = method.parameterList.parameters
            .joinToString(", ") { it.type.presentableText }

        val packageName = (method.containingFile as? PsiJavaFile)?.packageName
            ?: (JavaDirectoryService.getInstance().getPackage(method.containingFile.containingDirectory!!)?.qualifiedName ?: "")

        return "$className.$methodName($params)  ($packageName)"
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
