package com.novelcharacter.app.ui.character

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 동적 필드 편집 폼 빌더 — [DynamicFieldRenderer](읽기 전용 표시)와 대칭을 이루는 편집용 공용 모듈.
 * CharacterEditFragment에 갇혀 있던 폼 구성/값 적재/수집/검증 로직을 컨테이너 주입형으로 추출하여
 * 캐릭터 편집 화면과 보충탭 인라인 편집 등 어떤 화면에서도 재사용할 수 있다.
 *
 * 호스트 계약:
 * - [fieldDefinitions]와 [currentUniverseId]를 설정한 뒤 [buildForm]을 호출한다.
 * - 사용자 입력 변경 시 [onFieldChanged]가 호출된다(더티 플래그 훅).
 * - 회전 보존은 [saveStateTo]/[restoreFieldValues], 영구 드래프트는 [widgetStateStrings]를 쓴다.
 */
class DynamicFieldFormBuilder(
    private val containerGetter: () -> LinearLayout,
    private val contextGetter: () -> Context?,
    private val scopeGetter: () -> CoroutineScope,
    private val isAlive: () -> Boolean,
    private val fragmentManagerGetter: () -> FragmentManager?,
    private val viewModel: CharacterViewModel,
    private val onFieldChanged: () -> Unit
) {

    var fieldDefinitions: List<FieldDefinition> = emptyList()
    var currentUniverseId: Long? = null
    private val fieldInputMap = mutableMapOf<Long, Any>() // fieldDefinitionId -> input widget

    private fun getString(resId: Int): String = contextGetter()?.getString(resId) ?: ""
    private fun getString(resId: Int, arg: Any): String = contextGetter()?.getString(resId, arg) ?: ""

    /** 입력 위젯의 현재 값 직렬화 — 회전 Bundle과 영구 드래프트가 같은 포맷을 공유한다 */
    private fun widgetStateString(widget: Any): String = when (widget) {
        is MaterialAutoCompleteTextView -> widget.text.toString()
        is TextInputEditText -> widget.text.toString()
        is Spinner -> widget.selectedItemPosition.toString()
        is LinearLayout -> {
            val parts = mutableListOf<String>()
            for (i in 0 until widget.childCount) {
                val partLayout = widget.getChildAt(i) as? TextInputLayout
                parts.add(partLayout?.editText?.text?.toString() ?: "")
            }
            parts.joinToString("\u001F")
        }
        else -> ""
    }

    /** 회전 보존: 동적 필드 입력값을 Bundle에 기록한다 (onSaveInstanceState용) */
    fun saveStateTo(outState: Bundle) {
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            outState.putString(field.id.toString(), widgetStateString(widget))
        }
    }

    /** 영구 드래프트용: 동적 필드 입력값을 맵으로 수집한다 (CharacterDraftPrefs.Draft.fieldValues 포맷) */
    fun widgetStateStrings(): Map<String, String> {
        val fieldValues = mutableMapOf<String, String>()
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            if (FieldType.fromName(field.type) == FieldType.CALCULATED) continue
            fieldValues[field.id.toString()] = widgetStateString(widget)
        }
        return fieldValues
    }

    // ===== 🎲 랜덤 값 생성 =====

    private fun createDiceButton(context: Context, density: Float, action: () -> Unit): com.google.android.material.button.MaterialButton {
        return com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "🎲"; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { action() }
        }
    }

    private fun showBirthdaySeasonDialog(field: com.novelcharacter.app.data.model.FieldDefinition) {
        val ctx = contextGetter() ?: return
        val seasons = com.novelcharacter.app.util.FieldRandomGenerator.Season.entries
        val labels = seasons.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.random_birthday_season)
            .setItems(labels) { _, which ->
                val date = com.novelcharacter.app.util.FieldRandomGenerator.generateBirthday(seasons[which])
                applyRandomValue(field, date)
            }
            .show()
    }

    private fun applyRandomForField(field: com.novelcharacter.app.data.model.FieldDefinition, type: FieldType) {
        val config = com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config)
        val value: String? = when (type) {
            FieldType.NUMBER -> com.novelcharacter.app.util.FieldRandomGenerator.generateNumber(
                config.min ?: 0.0, config.max ?: 100.0, config.decimalPlaces
            )
            FieldType.SELECT -> {
                val options = try {
                    val json = org.json.JSONObject(field.config)
                    val arr = json.optJSONArray("options")
                    if (arr != null) (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
                    else emptyList()
                } catch (_: Exception) { emptyList() }
                com.novelcharacter.app.util.FieldRandomGenerator.generateSelect(options)
            }
            FieldType.GRADE -> {
                // parseGradeOptions()를 사용하여 allowNegative 포함 전체 등급 목록 획득
                val grades = parseGradeOptions(field.config)
                com.novelcharacter.app.util.FieldRandomGenerator.generateGrade(grades)
            }
            else -> return
        }
        if (value == null) {
            val ctx = contextGetter() ?: return
            android.widget.Toast.makeText(ctx, R.string.random_no_options, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        applyRandomValue(field, value)
    }

    private fun applyRandomValue(field: com.novelcharacter.app.data.model.FieldDefinition, value: String) {
        val widget = fieldInputMap[field.id]
        when (widget) {
            is android.widget.EditText -> widget.setText(value)
            is android.widget.LinearLayout -> {
                // 구조화 입력: "MM-DD" 등을 parts로 분리
                val parts = value.split("-")
                for (i in 0 until minOf(widget.childCount, parts.size)) {
                    val child = widget.getChildAt(i)
                    if (child is com.google.android.material.textfield.TextInputLayout) {
                        child.editText?.setText(parts.getOrNull(i) ?: "")
                    }
                }
            }
            is android.widget.Spinner -> {
                val idx = (0 until widget.count).firstOrNull {
                    widget.getItemAtPosition(it).toString() == value
                } ?: return
                widget.setSelection(idx)
            }
        }
        onFieldChanged()
        val ctx = contextGetter() ?: return
        android.widget.Toast.makeText(ctx, getString(R.string.random_applied, value), android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showBodyGenerator(bodySizeField: com.novelcharacter.app.data.model.FieldDefinition) {
        val fm = fragmentManagerGetter() ?: return
        val sheet = BodyGeneratorBottomSheet()
        sheet.analysisConfig = com.novelcharacter.app.data.model.BodyAnalysisConfig.fromConfig(bodySizeField.config)
        // TODO: 같은 작품의 캐릭터 목록을 제공하여 상대 생성/분포 활성화
        sheet.onApply = { body ->
            // BWH 필드에 값 채우기
            val bwhView = fieldInputMap[bodySizeField.id]
            if (bwhView is android.widget.LinearLayout) {
                val parts = body.bwhString.split("-")
                for (i in 0 until minOf(bwhView.childCount, parts.size)) {
                    val child = bwhView.getChildAt(i)
                    if (child is com.google.android.material.textfield.TextInputLayout) {
                        child.editText?.setText(parts[i])
                    }
                }
            } else if (bwhView is android.widget.EditText) {
                bwhView.setText(body.bwhString)
            }
            // 키 필드 채우기
            for ((id, view) in fieldInputMap) {
                val fd = fieldDefinitions.find { it.id == id } ?: continue
                val role = com.novelcharacter.app.data.model.SemanticRole.fromConfig(fd.config)
                if (role == com.novelcharacter.app.data.model.SemanticRole.HEIGHT) {
                    val editText = when (view) {
                        is android.widget.EditText -> view
                        is com.google.android.material.textfield.TextInputLayout -> view.editText
                        else -> null
                    }
                    editText?.setText(body.height.toInt().toString())
                } else if (role == com.novelcharacter.app.data.model.SemanticRole.WEIGHT) {
                    val editText = when (view) {
                        is android.widget.EditText -> view
                        is com.google.android.material.textfield.TextInputLayout -> view.editText
                        else -> null
                    }
                    editText?.setText(body.weight.toInt().toString())
                }
            }
        }
        sheet.show(fm, "body_generator")
    }

    /** DB에 저장된 필드값을 폼 위젯에 채운다 */
    suspend fun loadFieldValues(characterId: Long) {
        val values = viewModel.getValuesByCharacterList(characterId)
        val valueMap = values.associateBy { it.fieldDefinitionId }

        for (field in fieldDefinitions) {
            val savedValue = valueMap[field.id]?.value ?: ""
            val widget = fieldInputMap[field.id] ?: continue

            when (widget) {
                is MaterialAutoCompleteTextView -> widget.setText(savedValue, false)
                is TextInputEditText -> widget.setText(savedValue)
                is LinearLayout -> {
                    // 구조화 입력: 저장된 값을 파트별로 분리하여 채움
                    val config = widget.tag as? StructuredInputConfig
                    if (config != null && savedValue.isNotEmpty()) {
                        val parts = config.splitValue(savedValue)
                        for (i in 0 until minOf(widget.childCount, parts.size)) {
                            val partLayout = widget.getChildAt(i) as? TextInputLayout
                            partLayout?.editText?.setText(parts[i].second)
                        }
                    }
                }
                is Spinner -> {
                    val fieldType = FieldType.fromName(field.type)
                    if (fieldType == FieldType.SELECT) {
                        val options = parseSelectOptions(field.config)
                        val optionWithBlank = mutableListOf(getString(R.string.no_selection))
                        optionWithBlank.addAll(options)
                        val idx = optionWithBlank.indexOf(savedValue)
                        if (idx >= 0) {
                            widget.setSelection(idx)
                        } else if (savedValue.isNotBlank()) {
                            // 고아 값: 현재 옵션에 없지만 저장된 값을 스피너에 추가하여 보존
                            optionWithBlank.add(savedValue)
                            val ctx = contextGetter() ?: continue
                            widget.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, optionWithBlank).also {
                                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            widget.setSelection(optionWithBlank.size - 1)
                        }
                    } else if (fieldType == FieldType.GRADE) {
                        val grades = parseGradeOptions(field.config)
                        val gradeWithBlank = mutableListOf(getString(R.string.no_grade_selected))
                        gradeWithBlank.addAll(grades)
                        val idx = gradeWithBlank.indexOf(savedValue)
                        if (idx >= 0) {
                            widget.setSelection(idx)
                        } else if (savedValue.isNotBlank()) {
                            gradeWithBlank.add(savedValue)
                            val ctx = contextGetter() ?: continue
                            widget.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, gradeWithBlank).also {
                                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                            widget.setSelection(gradeWithBlank.size - 1)
                        }
                    }
                }
            }
        }
    }

    /** 회전/드래프트로 보존된 입력값을 폼 위젯에 복원한다 */
    fun restoreFieldValues(saved: Bundle) {
        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            val value = saved.getString(field.id.toString()) ?: continue

            when (widget) {
                is MaterialAutoCompleteTextView -> widget.setText(value, false)
                is TextInputEditText -> widget.setText(value)
                is Spinner -> {
                    val pos = value.toIntOrNull() ?: 0
                    if (pos in 0 until widget.adapter.count) widget.setSelection(pos)
                }
                is LinearLayout -> {
                    val parts = value.split("\u001F")
                    for (i in 0 until minOf(widget.childCount, parts.size)) {
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.setText(parts[i])
                    }
                }
            }
        }
    }

    /** [fieldDefinitions] 기준으로 동적 입력 폼을 컨테이너에 구성한다 */
    fun buildForm() {
        if (!isAlive()) return
        val container = containerGetter()
        container.removeAllViews()
        fieldInputMap.clear()

        val context = contextGetter() ?: return
        val density = context.resources.displayMetrics.density

        // 자동완성 대상 수집 (필드마다 개별 쿼리 대신 루프 종료 후 1회 배치 조회)
        val autoCompleteTargets = mutableListOf<Triple<Long, DisplayFormat?, MaterialAutoCompleteTextView>>()

        for (field in fieldDefinitions.sortedBy { it.displayOrder }) {
            val fieldType = FieldType.fromName(field.type)

            when (fieldType) {
                FieldType.TEXT, FieldType.BODY_SIZE -> {
                    var structuredConfig = StructuredInputConfig.fromConfig(field.config)
                    // BODY_SIZE 타입인데 structuredInput 설정이 없으면 기본 B-W-H 구조화 입력 자동 적용
                    if (fieldType == FieldType.BODY_SIZE && !structuredConfig.enabled) {
                        structuredConfig = StructuredInputConfig(
                            enabled = true,
                            separator = "-",
                            parts = listOf(
                                StructuredInputConfig.Part("B", "cm", "number"),
                                StructuredInputConfig.Part("W", "cm", "number"),
                                StructuredInputConfig.Part("H", "cm", "number")
                            )
                        )
                    }
                    // BIRTH_DATE semanticRole인데 structuredInput이 없으면 월/일 구조화 입력 자동 적용
                    if (!structuredConfig.enabled && SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE) {
                        structuredConfig = StructuredInputConfig(
                            enabled = true,
                            separator = "-",
                            parts = listOf(
                                StructuredInputConfig.Part("월", "", "number"),
                                StructuredInputConfig.Part("일", "", "number")
                            )
                        )
                    }
                    if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                        // 구조화 입력: 파트별 개별 입력 필드
                        val labelRow = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { topMargin = (4 * density).toInt() }
                        }
                        val label = TextView(context).apply {
                            text = field.name
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        labelRow.addView(label)
                        // 🎲 생성 버튼
                        val diceAction: (() -> Unit)? = when {
                            fieldType == FieldType.BODY_SIZE -> {{ showBodyGenerator(field) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        if (diceAction != null) {
                            val genBtn = createDiceButton(context, density, diceAction)
                            labelRow.addView(genBtn)
                        }
                        container.addView(labelRow)

                        val partsContainer = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (8 * density).toInt()
                            }
                            tag = structuredConfig // 나중에 값 수집 시 사용
                        }
                        for (part in structuredConfig.parts) {
                            val partLayout = TextInputLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    0,
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    1f
                                ).apply {
                                    marginEnd = (4 * density).toInt()
                                }
                                hint = if (part.suffix.isNotEmpty()) "${part.label} (${part.suffix})" else part.label
                            }
                            val partEdit = TextInputEditText(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                if (part.inputType == "number") {
                                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                                }
                            }
                            partLayout.addView(partEdit)
                            partsContainer.addView(partLayout)
                        }
                        container.addView(partsContainer)
                        fieldInputMap[field.id] = partsContainer
                    } else {
                        // 일반 텍스트 입력
                        val format = DisplayFormat.fromConfig(field.config)
                        val inputLayout = TextInputLayout(context).apply {
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply {
                                bottomMargin = (8 * density).toInt()
                            }
                            hint = field.name
                            if (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST) {
                                helperText = getString(R.string.hint_comma_list_helper)
                                isHelperTextEnabled = true
                            } else if (format == DisplayFormat.MULTILINE) {
                                helperText = getString(R.string.hint_multiline_helper)
                                isHelperTextEnabled = true
                            }
                        }
                        val editText = MaterialAutoCompleteTextView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                            if (format == DisplayFormat.MULTILINE) {
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                minLines = 2
                            }
                            threshold = 1 // 1글자부터 자동완성 제안
                        }
                        inputLayout.addView(editText)
                        // 비구조화 필드에 🎲 버튼 추가 판정
                        val unstructuredDiceAction: (() -> Unit)? = when {
                            fieldType == FieldType.BODY_SIZE -> {{ showBodyGenerator(field) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        if (unstructuredDiceAction != null) {
                            val row = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutParams = ViewGroup.MarginLayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (8 * density).toInt() }
                            }
                            inputLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            row.addView(inputLayout)
                            val genBtn = createDiceButton(context, density, unstructuredDiceAction).apply {
                                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                                    topMargin = (8 * density).toInt()
                                }
                            }
                            row.addView(genBtn)
                            container.addView(row)
                        } else {
                            container.addView(inputLayout)
                        }
                        fieldInputMap[field.id] = editText

                        // 자동완성 데이터는 루프 종료 후 배치 조회로 채움
                        autoCompleteTargets.add(Triple(field.id, format, editText))
                    }
                }

                FieldType.NUMBER -> {
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = field.name
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    inputLayout.addView(editText)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        val row = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams = ViewGroup.MarginLayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { bottomMargin = (8 * density).toInt() }
                        }
                        inputLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        row.addView(inputLayout)
                        row.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                        container.addView(row)
                    } else {
                        container.addView(inputLayout)
                    }
                    fieldInputMap[field.id] = editText
                }

                FieldType.SELECT -> {
                    val labelRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * density).toInt() }
                    }
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    labelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        labelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    container.addView(labelRow)

                    val options = parseSelectOptions(field.config)
                    val optionsWithBlank = mutableListOf(getString(R.string.no_selection))
                    optionsWithBlank.addAll(options)

                    val spinner = Spinner(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_item,
                            optionsWithBlank
                        ).also {
                            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                    }
                    container.addView(spinner)
                    fieldInputMap[field.id] = spinner
                }

                FieldType.GRADE -> {
                    val gradeLabelRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * density).toInt() }
                    }
                    val label = TextView(context).apply {
                        text = field.name
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    gradeLabelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        gradeLabelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    container.addView(gradeLabelRow)

                    val grades = parseGradeOptions(field.config)
                    val gradesWithBlank = mutableListOf(getString(R.string.no_grade_selected))
                    gradesWithBlank.addAll(grades)

                    val spinner = Spinner(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        adapter = ArrayAdapter(
                            context,
                            android.R.layout.simple_spinner_item,
                            gradesWithBlank
                        ).also {
                            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        }
                    }
                    container.addView(spinner)
                    fieldInputMap[field.id] = spinner
                }

                FieldType.MULTI_TEXT -> {
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = getString(R.string.hint_multi_text_format, field.name)
                    }
                    val editText = MaterialAutoCompleteTextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        threshold = 1
                    }
                    inputLayout.addView(editText)
                    container.addView(inputLayout)
                    fieldInputMap[field.id] = editText

                    // 자동완성: 기존 값의 개별 항목을 제안
                    val uId = currentUniverseId
                    if (uId != null) {
                        scopeGetter().launch {
                            val existingValues = viewModel.getFieldValuesForUniverse(uId, field.id)
                            val suggestions = existingValues
                                .flatMap { it.split(",").map { v -> v.trim() } }
                                .filter { it.isNotBlank() }.distinct().sorted()
                            if (suggestions.isNotEmpty() && isAlive()) {
                                val ctx = contextGetter() ?: return@launch
                                editText.setAdapter(ArrayAdapter(
                                    ctx,
                                    android.R.layout.simple_dropdown_item_1line,
                                    suggestions
                                ))
                            }
                        }
                    }
                }

                FieldType.CALCULATED -> {
                    val textView = TextView(context).apply {
                        text = getString(R.string.auto_calculated, field.name)
                        textSize = 14f
                        isEnabled = false
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                            topMargin = (4 * density).toInt()
                        }
                    }
                    container.addView(textView)
                    fieldInputMap[field.id] = textView
                }

                null -> {
                    // 알 수 없는 필드 타입 - 기본 텍스트 입력
                    val inputLayout = TextInputLayout(context).apply {
                        layoutParams = ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = (8 * density).toInt()
                        }
                        hint = field.name
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    inputLayout.addView(editText)
                    container.addView(inputLayout)
                    fieldInputMap[field.id] = editText
                }
            }
        }

        // 자동완성 데이터 배치 로드: 필드마다 개별 쿼리(M회) 대신 세계관 전체 값을 1회 조회 후 필드별 분배
        val uId = currentUniverseId
        if (uId != null && autoCompleteTargets.isNotEmpty()) {
            scopeGetter().launch {
                val valuesByField = viewModel.getAllFieldValuesForUniverse(uId)
                    .groupBy { it.fieldDefinitionId }
                if (!isAlive()) return@launch
                val ctx = contextGetter() ?: return@launch
                for ((fieldId, format, editText) in autoCompleteTargets) {
                    val existingValues = valuesByField[fieldId].orEmpty().map { it.value }
                    val suggestions = if (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST) {
                        existingValues.flatMap { it.split(",").map { v -> v.trim() } }
                            .filter { it.isNotBlank() }.distinct().sorted()
                    } else {
                        existingValues.filter { it.isNotBlank() }.distinct().sorted()
                    }
                    if (suggestions.isNotEmpty()) {
                        editText.setAdapter(ArrayAdapter(
                            ctx,
                            android.R.layout.simple_dropdown_item_1line,
                            suggestions
                        ))
                    }
                }
            }
        }

        // 동적 필드에 변경 추적 리스너 추가
        attachDynamicFieldChangeTracking()
    }

    private fun attachDynamicFieldChangeTracking() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onFieldChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        for ((_, widget) in fieldInputMap) {
            when (widget) {
                is TextInputEditText -> widget.addTextChangedListener(watcher)
                is MaterialAutoCompleteTextView -> widget.addTextChangedListener(watcher)
                is Spinner -> widget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    private var initialized = false
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (initialized) { onFieldChanged() }
                        initialized = true
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                is LinearLayout -> {
                    for (i in 0 until widget.childCount) {
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.addTextChangedListener(watcher)
                    }
                }
            }
        }
    }

    private fun parseSelectOptions(configJson: String): List<String> =
        com.novelcharacter.app.util.FieldOptionParser.parseSelectOptions(configJson)

    private fun parseGradeOptions(configJson: String): List<String> =
        com.novelcharacter.app.util.FieldOptionParser.parseGradeOptions(configJson)

    /** 필수 필드 검증 — 비어 있는 필수 필드 이름을 반환(없으면 null) */
    fun validateRequiredFields(): String? {
        for (field in fieldDefinitions) {
            if (!field.isRequired) continue
            val fieldType = FieldType.fromName(field.type)
            if (fieldType == FieldType.CALCULATED) continue
            val widget = fieldInputMap[field.id] ?: continue
            val isEmpty = when (widget) {
                is MaterialAutoCompleteTextView -> widget.text.isNullOrBlank()
                is TextInputEditText -> widget.text.isNullOrBlank()
                is Spinner -> widget.selectedItemPosition <= 0
                is LinearLayout -> {
                    // 구조화 입력: 모든 파트가 비어있으면 isEmpty
                    (0 until widget.childCount).all { i ->
                        val partLayout = widget.getChildAt(i) as? TextInputLayout
                        partLayout?.editText?.text.isNullOrBlank()
                    }
                }
                else -> true
            }
            if (isEmpty) return field.name
        }
        return null
    }

    /** 폼 위젯에서 저장할 필드값 목록을 수집한다 (CALCULATED 제외, 빈 값 제거) */
    fun collectFieldValues(characterId: Long): List<CharacterFieldValue> {
        val values = mutableListOf<CharacterFieldValue>()

        for (field in fieldDefinitions) {
            val widget = fieldInputMap[field.id] ?: continue
            val fieldType = FieldType.fromName(field.type)

            // CALCULATED 필드는 저장하지 않음
            if (fieldType == FieldType.CALCULATED) continue

            val value: String = when (widget) {
                is MaterialAutoCompleteTextView -> widget.text.toString().trim()
                is TextInputEditText -> widget.text.toString().trim()
                is Spinner -> {
                    if (widget.selectedItemPosition > 0) {
                        widget.selectedItem.toString()
                    } else {
                        ""
                    }
                }
                is LinearLayout -> {
                    // 구조화 입력: 파트별 값을 separator로 합침
                    val config = widget.tag as? StructuredInputConfig
                    if (config != null) {
                        val partValues = mutableListOf<String>()
                        for (i in 0 until widget.childCount) {
                            val partLayout = widget.getChildAt(i) as? TextInputLayout
                            val partEdit = partLayout?.editText
                            partValues.add(partEdit?.text?.toString()?.trim() ?: "")
                        }
                        if (partValues.any { it.isNotEmpty() }) {
                            config.joinValues(partValues)
                        } else ""
                    } else ""
                }
                else -> ""
            }

            if (value.isNotEmpty()) {
                values.add(
                    CharacterFieldValue(
                        characterId = characterId,
                        fieldDefinitionId = field.id,
                        value = value
                    )
                )
            }
        }
        return values
    }
}
