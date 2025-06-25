package org.xfqy.callhierarchygraph.entity

// 顶层数据结构
data class GraphData(
    val nodes: List<NodeData>,
    val edges: List<EdgeData>
)

// 节点信息
data class NodeData(
    val id: String,          // 唯一ID，例如 "node1", "node2"
    val className: String,
    val methodName: String,
    val params: String,
    val packageName: String,
    val classColor: String   // 由后端生成的类颜色
)

// 边信息
data class EdgeData(
    val source: String,      // 源节点 ID
    val target: String       // 目标节点 ID
)
