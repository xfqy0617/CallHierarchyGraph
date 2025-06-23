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
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.content.ContentFactory

class CallHierarchyGraphAction : AnAction("Export Call Hierarchy to HTML") {

    private val consoleViewMap = mutableMapOf<Project, ConsoleView>()

    /**
     * 【新增】指定 update 方法在 EDT 线程上运行。
     *  这是现代插件开发的最佳实践，可以避免很多线程问题。
     */
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * 更新 Action 的状态，只有当光标在方法内时才启用
     */
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)

        if (project == null || editor == null || psiFile == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // 检查光标是否在 PsiMethod 内部
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset)
        val psiMethod = PsiTreeUtil.getParentOfType(elementAtCaret, PsiMethod::class.java)
        e.presentation.isEnabledAndVisible = psiMethod != null
    }

    /**
     * 执行 Action
     */
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        // 获取光标下的 PsiMethod
        val offset = editor.caretModel.offset
        val elementAtCaret = psiFile.findElementAt(offset)
        val targetMethod = PsiTreeUtil.getParentOfType(elementAtCaret, PsiMethod::class.java) ?: return

        // 显示并清空控制台
        val consoleView = getOrCreateConsole(project)
        consoleView.clear()

        // 使用后台任务执行搜索，避免 UI 冻结
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Finding Method Callers", true) {
                override fun run(indicator: ProgressIndicator) {
                    // 打印目标方法
                    consoleView.print(formatMethod(targetMethod) + "\n", ConsoleViewContentType.NORMAL_OUTPUT)

                    // 启动递归查找
                    findAndPrintCallers(targetMethod, project, consoleView, 1, mutableSetOf())
                }
            }
        )
    }

    /**
     * 递归查找并打印方法的调用者
     */
    private fun findAndPrintCallers(
        method: PsiMethod,
        project: Project,
        consoleView: ConsoleView,
        indentLevel: Int,
        visitedMethods: MutableSet<PsiMethod>
    ) {
        val searchScope = GlobalSearchScope.projectScope(project)
        val references = ReferencesSearch.search(method, searchScope).findAll()

        val callingMethods = references
            .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
            .distinct()

        for (caller in callingMethods) {
            if (!visitedMethods.add(caller)) { // 使用 add 的返回值来判断是否已存在
                continue
            }
            val indent = " ".repeat(4 * indentLevel)
            consoleView.print("$indent${formatMethod(caller)}\n", ConsoleViewContentType.NORMAL_OUTPUT)
            findAndPrintCallers(caller, project, consoleView, indentLevel + 1, visitedMethods)
        }
    }

    /**
     * 格式化 PsiMethod 的输出
     */
    private fun formatMethod(method: PsiMethod): String {
        val className = method.containingClass?.name ?: "UnknownClass"
        val methodName = method.name
        val params = method.parameterList.parameters
            .joinToString(", ") { it.type.presentableText }

        val packageName = (method.containingFile as? PsiJavaFile)?.packageName ?:
        (JavaDirectoryService.getInstance().getPackage(method.containingFile.containingDirectory!!)?.qualifiedName ?: "")

        return "$className.$methodName($params)  ($packageName)"
    }

    /**
     * 获取或创建一个新的控制台 Tool Window
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
            val content = ContentFactory.getInstance().createContent(consoleView.component, "Call Hierarchy", false)
            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(content)
            consoleViewMap[project] = consoleView
        }

        toolWindow.show(null)
        return consoleView
    }
}