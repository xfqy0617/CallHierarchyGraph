package org.xfqy.callhierarchygraph.visualizer

import com.google.gson.GsonBuilder
import org.xfqy.callhierarchygraph.model.GraphData
import java.awt.Desktop
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// 我们将使用一个新的模板文件，稍后创建它
private const val TEMPLATE_PATH = "templates/graph_template_js.html"

/**
 * 新的可视化器，将结构化的 GraphData 渲染成一个可交互的 HTML 文件。
 */
class CallHierarchyVisualizer {

    /**
     * @param graphData 包含所有节点和边的结构化数据。
     * @param outputFilename 用户指定的输出文件名（不含扩展名）。
     * @param view 是否在完成后自动在浏览器中打开。
     * @param basePath 用户指定的输出目录。
     */
    fun renderGraph(graphData: GraphData, outputFilename: String, view: Boolean, basePath: String) {
        try {
            val outputDir = Paths.get(basePath)
            Files.createDirectories(outputDir) // 确保目录存在
            val outputPath = outputDir.resolve("$outputFilename.html")

            // 1. 加载 HTML 模板
            val templateContent = loadTemplate()

            // 2. 将 GraphData 对象序列化为 JSON 字符串
            val gson = GsonBuilder().create() // .setPrettyPrinting() is good for debugging
            val graphJson = gson.toJson(graphData)

            // 3. 将 JSON 数据安全地注入到 HTML 模板中
            val htmlContent = templateContent.replace("'{graph_data_placeholder}'", graphJson)

            // 4. 将最终的 HTML 内容写入文件
            Files.writeString(outputPath, htmlContent, StandardCharsets.UTF_8)
            println("图表已成功导出至: ${outputPath.toAbsolutePath()}")

            // 5. 如果需要，在默认浏览器中打开文件
            if (view && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(outputPath.toFile())
            }

        } catch (e: Exception) {
            System.err.println("渲染图表时发生错误: ${e.message}")
            e.printStackTrace()
            throw IOException("渲染失败", e)
        }
    }

    private fun loadTemplate(): String {
        val inputStream = javaClass.classLoader.getResourceAsStream(TEMPLATE_PATH)
            ?: throw IOException("无法找到HTML模板: $TEMPLATE_PATH. 请确保它在 resources 目录下。")

        return inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }
}
