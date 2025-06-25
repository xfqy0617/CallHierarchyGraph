package org.xfqy.callhierarchygraph.plugin

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
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
import org.xfqy.callhierarchygraph.entity.EdgeData
import org.xfqy.callhierarchygraph.entity.GraphData
import org.xfqy.callhierarchygraph.entity.NodeData
import org.xfqy.callhierarchygraph.manager.NodeManager
import org.xfqy.callhierarchygraph.visualizer.CallHierarchyVisualizer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter


class CallHierarchyGraphAction : AnAction("分析方法调用链...") { // 更新了文本

    private val consoleViewMap = mutableMapOf<Project, ConsoleView>()

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
     * 获取或创建一个新的控制台 Tool Window
     * (针对 ContentFactory 进行兼容性修改)
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

            // [兼容性修改] 使用 ContentFactory.SERVICE.getInstance()
            // 这是从 2020.3 版本开始推荐的方式，在 2022.1.1 中完全可用
            val contentFactory = ContentFactory.SERVICE.getInstance()
            val content = contentFactory.createContent(consoleView.component, "调用链分析", false)

            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(content)
            consoleViewMap[project] = consoleView
        }

        toolWindow.show(null)
        return consoleView
    }
    // CallHierarchyGraphAction.kt (部分修改)
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
                    // 新的数据收集器
                    val nodeManager = NodeManager()
                    val allNodes = mutableMapOf<String, NodeData>() // ID -> NodeData
                    val allEdges = mutableListOf<EdgeData>()
                    val processedEdges = mutableSetOf<String>() // 防止重复边

                    methods.forEach { targetMethod ->
                        indicator.text = "正在分析: ${targetMethod.name}"

                        val initialMethodContent = formatMethod(targetMethod)
                        val (methodId, methodNodeData) = nodeManager.getOrGenerateNode(initialMethodContent)
                        allNodes[methodId] = methodNodeData

                        // 递归查找并填充 allNodes 和 allEdges
                        findCallersRecursive(
                            targetMethod, project, setOf(targetMethod), indicator, scope,
                            nodeManager, allNodes, allEdges, processedEdges
                        )

                        indicator.checkCanceled()
                    }

                    // 构建最终的 GraphData 对象
                    val graphData = GraphData(nodes = allNodes.values.toList(), edges = allEdges)

                    try {
                        // 使用新的 Visualizer
                        val visualizer = CallHierarchyVisualizer()
                        visualizer.renderGraph(graphData, outputFilename, true, outputPath)
                        consoleView.print("图表已成功导出！\n路径为:${outputPath}${File.separator}${outputFilename}.html", ConsoleViewContentType.SYSTEM_OUTPUT)
                    } catch (e: Exception) {
                        val sw = StringWriter()
                        e.printStackTrace(PrintWriter(sw))
                        consoleView.print("图表导出失败: ${e.message}\n$sw", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        )
    }

    // 需要一个新的递归函数
    private fun findCallersRecursive(
        method: PsiMethod,
        project: Project,
        path: Set<PsiMethod>,
        indicator: ProgressIndicator,
        scope: AnalysisScope,
        nodeManager: NodeManager,
        allNodes: MutableMap<String, NodeData>,
        allEdges: MutableList<EdgeData>,
        processedEdges: MutableSet<String>
    ) {
        indicator.checkCanceled()

        val parentMethodContent = formatMethod(method)
        val (parentId, parentNodeData) = nodeManager.getOrGenerateNode(parentMethodContent)
        allNodes[parentId] = parentNodeData

        // 查找调用者的逻辑保持不变
        val callingMethods = ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(method, searchScope).findAll()

            val allCallers = references
                .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
                .distinct()

            if (scope == AnalysisScope.ALL) {
                allCallers // 在 lambda 中，可以直接返回值，无需 `return@...`
            } else {
                val projectFileIndex = ProjectFileIndex.getInstance(project)
                // [修正] 将 filter 的结果作为 else 分支的返回值
                allCallers.filter { caller ->
                    val virtualFile = caller.containingFile?.virtualFile ?: return@filter false
                    val isInTestSources = projectFileIndex.isInTestSourceContent(virtualFile)

                    when (scope) {
                        AnalysisScope.PRODUCTION -> !isInTestSources
                        AnalysisScope.TEST -> isInTestSources
                        AnalysisScope.ALL -> true
                    }
                }
            }
        }

        for (caller in callingMethods) {
            if (caller in path) { // 循环依赖
                continue
            }

            val childMethodContent = formatMethod(caller)
            val (childId, childNodeData) = nodeManager.getOrGenerateNode(childMethodContent)
            allNodes[childId] = childNodeData

            // 添加边 (从调用者指向被调用者，即 child -> parent)
            val edgeId = "$childId -> $parentId"
            if (processedEdges.add(edgeId)) {
                allEdges.add(EdgeData(source = childId, target = parentId))
            }

            val newPath = ApplicationManager.getApplication().runReadAction<Set<PsiMethod>> { path + method }
            findCallersRecursive(caller, project, newPath, indicator, scope, nodeManager, allNodes, allEdges, processedEdges)
        }
    }

}
