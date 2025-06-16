package org.xfqy.callhierarchygraph.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel
import javax.swing.JTextArea

class CallHierarchyGraphToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel()
        val textArea = JTextArea(10, 40)
        textArea.isEditable = false
        val scrollPane = JBScrollPane(textArea)
        panel.add(scrollPane)
        val content = ContentFactory.getInstance().createContent(panel, "Call Hierarchy", false)
        toolWindow.contentManager.addContent(content)
    }
}
