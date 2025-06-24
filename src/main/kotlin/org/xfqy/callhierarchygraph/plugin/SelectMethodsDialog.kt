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
     * [UI增强最终修正版] 动态创建一个视觉层次感更强的“卡片式”面板，用于展示类和其方法。
     * 该版本使用了兼容性更好的UIUtil API，确保在不同版本的IDE中都能正常编译和运行。
     */
    private fun addClassPanel(psiClass: PsiClass, methodToSelect: PsiMethod?) {
        // --- 1. 创建卡片式容器 ---
        val classContainerPanel = JPanel(BorderLayout())
        // [修复] 使用 UIUtil.getSeparatorColor() 获取标准的分隔线/边框颜色。
        // 这是兼容性非常好的一个API，用于UI元素的分隔。
        classContainerPanel.border = BorderFactory.createLineBorder(UIUtil.getSeparatorColor())

        // --- 2. 创建带背景色和图标的标题栏 ---
        val headerPanel = JPanel(BorderLayout())
        // [修复] 使用 UIUtil.getDecoratedRowColor() 获取用于装饰/高亮的背景色。
        // 这个颜色通常用于表格的交替行或设置中的分组，非常适合作为标题栏背景。
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

        // --- 3. 创建方法列表 ---
        val methodList = CheckBoxList<PsiMethod>()
        val methodsToShow = psiClass.methods.filter { !it.isConstructor }
        methodsToShow.forEach { method ->
            methodList.addItem(
                method,
                formatMethodForDisplay(method),
                method == methodToSelect
            )
        }

        if (methodsToShow.isNotEmpty() && methodsToShow.all { it == methodToSelect }) {
            masterCheckbox.isSelected = true
        }

        masterCheckbox.addActionListener {
            val isSelected = masterCheckbox.isSelected
            for (i in 0 until methodList.itemsCount) {
                methodList.setItemSelected(methodList.getItemAt(i), isSelected)
            }
            methodList.repaint()
        }

        // --- 4. 将方法列表放入带内边距的滚动面板中 ---
        val methodScrollPane = JBScrollPane(methodList)
        methodScrollPane.border = JBUI.Borders.empty(5, 20, 10, 10)

        // --- 5. 组装最终的卡片 ---
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