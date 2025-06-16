package org.xfqy.callhierarchygraph.visualizer

import com.google.gson.GsonBuilder
import com.jetbrains.rd.util.string.println
import guru.nidi.graphviz.attribute.Attributes.attr
import guru.nidi.graphviz.attribute.Color.named
import guru.nidi.graphviz.attribute.Font.size
import guru.nidi.graphviz.attribute.Label
import guru.nidi.graphviz.attribute.Rank
import guru.nidi.graphviz.attribute.Rank.RankDir
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.model.MutableNode
import org.xfqy.callhierarchygraph.manager.NodeManager
import org.xfqy.callhierarchygraph.util.Pair
import org.xfqy.callhierarchygraph.config.GraphConfig
import java.awt.Desktop
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

private const val TEMPLATE_PATH = "templates/graph_template.html"
/**
 * 调用层次可视化器主类
 */
class CallHierarchyVisualizer {
    private val config: GraphConfig
    private val spacesPerIndent: Int
    private val nodeManager: NodeManager
    private val edgeSet: MutableSet<String>  // 存储已添加的边，防止重复
    private val graph: MutableGraph // Graphviz图对象

    constructor(config: GraphConfig, spacesPerIndent: Int) {
        this.config = config
        this.spacesPerIndent = spacesPerIndent
        this.nodeManager = NodeManager()
        this.edgeSet = HashSet()
        this.graph = initializeGraph()
    }


    constructor(spacesPerIndent: Int) : this(GraphConfig(), spacesPerIndent)

    /**
     * 初始化Graphviz图对象
     *
     * @return MutableGraph 对象，初始化后的可变图对象
     */
    private fun initializeGraph(): MutableGraph {
        // 创建有向图
        val newGraph = mutGraph(config.graphName).setDirected(true)

        try {
            // 设置图的全局属性
            val graphAttrs = newGraph.graphAttrs()
            // 设置图的方向
            graphAttrs.add(Rank.dir(RankDir.LEFT_TO_RIGHT))
            // 设置节点间距
            graphAttrs.add(attr("nodesep", config.nodesep))
            // 设置层间距
            graphAttrs.add(attr("ranksep", config.ranksep))
            // 设置边的箭头大小
            graphAttrs.add(attr("arrowsize", config.edgeArrowsize))

            // 设置节点属性
            val nodeAttrs = newGraph.nodeAttrs()
            // 设置节点形状
            nodeAttrs.add(attr("shape", config.nodeShape))
            // 设置节点样式
            nodeAttrs.add(attr("style", config.nodeStyle))
            // 设置节点字体大小
            nodeAttrs.add(size(config.nodeFontsize))
        } catch (e: IllegalArgumentException) {
            // 处理属性值无效的异常
            System.err.println("初始化图属性时发生错误: ${e.message}")
        }
        return newGraph
    }


    /**
     * 添加新节点及其与父节点的边
     *
     * @param newNodeContent    新节点的完整内容
     * @param parentNodeContent 父节点的完整内容 (可选)
     */
    private fun addNodeWithParent(newNodeContent: String?, parentNodeContent: String?) {
        if (newNodeContent.isNullOrBlank()) {
            return
        }

        val newNodeInfo = nodeManager.getNodeIdAndLabel(newNodeContent)
        val newNodeId = newNodeInfo.key
        // 2. 创建一个节点
        val newNode = mutNode(newNodeId)

        // 3. 构建 HTML-like label 字符串
        val htmlLabelContent = newNodeInfo.value
        // 4. 将 HTML-like label 添加到节点
        newNode.add(Label.html(htmlLabelContent))
        graph.add(newNode)


        if (!parentNodeContent.isNullOrBlank()) {
            val parentNodeInfo: Pair<String, String> = nodeManager.getNodeIdAndLabel(parentNodeContent)
            val parentNodeId = parentNodeInfo.key

            val edgeId = "$parentNodeId->$newNodeId"
            if (edgeSet.add(edgeId)) { // 使用Set防止重复添加边
                graph.add(mutNode(newNodeId).addLink(mutNode(parentNodeId)))
                nodeManager.addParentChildRelationship(parentNodeId, newNodeId)
            }
        }
    }

    /**
     * 根据节点类型设置节点颜色
     */
    private fun setNodeColors() {
        for (nodeId in nodeManager.getNodeInfoMap().keys) { // Iterate over all known node IDs
            var node: MutableNode? = null
            for (n in graph.nodes()) {
                if (nodeId.equals(n.name().toString()) && n.links().isEmpty()) {
                    node = n
                }
            }
            if (node == null) {
                continue
            }

            if (nodeManager.isRootNode(nodeId)) {
                node.add(named(config.rootNodeColor))
            } else if (nodeManager.isLeafNode(nodeId)) {
                node.add(named(config.leafNodeColor))
            } else {
                node.add(named(config.defaultNodeColor))
            }
        }
    }

    /**
     * 解析文本内容并构建图
     *
     * @param fileContentString 包含调用层次信息的文本内容
     */
    fun parseAndBuildGraph(fileContentString: String) {
        val parentStack : ArrayList<String> = ArrayList()

        val lines = fileContentString.split("\n")

        for (lineNumber in lines.indices) {
            val rawLine = lines[lineNumber]
            val line = rawLine.trim()

            if (line.isEmpty()) { // Skip empty lines or lines with only spaces
                continue
            }

            val leadingSpaces = rawLine.length - line.length
            // 如果当前行没有子级, 则跳过
            if (skipLine(leadingSpaces, lineNumber, lines)) {
                println("跳过 $line")
                continue
            }

            val currentLevel = calculateIndentLevel(leadingSpaces, lineNumber + 1) // line numbers are 1-based

            val currentParentContent = getParentContent(currentLevel, parentStack, lineNumber + 1)
            addNodeWithParent(line, currentParentContent)

            // Update parentStack for the current level
            while (parentStack.size > currentLevel) {
                parentStack.removeAt(parentStack.size - 1)
            }
            if (parentStack.size == currentLevel) {
                parentStack.add(line)
            } else {
                // 处理缩进突然增加的情况（当前层级跳跃式加深）
                // 这种情况表示跳过了中间层级，直接进入更深的层次
                // 当前逻辑已通过获取最后一个已知父节点处理了这种情况
                // 现在确保 parentStack 能正确对齐当前层级
                // 通过截断 parentStack 到当前层级长度，然后添加当前节点来实现
                parentStack.add(line) // 在正确层级位置添加当前节点
            }
        }
        setNodeColors()
    }

    private fun skipLine(leadingSpaces: Int, lineNumber: Int, lines: List<String>): Boolean {
        if (leadingSpaces == 0 && lineNumber != lines.size - 1) {
            val nextLine = lines[lineNumber + 1]
            if (nextLine.length == nextLine.trim().length) {
                return true
            }
        }
        return false
    }

    /**
     * 计算缩进级别
     *
     * @param leadingSpaces 前导空格数
     * @param lineNumber    行号
     * @return 缩进级别
     */
    private fun calculateIndentLevel(leadingSpaces:Int, lineNumber:Int): Int {
        if (spacesPerIndent == 0) {
            return leadingSpaces
        }
        if (leadingSpaces % spacesPerIndent != 0) {
            System.err.printf("警告: 第 %d 行缩进不一致 (%d 空格). 期望是 %d 的倍数。%n",
                lineNumber, leadingSpaces, spacesPerIndent)
        }
        return leadingSpaces / spacesPerIndent
    }

    /**
     * 获取父节点内容
     *
     * @param currentLevel 当前缩进级别
     * @param parentStack  父节点内容堆栈
     * @param lineNumber   行号
     * @return 父节点内容字符串，如果当前节点是根节点则为null
     */
    private fun getParentContent(currentLevel: Int, parentStack: List<String>, lineNumber: Int): String? {
        if (currentLevel == 0) {
            return null
        }

        if (currentLevel - 1 < parentStack.size) {
            return parentStack[currentLevel - 1]
        }

        if (parentStack.isNotEmpty()) {
            System.err.printf("警告: 第 $lineNumber 行缩进跳跃或缺少父级。使用最后一个已知父级。%n")
            return parentStack.last()
        }

        System.err.printf("警告: 第 $lineNumber 行缩进跳跃或缺少父级。没有找到父级，作为根节点处理。%n")
        return null
    }

    /**
     * 渲染图到文件
     *
     * @param outputFilename 输出文件名 (不含扩展名)
     * @param view           是否自动打开生成的文件
     * @param format         输出格式 (png, html)
     */
    fun renderGraph(outputFilename: String, view: Boolean, format: String) {
        try {
            val outputDir = Paths.get("output")
            Files.createDirectories(outputDir) // Create directory if not exists

            val outputPath = outputDir.resolve(outputFilename)

            if ("html".equals(format, ignoreCase = true)) {
                renderHtml(outputPath, view)
            } else {
                val graphvizFormat = Format.valueOf(format.uppercase())
                // --- 关键修改点 ---
                // 在 graphviz-java 0.18.1 版本中，toFile() 方法返回的是 File 对象。
                // 捕获为 File，然后转换为 Path。
                var renderer = Graphviz.fromGraph(graph).render(graphvizFormat)
                val renderedFile = renderer.toFile(outputPath.toFile())
                val renderedPath = renderedFile.toPath() // 将 File 转换为 Path

                println("图已渲染到 ${renderedPath.toAbsolutePath()}")
                println(renderer.toString())

                if (view && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(renderedFile) // Desktop.open 方法接受 File 对象
                }
            }
        } catch (e: Exception) {
            System.err.println(
                """
                渲染过程中发生错误: ${e.message }
                图的 DOT 源代码:
                ${Graphviz.fromGraph(graph).render(Format.DOT)}
            """.trimIndent()
            )
            throw IOException("渲染失败", e)
        }
    }

    /**
     * 渲染HTML格式的图
     *
     * @param outputPath 输出文件路径 (不含扩展名)
     * @param view       是否自动打开
     * @throws IOException 如果文件操作失败
     */
    private fun renderHtml(outputPath: Path, view: Boolean) {

        // 1. Render SVG content
        val svgContent = Graphviz.fromGraph(graph).render(Format.SVG).toString()

        // 2. Load HTML template
        val templateContent: String =
            try {
                object {}.javaClass.classLoader.getResourceAsStream(TEMPLATE_PATH)?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                        reader.lines().collect(Collectors.joining("\n"))
                    }
                } ?: throw IOException()
            } catch (e: IOException) {
                throw IOException("无法加载HTML模板文件: templates/graph_template.html", e)
            } catch (e: Exception) {
                throw RuntimeException(e)
            }



        // 3. Prepare node relations for JavaScript
        val gson = GsonBuilder().create()
        val nodeRelations: MutableMap<String, Any> = HashMap()
        nodeRelations["parent_map"] = nodeManager.parentMap
        nodeRelations["children_map"] = nodeManager.childrenMap
        val nodeRelationsJson = gson.toJson(nodeRelations)

        // 4. Populate template
        val htmlContent = templateContent
            .replace("{graph_name}", config.graphName)
            .replace("{svg_content}", svgContent)
            .replace("\"{node_relations}\"", nodeRelationsJson)

        // 5. Write to HTML file
        val htmlPath = outputPath.resolveSibling(outputPath.fileName.toString() + ".html")
        Files.writeString(htmlPath, htmlContent, StandardCharsets.UTF_8)
        println("图已渲染到 " + htmlPath.toAbsolutePath())

        if (view && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(htmlPath.toFile())
        }
    }


}
