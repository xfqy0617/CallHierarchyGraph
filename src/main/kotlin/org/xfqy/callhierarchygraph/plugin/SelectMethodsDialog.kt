package org.xfqy.callhierarchygraph.plugin

import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Paths
import javax.swing.*

class SelectMethodsDialog(
    private val project: Project,
    initialClass: PsiClass,
    private val preselectedMethod: PsiMethod?
) : DialogWrapper(project) {


    private lateinit var pathTextField: JBTextField
    private lateinit var filenameTextField: JBTextField
    private val classMethodLists = mutableMapOf<PsiClass, CheckBoxList<PsiMethod>>()
    private val mainPanel = JPanel()
    private val scopeRadioButtons = mutableMapOf<AnalysisScope, JRadioButton>() // [新增] 用于存储单选按钮

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
    /**
     * [重构] 创建对话框底部的自定义面板，包含作用域和文件选择功能
     */
    override fun createSouthPanel(): JComponent {
        // --- 1. 创建作用域选择器 ---
        val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val scopeButtonGroup = ButtonGroup()
        AnalysisScope.values().forEach { scope ->
            val radioButton = JRadioButton(scope.displayName)
            scopeRadioButtons[scope] = radioButton
            scopeButtonGroup.add(radioButton)
            scopePanel.add(radioButton)
        }
        // 设置默认选项为 "仅生产代码"
        scopeRadioButtons[AnalysisScope.PRODUCTION]?.isSelected = true

        // --- 2. 创建文件路径和文件名输入字段 (逻辑不变) ---
        val userHome = System.getProperty("user.home")
        val defaultPath = Paths.get(userHome, "Downloads", "CallHierarchyGraph", "output").toString()
        val defaultFilename = "call_hierarchy"

        pathTextField = JBTextField(defaultPath, 30)
        filenameTextField = JBTextField(defaultFilename, 30)

        // [新增] 创建 "浏览..." 按钮
        val browseButton = JButton("浏览...")

        // [新增] 为 "浏览..." 按钮添加点击事件
        browseButton.addActionListener {
            // a. 创建一个文件选择器描述符，配置它只选择文件夹
            val descriptor = FileChooserDescriptor(
                false, // chooseFiles
                true,  // chooseFolders
                false, // chooseJars
                false, // chooseJarsAsFiles
                false, // chooseJarContents
                false  // chooseMultiple
            ).withTitle("选择导出目录") // 设置对话框标题

            // b. 创建并显示文件选择对话框
            //    FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            //    使用一个更简洁的方式
            val chooserDialog = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val files = chooserDialog.choose(project)

            // c. 如果用户选择了一个文件夹，则更新文本框
            if (files.isNotEmpty()) {
                pathTextField.text = files[0].path
            }
        }

        val pathPanel = JPanel(BorderLayout(JBUI.scale(5), 0))
        pathPanel.add(pathTextField, BorderLayout.CENTER)
        pathPanel.add(browseButton, BorderLayout.EAST)

        // --- 3. 使用 FormBuilder 构建整体表单 ---
        // [修改] 将作用域选择器添加到表单中
        val inputFieldsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JLabel("分析作用域:"), scopePanel, true) // [新增]
            .addLabeledComponent(JLabel("导出路径:"), pathPanel, true)
            .addLabeledComponent(JLabel("导出文件名:"), filenameTextField, true)
            .panel

        // --- 4. 组合所有南部组件 (逻辑不变) ---
        val southPanel = super.createSouthPanel()
        val customButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val addClassButton = JButton("添加类...")
        // ... addClassButton.addActionListener 逻辑不变 ...
        customButtonsPanel.add(addClassButton)

        val container = JPanel(BorderLayout())
        container.add(inputFieldsPanel, BorderLayout.CENTER)
        val rightButtonsPanel = JPanel(BorderLayout())
        rightButtonsPanel.add(customButtonsPanel, BorderLayout.WEST)
        rightButtonsPanel.add(southPanel, BorderLayout.EAST)
        container.add(rightButtonsPanel, BorderLayout.EAST)

        return container
    }

    // [新增] 公共方法，用于获取用户选择的作用域
    fun getSelectedScope(): AnalysisScope {
        return scopeRadioButtons.entries
            .firstOrNull { it.value.isSelected }
            ?.key ?: AnalysisScope.PRODUCTION // 如果没找到，默认返回 PRODUCTION
    }

    // [新增] 公共方法用于获取用户输入
    fun getOutputPath(): String = pathTextField.text.trim()
    fun getOutputFilename(): String = filenameTextField.text.trim()

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
