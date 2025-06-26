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
import org.xfqy.callhierarchygraph.manager.NodeManager
import org.xfqy.callhierarchygraph.model.EdgeData
import org.xfqy.callhierarchygraph.model.GraphData
import org.xfqy.callhierarchygraph.model.NodeData
import org.xfqy.callhierarchygraph.visualizer.CallHierarchyVisualizer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CallHierarchyGraphAction : AnAction("分析方法调用链...") {

    private val consoleViewMap = mutableMapOf<Project, ConsoleView>()

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiElement = e.getData(CommonDataKeys.PSI_ELEMENT)
        if (project == null || psiElement == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val psiClass = when (psiElement) {
            is PsiClass -> psiElement
            is PsiMethod -> psiElement.containingClass
            else -> null
        }
        e.presentation.isEnabledAndVisible = psiClass != null && psiClass.methods.isNotEmpty()
    }

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
                targetClass = method?.containingClass
                preselectedMethod = method
            }
        }

        if (targetClass == null || targetClass.methods.isEmpty()) {
            return
        }

        val dialog = SelectMethodsDialog(project, targetClass, preselectedMethod)

        if (dialog.showAndGet()) {
            val selectedMethods = dialog.getSelectedMethods()
            val outputPath = dialog.getOutputPath()
            val outputFilename = dialog.getOutputFilename()
            val selectedScope = dialog.getSelectedScope()
            // [优化2] 获取新复选框的状态
            val skipUncalledMethods = dialog.shouldSkipUncalledMethods()

            if (selectedMethods.isNotEmpty() && outputPath.isNotBlank() && outputFilename.isNotBlank()) {
                val consoleView = getOrCreateConsole(project)
                consoleView.clear()
                runAnalysisInBackground(project, selectedMethods, consoleView, outputPath, outputFilename, selectedScope, skipUncalledMethods)
            }
        }
    }

    private fun runAnalysisInBackground(
        project: Project,
        methods: List<PsiMethod>,
        consoleView: ConsoleView,
        outputPath: String,
        outputFilename: String,
        scope: AnalysisScope,
        // [优化2] 接收新参数
        skipUncalledMethods: Boolean
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "分析方法调用链", true) {
                override fun run(indicator: ProgressIndicator) {
                    val nodeManager = NodeManager()
                    val allNodes = mutableMapOf<String, NodeData>()
                    val allEdges = mutableListOf<EdgeData>()
                    val processedEdges = mutableSetOf<String>()

                    val rootMethodsContent = methods.map { formatMethod(it) }

                    methods.forEach { targetMethod ->
                        indicator.text = "正在分析: ${targetMethod.name}"
                        findCallersRecursive(
                            method = targetMethod,
                            project = project,
                            path = setOf(targetMethod),
                            indicator = indicator,
                            scope = scope,
                            nodeManager = nodeManager,
                            allNodes = allNodes,
                            allEdges = allEdges,
                            processedEdges = processedEdges,
                            rootMethodsContent = rootMethodsContent
                        )
                        indicator.checkCanceled()
                    }

                    val outDegrees = mutableMapOf<String, Int>()
                    val inDegrees = mutableMapOf<String, Int>()

                    allEdges.forEach { edge ->
                        outDegrees[edge.source] = outDegrees.getOrDefault(edge.source, 0) + 1
                        inDegrees[edge.target] = inDegrees.getOrDefault(edge.target, 0) + 1
                    }

                    // [优化2] 在此处实现过滤逻辑
                    var finalNodes: List<NodeData>
                    val finalEdges: List<EdgeData>

                    if (skipUncalledMethods) {
                        // 找出所有需要保留的节点ID
                        val nodesToKeep = allNodes.values.filter { node ->
                            // 保留所有非入口节点，或者那些虽然是入口但确实有上游调用的节点
                            !node.isEntry || inDegrees.getOrDefault(node.id, 0) > 0
                        }.map { it.id }.toSet()

                        // 过滤节点和边
                        finalNodes = allNodes.values.filter { it.id in nodesToKeep }
                        finalEdges = allEdges.filter { it.source in nodesToKeep && it.target in nodesToKeep }

                        // 重新计算过滤后的度数，以便正确设置节点类型
                        val finalOutDegrees = mutableMapOf<String, Int>()
                        val finalInDegrees = mutableMapOf<String, Int>()
                        finalEdges.forEach { edge ->
                            finalOutDegrees[edge.source] = finalOutDegrees.getOrDefault(edge.source, 0) + 1
                            finalInDegrees[edge.target] = finalInDegrees.getOrDefault(edge.target, 0) + 1
                        }

                        // 更新节点类型
                        val typedFinalNodes = finalNodes.map { node ->
                            val nodeType = when {
                                finalOutDegrees.getOrDefault(node.id, 0) == 0 -> "ROOT"
                                finalInDegrees.getOrDefault(node.id, 0) == 0 -> "LEAF"
                                else -> "INTERMEDIATE"
                            }
                            node.copy(nodeType = nodeType)
                        }
                        finalNodes = typedFinalNodes

                    } else {
                        // 如果不跳过，则使用原始数据
                        finalEdges = allEdges
                        finalNodes = allNodes.values.map { node ->
                            val nodeType = when {
                                outDegrees.getOrDefault(node.id, 0) == 0 -> "ROOT"
                                inDegrees.getOrDefault(node.id, 0) == 0 -> "LEAF"
                                else -> "INTERMEDIATE"
                            }
                            node.copy(nodeType = nodeType)
                        }
                    }

                    val graphData = GraphData(nodes = finalNodes, edges = finalEdges)

                    try {
                        val visualizer = CallHierarchyVisualizer()
                        visualizer.renderGraph(graphData, outputFilename, true, outputPath)
                        consoleView.print(
                            "图表已成功导出！\n路径为: ${outputPath}${File.separator}${outputFilename}.html",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        )
                    } catch (e: Exception) {
                        val sw = StringWriter()
                        e.printStackTrace(PrintWriter(sw))
                        consoleView.print("图表导出失败: ${e.message}\n$sw", ConsoleViewContentType.ERROR_OUTPUT)
                    }
                }
            }
        )
    }

    private fun findCallersRecursive(
        method: PsiMethod,
        project: Project,
        path: Set<PsiMethod>,
        indicator: ProgressIndicator,
        scope: AnalysisScope,
        nodeManager: NodeManager,
        allNodes: MutableMap<String, NodeData>,
        allEdges: MutableList<EdgeData>,
        processedEdges: MutableSet<String>,
        rootMethodsContent: List<String>
    ) {
        indicator.checkCanceled()

        val parentMethodContent = formatMethod(method)
        val isEntry = parentMethodContent in rootMethodsContent
        val (parentId, _) = nodeManager.getOrGenerateNode(parentMethodContent, isEntry)
        allNodes[parentId] = nodeManager.getOrGenerateNode(parentMethodContent, isEntry).value

        val callingMethods = ApplicationManager.getApplication().runReadAction<List<PsiMethod>> {
            val searchScope = GlobalSearchScope.projectScope(project)
            val references = ReferencesSearch.search(method, searchScope).findAll()

            val allCallers = references
                .mapNotNull { PsiTreeUtil.getParentOfType(it.element, PsiMethod::class.java) }
                .distinct()

            if (scope == AnalysisScope.ALL) {
                allCallers
            } else {
                val projectFileIndex = ProjectFileIndex.getInstance(project)
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
            val isInPath = ApplicationManager.getApplication().runReadAction<Boolean> { caller in path }
            if (isInPath) {
                continue
            }

            val childMethodContent = formatMethod(caller)
            val (childId, _) = nodeManager.getOrGenerateNode(childMethodContent, false)
            allNodes[childId] = nodeManager.getOrGenerateNode(childMethodContent, false).value

            val edgeId = "$childId->$parentId"
            if (processedEdges.add(edgeId)) {
                allEdges.add(EdgeData(source = childId, target = parentId))
            }

            val newPath = ApplicationManager.getApplication().runReadAction<Set<PsiMethod>> { path + caller }
            findCallersRecursive(
                caller, project, newPath, indicator, scope,
                nodeManager, allNodes, allEdges, processedEdges, rootMethodsContent
            )
        }
    }

    private fun formatMethod(method: PsiMethod): String {
        return ApplicationManager.getApplication().runReadAction<String> {
            val containingClass = method.containingClass
            val className: String
            if (containingClass is PsiAnonymousClass) {
                val contextMethod = PsiTreeUtil.getParentOfType(containingClass, PsiMethod::class.java, true)
                val contextDescription = if (contextMethod != null) {
                    val outerClassName = contextMethod.containingClass?.name ?: ""
                    " in ${contextMethod.name}() in $outerClassName"
                } else {
                    val outerClass = PsiTreeUtil.getParentOfType(containingClass, PsiClass::class.java, true)
                    if (outerClass != null) " in ${outerClass.name}" else ""
                }
                className = "Anonymous$contextDescription"
            } else {
                className = containingClass?.name ?: "UnknownClass"
            }

            val methodName = method.name
            val params = method.parameterList.parameters.joinToString(", ") { it.type.presentableText }
            val packageName = (method.containingFile as? PsiJavaFile)?.packageName ?: (JavaDirectoryService.getInstance().getPackage(method.containingFile.containingDirectory!!)?.qualifiedName ?: "")

            "$className.$methodName($params)  ($packageName)"
        }
    }

    private fun getOrCreateConsole(project: Project): ConsoleView {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindowId = "Method Callers"
        var toolWindow = toolWindowManager.getToolWindow(toolWindowId)

        val consoleView: ConsoleView
        if (toolWindow != null && consoleViewMap.containsKey(project)) {
            consoleView = consoleViewMap[project]!!
        } else {
            if (toolWindow == null) {
                toolWindow = toolWindowManager.registerToolWindow(toolWindowId) { this.canCloseContent = true }
            }
            val consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
            consoleView = consoleBuilder.console

            val contentFactory = ContentFactory.SERVICE.getInstance()
            val content = contentFactory.createContent(consoleView.component, "调用链分析", false)

            toolWindow.contentManager.removeAllContents(true)
            toolWindow.contentManager.addContent(content)
            consoleViewMap[project] = consoleView
        }

        toolWindow.show(null)
        return consoleView
    }
}
