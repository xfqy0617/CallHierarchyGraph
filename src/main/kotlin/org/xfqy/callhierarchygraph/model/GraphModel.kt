package org.xfqy.callhierarchygraph.model

/**
 * 图数据的顶层容器，可被序列化为 JSON。
 */
data class GraphData(
    val nodes: List<NodeData>,
    val edges: List<EdgeData>
)

/**
 * 定义单个节点的数据结构。
 * 每个字段都将成为 JSON 中的一个 key。
 */
data class NodeData(
    val id: String,          // 节点的唯一ID, e.g., "node1"
    val className: String,
    val methodName: String,
    val params: String,
    val packageName: String,
    val classColor: String,  // 后端生成的、用于UI提示的颜色
    val isEntry: Boolean,      // 标记是否为用户选择的分析入口点
    val nodeType: String   // 新增: "ROOT", "LEAF", "INTERMEDIATE"
)

/**
 * 定义单条边的数据结构。
 * 表示从 'source' 节点到 'target' 节点的有向连接。
 */
data class EdgeData(
    val source: String,      // 源节点 ID
    val target: String       // 目标节点 ID
)
