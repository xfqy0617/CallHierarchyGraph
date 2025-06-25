// CallHierarchyVisualizer.kt (全新简化版)
package org.xfqy.callhierarchygraph.visualizer

import com.google.gson.GsonBuilder
import org.xfqy.callhierarchygraph.model.GraphData
import java.awt.Desktop
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

private const val TEMPLATE_PATH = "templates/graph_template_js.html" // 使用新的模板

class CallHierarchyVisualizer {

    fun renderGraph(graphData: GraphData, outputFilename: String, view: Boolean, basePath: String) {
        try {
            val outputDir = Paths.get(basePath)
            Files.createDirectories(outputDir)
            val outputPath = outputDir.resolve(outputFilename)

            // 1. 加载 HTML 模板
            val templateContent = loadTemplate()

            // 2. 将 GraphData 序列化为 JSON
            val gson = GsonBuilder().setPrettyPrinting().create() // 使用 pretty printing 方便调试
            val graphJson = gson.toJson(graphData)

            // 3. 注入 JSON 数据到模板
            val htmlContent = templateContent
                .replace("'{graph_data_placeholder}'", graphJson) // 注意占位符的变化

            // 4. 写入文件并打开
            val htmlPath = outputPath.resolveSibling(outputPath.fileName.toString() + ".html")
            Files.writeString(htmlPath, htmlContent, StandardCharsets.UTF_8)
            println("图已渲染到 " + htmlPath.toAbsolutePath())

            if (view && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(htmlPath.toFile())
            }

        } catch (e: Exception) {
            System.err.println("渲染过程中发生错误: ${e.message}")
            throw IOException("渲染失败", e)
        }
    }

    private fun loadTemplate(): String {
        return object {}.javaClass.classLoader.getResourceAsStream(TEMPLATE_PATH)?.bufferedReader(StandardCharsets.UTF_8)?.readText()
            ?: throw IOException("无法加载HTML模板文件: $TEMPLATE_PATH")
    }
}
