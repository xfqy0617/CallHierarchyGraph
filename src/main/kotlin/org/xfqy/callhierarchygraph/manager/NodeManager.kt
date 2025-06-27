package org.xfqy.callhierarchygraph.manager

import org.xfqy.callhierarchygraph.model.NodeData
import org.xfqy.callhierarchygraph.util.DistinctDarkColorGenerator
import org.xfqy.callhierarchygraph.util.Pair

private val LABEL_PATTERN_KOTLIN: Regex = "^(.+?)\\.([a-zA-Z0-9_<>\$]+)\\((.*?)\\)(?:\\s*\\(\\s*(\\d+)\\s+usages\\s*\\))?\\s+\\(([a-zA-Z0-9_.]+)\\)(\\[Override\\])?\$".toRegex()
class NodeManager {
    // 完整节点内容 -> 唯一节点ID
    private val nodeContentToIdMap: MutableMap<String, String> = HashMap()
    // 唯一节点ID -> 节点数据
    private val nodeDataMap: MutableMap<String, NodeData> = HashMap()
    private var nodeCounter: Int = 1

    private val colorSet: MutableSet<String> = mutableSetOf()
    private val classColorMap: MutableMap<String, String> = mutableMapOf()

    /**
     * 根据方法的全限定内容，生成或获取其唯一的ID和对应的NodeData对象。
     * @param fullNodeContent 格式化后的方法字符串。
     * @param isEntry 标记这个节点是否是分析的起点。
     * @return 返回包含唯一ID和NodeData的Pair。
     */
    fun getOrGenerateNode(fullNodeContent: String, isEntry: Boolean = false): Pair<String, NodeData> {
        val uniqueId = nodeContentToIdMap.computeIfAbsent(fullNodeContent) {
            "node${nodeCounter++}"
        }

        val nodeData = nodeDataMap.computeIfAbsent(uniqueId) {
            val nodeInfo = NodeInfo.from(fullNodeContent)
            NodeData(
                id = uniqueId,
                className = nodeInfo.className,
                methodName = nodeInfo.funName,
                params = nodeInfo.param,
                packageName = nodeInfo.packageName,
                classColor = getClassColor(nodeInfo.className),
                isEntry = isEntry,
                nodeType = "", // 初始为空，由 Action 在最后统一计算
                isOverride = nodeInfo.isOverride // <-- 新增赋值
            )
        }

        if (isEntry && !nodeData.isEntry) {
            nodeDataMap[uniqueId] = nodeData.copy(isEntry = true)
        }

        return Pair(uniqueId, nodeDataMap[uniqueId]!!)
    }

    private fun getClassColor(className: String): String {
        return classColorMap.getOrPut(className) {
            DistinctDarkColorGenerator.generate(colorSet).also {
                colorSet.add(it)
            }
        }
    }

    // 内部数据类，用于从字符串解析
    private data class NodeInfo(
        val className: String,
        val funName: String,
        val param: String,
        val packageName: String,
        val isOverride: Boolean // <-- 新增字段
    ) {
        companion object {
            fun from(fullNodeContent: String): NodeInfo {
                val matchResult = LABEL_PATTERN_KOTLIN.find(fullNodeContent)
                if (matchResult != null) {
                    // 更新解构以包含新的捕获组
                    val (rawClassName, rawFunName, rawParam, _, rawPackageName, overrideMarker) = matchResult.destructured
                    return NodeInfo(
                        rawClassName.trim(),
                        rawFunName.trim(),
                        htmlEscaped(rawParam.trim()),
                        rawPackageName.trim(),
                        isOverride = overrideMarker.isNotEmpty() // 如果标记存在，则isOverride为true
                    )
                } else {
                    System.err.println("无法解析节点内容: $fullNodeContent")
                    return NodeInfo("UnknownClass", fullNodeContent, "", "unknown.package", false)
                }
            }

            private fun htmlEscaped(text: String): String {
                val result = text.replace("<", "<").replace(">", ">")
                return result.ifEmpty { " " }
            }
        }
    }
}
