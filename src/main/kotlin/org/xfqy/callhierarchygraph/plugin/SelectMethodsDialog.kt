package org.xfqy.callhierarchygraph.plugin

import com.intellij.icons.AllIcons
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Paths
import javax.swing.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class SelectMethodsDialog(
    private val project: Project,
    initialClass: PsiClass,
    private val preselectedMethod: PsiMethod?
) : DialogWrapper(project) {

    private lateinit var pathTextField: JBTextField
    private lateinit var filenameTextField: JBTextField
    private val classMethodLists = mutableMapOf<PsiClass, CheckBoxList<PsiMethod>>()
    private val mainPanel = JPanel()
    private val scopeRadioButtons = mutableMapOf<AnalysisScope, JRadioButton>()

    init {
        title = "选择要分析的方法"
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        addClassPanel(initialClass, preselectedMethod)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
        scrollPane.border = JBUI.Borders.empty(5)
        return scrollPane
    }

    /**
     * [重构] 创建一个结构更清晰、视觉效果更好的底部面板
     */
    override fun createSouthPanel(): JComponent {
        // --- 1. 创建 "导出选项" 面板 (使用带标题的边框) ---
        val exportOptionsPanel = JPanel(BorderLayout())
        exportOptionsPanel.border = BorderFactory.createTitledBorder("导出选项")

        // a. 创建作用域选择器
        val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)) // 增加按钮间距
        val scopeButtonGroup = ButtonGroup()
        AnalysisScope.values().forEach { scope ->
            val radioButton = JRadioButton(scope.displayName)
            scopeRadioButtons[scope] = radioButton
            scopeButtonGroup.add(radioButton)
            scopePanel.add(radioButton)
        }
        scopeRadioButtons[AnalysisScope.PRODUCTION]?.isSelected = true

        // b. 创建文件路径和文件名输入字段
        val userHome = System.getProperty("user.home")
        val defaultPath = Paths.get(userHome, "Downloads", "CallHierarchyGraph", "output").toString()
        pathTextField = JBTextField(defaultPath, 30)
        filenameTextField = JBTextField("call_hierarchy", 30)

        val browseButton = JButton("浏览...")
        browseButton.addActionListener {
            val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
                .withTitle("选择导出目录")
            val chooserDialog = FileChooserFactory.getInstance().createFileChooser(descriptor, project, null)
            val files = chooserDialog.choose(project)
            if (files.isNotEmpty()) {
                pathTextField.text = files[0].path
            }
        }
        val pathPanel = JPanel(BorderLayout(JBUI.scale(5), 0))
        pathPanel.add(pathTextField, BorderLayout.CENTER)
        pathPanel.add(browseButton, BorderLayout.EAST)

        // c. 使用 FormBuilder 组合选项
        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("分析作用域:", scopePanel, true)
            .addLabeledComponent("导出路径:", pathPanel, true)
            .addLabeledComponent("导出文件名:", filenameTextField, true)
            .panel.apply {
                // 给表单内容加一点内边距，让它不紧贴边框
                border = JBUI.Borders.empty(5, 10, 5, 10)
            }

        exportOptionsPanel.add(formPanel, BorderLayout.CENTER)

        // --- 2. 创建底部按钮栏 ---
        val buttonsPanel = JPanel(BorderLayout())

        // a. 左侧的 "添加类..." 按钮
        val addClassButton = JButton("添加类...")
        addClassButton.addActionListener {
            val chooser = TreeClassChooserFactory.getInstance(project)
                .createWithInnerClassesScopeChooser(
                    "选择要添加的类",
                    GlobalSearchScope.projectScope(project),
                    { true }, // 可以添加过滤器，这里允许所有类
                    null
                )
            chooser.showDialog()
            val selectedClass = chooser.selected
            if (selectedClass != null && !classMethodLists.containsKey(selectedClass)) {
                // 如果用户选择了类，并且这个类尚未在列表中，则添加它
                addClassPanel(selectedClass, null)
            }
        }
        val leftButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        leftButtonsPanel.add(addClassButton)
        buttonsPanel.add(leftButtonsPanel, BorderLayout.WEST)

        // b. 右侧的 "OK" 和 "Cancel" 按钮
        // super.createSouthPanel() 返回的是包含 OK/Cancel 按钮的面板
        val rightButtonsPanel = super.createSouthPanel()
        buttonsPanel.add(rightButtonsPanel, BorderLayout.EAST)

        // --- 3. 组合最终的底部面板 ---
        val finalSouthPanel = JPanel(BorderLayout(0, JBUI.scale(5))) // 增加垂直间距
        finalSouthPanel.add(exportOptionsPanel, BorderLayout.CENTER)
        finalSouthPanel.add(buttonsPanel, BorderLayout.SOUTH)

        return finalSouthPanel
    }

    fun getOutputPath(): String = pathTextField.text.trim()

    fun getOutputFilename(): String = filenameTextField.text.trim()

    fun getSelectedScope(): AnalysisScope {
        return scopeRadioButtons.entries
            .firstOrNull { it.value.isSelected }
            ?.key ?: AnalysisScope.PRODUCTION // 如果没找到，默认返回 PRODUCTION
    }

    /**
     * [UI增强最终版 v3] 动态创建可移除的、视觉层次感更强的“卡片式”面板。
     * - 修复了弃用警告。
     * - 在每个类面板的标题栏右上角添加了删除按钮。
     */
    private fun addClassPanel(psiClass: PsiClass, methodToSelect: PsiMethod?) {
        // --- 1. 创建卡片式容器 ---
        val classContainerPanel = JPanel(BorderLayout())
        classContainerPanel.border = BorderFactory.createLineBorder(UIUtil.CONTRAST_BORDER_COLOR)

        // --- 2. 创建带背景色、图标和删除按钮的标题栏 ---
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = UIUtil.getDecoratedRowColor()
        headerPanel.isOpaque = true
        headerPanel.border = JBUI.Borders.empty(8, 10)

        // -- a. 左侧的标题内容 --
        val masterCheckbox = JCheckBox()
        val classNameLabel = JLabel(psiClass.qualifiedName ?: psiClass.name, AllIcons.Nodes.Class, SwingConstants.LEFT)
        classNameLabel.font = classNameLabel.font.deriveFont(Font.BOLD)

        val titleContentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        titleContentPanel.isOpaque = false
        titleContentPanel.add(masterCheckbox)
        titleContentPanel.add(Box.createHorizontalStrut(8))
        titleContentPanel.add(classNameLabel)

        headerPanel.add(titleContentPanel, BorderLayout.CENTER)

        // -- b. [新增] 右侧的删除按钮 --
        val removeButton = JLabel(AllIcons.Actions.Close)
        removeButton.toolTipText = "移除该类" // 提示用户按钮的功能
        // 设置鼠标指针样式为手型，增强可点击的视觉提示
        removeButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        removeButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                // 执行删除操作
                mainPanel.remove(classContainerPanel) // 移除UI面板
                classMethodLists.remove(psiClass)     // 移除数据

                // 刷新主面板以更新布局和显示
                mainPanel.revalidate()
                mainPanel.repaint()
            }

            override fun mouseEntered(e: MouseEvent?) {
                // 鼠标悬浮时切换到高亮图标
                removeButton.icon = AllIcons.Actions.CloseHovered
            }

            override fun mouseExited(e: MouseEvent?) {
                // 鼠标离开时恢复默认图标
                removeButton.icon = AllIcons.Actions.Close
            }
        })
        headerPanel.add(removeButton, BorderLayout.EAST)


        // --- 3. 创建方法列表 (逻辑不变) ---
        val methodList = CheckBoxList<PsiMethod>()
        val methodsToShow = psiClass.methods.filter { !it.isConstructor }
        // 修改此处的选择逻辑
        methodsToShow.forEach { method ->
            // 如果 methodToSelect 为 null (即右键点击的是类)，则默认选中。
            // 否则，只选中被指定的那个方法。
            val shouldBeSelected = if (methodToSelect == null) true else method == methodToSelect
            methodList.addItem(method, formatMethodForDisplay(method), shouldBeSelected)
        }

        // 相应地，更新主复选框的初始状态
        // 如果 methodToSelect 为 null (全选模式)，或者只有一个方法且被选中，则勾选主复选框
        if (methodToSelect == null || (methodsToShow.size == 1 && methodsToShow.first() == methodToSelect)) {
            masterCheckbox.isSelected = true
        }
        masterCheckbox.addActionListener {
            val isSelected = masterCheckbox.isSelected
            for (i in 0 until methodList.itemsCount) {
                methodList.setItemSelected(methodList.getItemAt(i), isSelected)
            }
            methodList.repaint()
        }


        // --- 4. 将方法列表放入带内边距的滚动面板中 (逻辑不变) ---
        val methodScrollPane = JBScrollPane(methodList)
        methodScrollPane.border = JBUI.Borders.empty(5, 20, 10, 10)

        // --- 5. 组装最终的卡片 (逻辑不变) ---
        classContainerPanel.add(headerPanel, BorderLayout.NORTH)
        classContainerPanel.add(methodScrollPane, BorderLayout.CENTER)

        classMethodLists[psiClass] = methodList

        mainPanel.add(classContainerPanel)
        mainPanel.add(Box.createVerticalStrut(10))
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