package org.xfqy.callhierarchygraph.plugin

import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class SelectMethodsDialog(
    private val project: Project,
    initialClass: PsiClass, // 必须传入一个初始类
    private val preselectedMethod: PsiMethod?
) : DialogWrapper(project) {

    // 使用一个 Map 来存储每个类和其对应的 CheckBoxList
    private val classMethodLists = mutableMapOf<PsiClass, CheckBoxList<PsiMethod>>()
    // 主内容面板，所有“类面板”都将添加到这里
    private val mainPanel = JPanel()

    init {
        title = "选择要分析的方法"
        // 设置主面板为垂直盒式布局
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)

        // 初始化时，首先添加从 Action 上下文传来的初始类
        addClassPanel(initialClass, preselectedMethod)

        init()
    }

    override fun createCenterPanel(): JComponent {
        // 将我们的动态主面板放入滚动窗格中
        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
        return scrollPane
    }

    /**
     * 创建对话框底部的自定义按钮面板
     */
    override fun createSouthPanel(): JComponent {
        // 获取标准的 OK/Cancel 按钮面板
        val southPanel = super.createSouthPanel()

        // 创建一个用于放置我们自定义按钮的面板
        val customButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))

        val addClassButton = JButton("添加类...")
        addClassButton.addActionListener {
            // 打开 IntelliJ 的标准类选择器
            val chooser = TreeClassChooserFactory.getInstance(project)
                .createProjectScopeChooser("选择要添加的类")

            chooser.showDialog()

            // 如果用户选择了一个类
            val selectedClass = chooser.selected
            if (selectedClass != null && !classMethodLists.containsKey(selectedClass)) {
                // 动态添加一个新的类面板到UI上
                addClassPanel(selectedClass, null)
                // 重新调整对话框大小以适应新内容
                pack()
            }
        }

        customButtonsPanel.add(addClassButton)

        // 将自定义按钮面板和标准按钮面板组合在一起
        val container = JPanel(BorderLayout())
        container.add(customButtonsPanel, BorderLayout.WEST)
        container.add(southPanel, BorderLayout.EAST)

        return container
    }

    /**
     * 动态创建一个包含类名标题和方法复选框列表的面板，并添加到主面板中
     */
    private fun addClassPanel(psiClass: PsiClass, methodToSelect: PsiMethod?) {
        val classPanel = JPanel(BorderLayout())
        // 使用 TitledBorder 来显示类名，非常清晰
        val borderTitle = psiClass.qualifiedName ?: psiClass.name ?: "Unknown Class"
        classPanel.border = BorderFactory.createTitledBorder(borderTitle)

        val methodList = CheckBoxList<PsiMethod>()
        psiClass.methods.forEach { method ->
            methodList.addItem(
                method,
                formatMethodForDisplay(method),
                method == methodToSelect // 预选
            )
        }

        // 存储这个列表以便之后获取选中的方法
        classMethodLists[psiClass] = methodList

        classPanel.add(methodList, BorderLayout.CENTER)
        mainPanel.add(classPanel)
        // 刷新UI
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    /**
     * 获取所有面板中所有被勾选的方法
     */
    fun getSelectedMethods(): List<PsiMethod> {
        val allSelectedMethods = mutableListOf<PsiMethod>()

        // 遍历我们存储的所有 CheckBoxList
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
