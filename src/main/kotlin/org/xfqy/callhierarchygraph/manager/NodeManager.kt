package org.xfqy.callhierarchygraph.manager

import org.xfqy.callhierarchygraph.util.DistinctDarkColorGenerator
import org.xfqy.callhierarchygraph.util.Pair

// 预编译正则表达式，用于提取节点标签信息
private val LABEL_PATTERN_KOTLIN: Regex = "^(.+?)\\.([a-zA-Z0-9_<>\$]+)\\((.*?)\\)(?:\\s*\\(\\s*(\\d+)\\s+usages\\s*\\))?\\s+\\(([a-zA-Z0-9_.]+)\\)\$".toRegex()
class NodeManager {
    // 完整节点内容 -> 唯一节点ID
    private val nodeMap: MutableMap<String, NodeInfo> = HashMap()
    private val nodeIdMap: MutableMap<NodeInfo, String> = HashMap()
    private var nodeCounter: Int = 1

    // 子节点ID -> 父节点ID集合
    var parentMap: MutableMap<String, MutableSet<String>> = HashMap()
        private set

    // 父节点ID -> 子节点ID集合
    var childrenMap: MutableMap<String, MutableSet<String>> = HashMap()
        private set

    private val colorSet: MutableSet<String> = mutableSetOf()

    private val classColorMap: MutableMap<String, String> = mutableMapOf()

    /**
     * 生成唯一的节点ID和显示标签
     * @param fullNodeContent 完整的节点内容字符串
     * @return 包含节点ID和标签的Pair
     */
    fun getNodeIdAndLabel(fullNodeContent: String): Pair<String, String> {
        var nodeInfo = nodeMap[fullNodeContent]
        if (nodeInfo  == null) {
            nodeInfo = NodeInfo.from(fullNodeContent)
            nodeMap[fullNodeContent] = nodeInfo
        }
        var uniqueId = nodeIdMap[nodeInfo]
        if (uniqueId == null) {
            uniqueId = "node" + nodeCounter++
            nodeIdMap[nodeInfo] = uniqueId
        }
        val label = createLabel(nodeInfo)
        return Pair(uniqueId, label)
    }

    /**
     * 创建节点的显示标签
     * @param fullNodeContent 完整的节点内容
     * @return HTML格式的标签字符串
     */
    private fun createLabel(nodeInfo: NodeInfo): String {
        // 这里通过不同的Size大小来区分数据, 便于JS中处理, 因为Graphviz生成图后没有办法对节点内部的不同部分进行有效区分
        return """
            <FONT POINT-SIZE="14" color="${getClassColor(nodeInfo.className)}"><B>${nodeInfo.className}</B></FONT>
            <BR/>
            <FONT POINT-SIZE="12" color="#0062c4"><B>${nodeInfo.funName}</B></FONT>
            <BR/>
            <FONT POINT-SIZE="7" color="#a8a8a8">${nodeInfo.param}</FONT>
            <BR/>
            <FONT POINT-SIZE="8" color="#a8a8a8">${nodeInfo.packageName}</FONT>
            """
    }

    private fun getClassColor(className: String): String {
        var classColor = classColorMap[className]
        if (classColor != null) {
            return classColor
        }
        classColor = DistinctDarkColorGenerator.generate(colorSet)
        colorSet.add(classColor)
        classColorMap[className] = classColor
        return classColor
    }

    /**
     * 添加父子节点关系
     * @param parentId 父节点ID
     * @param childId 子节点ID
     */
    fun addParentChildRelationship(parentId: String, childId: String) {
        val parentSet = parentMap.getOrDefault(childId, mutableSetOf())
        parentSet.add(parentId)
        parentMap[childId] = parentSet

        val childrenSet = childrenMap.getOrDefault(parentId, mutableSetOf())
        childrenSet.add(childId)
        childrenMap[parentId] = childrenSet
    }

    /**
     * 判断是否为根节点
     * @param nodeId 节点ID
     * @return 如果是根节点则返回true
     */
    fun isRootNode(nodeId: String): Boolean {
        return !parentMap.containsKey(nodeId) || parentMap[nodeId].isNullOrEmpty()
    }

    /**
     * 判断是否为叶节点
     * @param nodeId 节点ID
     * @return 如果是叶节点则返回true
     */
    fun isLeafNode(nodeId: String): Boolean {
        return !childrenMap.containsKey(nodeId) || childrenMap[nodeId].isNullOrEmpty()
    }

    /**
     * 获取所有节点ID到其完整内容的映射
     * @return Map<String, NodeInfo> 节点ID -> 节点内容
     */
    fun getNodeInfoMap(): MutableMap<String, NodeInfo> {
        val contentMap: MutableMap<String, NodeInfo> = HashMap()
        for (entry in nodeIdMap.entries) {
            contentMap[entry.value] = entry.key
        }
        return contentMap
    }

    data class NodeInfo(
        val className: String,
        val funName: String,
        val param: String,
        val packageName: String,
    ) {

        companion object {
            fun from(fullNodeContent: String): NodeInfo {
                val matchResult = LABEL_PATTERN_KOTLIN.find(fullNodeContent) // 使用 Kotlin Regex 的 find 方法
                if (matchResult != null) {
                    val (rawClassName, rawFunName, rawParam, _, rawPackageName) = matchResult.destructured

                    // 这里创建 NodeInfo 实例，只存储原始数据
                    return NodeInfo(
                        rawClassName.trim(),
                        rawFunName.trim(),
                        htmlEscaped(rawParam.trim()),
                        rawPackageName.trim()
                    )
                } else {
                    throw RuntimeException("异常的节点内容")
                }
            }

            private fun htmlEscaped(text:String): String {
                val result = text.replace("<", "&lt;").replace(">", "&gt;")
                return result.ifEmpty { " " }
            }
        }
    }
}