package org.xfqy.callhierarchygraph.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiMethod
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SelectMethodsDialog(
    project: Project,
    private val allMethods: List<PsiMethod>,
    private val preselectedMethod: PsiMethod? // 新增参数：预选中的方法
) : DialogWrapper(project) {

    private lateinit var methodsList: CheckBoxList<PsiMethod>

    init {
        title = "选择要分析的方法"
        init()
    }

    override fun createCenterPanel(): JComponent {
        // 创建 CheckBoxList 实例
        methodsList = CheckBoxList()

        // 填充列表项
        allMethods.forEach { method ->
            // 为每个方法创建一个列表项，包含复选框和方法描述
            // 第三个参数 isSelected 决定了该项是否默认被勾选
            methodsList.addItem(
                method,
                formatMethodForDisplay(method),
                method == preselectedMethod // 如果是预选中的方法，则默认勾选
            )
        }

        val scrollPane = JBScrollPane(methodsList)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)

        val panel = JPanel(BorderLayout())
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    /**
     * 自定义一个简单的方法格式化函数，用于在列表中显示
     */
    private fun formatMethodForDisplay(method: PsiMethod): String {
        val params = method.parameterList.parameters
            .joinToString(", ") { it.type.presentableText }
        return "${method.name}($params)"
    }

    /**
     * 获取所有被勾选的方法
     */
    fun getSelectedMethods(): List<PsiMethod> {
        val selected = mutableListOf<PsiMethod>()
        for (i in 0 until allMethods.size) {
            val method = methodsList.getItemAt(i)
            if (method != null && methodsList.isItemSelected(i)) {
                selected.add(method)
            }
        }
        return selected
    }
}
