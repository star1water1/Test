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
import com.novelcharacter.app.data.model.RequiredEnforcement
import com.novelcharacter.app.data.model.RequiredFieldMark
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.util.RequiredFieldGaps
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

    /**
     * 지금 고른 작품 — 실루엣 편집기의 상대 생성·분포·작품 평균이 이 축으로 모인다.
     * 설정되지 않으면 그 셋이 재료 없이 조용히 빠진다(호스트가 작품을 모르는 화면).
     */
    var currentNovelId: Long? = null
    /**
     * 필드별 ✨ AI 추천 진입 훅 — 호스트가 설정하면 편집 가능 필드(CALCULATED·미지 타입 제외)에
     * 🎲과 같은 문법의 ✨ 버튼이 붙는다. null이면 폼은 기존과 동일하게 렌더된다.
     */
    var aiSuggestHandler: ((FieldDefinition) -> Unit)? = null
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

    // ===== 🎲 랜덤 값 생성 / ✨ AI 추천 — 인라인 액션 버튼 =====

    private fun createInlineActionButton(
        context: Context,
        density: Float,
        label: String,
        action: () -> Unit
    ): com.google.android.material.button.MaterialButton {
        return com.google.android.material.button.MaterialButton(
            context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = label; textSize = 14f; minWidth = 0; minimumWidth = 0
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { action() }
        }
    }

    private fun createDiceButton(context: Context, density: Float, action: () -> Unit) =
        createInlineActionButton(context, density, "🎲", action)

    /**
     * 실루엣 편집기 진입 — 🎲·✨과 같은 문법의 인라인 버튼이다(설계 5-4-4).
     * 🎲도 같은 시트를 열지만 이쪽은 그림부터, 저쪽은 생성 패널부터 시작한다.
     */
    private fun createSilhouetteButton(context: Context, density: Float, action: () -> Unit) =
        createInlineActionButton(context, density, "🧍", action).apply {
            contentDescription = getString(R.string.silhouette_editor_open)
        }

    /** 필드가 ✨ AI 추천 대상이면 버튼 생성 — CALCULATED·미지 타입·AI 추천 꺼짐(A-1)은 제외 */
    private fun createAiButtonOrNull(
        context: Context,
        density: Float,
        field: FieldDefinition
    ): com.google.android.material.button.MaterialButton? {
        val handler = aiSuggestHandler ?: return null
        val type = FieldType.fromName(field.type) ?: return null
        if (type == FieldType.CALCULATED) return null
        // 토글 OFF의 의미는 "이 필드는 AI가 건드리지 않는다" — 버튼만 남기면 토글이 일괄
        // 필터로 격하된다. 꺼짐 사유는 필드 관리 목록(스위치 조감)과 일괄 고지에서 보인다.
        // '개별만'(B-80)은 여기서 살아 있어야 하는 상태다 — 일괄에서만 빠진다.
        if (!com.novelcharacter.app.data.model.FieldAiPolicy.isInlineSparkleEnabled(field.config)) return null
        return createInlineActionButton(context, density, "✨") { handler(field) }
    }

    /**
     * 필드 설명(A-2)이 있으면 ⓘ 버튼 생성 — 🎲·✨과 같은 문법. 설명이 없는 필드에는
     * 아이콘을 만들지 않는다(눌러도 빈 다이얼로그가 뜨는 버튼은 구색이다).
     */
    private fun createInfoButtonOrNull(
        context: Context,
        density: Float,
        field: FieldDefinition
    ): com.google.android.material.button.MaterialButton? {
        if (com.novelcharacter.app.data.model.FieldDescription.fromConfig(field.config).isBlank()) return null
        return createInlineActionButton(context, density, "ⓘ") {
            com.novelcharacter.app.ui.common.HelpDialog.showFieldNote(context, field)
        }
    }

    /** 입력 레이아웃 + 인라인 버튼들을 한 행으로 배치. 버튼이 없으면 입력만 추가 (기존 렌더 유지) */
    private fun addInputRow(
        container: LinearLayout,
        context: Context,
        density: Float,
        inputLayout: TextInputLayout,
        buttons: List<com.google.android.material.button.MaterialButton>
    ) {
        if (buttons.isEmpty()) {
            container.addView(inputLayout)
            return
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }
        inputLayout.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(inputLayout)
        for (button in buttons) {
            button.layoutParams = (button.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = (8 * density).toInt()
            }
            row.addView(button)
        }
        container.addView(row)
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

    /**
     * 🎲 랜덤·✨ AI 추천 공용 값 주입 — 폼 위젯에만 기입하고 더티 플래그를 세운다 (DB 미접근).
     * 영속화는 사용자가 저장을 눌러야 저장 체인(생일 __birth 동기화 포함)을 타고 이뤄진다.
     */
    fun applyRandomValue(
        field: com.novelcharacter.app.data.model.FieldDefinition,
        value: String,
        showToast: Boolean = true
    ) {
        val widget = fieldInputMap[field.id]
        when (widget) {
            is android.widget.EditText -> widget.setText(value)
            is android.widget.LinearLayout -> {
                // 구조화 입력: 필드 설정의 구분자 기준으로 파트 분리 (설정이 없으면 기존 "-" 분리).
                // 생일 역할 필드만 예외 — 🎲(generateBirthday)·✨(normalizeBirthDate)가 넘기는 값은
                // 항상 "MM-DD" 정규형이라, 커스텀 구분자 설정과 무관하게 "-"로 분리해야
                // 월 칸에 "03-15"가 통째로 들어가는 오염이 없다.
                val config = widget.tag as? StructuredInputConfig
                val isBirth = SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE
                val parts = when {
                    isBirth -> value.split("-")
                    config != null -> config.splitValue(value).map { it.second }
                    else -> value.split("-")
                }
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
        if (!showToast) return
        val ctx = contextGetter() ?: return
        android.widget.Toast.makeText(ctx, getString(R.string.random_applied, value), android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 값이 비어 있는 편집 가능 필드 id 집합 — ✨ 전체 추천의 '빈 필드만' 대상 산출용 */
    fun emptyEditableFieldIds(): Set<Long> {
        val result = HashSet<Long>()
        for (field in fieldDefinitions) {
            val fieldType = FieldType.fromName(field.type) ?: continue
            if (fieldType == FieldType.CALCULATED) continue
            val widget = fieldInputMap[field.id] ?: continue
            val isEmpty = when (widget) {
                is MaterialAutoCompleteTextView -> widget.text.isNullOrBlank()
                is TextInputEditText -> widget.text.isNullOrBlank()
                is Spinner -> widget.selectedItemPosition <= 0
                is LinearLayout -> (0 until widget.childCount).all { i ->
                    (widget.getChildAt(i) as? TextInputLayout)?.editText?.text.isNullOrBlank()
                }
                else -> false
            }
            if (isEmpty) result.add(field.id)
        }
        return result
    }

    /**
     * 실루엣 편집기를 연다 (설계 5-2 — 🎲 생성기는 이 시트 안으로 들어왔다).
     *
     * 폼의 🎲 버튼도 같은 시트를 열되 [openGenerator]로 패널을 펼친 상태로 시작한다 —
     * 기존 사용자의 동선을 끊지 않기 위해서다.
     */
    private fun showSilhouetteEditor(
        bodySizeField: FieldDefinition,
        openGenerator: Boolean
    ) {
        val fm = fragmentManagerGetter() ?: return
        val config = com.novelcharacter.app.data.model.BodyAnalysisConfig.fromConfig(bodySizeField.config)
        val structured = StructuredInputConfig.fromConfig(bodySizeField.config)
        val widget = fieldInputMap[bodySizeField.id]

        val partValues = readBodyParts(widget)
        val partLabels = if (structured.enabled) structured.parts.map { it.label } else emptyList()
        val roleField = { role: SemanticRole ->
            fieldDefinitions.firstOrNull { SemanticRole.fromConfig(it.config) == role }
        }
        val heightField = roleField(SemanticRole.HEIGHT)
        val weightField = roleField(SemanticRole.WEIGHT)
        val measurements = com.novelcharacter.app.util.BodyMeasurements.resolveParts(
            labels = partLabels,
            texts = partValues,
            config = config,
            heightText = heightField?.let { readSingleValue(fieldInputMap[it.id]) },
            weightText = weightField?.let { readSingleValue(fieldInputMap[it.id]) }
        )
        val explicitSlots = measurements.mode ==
            com.novelcharacter.app.util.BodyMeasurements.MappingMode.EXPLICIT
        val slots = com.novelcharacter.app.util.BodyEditorModel.writableSlots(
            measurements.partSlots, maxOf(partValues.size, structured.parts.size),
            explicit = explicitSlots
        )

        val sheet = BodySilhouetteEditorSheet()
        sheet.analysisConfig = config
        sheet.initial = measurements
        sheet.writableSlots = slots
        sheet.partValues = partValues
        sheet.hasHeightField = heightField != null && fieldInputMap[heightField.id] != null
        sheet.hasWeightField = weightField != null && fieldInputMap[weightField.id] != null
        // 위치 폴백 고지는 **실제로 폴백이 걸렸을 때만** 단다 — 사용자가 설정에서 전부
        // '사용 안 함'으로 정한 경우에도 켜지면, 앞 세 칸을 가슴·허리·엉덩이로 본다고
        // 거짓을 말하게 된다(그 경우 되쓸 자리는 하나도 없다).
        sheet.positionalFallback = !explicitSlots &&
            measurements.partSlots.none { it != com.novelcharacter.app.data.model.BodySlot.NONE }
        sheet.noWritableSlot = slots.none { it != com.novelcharacter.app.data.model.BodySlot.NONE }
        sheet.openWithGenerator = openGenerator
        sheet.onApply = { parts, heightCm, weightKg ->
            writeBodyParts(widget, parts, structured)
            heightCm?.let { h -> heightField?.let { setSingleValue(fieldInputMap[it.id], formatMeasure(h)) } }
            weightKg?.let { w -> weightField?.let { setSingleValue(fieldInputMap[it.id], formatMeasure(w)) } }
            onFieldChanged()
            contextGetter()?.let {
                android.widget.Toast.makeText(it, R.string.silhouette_editor_applied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        sheet.show(fm, BodySilhouetteEditorSheet.TAG)
        loadBodyPeers(bodySizeField, config, sheet)
    }

    /**
     * 같은 작품 캐릭터의 수치를 붙인다 — 상대 생성·분포·작품 평균 오버레이의 재료다.
     * 시트를 띄운 뒤 비동기로 채운다(조회 때문에 여는 것이 늦어지지 않게).
     */
    private fun loadBodyPeers(
        bodySizeField: FieldDefinition,
        config: com.novelcharacter.app.data.model.BodyAnalysisConfig,
        sheet: BodySilhouetteEditorSheet
    ) {
        val novelId = currentNovelId ?: return
        scopeGetter().launch {
            val characters = viewModel.getCharactersByNovelList(novelId)
            if (characters.isEmpty()) return@launch
            val heightField = fieldDefinitions.firstOrNull { SemanticRole.fromConfig(it.config) == SemanticRole.HEIGHT }
            val weightField = fieldDefinitions.firstOrNull { SemanticRole.fromConfig(it.config) == SemanticRole.WEIGHT }
            val peers = mutableMapOf<Long, com.novelcharacter.app.util.BodyMeasurements>()
            for (character in characters) {
                val values = viewModel.getValuesByCharacterList(character.id).associateBy { it.fieldDefinitionId }
                val raw = values[bodySizeField.id]?.value ?: continue
                peers[character.id] = com.novelcharacter.app.util.BodyMeasurements.resolve(
                    field = bodySizeField,
                    rawValue = raw,
                    heightText = heightField?.let { values[it.id]?.value },
                    weightText = weightField?.let { values[it.id]?.value },
                    config = config
                )
            }
            if (!isAlive() || peers.isEmpty()) return@launch
            sheet.setPeers(characters.filter { peers.containsKey(it.id) }, peers)
        }
    }

    /** BODY_SIZE 위젯의 파트 원문. 구조화 입력이 아니면 구분자로 쪼갠 결과다. */
    private fun readBodyParts(widget: Any?): List<String> = when (widget) {
        is LinearLayout -> (0 until widget.childCount).map { i ->
            (widget.getChildAt(i) as? TextInputLayout)?.editText?.text?.toString()?.trim() ?: ""
        }
        else -> com.novelcharacter.app.util.BodyMeasurements.splitPlain(readSingleValue(widget) ?: "")
    }

    private fun writeBodyParts(widget: Any?, parts: List<String>, structured: StructuredInputConfig) {
        when (widget) {
            is LinearLayout -> for (i in 0 until minOf(widget.childCount, parts.size)) {
                (widget.getChildAt(i) as? TextInputLayout)?.editText?.setText(parts[i])
            }
            else -> setSingleValue(
                widget,
                if (structured.enabled) structured.joinValues(parts) else parts.joinToString("-")
            )
        }
    }

    private fun readSingleValue(widget: Any?): String? = when (widget) {
        is MaterialAutoCompleteTextView -> widget.text?.toString()
        is TextInputEditText -> widget.text?.toString()
        is android.widget.EditText -> widget.text?.toString()
        is TextInputLayout -> widget.editText?.text?.toString()
        is Spinner -> if (widget.selectedItemPosition > 0) widget.selectedItem?.toString() else null
        else -> null
    }

    private fun setSingleValue(widget: Any?, value: String) {
        when (widget) {
            is MaterialAutoCompleteTextView -> widget.setText(value, false)
            is android.widget.EditText -> widget.setText(value)
            is TextInputLayout -> widget.editText?.setText(value)
        }
    }

    /** 키·체중은 소수 한 자리까지만 적는다 — 정수는 정수로(종전 🎲가 하던 것과 같은 모양). */
    private fun formatMeasure(value: Double): String =
        com.novelcharacter.app.util.BodyEditorModel.formatValue(value)

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
                            text = RequiredFieldMark.label(field)
                            textSize = 14f
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        labelRow.addView(label)
                        // 실루엣 편집기 진입 (설계 5-4-4 — BODY_SIZE 라벨 행 우측)
                        if (fieldType == FieldType.BODY_SIZE) {
                            labelRow.addView(createSilhouetteButton(context, density) {
                                showSilhouetteEditor(field, openGenerator = false)
                            })
                        }
                        // 🎲 생성 버튼
                        val diceAction: (() -> Unit)? = when {
                            fieldType == FieldType.BODY_SIZE -> {{ showSilhouetteEditor(field, openGenerator = true) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        if (diceAction != null) {
                            val genBtn = createDiceButton(context, density, diceAction)
                            labelRow.addView(genBtn)
                        }
                        createAiButtonOrNull(context, density, field)?.let { labelRow.addView(it) }
                        createInfoButtonOrNull(context, density, field)?.let { labelRow.addView(it) }
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
                            hint = RequiredFieldMark.label(field)
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
                            fieldType == FieldType.BODY_SIZE -> {{ showSilhouetteEditor(field, openGenerator = true) }}
                            SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE -> {{ showBirthdaySeasonDialog(field) }}
                            com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled -> {{ applyRandomForField(field, fieldType) }}
                            else -> null
                        }
                        val textButtons = buildList {
                            if (fieldType == FieldType.BODY_SIZE) {
                                add(createSilhouetteButton(context, density) {
                                    showSilhouetteEditor(field, openGenerator = false)
                                })
                            }
                            if (unstructuredDiceAction != null) add(createDiceButton(context, density, unstructuredDiceAction))
                            createAiButtonOrNull(context, density, field)?.let { add(it) }
                            createInfoButtonOrNull(context, density, field)?.let { add(it) }
                        }
                        addInputRow(container, context, density, inputLayout, textButtons)
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
                        hint = RequiredFieldMark.label(field)
                    }
                    val editText = TextInputEditText(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    inputLayout.addView(editText)
                    val numberButtons = buildList {
                        if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                            add(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                        }
                        createAiButtonOrNull(context, density, field)?.let { add(it) }
                        createInfoButtonOrNull(context, density, field)?.let { add(it) }
                    }
                    addInputRow(container, context, density, inputLayout, numberButtons)
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
                        text = RequiredFieldMark.label(field)
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    labelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        labelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    createAiButtonOrNull(context, density, field)?.let { labelRow.addView(it) }
                    createInfoButtonOrNull(context, density, field)?.let { labelRow.addView(it) }
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
                        text = RequiredFieldMark.label(field)
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    gradeLabelRow.addView(label)
                    if (com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config).enabled) {
                        gradeLabelRow.addView(createDiceButton(context, density) { applyRandomForField(field, fieldType) })
                    }
                    createAiButtonOrNull(context, density, field)?.let { gradeLabelRow.addView(it) }
                    createInfoButtonOrNull(context, density, field)?.let { gradeLabelRow.addView(it) }
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
                    addInputRow(container, context, density, inputLayout,
                        listOfNotNull(
                            createAiButtonOrNull(context, density, field),
                            createInfoButtonOrNull(context, density, field)
                        ))
                    fieldInputMap[field.id] = editText
                    // 자동완성은 폼 구성 후 배치 로드에서 일괄 장착 (라이브러리 제안 + 폴백)
                    autoCompleteTargets.add(Triple(field.id, DisplayFormat.fromConfig(field.config), editText))
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
                        hint = RequiredFieldMark.label(field)
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

        // 자동완성 데이터 배치 로드: 라이브러리 제안(정규값·별칭 매칭, usageCount 순) 우선.
        // 엔트리가 없는 필드(시드 전·신규)는 기존 distinct 경로로 폴백해 제안이 끊기지 않는다.
        val uId = currentUniverseId
        if (uId != null && autoCompleteTargets.isNotEmpty()) {
            val defsById = fieldDefinitions.associateBy { it.id }
            scopeGetter().launch {
                val libraryByField = viewModel.getLibrarySuggestionsForUniverse(uId)
                val valuesByField = viewModel.getAllFieldValuesForUniverse(uId)
                    .groupBy { it.fieldDefinitionId }
                if (!isAlive()) return@launch
                val ctx = contextGetter() ?: return@launch
                for ((fieldId, format, editText) in autoCompleteTargets) {
                    // 자유 입력 모드: 제안 끔 (필드별 자율성)
                    val libConfig = defsById[fieldId]?.let {
                        com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(it.config)
                    }
                    if (libConfig?.isSuggestEnabled == false) continue

                    val entries = libraryByField[fieldId].orEmpty()
                    if (entries.isNotEmpty()) {
                        editText.setAdapter(
                            com.novelcharacter.app.ui.fieldlibrary.LibrarySuggestionAdapter(ctx, entries)
                        )
                        continue
                    }
                    val existingValues = valuesByField[fieldId].orEmpty().map { it.value }
                    val suggestions = if (format == DisplayFormat.COMMA_LIST || format == DisplayFormat.BULLET_LIST ||
                        defsById[fieldId]?.type == FieldType.MULTI_TEXT.name
                    ) {
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

    /**
     * 비어 있는 필수 필드 전부 — 강도는 여기서 보지 않는다 (B-90).
     *
     * **어느 필드를 세는가는 [RequiredFieldGaps.countsAsSlot]과 같은 규칙이다**(B-99 —
     * 어시스턴트 카드가 같은 것을 센다). 여기서만 규칙을 바꾸면 *카드가 말한 칸 수와
     * 열어서 보는 칸 수가 갈린다* — 그때는 카드가 없느니만 못하다. 둘을 함께 고칠 것.
     *
     * 판정 자체는 이 함수가 **위젯**을 보고(폼은 아직 저장되지 않은 입력까지 안다),
     * 카드 쪽은 **저장된 값**을 본다. 규칙은 같고 보는 대상이 다르다.
     */
    private fun emptyRequiredFields(): List<FieldDefinition> {
        val empty = mutableListOf<FieldDefinition>()
        for (field in fieldDefinitions) {
            if (!RequiredFieldGaps.countsAsSlot(field)) continue
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
            if (isEmpty) empty.add(field)
        }
        return empty
    }

    private fun enforcementOf(field: FieldDefinition): RequiredEnforcement =
        RequiredEnforcement.resolve(field.config, field.entityType)

    /**
     * 저장을 **막는** 필수 필드 이름(없으면 null).
     *
     * **'필수'라고 다 막지 않는다** — 막는 것은 강도가 [RequiredEnforcement.BLOCK]인 필드뿐이다
     * (B-90). 나머지는 [emptyRequiredNoticeCount]가 세어 저장 뒤 알린다.
     */
    fun validateRequiredFields(): String? =
        emptyRequiredFields().firstOrNull { enforcementOf(it) == RequiredEnforcement.BLOCK }?.name

    /** 저장은 되지만 알려야 할 빈 필수 칸 수 (B-90). */
    fun emptyRequiredNoticeCount(): Int =
        emptyRequiredFields().count { enforcementOf(it) == RequiredEnforcement.NOTIFY }

    /**
     * 폼이 실제로 렌더한 필드 정의 id 집합 — 저장 시 폼의 권한 범위다 (N2).
     *
     * [collectFieldValues]가 빈 목록을 돌려주는 데는 두 가지 이유가 있다: 값이 전부 비었거나,
     * **애초에 렌더할 필드가 없거나**(작품 미선택 → `fieldDefinitions`가 비어 있음).
     * 저장 경로가 이 둘을 구분하지 못해 후자에서 기존 값을 전량 삭제했다.
     *
     * CALCULATED도 **포함한다.** 폼이 렌더하되 값을 수집하지 않는 필드이므로 폼의 권한
     * 안이며, 타입 변경 등으로 CALCULATED 정의를 가리키는 잔여 행이 DB에 남아 있으면
     * 저장 시 정리돼야 한다. 제외하면 그 행이 편집 화면으로는 영원히 지워지지 않고
     * "작품을 다시 배정하면 그대로 보입니다"라는 사실과 다른 고지가 매번 반복된다.
     */
    fun coveredFieldDefinitionIds(): Set<Long> =
        fieldDefinitions.mapTo(HashSet()) { it.id }

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
