package org.xfqy.callhierarchygraph.plugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import org.xfqy.callhierarchygraph.visualizer.CallHierarchyVisualizer
import java.nio.file.Files
import java.nio.file.Paths

class CallHierarchyGraphAction : AnAction("Export Call Hierarchy to HTML") {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val callHierarchyText = getCallHierarchyText(project)
        if (callHierarchyText.isBlank()) {
            Messages.showErrorDialog(project, "No call hierarchy data available.", "Error")
            return
        }
        val visualizer = CallHierarchyVisualizer(4) // 假设缩进为4个空格
        val outputPath = exportToHtml(project, visualizer)
        Messages.showInfoMessage(project, "Call hierarchy exported to: $outputPath", "Success")
    }

    private fun getCallHierarchyText(project: Project): String {
        val callHierarchyText = StringBuilder()
        try {
            val editor = com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.firstOrNull { it.project == project } ?: return ""
            val psiFile = com.intellij.psi.PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return ""
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset) ?: return ""
            val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return ""
            buildCallHierarchy(method, callHierarchyText, 0)
        } catch (e: Exception) {
            println(e)
        }
        return callHierarchyText.toString()
    }

    private fun buildCallHierarchy(method: PsiMethod, result: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        result.append(indent).append(method.name).append("\n")
        val references = ReferencesSearch.search(method).findAll()
        for (reference in references) {
            val caller = reference.element
            val callerMethod = PsiTreeUtil.getParentOfType(caller, PsiMethod::class.java)
            if (callerMethod != null) {
                buildCallHierarchy(callerMethod, result, depth + 1)
            }
        }
    }

    private fun exportToHtml(project: Project, visualizer: CallHierarchyVisualizer): String {
        val outputDir = Paths.get(project.basePath, "call-hierarchy-output")
        Files.createDirectories(outputDir)
        val outputFile = outputDir.resolve("call-hierarchy.html")
        visualizer.parseAndBuildGraph(outputFile.toString())
        visualizer.renderGraph("call-hierarchy.html", false, "html")
        return outputFile.toString()
    }
}
