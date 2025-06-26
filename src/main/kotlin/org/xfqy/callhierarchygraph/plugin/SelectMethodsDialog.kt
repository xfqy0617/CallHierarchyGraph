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
import com.intellij.ui.components.JBCheckBox
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
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class SelectMethodsDialog(
    private val project: Project,
    initialClass: PsiClass,
    private val preselectedMethod: PsiMethod?
) : DialogWrapper(project) {

    private lateinit var pathTextField: JBTextField
    private lateinit var filenameTextField: JBTextField
    // [优化2] 新增复选框的引用
    private lateinit var skipUncalledMethodsCheckbox: JBCheckBox
    private val classMethodLists = mutableMapOf<PsiClass, CheckBoxList<PsiMethod>>()
    private val mainPanel = JPanel()
    private val scopeRadioButtons = mutableMapOf<AnalysisScope, JRadioButton>()

    init {
        title = "选择要分析的方法"
        mainPanel.layout = BoxLayout(mainPanel, BoxLayout.Y_AXIS)
        addClassPanel(initialClass, preselectedMethod)
        init()
        // [优化1] 初始化时检查一次按钮状态
        updateOkButtonState()
    }

    override fun createCenterPanel(): JComponent {
        val scrollPane = JBScrollPane(mainPanel)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
        scrollPane.border = JBUI.Borders.empty(5)
        return scrollPane
    }

    override fun createSouthPanel(): JComponent {
        val exportOptionsPanel = JPanel(BorderLayout())
        exportOptionsPanel.border = BorderFactory.createTitledBorder("导出选项")

        val scopePanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0))
        val scopeButtonGroup = ButtonGroup()
        AnalysisScope.values().forEach { scope ->
            val radioButton = JRadioButton(scope.displayName)
            scopeRadioButtons[scope] = radioButton
            scopeButtonGroup.add(radioButton)
            scopePanel.add(radioButton)
        }
        scopeRadioButtons[AnalysisScope.PRODUCTION]?.isSelected = true

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

        // [优化2] 创建新的复选框
        skipUncalledMethodsCheckbox = JBCheckBox("不绘制无调用的方法", true)

        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("分析作用域:", scopePanel, true)
            .addLabeledComponent("导出路径:", pathPanel, true)
            .addLabeledComponent("导出文件名:", filenameTextField, true)
            // [优化2] 将复选框添加到表单中
            .addComponentToRightColumn(skipUncalledMethodsCheckbox, 1)
            .panel.apply {
                border = JBUI.Borders.empty(5, 10, 5, 10)
            }

        exportOptionsPanel.add(formPanel, BorderLayout.CENTER)

        val buttonsPanel = JPanel(BorderLayout())

        val addClassButton = JButton("添加类...")
        addClassButton.addActionListener {
            val chooser = TreeClassChooserFactory.getInstance(project)
                .createWithInnerClassesScopeChooser(
                    "选择要添加的类",
                    GlobalSearchScope.projectScope(project),
                    { true },
                    null
                )
            chooser.showDialog()
            val selectedClass = chooser.selected
            if (selectedClass != null && !classMethodLists.containsKey(selectedClass)) {
                addClassPanel(selectedClass, null)
            }
        }
        val leftButtonsPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        leftButtonsPanel.add(addClassButton)
        buttonsPanel.add(leftButtonsPanel, BorderLayout.WEST)

        val rightButtonsPanel = super.createSouthPanel()
        buttonsPanel.add(rightButtonsPanel, BorderLayout.EAST)

        val finalSouthPanel = JPanel(BorderLayout(0, JBUI.scale(5)))
        finalSouthPanel.add(exportOptionsPanel, BorderLayout.CENTER)
        finalSouthPanel.add(buttonsPanel, BorderLayout.SOUTH)

        return finalSouthPanel
    }

    fun getOutputPath(): String = pathTextField.text.trim()
    fun getOutputFilename(): String = filenameTextField.text.trim()
    fun getSelectedScope(): AnalysisScope = scopeRadioButtons.entries.firstOrNull { it.value.isSelected }?.key ?: AnalysisScope.PRODUCTION

    // [优化2] 提供获取新复选框状态的方法
    fun shouldSkipUncalledMethods(): Boolean = skipUncalledMethodsCheckbox.isSelected

    private fun addClassPanel(psiClass: PsiClass, methodToSelect: PsiMethod?) {
        val classContainerPanel = JPanel(BorderLayout())
        classContainerPanel.border = BorderFactory.createLineBorder(UIUtil.CONTRAST_BORDER_COLOR)
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = UIUtil.getDecoratedRowColor()
        headerPanel.isOpaque = true
        headerPanel.border = JBUI.Borders.empty(8, 10)

        val masterCheckbox = JCheckBox()
        val classNameLabel = JLabel(psiClass.qualifiedName ?: psiClass.name, AllIcons.Nodes.Class, SwingConstants.LEFT)
        classNameLabel.font = classNameLabel.font.deriveFont(Font.BOLD)

        val titleContentPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        titleContentPanel.isOpaque = false
        titleContentPanel.add(masterCheckbox)
        titleContentPanel.add(Box.createHorizontalStrut(8))
        titleContentPanel.add(classNameLabel)
        headerPanel.add(titleContentPanel, BorderLayout.CENTER)

        val removeButton = JLabel(AllIcons.Actions.Close)
        removeButton.toolTipText = "移除该类"
        removeButton.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        removeButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                mainPanel.remove(classContainerPanel)
                classMethodLists.remove(psiClass)
                mainPanel.revalidate()
                mainPanel.repaint()
                updateOkButtonState() // [优化1] 移除类后也要更新按钮状态
            }
            override fun mouseEntered(e: MouseEvent?) { removeButton.icon = AllIcons.Actions.CloseHovered }
            override fun mouseExited(e: MouseEvent?) { removeButton.icon = AllIcons.Actions.Close }
        })
        headerPanel.add(removeButton, BorderLayout.EAST)

        val methodList = CheckBoxList<PsiMethod>()
        val methodsToShow = psiClass.methods.filter { !it.isConstructor }
        methodsToShow.forEach { method ->
            val shouldBeSelected = if (methodToSelect == null) true else method == methodToSelect
            methodList.addItem(method, formatMethodForDisplay(method), shouldBeSelected)
        }

        if (methodToSelect == null || (methodsToShow.size == 1 && methodsToShow.first() == methodToSelect)) {
            masterCheckbox.isSelected = true
        }

        masterCheckbox.addActionListener {
            val isSelected = masterCheckbox.isSelected
            for (i in 0 until methodList.itemsCount) {
                methodList.setItemSelected(methodList.getItemAt(i), isSelected)
            }
            methodList.repaint()
            updateOkButtonState() // [优化1] 主复选框变化时更新按钮状态
        }

        // [优化1] 监听列表选择变化
        methodList.model.addListDataListener(object : ListDataListener {
            override fun intervalAdded(e: ListDataEvent?) { updateOkButtonState() }
            override fun intervalRemoved(e: ListDataEvent?) { updateOkButtonState() }
            override fun contentsChanged(e: ListDataEvent?) { updateOkButtonState() }
        })

        val methodScrollPane = JBScrollPane(methodList)
        methodScrollPane.border = JBUI.Borders.empty(5, 20, 10, 10)
        classContainerPanel.add(headerPanel, BorderLayout.NORTH)
        classContainerPanel.add(methodScrollPane, BorderLayout.CENTER)
        classMethodLists[psiClass] = methodList
        mainPanel.add(classContainerPanel)
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.revalidate()
        mainPanel.repaint()
    }

    // [优化1] 检查是否有方法被选中，并更新OK按钮状态
    private fun updateOkButtonState() {
        isOKActionEnabled = getSelectedMethods().isNotEmpty()
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
