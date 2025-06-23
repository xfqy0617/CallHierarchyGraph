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
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory

class CallHierarchyGraphAction : AnAction("分析方法调用链") { // 更新了文本

    private val consoleViewMap = mutableMapOf<Project, ConsoleView>()

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * 更新 Action 的状态，只有当选中的是 Java/Kotlin 类时才启用
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        // 从事件上下文中获取 PSI 元素
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)

        // 检查项目是否存在，并且选中的元素是否是一个 PsiClass
        if (project == null || psiElement !is PsiClass) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // 如果是一个类，并且它有方法，则启用 Action
        e.presentation.isEnabledAndVisible = psiElement.methods.isNotEmpty()
    }

    /**
     * 执行 Action
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 获取选中的 PsiClass
        val psiClass = e.getData(CommonDataKeys.PSI_ELEMENT) as? PsiClass ?: return

        // 如果类中没有方法，则不执行任何操作
        if (psiClass.methods.isEmpty()) {
            return
        }

        // 创建并显示方法选择对话框
        val dialog = SelectMethodsDialog(project, psiClass.methods.toList())

        // showAndGet() 会显示对话框并等待用户操作。如果用户点击 OK，返回 true
        if (dialog.showAndGet()) {
            val selectedMethods = dialog.getSelectedMethods()

            // 如果用户至少选择了一个方法
            if (selectedMethods.isNotEmpty()) {
                // 显示并清空控制台
                val consoleView = getOrCreateConsole(project)
                consoleView.clear()

                // 使用后台任务执行搜索，避免 UI 冻结
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
