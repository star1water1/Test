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
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.databinding.DialogFieldEditBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 체형 분석 컵 매핑 관리
    private data class CupMappingRow(
        val container: View,
        val editMaxDiff: EditText,
        val editLabel: EditText
    )
    private val cupMappingRows = mutableListOf<CupMappingRow>()
    private val insightToggleSwitches = mutableMapOf<String, androidx.appcompat.widget.SwitchCompat>()
    private var currentBodyTypeRules: List<BodyAnalysisConfig.BodyTypeRule> = BodyAnalysisConfig.DEFAULT_BODY_TYPE_RULES

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
        setupBodyAnalysisSection(binding)
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

        // Percentile toggle
        binding.switchPercentileEnabled.setOnCheckedChangeListener { _, isChecked ->
            binding.percentileScopeLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

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
                // 체형 분석 설정: BODY_SIZE 전용
                binding.bodyAnalysisSettingsLayout.visibility =
                    if (selectedType == FieldType.BODY_SIZE) View.VISIBLE else View.GONE
                // 상위 % 표기: NUMBER, CALCULATED, BODY_SIZE, GRADE
                val isNumericType = selectedType == FieldType.NUMBER || selectedType == FieldType.CALCULATED || selectedType == FieldType.BODY_SIZE || selectedType == FieldType.GRADE
                binding.percentileLayout.visibility = if (isNumericType) View.VISIBLE else View.GONE
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

        // 연동 규칙 스피너 설정
        setupLinkageRuleSpinner(binding)

        binding.spinnerSemanticRole.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    binding.textSemanticRoleDesc.visibility = View.GONE
                    linkageRuleContainer?.visibility = View.GONE
                } else {
                    val role = SemanticRole.entries[position - 1]
                    binding.textSemanticRoleDesc.text = role.description
                    binding.textSemanticRoleDesc.visibility = View.VISIBLE
                    // AGE 선택 시 연동 규칙 표시
                    linkageRuleContainer?.visibility = if (role == SemanticRole.AGE) View.VISIBLE else View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var linkageRuleContainer: View? = null
    private var linkageRuleSpinner: android.widget.Spinner? = null
    private var existingAliveValue: String? = null
    private var existingDeadValue: String? = null

    private fun setupLinkageRuleSpinner(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density
        val ctx = requireContext()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }

        val label = android.widget.TextView(ctx).apply {
            text = getString(R.string.linkage_rule_label)
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_secondary))
        }
        container.addView(label)

        val spinner = android.widget.Spinner(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
        }
        val ruleLabels = listOf(
            getString(R.string.linkage_rule_age_anchor),
            getString(R.string.linkage_rule_birth_anchor)
        )
        val ruleAdapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, ruleLabels)
        ruleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = ruleAdapter
        container.addView(spinner)

        // semanticRoleDesc 뒤에 삽입
        val parent = binding.textSemanticRoleDesc.parent as? android.view.ViewGroup
        if (parent != null) {
            val descIndex = parent.indexOfChild(binding.textSemanticRoleDesc)
            parent.addView(container, descIndex + 1)
        }

        linkageRuleContainer = container
        linkageRuleSpinner = spinner
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

    private fun setupBodyAnalysisSection(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density
        val ctx = requireContext()

        // 컵 매핑 추가 버튼
        binding.btnAddCupMapping.setOnClickListener {
            addCupMappingRow(binding.cupMappingContainer, density)
        }

        // 기본값 복원 버튼
        binding.btnRestoreCupDefaults.setOnClickListener {
            binding.cupMappingContainer.removeAllViews()
            cupMappingRows.clear()
            for (entry in BodyAnalysisConfig.DEFAULT_CUP_MAPPING) {
                addCupMappingRow(binding.cupMappingContainer, density, entry.maxDiff, entry.label)
            }
            // 체형 분류도 기본값 복원
            binding.spinnerBodyTypePreset.setSelection(0)
            currentBodyTypeRules = BodyAnalysisConfig.DEFAULT_BODY_TYPE_RULES
            // 인사이트 토글도 기본값 복원
            for ((key, switch) in insightToggleSwitches) {
                switch.isChecked = BodyAnalysisConfig.DEFAULT_ENABLED_INSIGHTS[key] ?: true
            }
        }

        // 체형 분류 프리셋 스피너
        val presetLabels = listOf(
            getString(R.string.body_preset_subculture),
            getString(R.string.body_preset_detailed),
            getString(R.string.body_preset_custom)
        )
        binding.spinnerBodyTypePreset.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, presetLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerBodyTypePreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> {
                        currentBodyTypeRules = BodyAnalysisConfig.DEFAULT_BODY_TYPE_RULES
                        binding.bodyTypeCustomJsonLayout.visibility = View.GONE
                    }
                    1 -> {
                        // 상세 분류: 더 세분화된 카테고리
                        currentBodyTypeRules = listOf(
                            BodyAnalysisConfig.BodyTypeRule("글래머", mapOf(
                                "bustWaistDiff" to BodyAnalysisConfig.RangeCondition(min = 20.0),
                                "whr" to BodyAnalysisConfig.RangeCondition(max = 0.70),
                                "bust" to BodyAnalysisConfig.RangeCondition(min = 90.0)
                            ), priority = 1),
                            BodyAnalysisConfig.BodyTypeRule("세미글래머", mapOf(
                                "bustWaistDiff" to BodyAnalysisConfig.RangeCondition(min = 15.0, max = 20.0),
                                "whr" to BodyAnalysisConfig.RangeCondition(max = 0.75),
                                "bust" to BodyAnalysisConfig.RangeCondition(min = 85.0)
                            ), priority = 2),
                            BodyAnalysisConfig.BodyTypeRule("풍만형", mapOf(
                                "bust" to BodyAnalysisConfig.RangeCondition(min = 95.0),
                                "hip" to BodyAnalysisConfig.RangeCondition(min = 98.0)
                            ), priority = 3),
                            BodyAnalysisConfig.BodyTypeRule("날씬형", mapOf(
                                "bustWaistDiff" to BodyAnalysisConfig.RangeCondition(max = 10.0),
                                "bust" to BodyAnalysisConfig.RangeCondition(max = 80.0)
                            ), priority = 4),
                            BodyAnalysisConfig.BodyTypeRule("소녀체형", mapOf(
                                "height" to BodyAnalysisConfig.RangeCondition(max = 158.0),
                                "bust" to BodyAnalysisConfig.RangeCondition(max = 78.0),
                                "hip" to BodyAnalysisConfig.RangeCondition(max = 83.0)
                            ), priority = 5),
                            BodyAnalysisConfig.BodyTypeRule("볼륨형", mapOf(
                                "bustWaistDiff" to BodyAnalysisConfig.RangeCondition(min = 12.0, max = 18.0),
                                "hip" to BodyAnalysisConfig.RangeCondition(min = 90.0)
                            ), priority = 6),
                            BodyAnalysisConfig.BodyTypeRule("탄탄형", mapOf(
                                "whr" to BodyAnalysisConfig.RangeCondition(min = 0.70, max = 0.80),
                                "bustHipRatio" to BodyAnalysisConfig.RangeCondition(min = 0.93, max = 1.07)
                            ), priority = 7),
                            BodyAnalysisConfig.BodyTypeRule("마른형", mapOf(
                                "bustWaistDiff" to BodyAnalysisConfig.RangeCondition(max = 8.0),
                                "waistHipDiff" to BodyAnalysisConfig.RangeCondition(max = 6.0)
                            ), priority = 8)
                        )
                        binding.bodyTypeCustomJsonLayout.visibility = View.GONE
                    }
                    2 -> {
                        // 사용자 정의: JSON 편집기 표시
                        binding.bodyTypeCustomJsonLayout.visibility = View.VISIBLE
                        // 현재 규칙을 JSON으로 표시
                        val json = bodyTypeRulesToJson(currentBodyTypeRules)
                        binding.editBodyTypeCustomJson.setText(json)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 인사이트 토글 생성
        // ── 흉곽 보정값 (V2) ──
        val ribOffsetEdit = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "흉곽 보정값 (0=기존, 권장 6.0)"
            setText("0.0")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }
        val ribOffsetLabel = TextView(ctx).apply {
            text = "흉곽 보정 (cm) — 높을수록 컵 사이즈↓"
            textSize = 12f
            alpha = 0.7f
        }
        binding.insightTogglesContainer.addView(ribOffsetLabel, 0)
        binding.insightTogglesContainer.addView(ribOffsetEdit, 1)
        // Store reference for later use
        ribOffsetEdit.tag = "ribOffsetEdit"

        // ── 골든비율 이상값 (V2) ──
        val idealEntries = listOf(
            "whr" to "WHR 이상값 (기본 0.70)",
            "bustHipRatio" to "B/H 이상값 (기본 1.00)",
            "waistHeight" to "W/키 이상값 (기본 0.40)",
            "bustHeight" to "B/키 이상값 (기본 0.52)"
        )
        val goldenIdealEdits = mutableMapOf<String, EditText>()
        val idealLabel = TextView(ctx).apply {
            text = "골든비율 이상값"
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        binding.insightTogglesContainer.addView(idealLabel, 2)
        var insertIdx = 3
        for ((key, hint) in idealEntries) {
            val edit = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                this.hint = hint
                tag = "goldenIdeal_$key"
                setText(BodyAnalysisConfig.DEFAULT_GOLDEN_RATIO_IDEALS[key]?.toString() ?: "")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            }
            binding.insightTogglesContainer.addView(edit, insertIdx++)
            goldenIdealEdits[key] = edit
        }

        // 인사이트 토글 생성
        val insightKeys = listOf(
            BodyAnalysisConfig.INSIGHT_BODY_TAGS to "다층 태그",
            BodyAnalysisConfig.INSIGHT_BODY_TYPE to "체형 분류",
            BodyAnalysisConfig.INSIGHT_SILHOUETTE to "실루엣 설명",
            BodyAnalysisConfig.INSIGHT_CUP_SIZE to "컵 사이즈",
            BodyAnalysisConfig.INSIGHT_FRAME_SIZE to "프레임 사이즈",
            BodyAnalysisConfig.INSIGHT_PROPORTION to "볼륨/곡선 지수",
            BodyAnalysisConfig.INSIGHT_BWH_DIFF to "B/W/H 차이",
            BodyAnalysisConfig.INSIGHT_NORMALIZED_RATIO to "정규화 비율",
            BodyAnalysisConfig.INSIGHT_BMI to "BMI",
            BodyAnalysisConfig.INSIGHT_WHR to "WHR",
            BodyAnalysisConfig.INSIGHT_HEIGHT_RELATIVE to "키 대비 비율",
            BodyAnalysisConfig.INSIGHT_GOLDEN_RATIO to "골든 비율",
            BodyAnalysisConfig.INSIGHT_RANKING to "작품 내 순위"
        )

        for ((key, label) in insightKeys) {
            val switch = androidx.appcompat.widget.SwitchCompat(ctx).apply {
                text = label
                isChecked = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            }
            binding.insightTogglesContainer.addView(switch)
            insightToggleSwitches[key] = switch
        }

        // 기본 컵 매핑 행 추가
        for (entry in BodyAnalysisConfig.DEFAULT_CUP_MAPPING) {
            addCupMappingRow(binding.cupMappingContainer, density, entry.maxDiff, entry.label)
        }
    }

    private fun addCupMappingRow(container: LinearLayout, density: Float,
                                  maxDiff: Double = 0.0, label: String = "") {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }

        val editMaxDiff = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "차이(cm)"
            textSize = 13f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (maxDiff > 0) setText(if (maxDiff >= 999) "" else maxDiff.toString())
        }

        val arrow = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            text = " → "
            textSize = 14f
        }

        val editLabel = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "라벨"
            textSize = 13f
            if (label.isNotEmpty()) setText(label)
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(android.R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                cupMappingRows.removeAll { it.container == row }
            }
        }

        row.addView(editMaxDiff)
        row.addView(arrow)
        row.addView(editLabel)
        row.addView(btnRemove)
        container.addView(row)
        cupMappingRows.add(CupMappingRow(row, editMaxDiff, editLabel))
    }

    private fun bodyTypeRulesToJson(rules: List<BodyAnalysisConfig.BodyTypeRule>): String {
        val arr = org.json.JSONArray()
        for (rule in rules) {
            val ruleObj = org.json.JSONObject().apply {
                put("label", rule.label)
                put("priority", rule.priority)
                val condObj = org.json.JSONObject()
                for ((k, range) in rule.conditions) {
                    condObj.put(k, org.json.JSONObject().apply {
                        range.min?.let { put("min", it) }
                        range.max?.let { put("max", it) }
                    })
                }
                put("conditions", condObj)
            }
            arr.put(ruleObj)
        }
        return arr.toString(2)
    }

    private fun jsonToBodyTypeRules(json: String): List<BodyAnalysisConfig.BodyTypeRule>? {
        return try {
            val arr = org.json.JSONArray(json)
            val rules = mutableListOf<BodyAnalysisConfig.BodyTypeRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val conditions = mutableMapOf<String, BodyAnalysisConfig.RangeCondition>()
                val condObj = obj.optJSONObject("conditions")
                if (condObj != null) {
                    val keys = condObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val rangeObj = condObj.getJSONObject(k)
                        conditions[k] = BodyAnalysisConfig.RangeCondition(
                            min = if (rangeObj.has("min")) rangeObj.getDouble("min") else null,
                            max = if (rangeObj.has("max")) rangeObj.getDouble("max") else null
                        )
                    }
                }
                rules.add(BodyAnalysisConfig.BodyTypeRule(
                    label = obj.optString("label", ""),
                    conditions = conditions,
                    priority = obj.optInt("priority", i)
                ))
            }
            rules
        } catch (_: Exception) { null }
    }

    private fun collectBodyAnalysisConfig(binding: DialogFieldEditBinding): BodyAnalysisConfig {
        // 컵 매핑
        val cupMapping = cupMappingRows.mapNotNull { row ->
            val maxDiff = row.editMaxDiff.text.toString().toDoubleOrNull()
            val label = row.editLabel.text.toString().trim()
            if (label.isNotEmpty()) {
                BodyAnalysisConfig.CupMappingEntry(maxDiff ?: 999.0, label)
            } else null
        }.sortedBy { it.maxDiff }.ifEmpty { BodyAnalysisConfig.DEFAULT_CUP_MAPPING }

        // 체형 분류 규칙
        val bodyTypeRules = if (binding.spinnerBodyTypePreset.selectedItemPosition == 2) {
            // 사용자 정의: JSON 파싱
            val jsonText = binding.editBodyTypeCustomJson.text.toString().trim()
            jsonToBodyTypeRules(jsonText) ?: currentBodyTypeRules
        } else {
            currentBodyTypeRules
        }

        // 인사이트 토글
        val enabledInsights = insightToggleSwitches.mapValues { it.value.isChecked }

        // ribOffset (V2)
        val ribOffsetView = binding.insightTogglesContainer.findViewWithTag<EditText>("ribOffsetEdit")
        val ribOffset = ribOffsetView?.text?.toString()?.toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 0.0

        // 골든비율 이상값 (V2)
        val goldenIdeals = mutableMapOf<String, Double>()
        for (key in listOf("whr", "bustHipRatio", "waistHeight", "bustHeight")) {
            val edit = binding.insightTogglesContainer.findViewWithTag<EditText>("goldenIdeal_$key")
            edit?.text?.toString()?.toDoubleOrNull()?.let { goldenIdeals[key] = it }
        }

        return BodyAnalysisConfig(
            cupMapping = cupMapping,
            bodyTypeRules = bodyTypeRules,
            enabledInsights = enabledInsights.ifEmpty { BodyAnalysisConfig.DEFAULT_ENABLED_INSIGHTS },
            ribOffset = ribOffset,
            goldenRatioIdeals = goldenIdeals.ifEmpty { BodyAnalysisConfig.DEFAULT_GOLDEN_RATIO_IDEALS }
        )
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
            // AGE면 연동 규칙 복원
            if (semanticRole == SemanticRole.AGE) {
                linkageRuleContainer?.visibility = View.VISIBLE
                try {
                    val linkageRule = org.json.JSONObject(field.config).optString("linkageRule", "age_anchor")
                    linkageRuleSpinner?.setSelection(if (linkageRule == "birth_anchor") 1 else 0)
                } catch (_: Exception) {}
            }
            // ALIVE면 기존 aliveValue/deadValue 보존 (편집 시 config에 반영)
            if (semanticRole == SemanticRole.ALIVE) {
                try {
                    val configJson = org.json.JSONObject(field.config)
                    val existingAlive = configJson.optString("aliveValue", "")
                    val existingDead = configJson.optString("deadValue", "")
                    if (existingAlive.isNotEmpty()) existingAliveValue = existingAlive
                    if (existingDead.isNotEmpty()) existingDeadValue = existingDead
                } catch (_: Exception) {}
            }
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

        // 상위 % 표기 복원
        val percentileObj = try {
            org.json.JSONObject(field.config).optJSONObject("percentile")
        } catch (_: Exception) { null }
        if (percentileObj != null && percentileObj.optBoolean("enabled", false)) {
            binding.switchPercentileEnabled.isChecked = true
            val scopes = try {
                val arr = percentileObj.optJSONArray("scopes")
                if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
            } catch (_: Exception) { emptyList() }
            binding.checkPercentileNovel.isChecked = "novel" in scopes
            binding.checkPercentileUniverse.isChecked = "universe" in scopes
        }

        // 체형 분석 설정 복원
        if (field.type == "BODY_SIZE") {
            val bodyConfig = BodyAnalysisConfig.fromConfig(field.config)
            // 컵 매핑 복원
            val density2 = resources.displayMetrics.density
            binding.cupMappingContainer.removeAllViews()
            cupMappingRows.clear()
            for (entry in bodyConfig.cupMapping) {
                addCupMappingRow(binding.cupMappingContainer, density2, entry.maxDiff, entry.label)
            }
            // 체형 분류 프리셋 판별
            if (bodyConfig.bodyTypeRules == BodyAnalysisConfig.DEFAULT_BODY_TYPE_RULES) {
                binding.spinnerBodyTypePreset.setSelection(0)
            } else {
                binding.spinnerBodyTypePreset.setSelection(2) // 사용자 정의
                currentBodyTypeRules = bodyConfig.bodyTypeRules
            }
            // 인사이트 토글 복원
            for ((key, switch) in insightToggleSwitches) {
                switch.isChecked = bodyConfig.isInsightEnabled(key)
            }
            // ribOffset 복원 (V2)
            val ribOffsetView = binding.insightTogglesContainer.findViewWithTag<EditText>("ribOffsetEdit")
            ribOffsetView?.setText(bodyConfig.ribOffset.toString())
            // 골든비율 이상값 복원 (V2)
            for ((key, value) in bodyConfig.goldenRatioIdeals) {
                val edit = binding.insightTogglesContainer.findViewWithTag<EditText>("goldenIdeal_$key")
                edit?.setText(value.toString())
            }
        }

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

        // 타입 변경 시 기존 값 호환성 영향 분석
        val oldType = existingField?.type
        if (existingField != null && oldType != null && oldType != selectedType.name) {
            checkTypeChangeImpact(field, oldType, selectedType.name)
        } else {
            deliverResult(field)
        }
    }

    private fun checkTypeChangeImpact(field: FieldDefinition, oldType: String, newType: String) {
        val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
        val fieldValueDao = app.database.characterFieldValueDao()

        lifecycleScope.launch {
            val values = withContext(Dispatchers.IO) {
                fieldValueDao.getValuesByFieldDef(field.id)
            }

            val nonEmptyValues = values.filter { it.value.isNotBlank() }
            if (nonEmptyValues.isEmpty()) {
                // 기존 값이 없으면 바로 저장
                deliverResult(field)
                return@launch
            }

            val compatible = nonEmptyValues.count { isValueCompatible(it.value, newType) }
            val incompatible = nonEmptyValues.size - compatible

            if (incompatible == 0) {
                deliverResult(field)
                return@launch
            }

            val ctx = context ?: return@launch
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.field_type_change_title))
                .setMessage(getString(R.string.field_type_change_message,
                    oldType, newType, nonEmptyValues.size, compatible, incompatible))
                .setPositiveButton(getString(R.string.field_type_change_proceed)) { _, _ ->
                    // 호환 불가 값을 빈 문자열로 초기화
                    CoroutineScope(Dispatchers.IO).launch {
                        val toReset = nonEmptyValues.filter { !isValueCompatible(it.value, newType) }
                        toReset.forEach { fv ->
                            fieldValueDao.update(fv.copy(value = ""))
                        }
                        withContext(Dispatchers.Main) {
                            deliverResult(field)
                        }
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun isValueCompatible(value: String, newType: String): Boolean {
        return when (newType) {
            FieldType.NUMBER.name -> value.toDoubleOrNull() != null
            FieldType.GRADE.name -> value.toIntOrNull() != null
            FieldType.SELECT.name, FieldType.TEXT.name, FieldType.MULTI_TEXT.name -> true
            FieldType.BODY_SIZE.name -> false // 구조화 입력은 기존 텍스트와 호환 불가
            FieldType.CALCULATED.name -> false // 수식 필드는 기존 값과 무관
            else -> true
        }
    }

    private fun deliverResult(field: FieldDefinition) {
        // Support both callback (for non-rotation case) and FragmentResult (survives rotation)
        if (onSave != null) {
            onSave?.invoke(field)
        } else if (isAdded) {
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
                // 체형 분석 설정은 아래에서 applyToConfig로 추가
            }
            else -> {}
        }

        // Semantic role (CALCULATED 제외)
        if (type != FieldType.CALCULATED) {
            val rolePos = binding.spinnerSemanticRole.selectedItemPosition
            val role = if (rolePos > 0) SemanticRole.entries[rolePos - 1] else null
            if (role != null) {
                config["semanticRole"] = role.key
                // AGE 역할이면 연동 규칙 저장
                if (role == SemanticRole.AGE) {
                    val rulePos = linkageRuleSpinner?.selectedItemPosition ?: 0
                    config["linkageRule"] = if (rulePos == 1) "birth_anchor" else "age_anchor"
                }
                // ALIVE 역할이면 aliveValue/deadValue 자동 매핑
                if (role == SemanticRole.ALIVE) {
                    val options = config["options"] as? List<*>
                    if (options != null && options.size >= 2) {
                        config["aliveValue"] = existingAliveValue ?: options[0].toString()
                        config["deadValue"] = existingDeadValue ?: options[1].toString()
                    } else {
                        // 옵션이 부족하더라도 기존 값이 있으면 보존
                        existingAliveValue?.let { config["aliveValue"] = it }
                        existingDeadValue?.let { config["deadValue"] = it }
                    }
                }
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
                // 상위 % 설정 (구조화 입력 경로에서도)
                if (type == FieldType.BODY_SIZE && binding.switchPercentileEnabled.isChecked) {
                    val scopes = mutableListOf<String>()
                    if (binding.checkPercentileNovel.isChecked) scopes.add("novel")
                    if (binding.checkPercentileUniverse.isChecked) scopes.add("universe")
                    config["percentile"] = mapOf("enabled" to true, "scopes" to scopes)
                }
                val configJson = Gson().toJson(config)
                val withStructured = StructuredInputConfig.applyToConfig(configJson, structuredConfig)
                val statsConfig = collectStatsConfig(binding, type)
                val withStats = FieldStatsConfig.applyToConfig(withStructured, statsConfig)
                return if (type == FieldType.BODY_SIZE) {
                    BodyAnalysisConfig.applyToConfig(withStats, collectBodyAnalysisConfig(binding))
                } else withStats
            }
        }

        // 상위 % 설정 (NUMBER, CALCULATED, BODY_SIZE, GRADE)
        if (type == FieldType.NUMBER || type == FieldType.CALCULATED || type == FieldType.BODY_SIZE || type == FieldType.GRADE) {
            if (binding.switchPercentileEnabled.isChecked) {
                val scopes = mutableListOf<String>()
                if (binding.checkPercentileNovel.isChecked) scopes.add("novel")
                if (binding.checkPercentileUniverse.isChecked) scopes.add("universe")
                config["percentile"] = mapOf("enabled" to true, "scopes" to scopes)
            }
        }

        // Stats config (모든 타입 지원)
        val statsConfig = collectStatsConfig(binding, type)
        val configJson = Gson().toJson(config)
        val withStats = FieldStatsConfig.applyToConfig(configJson, statsConfig)
        return if (type == FieldType.BODY_SIZE) {
            BodyAnalysisConfig.applyToConfig(withStats, collectBodyAnalysisConfig(binding))
        } else withStats
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
