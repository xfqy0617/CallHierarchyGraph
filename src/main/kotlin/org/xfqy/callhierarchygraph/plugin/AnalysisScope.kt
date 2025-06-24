package org.xfqy.callhierarchygraph.plugin


enum class AnalysisScope(val displayName: String) {
    ALL("全部"),
    PRODUCTION("仅生产代码"),
    TEST("仅测试代码")
}