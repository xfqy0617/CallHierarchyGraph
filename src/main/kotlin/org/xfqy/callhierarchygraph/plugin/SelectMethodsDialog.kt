package org.xfqy.callhierarchygraph.plugin

import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

class SelectMethodsDialog(
    private val project: Project,
    initialClass: PsiClass,
    private val preselectedMethod: PsiMethod?
) : DialogWrapper(project) {

    private val classMethodLists = mutableMapOf<PsiClass, CheckBoxList<PsiMethod>>()
    private val mainPanel = JPanel()

    init {
        title = "选择要分析的方法"
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        addClassPanel(initialClass, preselectedMethod)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
        // 给滚动面板也加一点边距，让UI看起来不那么拥挤
        scrollPane.border = JBUI.Borders.empty(5)
        return scrollPane
    }

    override fun createSouthPanel(): JComponent {
        val southPanel = super.createSouthPanel()
        val customButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val addClassButton = JButton("添加类...")
        addClassButton.addActionListener {
            val chooser = TreeClassChooserFactory.getInstance(project)
                .createProjectScopeChooser("选择要添加的类")
            chooser.showDialog()
            val selectedClass = chooser.selected
            if (selectedClass != null && !classMethodLists.containsKey(selectedClass)) {
                addClassPanel(selectedClass, null)
                pack()
            }
        }
        customButtonsPanel.add(addClassButton)
        val container = JPanel(BorderLayout())
        container.add(customButtonsPanel, BorderLayout.WEST)
        container.add(southPanel, BorderLayout.EAST)
        return container
    }

    /**
     * [重构] 动态创建一个包含类标题(带全选框)和方法复选框列表的面板
     */
    private fun addClassPanel(psiClass: PsiClass, methodToSelect: PsiMethod?) {
        // 1. 创建最外层的容器面板，使用 BorderLayout
        val classContainerPanel = JPanel(BorderLayout())
        classContainerPanel.border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBUI.CurrentTheme.ToolWindow.borderColor(), 1, 0, 1, 0),
            JBUI.Borders.empty(5)
        )

        // 2. 创建自定义的标题面板
        val headerPanel = JPanel(BorderLayout())
        val masterCheckbox = JCheckBox() // 全选/全不选的主复选框
        val classNameLabel = JLabel(psiClass.qualifiedName ?: psiClass.name)
        classNameLabel.font = classNameLabel.font.deriveFont(java.awt.Font.BOLD) // 加粗显示类名

        val titleContentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        titleContentPanel.add(masterCheckbox)
        titleContentPanel.add(Box.createHorizontalStrut(5)) // 增加一点间距
        titleContentPanel.add(classNameLabel)
        headerPanel.add(titleContentPanel, BorderLayout.WEST)
        headerPanel.border = JBUI.Borders.emptyBottom(5)

        // 3. 创建方法列表
        val methodList = CheckBoxList<PsiMethod>()
        psiClass.methods.forEach { method ->
            methodList.addItem(
                method,
                formatMethodForDisplay(method),
                method == methodToSelect
            )
        }

        // 检查初始状态，如果只有一个方法且被选中，则主复选框也应被选中
        if (psiClass.methods.size == 1 && methodToSelect != null && psiClass.methods.first() == methodToSelect) {
            masterCheckbox.isSelected = true
        }

        // 4. 为主复选框添加事件监听器
        masterCheckbox.addActionListener {
            val isSelected = masterCheckbox.isSelected
            for (i in 0 until methodList.itemsCount) {
                // 直接使用 setItemSelected(index, boolean) 效率更高
                methodList.setItemSelected(methodList.getItemAt(i), isSelected)
            }
            // 强制重绘列表以确保UI立即更新
            methodList.repaint()
        }

        // 5. 将所有组件组装到容器面板中
        classContainerPanel.add(headerPanel, BorderLayout.NORTH)
        // 将方法列表放入滚动窗格，以防方法过多
        classContainerPanel.add(JBScrollPane(methodList).apply { border = null }, BorderLayout.CENTER)

        // 存储列表以便之后获取选中的方法
        classMethodLists[psiClass] = methodList

        // 将最终完成的类面板添加到主面板
        mainPanel.add(classContainerPanel)
        mainPanel.add(Box.createVerticalStrut(5)) // 增加类与类之间的垂直间距
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    fun getSelectedMethods(): List<PsiMethod> {
        val allSelectedMethods = mutableListOf<PsiMethod>()
        classMethodLists.values.forEach { list ->
            for (i in 0 until list.itemsCount) {
                if (list.isItemSelected(i)) {
                    list.getItemAt(i)?.let { allSelectedMethods.add(it) }
                }
            }
        }
        return allSelectedMethods
    }

    private fun formatMethodForDisplay(method: PsiMethod): String {
        val params = method.parameterList.parameters
            .joinToString(", ") { it.type.presentableText }
        return "${method.name}($params)"
    }
}
