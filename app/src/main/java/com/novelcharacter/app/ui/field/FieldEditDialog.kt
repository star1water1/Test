package com.novelcharacter.app.ui.field

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.google.android.material.tabs.TabLayout
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.databinding.DialogFieldEditBinding

class FieldEditDialog : DialogFragment() {

    private var onSave: ((FieldDefinition) -> Unit)? = null
    private var universeId: Long = 0
    private var existingField: FieldDefinition? = null

    // 동적 분석 항목 관리
    private data class AnalysisRow(
        val container: View,
        val spinnerType: Spinner,
        val spinnerChart: Spinner,
        val editLimit: EditText
    )
    private val analysisRows = mutableListOf<AnalysisRow>()

    // 동적 값 라벨 관리
    private data class ValueLabelRow(
        val container: View,
        val editKey: EditText,
        val editValue: EditText
    )
    private val valueLabelRows = mutableListOf<ValueLabelRow>()

    // 동적 구간 관리
    private data class BinRangeRow(
        val container: View,
        val editRange: EditText
    )
    private val binRangeRows = mutableListOf<BinRangeRow>()

    // 동적 카테고리 매핑 관리
    private data class ValueCategoryRow(
        val container: View,
        val editKey: EditText,
        val editCategory: EditText
    )
    private val valueCategoryRows = mutableListOf<ValueCategoryRow>()

    // 구조화 입력 파트 관리
    private data class StructuredPartRow(
        val container: View,
        val editLabel: EditText,
        val editSuffix: EditText,
        val spinnerInputType: Spinner
    )
    private val structuredPartRows = mutableListOf<StructuredPartRow>()

    private var fieldTypeSpinner: Spinner? = null

    private fun currentFieldType(): String {
        val pos = fieldTypeSpinner?.selectedItemPosition ?: 0
        val types = FieldType.entries.toTypedArray()
        return if (pos in types.indices) types[pos].name else "TEXT"
    }

    fun setOnSaveListener(listener: (FieldDefinition) -> Unit) {
        onSave = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogFieldEditBinding.inflate(layoutInflater)

        universeId = arguments?.getLong(ARG_UNIVERSE_ID) ?: 0
        val fieldJson = arguments?.getString(ARG_FIELD_JSON)
        existingField = if (fieldJson != null) Gson().fromJson(fieldJson, FieldDefinition::class.java) else null

        setupTabSwitching(binding)
        setupTypeSpinner(binding)
        setupSemanticRoleSpinner(binding)
        setupStatsSection(binding)
        setupStructuredInputSection(binding)
        populateFields(binding)

        return AlertDialog.Builder(requireContext())
            .setTitle(if (existingField == null) R.string.add_field else R.string.edit_field)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                saveField(binding)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
    }

    private fun setupTypeSpinner(binding: DialogFieldEditBinding) {
        val types = FieldType.entries.toTypedArray()
        val labels = types.map { it.label }
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerFieldType.adapter = spinnerAdapter
        fieldTypeSpinner = binding.spinnerFieldType

        // Display format spinner
        val formatLabels = DisplayFormat.labels()
        val formatAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, formatLabels)
        formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDisplayFormat.adapter = formatAdapter

        binding.spinnerFieldType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedType = types[position]
                binding.selectOptionsLayout.visibility =
                    if (selectedType == FieldType.SELECT) View.VISIBLE else View.GONE
                binding.gradeLayout.visibility =
                    if (selectedType == FieldType.GRADE) View.VISIBLE else View.GONE
                binding.calculatedLayout.visibility =
                    if (selectedType == FieldType.CALCULATED) View.VISIBLE else View.GONE
                binding.displayFormatLayout.visibility =
                    if (selectedType == FieldType.TEXT || selectedType == FieldType.MULTI_TEXT) View.VISIBLE else View.GONE
                // 시스템 연동: CALCULATED 제외
                binding.semanticRoleLayout.visibility =
                    if (selectedType != FieldType.CALCULATED) View.VISIBLE else View.GONE
                // 통계 설정: 모든 타입 지원 (CALCULATED 포함 — 수식 결과를 통계 분석 가능)
                binding.statsSettingsLayout.visibility = View.VISIBLE
                // NUMBER 전용 구간 설정
                binding.binningLayout.visibility =
                    if (selectedType == FieldType.NUMBER) View.VISIBLE else View.GONE
                // 구조화 입력: TEXT, BODY_SIZE
                binding.structuredInputLayout.visibility =
                    if (selectedType == FieldType.TEXT || selectedType == FieldType.BODY_SIZE) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupTabSwitching(binding: DialogFieldEditBinding) {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.basicTabContent.visibility = View.VISIBLE
                        binding.advancedTabContent.visibility = View.GONE
                    }
                    1 -> {
                        binding.basicTabContent.visibility = View.GONE
                        binding.advancedTabContent.visibility = View.VISIBLE
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSemanticRoleSpinner(binding: DialogFieldEditBinding) {
        val roleLabels = listOf(getString(R.string.label_semantic_role_none)) + SemanticRole.labels()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, roleLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSemanticRole.adapter = adapter

        binding.spinnerSemanticRole.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    binding.textSemanticRoleDesc.visibility = View.GONE
                } else {
                    val role = SemanticRole.entries[position - 1]
                    binding.textSemanticRoleDesc.text = role.description
                    binding.textSemanticRoleDesc.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupStatsSection(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density

        // 통계 토글
        binding.switchStatsEnabled.setOnCheckedChangeListener { _, isChecked ->
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.analysisListContainer.visibility = visibility
            binding.btnAddAnalysis.visibility = visibility
            binding.valueLabelContainer.visibility = visibility
            binding.btnAddValueLabel.visibility = visibility
            // valueLabelContainer 위의 라벨 TextView도 토글
        }

        // 분석 추가 버튼
        binding.btnAddAnalysis.setOnClickListener {
            addAnalysisRow(binding.analysisListContainer, density)
        }

        // 값 라벨 추가 버튼
        binding.btnAddValueLabel.setOnClickListener {
            addValueLabelRow(binding.valueLabelContainer, density)
        }

        // 구간 모드 스피너
        val binModes = listOf(getString(R.string.label_binning_auto), getString(R.string.label_binning_custom))
        binding.spinnerBinningMode.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, binModes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerBinningMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = position == 1
                binding.customBinContainer.visibility = if (isCustom) View.VISIBLE else View.GONE
                binding.btnAddBinRange.visibility = if (isCustom) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 구간 추가 버튼
        binding.btnAddBinRange.setOnClickListener {
            addBinRangeRow(binding.customBinContainer, density)
        }

        // 기본 분석 1개 추가
        addAnalysisRow(binding.analysisListContainer, density)

        // 카테고리 매핑 추가 버튼
        binding.btnAddValueCategory.setOnClickListener {
            addValueCategoryRow(binding.valueCategoryContainer, density)
        }

        // statsGroupBy 스피너
        val groupByLabels = listOf(
            getString(R.string.label_group_by_value),
            getString(R.string.label_group_by_category),
            getString(R.string.label_group_by_both)
        )
        binding.spinnerStatsGroupBy.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, groupByLabels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupStructuredInputSection(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density

        binding.switchStructuredInput.setOnCheckedChangeListener { _, isChecked ->
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.structuredSeparatorLayout.visibility = visibility
            binding.structuredPartsContainer.visibility = visibility
            binding.btnAddStructuredPart.visibility = visibility
        }

        binding.btnAddStructuredPart.setOnClickListener {
            addStructuredPartRow(binding.structuredPartsContainer, density)
        }
    }

    private fun addAnalysisRow(container: LinearLayout, density: Float, fieldType: String = currentFieldType()) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }

        val allowedTypes = FieldStatsConfig.StatsType.forFieldType(fieldType)
        val spinnerType = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, allowedTypes.map { it.label }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val spinnerChart = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, (40 * density).toInt(), 1f)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, FieldStatsConfig.ChartType.labels()).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        }

        val editLimit = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((48 * density).toInt(), (40 * density).toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("10")
            textSize = 12f
            hint = "N"
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = ctx.getString(R.string.delete)
            setOnClickListener {
                container.removeView(row)
                analysisRows.removeAll { it.container == row }
            }
        }

        row.addView(spinnerType)
        row.addView(spinnerChart)
        row.addView(editLimit)
        row.addView(btnRemove)
        container.addView(row)
        analysisRows.add(AnalysisRow(row, spinnerType, spinnerChart, editLimit))
    }

    private fun addValueLabelRow(container: LinearLayout, density: Float) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        val editKey = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_value_label_key)
            textSize = 13f
        }

        val arrow = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = " → "
            textSize = 14f
        }

        val editValue = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_value_label_value)
            textSize = 13f
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                valueLabelRows.removeAll { it.container == row }
            }
        }

        row.addView(editKey)
        row.addView(arrow)
        row.addView(editValue)
        row.addView(btnRemove)
        container.addView(row)
        valueLabelRows.add(ValueLabelRow(row, editKey, editValue))
    }

    private fun addBinRangeRow(container: LinearLayout, density: Float) {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        val editRange = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_bin_range)
            textSize = 13f
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                binRangeRows.removeAll { it.container == row }
            }
        }

        row.addView(editRange)
        row.addView(btnRemove)
        container.addView(row)
        binRangeRows.add(BinRangeRow(row, editRange))
    }

    private fun addValueCategoryRow(container: LinearLayout, density: Float, key: String = "", category: String = "") {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        val editKey = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_category_value)
            textSize = 13f
            if (key.isNotEmpty()) setText(key)
        }

        val arrow = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = " → "
            textSize = 14f
        }

        val editCategory = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_category_name)
            textSize = 13f
            if (category.isNotEmpty()) setText(category)
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                valueCategoryRows.removeAll { it.container == row }
            }
        }

        row.addView(editKey)
        row.addView(arrow)
        row.addView(editCategory)
        row.addView(btnRemove)
        container.addView(row)
        valueCategoryRows.add(ValueCategoryRow(row, editKey, editCategory))
    }

    private fun addStructuredPartRow(container: LinearLayout, density: Float,
                                      label: String = "", suffix: String = "", inputType: String = "text") {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        val editLabel = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
            hint = getString(R.string.hint_part_label)
            textSize = 13f
            if (label.isNotEmpty()) setText(label)
        }

        val editSuffix = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_part_suffix)
            textSize = 13f
            if (suffix.isNotEmpty()) setText(suffix)
        }

        val inputTypes = listOf(getString(R.string.label_input_text), getString(R.string.label_input_number))
        val spinnerInputType = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, (36 * density).toInt(), 1f)
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, inputTypes).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            if (inputType == "number") setSelection(1)
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                structuredPartRows.removeAll { it.container == row }
            }
        }

        row.addView(editLabel)
        row.addView(editSuffix)
        row.addView(spinnerInputType)
        row.addView(btnRemove)
        container.addView(row)
        structuredPartRows.add(StructuredPartRow(row, editLabel, editSuffix, spinnerInputType))
    }

    private fun populateFields(binding: DialogFieldEditBinding) {
        val field = existingField ?: return

        binding.editFieldName.setText(field.name)
        binding.editFieldKey.setText(field.key)
        binding.editGroupName.setText(field.groupName)
        binding.switchRequired.isChecked = field.isRequired

        // Set type spinner
        val types = FieldType.entries.toTypedArray()
        val typeIndex = types.indexOfFirst { it.name == field.type }
        if (typeIndex >= 0) binding.spinnerFieldType.setSelection(typeIndex)

        // Parse config
        val config = try {
            Gson().fromJson<Map<String, Any>>(field.config, Map::class.java) ?: emptyMap()
        } catch (e: Exception) { emptyMap<String, Any>() }

        // SELECT options
        val options = (config["options"] as? List<*>)?.joinToString(",")
        if (options != null) binding.editSelectOptions.setText(options)

        // GRADE mappings
        val grades = config["grades"] as? Map<*, *>
        if (grades != null) {
            binding.editGradeC.setText((grades["C"] as? Number)?.toString() ?: "0.5")
            binding.editGradeB.setText((grades["B"] as? Number)?.toString() ?: "1")
            binding.editGradeA.setText((grades["A"] as? Number)?.toString() ?: "2")
            binding.editGradeS.setText((grades["S"] as? Number)?.toString() ?: "3")
        }
        val allowNeg = config["allowNegative"] as? Boolean ?: false
        binding.switchAllowNegative.isChecked = allowNeg

        // CALCULATED formula
        val formula = config["formula"] as? String
        if (formula != null) binding.editFormula.setText(formula)

        // Display format
        val displayFormat = DisplayFormat.fromConfig(field.config)
        val formatIndex = DisplayFormat.entries.indexOf(displayFormat)
        if (formatIndex >= 0) binding.spinnerDisplayFormat.setSelection(formatIndex)

        // Semantic role
        val semanticRole = SemanticRole.fromConfig(field.config)
        if (semanticRole != null) {
            val roleIdx = SemanticRole.entries.indexOf(semanticRole) + 1 // +1 for "없음"
            binding.spinnerSemanticRole.setSelection(roleIdx)
        }

        // Stats config
        val statsConfig = FieldStatsConfig.fromConfig(field.config)
        binding.switchStatsEnabled.isChecked = statsConfig.enabled

        // 기존 분석 행 제거 후 복원
        val density = resources.displayMetrics.density
        binding.analysisListContainer.removeAllViews()
        analysisRows.clear()
        val allowedTypes = FieldStatsConfig.StatsType.forFieldType(field.type)
        for (entry in statsConfig.analyses) {
            addAnalysisRow(binding.analysisListContainer, density, field.type)
            val row = analysisRows.last()
            val typeIdx = allowedTypes.indexOf(entry.type)
            if (typeIdx >= 0) row.spinnerType.setSelection(typeIdx)
            val chartIdx = FieldStatsConfig.ChartType.entries.indexOf(entry.chart)
            if (chartIdx >= 0) row.spinnerChart.setSelection(chartIdx)
            row.editLimit.setText(entry.limit.toString())
        }

        // 값 라벨 복원
        for ((key, value) in statsConfig.valueLabels) {
            addValueLabelRow(binding.valueLabelContainer, density)
            val row = valueLabelRows.last()
            row.editKey.setText(key)
            row.editValue.setText(value)
        }

        // 구간 설정 복원
        val binning = statsConfig.binning
        if (binning != null) {
            if (binning.mode == "custom") {
                binding.spinnerBinningMode.setSelection(1)
                for (range in binning.ranges) {
                    addBinRangeRow(binding.customBinContainer, density)
                    binRangeRows.last().editRange.setText(range)
                }
            }
        }

        // 카테고리 매핑 복원
        for ((k, v) in statsConfig.valueCategories) {
            addValueCategoryRow(binding.valueCategoryContainer, density, k, v)
        }

        // statsGroupBy 복원
        val groupByIdx = when (statsConfig.statsGroupBy) {
            "category" -> 1
            "both" -> 2
            else -> 0  // "value"
        }
        binding.spinnerStatsGroupBy.setSelection(groupByIdx)

        // 구조화 입력 복원
        val structuredConfig = StructuredInputConfig.fromConfig(field.config)
        if (structuredConfig.enabled) {
            binding.switchStructuredInput.isChecked = true
            binding.editStructuredSeparator.setText(structuredConfig.separator)
            for (part in structuredConfig.parts) {
                addStructuredPartRow(binding.structuredPartsContainer, density,
                    part.label, part.suffix, part.inputType)
            }
        }
    }

    private fun saveField(binding: DialogFieldEditBinding) {
        val name = binding.editFieldName.text.toString().trim()
        val key = binding.editFieldKey.text.toString().trim()
        val groupName = binding.editGroupName.text.toString().trim().ifEmpty { getString(R.string.default_group_name) }
        val isRequired = binding.switchRequired.isChecked

        if (name.isEmpty() || key.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.field_name_key_required), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val types = FieldType.entries.toTypedArray()
        val selectedType = types[binding.spinnerFieldType.selectedItemPosition]

        val config = buildConfig(binding, selectedType)

        val field = if (existingField != null) {
            existingField!!.copy(
                name = name,
                key = key,
                type = selectedType.name,
                config = config,
                groupName = groupName,
                isRequired = isRequired
            )
        } else {
            FieldDefinition(
                universeId = universeId,
                name = name,
                key = key,
                type = selectedType.name,
                config = config,
                groupName = groupName,
                isRequired = isRequired
            )
        }

        // Support both callback (for non-rotation case) and FragmentResult (survives rotation)
        if (onSave != null) {
            onSave?.invoke(field)
        } else {
            setFragmentResult(RESULT_KEY, bundleOf(RESULT_FIELD_JSON to Gson().toJson(field)))
        }
    }

    private fun buildConfig(binding: DialogFieldEditBinding, type: FieldType): String {
        val config = mutableMapOf<String, Any>()

        // Display format (TEXT, MULTI_TEXT only)
        if (type == FieldType.TEXT || type == FieldType.MULTI_TEXT) {
            val formats = DisplayFormat.entries
            val selectedFormat = formats[binding.spinnerDisplayFormat.selectedItemPosition]
            if (selectedFormat != DisplayFormat.PLAIN) {
                config["displayFormat"] = selectedFormat.key
            }
        }

        when (type) {
            FieldType.SELECT -> {
                val optionsText = binding.editSelectOptions.text.toString().trim()
                if (optionsText.isNotEmpty()) {
                    config["options"] = optionsText.split(",").map { it.trim() }
                }
            }
            FieldType.GRADE -> {
                val grades = mapOf(
                    "C" to (binding.editGradeC.text.toString().toDoubleOrNull() ?: 0.5),
                    "B" to (binding.editGradeB.text.toString().toDoubleOrNull() ?: 1.0),
                    "A" to (binding.editGradeA.text.toString().toDoubleOrNull() ?: 2.0),
                    "S" to (binding.editGradeS.text.toString().toDoubleOrNull() ?: 3.0)
                )
                config["grades"] = grades
                config["allowNegative"] = binding.switchAllowNegative.isChecked
            }
            FieldType.CALCULATED -> {
                val formula = binding.editFormula.text.toString().trim()
                if (formula.isNotEmpty()) {
                    config["formula"] = formula
                }
            }
            FieldType.BODY_SIZE -> {
                // separator는 구조화 입력 설정에서 사용자가 지정 (기본값 "-")
                if (!binding.switchStructuredInput.isChecked) {
                    config["separator"] = binding.editStructuredSeparator.text.toString().ifEmpty { "-" }
                }
            }
            else -> {}
        }

        // Semantic role (CALCULATED 제외)
        if (type != FieldType.CALCULATED) {
            val rolePos = binding.spinnerSemanticRole.selectedItemPosition
            val role = if (rolePos > 0) SemanticRole.entries[rolePos - 1] else null
            if (role != null) {
                config["semanticRole"] = role.key
            }
        }

        // 구조화 입력 (TEXT/BODY_SIZE)
        if (type == FieldType.TEXT || type == FieldType.BODY_SIZE) {
            val structuredEnabled = binding.switchStructuredInput.isChecked
            if (structuredEnabled && structuredPartRows.isNotEmpty()) {
                val parts = structuredPartRows.map { row ->
                    val inputType = if (row.spinnerInputType.selectedItemPosition == 1) "number" else "text"
                    StructuredInputConfig.Part(
                        label = row.editLabel.text.toString().trim(),
                        suffix = row.editSuffix.text.toString().trim(),
                        inputType = inputType
                    )
                }.filter { it.label.isNotEmpty() }
                val structuredConfig = StructuredInputConfig(
                    enabled = true,
                    separator = binding.editStructuredSeparator.text.toString().ifEmpty { "-" },
                    parts = parts
                )
                val configJson = Gson().toJson(config)
                val withStructured = StructuredInputConfig.applyToConfig(configJson, structuredConfig)
                val statsConfig = collectStatsConfig(binding, type)
                return FieldStatsConfig.applyToConfig(withStructured, statsConfig)
            }
        }

        // Stats config (모든 타입 지원)
        val statsConfig = collectStatsConfig(binding, type)
        val configJson = Gson().toJson(config)
        return FieldStatsConfig.applyToConfig(configJson, statsConfig)
    }

    private fun collectStatsConfig(binding: DialogFieldEditBinding, type: FieldType): FieldStatsConfig {
        val enabled = binding.switchStatsEnabled.isChecked

        val allowedTypes = FieldStatsConfig.StatsType.forFieldType(type.name)
        val analyses = analysisRows.map { row ->
            val chartTypes = FieldStatsConfig.ChartType.entries
            val typePos = row.spinnerType.selectedItemPosition.coerceIn(0, allowedTypes.size - 1)
            FieldStatsConfig.AnalysisEntry(
                type = allowedTypes[typePos],
                chart = chartTypes[row.spinnerChart.selectedItemPosition],
                limit = row.editLimit.text.toString().toIntOrNull() ?: 10
            )
        }.ifEmpty { listOf(FieldStatsConfig.AnalysisEntry()) }

        val valueLabels = mutableMapOf<String, String>()
        for (row in valueLabelRows) {
            val k = row.editKey.text.toString().trim()
            val v = row.editValue.text.toString().trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                valueLabels[k] = v
            }
        }

        val binning = if (type == FieldType.NUMBER) {
            val mode = if (binding.spinnerBinningMode.selectedItemPosition == 1) "custom" else "auto"
            val ranges = if (mode == "custom") {
                binRangeRows.mapNotNull { row ->
                    val text = row.editRange.text.toString().trim()
                    text.ifEmpty { null }
                }
            } else emptyList()
            if (mode == "custom" && ranges.isNotEmpty()) {
                FieldStatsConfig.BinningConfig(mode, ranges)
            } else null
        } else null

        val valueCategories = mutableMapOf<String, String>()
        for (row in valueCategoryRows) {
            val k = row.editKey.text.toString().trim()
            val v = row.editCategory.text.toString().trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                valueCategories[k] = v
            }
        }

        val statsGroupBy = when (binding.spinnerStatsGroupBy.selectedItemPosition) {
            1 -> "category"
            2 -> "both"
            else -> "value"
        }

        return FieldStatsConfig(enabled, analyses, binning, valueLabels, valueCategories, statsGroupBy)
    }

    companion object {
        const val RESULT_KEY = "field_edit_result"
        const val RESULT_FIELD_JSON = "field_json"
        private const val ARG_UNIVERSE_ID = "universeId"
        private const val ARG_FIELD_JSON = "fieldJson"

        fun newInstance(universeId: Long, field: FieldDefinition?): FieldEditDialog {
            return FieldEditDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_UNIVERSE_ID, universeId)
                    if (field != null) {
                        putString(ARG_FIELD_JSON, Gson().toJson(field))
                    }
                }
            }
        }
    }
}
