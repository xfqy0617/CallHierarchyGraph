package org.xfqy.graphxfqy.config;

/**
 * 图配置类，存储所有可配置的图形参数
 */
class GraphConfig {
    val graphName: String = "CallHierarchyGraph"
    val nodesep: String = "0.5"
    val ranksep: String = "1.0"
    val nodeShape: String = "box"
    val nodeStyle: String = "rounded,filled"
    val nodeFontsize: Int = 10
    val edgeArrowsize: String = "0.7"
    val rootNodeColor: String = "#E0E0E0"
    val leafNodeColor: String = "#D2EEC1"
    val defaultNodeColor: String = "#B6E8F3"

}
