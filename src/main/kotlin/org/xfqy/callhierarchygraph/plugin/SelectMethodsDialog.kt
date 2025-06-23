package org.xfqy.callhierarchygraph.plugin

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiMethod
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class SelectMethodsDialog(project: Project, private val methods: List<PsiMethod>) : DialogWrapper(project) {

    private lateinit var methodsList: JBList<PsiMethod>

    init {
        // 设置对话框标题
        title = "选择要分析的方法"
        // 初始化UI
        init()
    }

    /**
     * 创建对话框的中央面板，这是主要内容区域。
     */
    override fun createCenterPanel(): JComponent {
        // 使用方法列表初始化 JBList
        methodsList = JBList(methods)

        // 设置为多选模式
        methodsList.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        // 自定义列表项的显示方式
        methodsList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                // 先调用父类实现，获取基本的 JLabel 组件
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is PsiMethod) {
                    // 自定义显示的文本，这里只显示方法名和参数
                    val params = value.parameterList.parameters.joinToString(", ") { it.type.presentableText }
                    text = "${value.name}($params)"
                }
                return this
            }
        }

        // 将列表放入可滚动的面板中，并设置其首选大小
        val scrollPane = JBScrollPane(methodsList)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)

        // 使用 BorderLayout 的 JPanel 作为根面板
        val panel = JPanel(BorderLayout())
        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    /**
     * 提供一个公共方法，用于在对话框关闭后获取用户选择的方法。
     */
    fun getSelectedMethods(): List<PsiMethod> {
        return methodsList.selectedValuesList
    }
}
