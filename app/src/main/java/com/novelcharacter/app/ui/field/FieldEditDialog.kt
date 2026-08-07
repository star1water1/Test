package com.novelcharacter.app.ui.field

import android.app.Dialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.util.Log
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
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.gson.Gson
import com.novelcharacter.app.R
import com.google.android.material.tabs.TabLayout
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.DisplayFormat
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.NarrativeMode
import com.novelcharacter.app.data.model.RequiredEnforcement
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.novelcharacter.app.databinding.DialogFieldEditBinding
import com.novelcharacter.app.util.FormulaLexer
import com.novelcharacter.app.util.FormulaValidator
import com.novelcharacter.app.util.setValidatedPositiveButton
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FieldEditDialog : DialogFragment() {

    /** 저장 콜백. false를 반환하면 저장이 거부된 것으로 간주하고 다이얼로그를 닫지 않는다. */
    private var onSave: ((FieldDefinition) -> Boolean)? = null

    /** 생성 모드에서 입력된 값 사전 등록분 (콤마 구분) — 결과 번들로 전달 */
    private var stagedInitialValues: String = ""

    /** 저장 시점의 '전역 기본 필드' 스위치 상태 (B-119). 실행은 호출부가 저장소를 통해 한다. */
    private var stagedDefaultField: Boolean = false

    /** 스위치를 보일 것인가 — 켜는 호출부만 결과를 처리한다([newInstance] KDoc). */
    private val allowDefaultField: Boolean
        get() = arguments?.getBoolean(ARG_ALLOW_DEFAULT_FIELD) == true
    private var universeId: Long = 0
    private var existingField: FieldDefinition? = null

    /**
     * **미리 채우되 생성 모드**로 열 때의 초안 필드(추천에서 고르기 — 설계 D7).
     *
     * [existingField]로 넘기면 안 된다: 그러면 제목이 '필드 수정'이 되고
     * `stagedInitialValues`가 **생성일 때만 채워지므로**(아래 저장부) 추천이 딸려 보낸 값이
     * 통째로 버려진다. 그래서 '무엇을 보여 줄까'(prefill)와 '무엇을 고치는 중인가'(existing)를
     * 다른 인자로 가른다.
     */
    private var prefillField: FieldDefinition? = null

    // 수식 검증용 — 현재 세계관·같은 대상(캐릭터/사건)의 필드 키 (비동기 로드, 로드 전이면 키 존재 검사만 생략)
    private var universeFieldKeys: Set<String>? = null

    // 수식 검증용 — 전이 순환 참조를 보기 위한 CALCULATED 필드의 키 → 수식 (편집 중인 필드는 제외)
    private var calculatedFormulas: Map<String, String> = emptyMap()

    // 동적 분석 항목 관리
    private data class AnalysisRow(
        val container: View,
        val spinnerType: Spinner,
        val spinnerChart: Spinner,
        val editLimit: EditText
    )
    private val analysisRows = mutableListOf<AnalysisRow>()

    // 동적 값 라벨 관리
    // 등급 표 관리 (B-69) — C·B·A·S 고정 4칸을 동적 행으로. 순서·검증은 GradeTable(순수)이 담당.
    private data class GradeRow(
        val container: View,
        val editLabel: EditText,
        val editValue: EditText
    )
    private val gradeRows = mutableListOf<GradeRow>()

    // 등급 체계 참조 (U-1) — 라벨 집합은 체계가 정하고 숫자만 이 필드에서 재정의한다.
    private var gradeSystems: List<com.novelcharacter.app.data.model.GradeSystem> = emptyList()
    private var selectedGradeSystem: com.novelcharacter.app.data.model.GradeSystem? = null
    /** 편집 중인 필드가 참조하던 체계 code — 목록 로드가 끝나면 스피너 선택으로 반영된다. */
    private var pendingGradeSystemCode: String? = null

    // 동적 구간 관리
    private data class BinRangeRow(
        val container: View,
        val editRange: EditText
    )
    private val binRangeRows = mutableListOf<BinRangeRow>()

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

    // 파트 연결 (설계 5-4-5) — 구조화 입력의 칸 목록을 따라 다시 그려지므로 행을 들고 있는다.
    private val partSlotRows = mutableListOf<Spinner>()
    /** 손대지 않았을 때의 배정 — 저장 시 이것과 같으면 싣지 않는다(설계 3-1). */
    private var inferredPartSlots: List<BodySlot> = emptyList()
    /** 편집 중인 필드가 이미 들고 있던 명시 연결. 행을 못 그릴 때(칸 0개) 그대로 보존한다. */
    private var loadedPartSlots: List<BodySlot> = emptyList()
    /** 드롭다운 순서 — 인덱스가 곧 [BodySlot]이라 이 목록 하나가 표시와 저장을 함께 정한다. */
    private val partSlotChoices = listOf(
        BodySlot.BUST, BodySlot.UNDERBUST, BodySlot.WAIST, BodySlot.HIP, BodySlot.SHOULDER, BodySlot.NONE
    )

    private var fieldTypeSpinner: Spinner? = null

    private fun currentFieldType(): String {
        val pos = fieldTypeSpinner?.selectedItemPosition ?: 0
        val types = FieldType.entries.toTypedArray()
        return if (pos in types.indices) types[pos].name else "TEXT"
    }

    /** [listener]가 false를 반환하면(예: 키 중복 거부) 다이얼로그가 닫히지 않고 입력이 유지된다. */
    fun setOnSaveListener(listener: (FieldDefinition) -> Boolean) {
        onSave = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogFieldEditBinding.inflate(layoutInflater)

        universeId = arguments?.getLong(ARG_UNIVERSE_ID) ?: 0
        val fieldJson = arguments?.getString(ARG_FIELD_JSON)
        existingField = if (fieldJson != null) Gson().fromJson(fieldJson, FieldDefinition::class.java) else null
        val prefillJson = arguments?.getString(ARG_PREFILL_JSON)
        // 편집이면 프리필은 무시한다 — 고치는 중인 값이 언제나 이긴다.
        prefillField = if (existingField == null && prefillJson != null)
            Gson().fromJson(prefillJson, FieldDefinition::class.java) else null

        setupTabSwitching(binding)
        setupTypeSpinner(binding)
        setupSemanticRoleSpinner(binding)
        setupStatsSection(binding)
        setupRandomSection(binding)
        setupStructuredInputSection(binding)
        setupBodyAnalysisSection(binding)
        setupRequiredSection(binding)
        setupCardDisplaySection(binding)
        setupAiAndDescriptionSection(binding)
        setupDuelGradeSection(binding)
        setupDefaultFieldSection(binding)
        setupHelpButtons(binding)
        setupFormulaHelp(binding)
        populateFields(binding)
        // 파트 연결 행은 칸이 다 복원된 뒤에 그린다 — 새 필드(칸 0개)에서도 안내가 뜨도록
        // populateFields 안이 아니라 여기서 부른다(그 함수는 편집·프리필이 없으면 일찍 반환한다).
        refreshPartSlotRows(binding)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existingField == null) R.string.add_field else R.string.edit_field)
            .setView(binding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // 수식 검증용 필드 목록 로드 (프리셋 편집(universeId=0)은 DB에 없으므로 제외)
        if (universeId != 0L) {
            lifecycleScope.launch {
                try {
                    val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                    // 대상(캐릭터/사건)을 맞춰 읽는다 — DAO 기본값이 캐릭터라, 사건 필드를 편집하면서
                    // 캐릭터 필드 목록을 보면 있는 키를 없다고 하고 없는 키를 있다고 한다.
                    val defs = app.database.fieldDefinitionDao()
                        .getFieldsByUniverseList(universeId, currentEntityType())
                    universeFieldKeys = defs.map { it.key }.toSet()
                    // 편집 중인 필드는 뺀다 — 그 수식은 저장된 것이 아니라 지금 입력 중인 것이다.
                    calculatedFormulas = defs
                        .filter { it.type == FieldType.CALCULATED.name && it.id != existingField?.id }
                        .mapNotNull { def -> formulaOf(def)?.let { def.key to it } }
                        .toMap()
                } catch (_: Exception) { /* 로드 실패 시 키 존재·순환 검사만 생략 */ }
            }
        }

        // 등급 체계 목록 (U-1) — 프리셋 편집(universeId=0)은 세계관이 없어 참조가 성립하지
        // 않으므로 섹션을 통째로 숨긴다(R-24). 목록이 비어도 스피너('독자 표' 하나)와 목적문은
        // 남긴다 — 체계를 어디서 만드는지 이 자리가 알려 준다(발견성, P4의 교훈).
        if (universeId != 0L) {
            binding.gradeSystemLayout.visibility = View.VISIBLE
            setupGradeSystemSpinner(binding)
            lifecycleScope.launch {
                try {
                    val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                    gradeSystems = app.database.gradeSystemDao().getByUniverseList(universeId)
                    applyGradeSystemList(binding)
                } catch (_: Exception) { /* 로드 실패 시 독자 표 편집만 가능 */ }
            }
        }

        // 대결 축 목록 (B-113) — 캐릭터 필드이고 세계관이 있을 때만. 이미지 축은 참가자가
        // 캐릭터가 아니라 그 순위를 캐릭터 필드에 쓸 수 없으므로 목록에서 뺀다.
        if (universeId != 0L && currentEntityType() == FieldDefinition.ENTITY_CHARACTER) {
            lifecycleScope.launch {
                try {
                    val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                    duelAxes = app.database.duelAxisDao().getByUniverseList(universeId)
                        .filter { it.targetType == com.novelcharacter.app.data.model.DuelAxis.TARGET_CHARACTER }
                    applyDuelAxisList(binding)
                } catch (_: Exception) { /* 로드 실패 시 섹션은 사유 줄만 보인다 */ }
            }
        }

        // 검증 실패 시 다이얼로그를 닫지 않는다 (입력 유실 방지).
        // 타입 변경 영향 분석(비동기) 경로는 checkTypeChangeImpact가 완료 시점에 직접 닫는다.
        dialog.setValidatedPositiveButton { saveField(binding) }
        return dialog
    }

    // ── 등급 체계 참조 (U-1) ──

    private fun setupGradeSystemSpinner(binding: DialogFieldEditBinding) {
        binding.spinnerGradeSystem.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            mutableListOf(getString(R.string.grade_system_standalone))
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerGradeSystem.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyGradeSystemSelection(binding, position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** 목록 로드 완료 — 어댑터를 채우고, 편집 중 필드가 참조하던 체계를 선택으로 반영한다. */
    private fun applyGradeSystemList(binding: DialogFieldEditBinding) {
        val labels = mutableListOf(getString(R.string.grade_system_standalone))
        gradeSystems.forEach { labels.add(it.name) }
        binding.spinnerGradeSystem.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val pending = pendingGradeSystemCode
        if (pending != null) {
            val index = gradeSystems.indexOfFirst { it.code == pending }
            if (index >= 0) {
                binding.spinnerGradeSystem.setSelection(index + 1)
            } else {
                // 참조가 끊긴 상태를 말없이 지우지 않는다 — 지금은 독자 표로 보여 주고,
                // 저장하면 독자 표로 확정된다는 사실을 알린다(변수 제어).
                android.widget.Toast.makeText(
                    requireContext(), getString(R.string.grade_system_dangling), android.widget.Toast.LENGTH_LONG
                ).show()
                pendingGradeSystemCode = null
            }
        }
    }

    /**
     * 스피너 선택 반영 — 체계를 고르면 라벨은 체계의 것으로 잠기고 숫자만 재정의 가능해진다.
     * 현재 행의 숫자는 라벨이 같으면 보존한다(체계 전환이 입력을 지우지 않게).
     */
    private fun applyGradeSystemSelection(binding: DialogFieldEditBinding, position: Int) {
        val system = if (position <= 0) null else gradeSystems.getOrNull(position - 1)
        selectedGradeSystem = system
        val currentValues = LinkedHashMap<String, String>()
        gradeRows.forEach { row ->
            val label = row.editLabel.text.toString().trim()
            if (label.isNotEmpty()) currentValues[label] = row.editValue.text.toString()
        }
        val container = binding.gradeRowsContainer
        val density = resources.displayMetrics.density
        if (system == null) {
            // 독자 표 — 잠금만 풀면 되므로 행을 다시 그리지 않고 상태만 되돌린다.
            gradeRows.forEach { it.editLabel.isEnabled = true }
            binding.btnAddGrade.visibility = View.VISIBLE
            setGradeRowRemovable(true)
            return
        }
        container.removeAllViews()
        gradeRows.clear()
        val defaults = com.novelcharacter.app.data.model.GradeSystemRef.gradesFromJson(system.gradesJson)
        defaults.entries.sortedBy { it.value }.forEach { (label, def) ->
            addGradeRow(
                container, density, label,
                currentValues[label] ?: com.novelcharacter.app.util.GradeTable.formatValue(def),
                lockedLabel = true
            )
        }
        binding.btnAddGrade.visibility = View.GONE
    }

    // ── 대결 등급 산정 (B-113) ──

    /**
     * 이 세계관의 **캐릭터** 대결 축들. 이미지 축은 참가자가 캐릭터가 아니라 그 순위를
     * 캐릭터 필드에 쓸 수 없다(설계 4-2가 GRADE 타입으로 좁힌 것과 같은 부류의 제약이다).
     */
    private var duelAxes: List<com.novelcharacter.app.data.model.DuelAxis> = emptyList()

    /** 목록이 비동기라 code만 적어 두고, 로드 완료가 선택으로 바꾼다(체계 참조와 같은 배선). */
    private var pendingDuelAxisCode: String? = null

    /** 편집 중인 컷. 슬라이더가 진짜 상태를 들고 있고 이쪽은 로드/저장의 통로다. */
    private var duelGradeCuts: List<com.novelcharacter.app.data.model.DuelGradeRef.Cut> = emptyList()

    /** 직전 반영 흔적 — 화면에서 만들지 않고 **원문을 그대로 실어 나른다**(반영만이 이것을 쓴다). */
    private var duelGradeLastApplied: com.novelcharacter.app.data.model.DuelGradeRef.LastApplied? = null

    private fun setupDuelGradeSection(binding: DialogFieldEditBinding) {
        binding.switchDuelGrade.setOnCheckedChangeListener { _, checked ->
            binding.duelGradeBody.visibility = if (checked) View.VISIBLE else View.GONE
            if (checked) refreshDuelGradeEditor(binding)
        }
        binding.spinnerDuelAxis.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                refreshDuelGradeEditor(binding)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.duelGradeSlider.onCutsChanged = { cuts ->
            duelGradeCuts = cuts
            renderDuelGradeNotice(binding)
            renderDuelGradeBoundaryRow(binding)
        }
        binding.duelGradeSlider.onSelectionChanged = { renderDuelGradeBoundaryRow(binding) }
        // 겹친 구분선(0% 구간) — 어느 경계인지 물어야 한다. 임의로 고르면 사용자가 끌던 것과
        // 다른 경계가 움직인다(설계 4-4 ⓒ).
        binding.duelGradeSlider.onAmbiguousTap = { candidates -> askDuelGradeBoundary(binding, candidates) }

        binding.btnDuelGradeMinus.setOnClickListener { binding.duelGradeSlider.nudgeSelected(-STEP_PERCENT) }
        binding.btnDuelGradePlus.setOnClickListener { binding.duelGradeSlider.nudgeSelected(STEP_PERCENT) }
        binding.editDuelGradePercent.setOnEditorActionListener { view, _, _ ->
            view.text.toString().trim().toDoubleOrNull()?.let { binding.duelGradeSlider.setSelectedPercent(it) }
            renderDuelGradeBoundaryRow(binding)
            true
        }
        binding.btnDuelGradeSuggest.setOnClickListener { suggestDuelGradeCuts(binding) }
        binding.btnDuelGradeApply.setOnClickListener { openDuelGradeApply(binding) }
        binding.btnDuelAxisCreate.setOnClickListener { promptCreateDuelAxis(binding) }
    }

    /** 목록 로드 완료 — 어댑터를 채우고, 편집 중 필드가 가리키던 축을 선택으로 반영한다. */
    private fun applyDuelAxisList(binding: DialogFieldEditBinding) {
        val labels = duelAxes.map { it.name }
        binding.spinnerDuelAxis.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            labels.ifEmpty { listOf(getString(R.string.duel_grade_no_axis)) }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val pending = pendingDuelAxisCode
        if (pending != null && duelAxes.isNotEmpty()) {
            val index = duelAxes.indexOfFirst { it.code == pending }
            // 못 찾으면 선택을 옮기지 않는다 — 사유는 refreshDuelGradeEditor가 말한다.
            // 조용히 0번 축으로 옮기면 **다른 축의 순위가 이 필드에 배정된다**(오배정).
            if (index >= 0) binding.spinnerDuelAxis.setSelection(index)
        }
        refreshDuelGradeEditor(binding)
    }

    /** 지금 화면의 등급 행이 정한 라벨 차례(높은 등급부터) — 슬라이더가 나눌 대상이다. */
    private fun currentGradeLabels(): List<String> {
        val outcome = com.novelcharacter.app.util.GradeTable.build(
            gradeRows.map { it.editLabel.text.toString() to it.editValue.text.toString() }
        )
        return com.novelcharacter.app.util.DuelGradeAssign.orderedLabels(outcome.grades)
    }

    /**
     * 대결 축을 **이 창에서 바로 만든다** (2026.08.07 사용자 요청).
     *
     * 종전 사유 줄은 *"대결 탭에서 축을 먼저 만드세요"*라고 내보냈다 — 하던 필드 편집을 접고
     * 나갔다 와야 하고, 돌아오면 적어 두던 것이 사라져 있다(원칙 04 — 교정 경로는 문제를
     * 말한 그 자리에 있어야 한다).
     *
     * **이름만 묻는다.** `DuelAxis`의 나머지는 전부 기본값이 있고, 필드 연결·순서는 축을
     * 만든 뒤 대결 탭에서 정하는 것이 원래 동선이다. 여기서 그 전부를 물으면 축 편집 창을
     * 두 벌 만드는 셈이고, 두 벌이 되는 순간 한쪽이 낡는다.
     * 참가자 종류는 **캐릭터로 고정**이다 — 이미지 축의 순위는 캐릭터 필드에 쓸 수 없어
     * 이 자리에서 고를 이유가 없다(축 목록도 같은 기준으로 거른다).
     */
    private fun promptCreateDuelAxis(binding: DialogFieldEditBinding) {
        if (universeId == 0L) return
        val context = requireContext()
        val input = EditText(context).apply {
            hint = getString(R.string.duel_grade_create_axis_hint)
            setSingleLine()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 16, 64, 0)
            addView(TextView(context).apply {
                text = getString(R.string.duel_grade_create_axis_desc)
                textSize = 12f
            })
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.duel_grade_create_axis_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.show()
        // 이름이 비면 창을 닫지 않는다 — 닫으면 사용자는 만들어진 줄 알고 돌아온다(R-27).
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.error = getString(R.string.duel_grade_create_axis_name_required)
                return@setOnClickListener
            }
            dialog.dismiss()
            createDuelAxis(binding, name)
        }
    }

    /** 만든 축을 목록에 넣고 **그것을 고른 상태로** 돌려준다 — 만들고 다시 고르게 하면 마찰이다. */
    private fun createDuelAxis(binding: DialogFieldEditBinding, name: String) {
        lifecycleScope.launch {
            val created = try {
                val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                val axis = com.novelcharacter.app.data.model.DuelAxis(
                    universeId = universeId,
                    name = name,
                    targetType = com.novelcharacter.app.data.model.DuelAxis.TARGET_CHARACTER,
                    displayOrder = duelAxes.size
                )
                axis.copy(id = app.database.duelAxisDao().insert(axis))
            } catch (e: Exception) {
                Log.e("FieldEditDialog", "Failed to create duel axis", e)
                null
            }
            // 이 창은 뷰 바인딩을 필드로 들지 않는다(`binding`은 람다가 잡은 지역값이라
            // 늘 유효하다) — 확인할 것은 창이 아직 붙어 있는가뿐이다.
            if (!isAdded) return@launch
            if (created == null) {
                android.widget.Toast.makeText(
                    requireContext(), R.string.duel_grade_create_axis_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            duelAxes = duelAxes + created
            pendingDuelAxisCode = created.code
            applyDuelAxisList(binding)   // 이 안에서 refreshDuelGradeEditor까지 돈다
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.duel_grade_create_axis_done, created.name),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun selectedDuelAxis(binding: DialogFieldEditBinding): com.novelcharacter.app.data.model.DuelAxis? =
        duelAxes.getOrNull(binding.spinnerDuelAxis.selectedItemPosition)

    /**
     * 대결 등급 산정이 **지금 성립하는가** — 성립하지 않으면 그 사유 문구, 성립하면 null.
     *
     * **화면(사유 줄)과 저장 검증이 같은 함수를 본다.** 두 벌로 두면 *화면은 괜찮다고 하는데
     * 저장이 막히는* 또는 그 반대의 어긋남이 생기고, 후자가 실제로 있던 버그다 —
     * 사유 줄은 떠 있는데 저장은 **말없이 스위치를 되돌렸다**(아래 [saveField] 주석).
     */
    private fun duelGradeProblem(binding: DialogFieldEditBinding): String? {
        val labels = currentGradeLabels()
        val axis = selectedDuelAxis(binding)
        return when {
            duelAxes.isEmpty() -> getString(R.string.duel_grade_no_axis)
            axis == null -> getString(R.string.duel_grade_axis_missing)
            axis.universeId != universeId -> getString(R.string.duel_grade_axis_foreign)
            labels.isEmpty() -> getString(R.string.duel_grade_no_labels)
            labels.size < 2 -> getString(R.string.duel_grade_single_label)
            else -> null
        }
    }

    /**
     * 슬라이더를 지금 상태로 다시 세운다 — **성립하지 않으면 슬라이더 대신 사유 한 줄**이다.
     *
     * 막을 것이 셋이고 셋 다 조용히 틀리는 부류다: 축이 없음 · 축이 남의 세계관 것
     * (오배정 이중 방어, 설계 4-2 ⓑ) · 나눌 등급이 없음.
     */
    private fun refreshDuelGradeEditor(binding: DialogFieldEditBinding) {
        if (!binding.switchDuelGrade.isChecked) return
        val labels = currentGradeLabels()
        val problem = duelGradeProblem(binding)
        binding.duelGradeProblem.text = problem.orEmpty()
        binding.duelGradeProblem.visibility = if (problem == null) View.GONE else View.VISIBLE
        binding.duelGradeEditor.visibility = if (problem == null) View.VISIBLE else View.GONE
        // 축이 하나도 없을 때만 만들기를 연다 — 있는데 못 고른 경우는 고르면 되고,
        // 그 자리에 만들기를 세우면 축이 뜻 없이 불어난다.
        binding.btnDuelAxisCreate.visibility =
            if (duelAxes.isEmpty()) View.VISIBLE else View.GONE
        if (problem != null) return

        // 등급 표가 바뀌었으면 컷을 따라가게 한다 — 기존 배정을 보존하는 방향으로.
        val reconciled = com.novelcharacter.app.util.DuelGradeAssign.reconcile(
            duelGradeCuts.ifEmpty { com.novelcharacter.app.util.DuelGradeAssign.evenCuts(labels) },
            labels, labels
        )
        duelGradeCuts = reconciled.cuts
        binding.duelGradeSlider.setData(labels, duelGradeCuts)
        renderDuelGradeNotice(binding)
        renderDuelGradeBoundaryRow(binding)
    }

    /** 0% 구간은 오류가 아니라 사실이다 — 그래도 말하지 않으면 사용자는 등급이 빠진 줄 모른다. */
    private fun renderDuelGradeNotice(binding: DialogFieldEditBinding) {
        val labels = currentGradeLabels()
        var previous = 0.0
        val empty = mutableListOf<String>()
        labels.forEachIndexed { index, label ->
            val until = duelGradeCuts.getOrNull(index)?.topPercent ?: 100.0
            if (until - previous < 0.05) empty.add(label)
            previous = until
        }
        binding.duelGradeNotice.text =
            if (empty.isEmpty()) "" else getString(R.string.duel_grade_zero_segment, empty.joinToString(", "))
        binding.duelGradeNotice.visibility = if (empty.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 정밀 조정 줄 — 고른 경계가 없으면 스테퍼·숫자 칸을 죽여 둔다(무엇을 고치는지 모르니까). */
    private fun renderDuelGradeBoundaryRow(binding: DialogFieldEditBinding) {
        val index = binding.duelGradeSlider.selectedIndex
        val labels = currentGradeLabels()
        val cut = index?.let { duelGradeCuts.getOrNull(it) }
        val enabled = cut != null
        binding.btnDuelGradeMinus.isEnabled = enabled
        binding.btnDuelGradePlus.isEnabled = enabled
        binding.editDuelGradePercent.isEnabled = enabled
        if (cut == null || index == null) {
            binding.duelGradeBoundaryLabel.text = getString(R.string.duel_grade_select_boundary_hint)
            binding.editDuelGradePercent.setText("")
            return
        }
        binding.duelGradeBoundaryLabel.text = getString(
            R.string.duel_grade_selected_boundary, cut.label, labels.getOrNull(index + 1).orEmpty()
        )
        binding.editDuelGradePercent.setText(formatPercent(cut.topPercent))
    }

    /** 겹친 구분선을 탭했을 때 — 후보를 목록으로 보여 사용자가 고른다. */
    private fun askDuelGradeBoundary(binding: DialogFieldEditBinding, candidates: List<Int>) {
        val labels = currentGradeLabels()
        val items = candidates.map { index ->
            val cut = duelGradeCuts[index]
            getString(
                R.string.duel_grade_boundary_item,
                cut.label, labels.getOrNull(index + 1).orEmpty(), formatPercent(cut.topPercent)
            )
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.duel_grade_pick_boundary_title)
            .setItems(items) { _, which -> binding.duelGradeSlider.select(candidates[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatPercent(value: Double): String =
        if (value == value.toInt().toDouble()) "${value.toInt()}.0" else value.toString()

    /**
     * 이 축의 점수표를 읽는다 — 순위표·목록 정렬·통계와 **같은 진입점**
     * ([com.novelcharacter.app.data.repository.DuelRepository.scoresOf])이라
     * 여기서 본 수와 순위표의 수가 갈리지 않는다([DuelScoreIndex] 계약 1).
     */
    private suspend fun loadDuelScores(
        axis: com.novelcharacter.app.data.model.DuelAxis
    ): com.novelcharacter.app.util.DuelScoreIndex.AxisScores {
        val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
        val participants = app.characterRepository
            .getCharactersByUniverseList(axis.universeId).map { it.code }
        return app.duelRepository.scoresOf(axis, participants)
    }

    /**
     * [경계 제안] — 점수 분포의 큰 간격으로 구분선을 옮긴다.
     *
     * **몇 개를 옮겼는지 말한다.** 라벨이 아홉인데 자연 단절이 셋뿐일 수 있고, 그때 조용히
     * 셋만 옮기면 사용자는 전부 분포에 맞춰진 줄로 읽는다(변수 제어 — 조용히 덜 하지 않는다).
     */
    private fun suggestDuelGradeCuts(binding: DialogFieldEditBinding) {
        val axis = selectedDuelAxis(binding) ?: return
        val labels = currentGradeLabels()
        if (labels.size < 2) return
        binding.btnDuelGradeSuggest.isEnabled = false
        lifecycleScope.launch {
            val suggestion = try {
                com.novelcharacter.app.util.DuelGradeAssign.suggestCuts(loadDuelScores(axis), labels)
            } catch (e: Exception) {
                Log.e("FieldEditDialog", "Failed to suggest duel grade cuts", e)
                null
            }
            if (!isAdded) return@launch
            binding.btnDuelGradeSuggest.isEnabled = true
            if (suggestion == null) {
                android.widget.Toast.makeText(
                    requireContext(), getString(R.string.duel_grade_suggest_failed), android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            duelGradeCuts = suggestion.cuts
            binding.duelGradeSlider.setData(labels, duelGradeCuts)
            renderDuelGradeNotice(binding)
            renderDuelGradeBoundaryRow(binding)
            android.widget.Toast.makeText(
                requireContext(),
                if (suggestion.moved > 0) getString(R.string.duel_grade_suggest_moved, suggestion.moved)
                else getString(R.string.duel_grade_suggest_none),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * [등급 반영] — 미리보기를 띄운다.
     *
     * **저장되지 않은 필드에서는 열지 않는다.** 반영은 이 필드의 값을 캐릭터들에게 쓰는
     * 일이라 대상 필드가 DB에 있어야 하고, 지금 화면의 컷도 저장돼 있어야 *"미리보기에서
     * 본 것"*과 *"반영된 것"*이 같다. 새 필드·미저장 변경이면 먼저 저장하라고 말한다.
     */
    private fun openDuelGradeApply(binding: DialogFieldEditBinding) {
        val existing = existingField
        val axis = selectedDuelAxis(binding)
        if (existing == null || axis == null) {
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.duel_grade_apply_save_first), android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = currentGradeLabels()
        val problems = com.novelcharacter.app.util.DuelGradeAssign.validate(duelGradeCuts, labels)
        if (problems.isNotEmpty()) {
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.duel_grade_apply_fix_cuts), android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        // 화면의 컷이 저장본과 다르면 미리보기가 거짓을 말한다 — 저장을 먼저 요구한다.
        val saved = com.novelcharacter.app.data.model.DuelGradeRef.fromConfig(existing.config)
        if (saved?.axisCode != axis.code || saved.cuts != duelGradeCuts) {
            android.widget.Toast.makeText(
                requireContext(), getString(R.string.duel_grade_apply_save_first), android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        com.novelcharacter.app.ui.duel.DuelGradeApplySheet
            .newInstance(existing.id, axis.code)
            .show(parentFragmentManager, "duelGradeApply")
    }

    /** 체계 참조 중에는 행 삭제 버튼을 숨긴다 — 라벨 집합은 체계가 정한다(R-24). */
    private fun setGradeRowRemovable(removable: Boolean) {
        gradeRows.forEach { row ->
            (row.container as? LinearLayout)?.let { rowLayout ->
                for (i in 0 until rowLayout.childCount) {
                    (rowLayout.getChildAt(i) as? ImageButton)?.visibility =
                        if (removable) View.VISIBLE else View.GONE
                }
            }
        }
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

        // 서술형 여부 — AI 작성 보조가 값 추천 경로로 갈지 긴 글 경로로 갈지 가른다.
        val narrativeAdapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, NarrativeMode.labels()
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerNarrativeMode.adapter = narrativeAdapter

        // AI 추천 대상 3단(B-80) — 전부 / 개별만 / 끄기. 라벨의 단일 소스는 SuggestMode다.
        binding.spinnerAiSuggest.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item,
            com.novelcharacter.app.data.model.FieldAiPolicy.SuggestMode.entries.map { it.label }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

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
                // 대결 등급 산정(B-113)은 GRADE + 캐릭터 필드 + 세계관 안에서만 성립한다.
                // SELECT까지 넓히지 않는 것은 선택지에 서열이 없어 컷을 정의할 수 없어서다(설계 4-2).
                binding.duelGradeLayout.visibility = if (
                    selectedType == FieldType.GRADE && universeId != 0L &&
                    currentEntityType() == FieldDefinition.ENTITY_CHARACTER
                ) View.VISIBLE else View.GONE
                binding.calculatedLayout.visibility =
                    if (selectedType == FieldType.CALCULATED) View.VISIBLE else View.GONE
                binding.displayFormatLayout.visibility =
                    if (selectedType == FieldType.TEXT || selectedType == FieldType.MULTI_TEXT) View.VISIBLE else View.GONE
                // 서술형은 TEXT에서만 성립한다(SELECT는 옵션 계약, NUMBER·BODY_SIZE는 형식 계약).
                binding.narrativeModeLayout.visibility =
                    if (NarrativeMode.isEligibleType(selectedType.name)) View.VISIBLE else View.GONE
                // 시스템 연동: CALCULATED 제외 + **이 종류에서 성립하는 역할이 있을 때만** (B-81).
                // 두 조건 다 "성립하지 않으면 보이지 않는다"(R-24)의 같은 적용이다.
                binding.semanticRoleLayout.visibility =
                    if (selectedType != FieldType.CALCULATED && semanticRoleOptions.isNotEmpty())
                        View.VISIBLE else View.GONE
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
                // 랜덤 생성: NUMBER, SELECT, GRADE
                val isRandomizable = selectedType == FieldType.NUMBER || selectedType == FieldType.SELECT || selectedType == FieldType.GRADE
                binding.randomLayout.visibility = if (isRandomizable) View.VISIBLE else View.GONE
                if (isRandomizable) updateRandomNumberOptionsVisibility(binding)
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

    /**
     * 이 필드 종류에서 고를 수 있는 시맨틱 역할 (B-81).
     *
     * **역할 목록을 종류가 가린다 — 섹션을 종류 이름으로 감추지 않는다.** 지금은 역할 여덟이
     * 전부 캐릭터 축이라 사건·작품에서는 이 목록이 비고, 그 사실만 보고 섹션이 사라진다
     * (R-24가 조건문 없이 성립한다). 사건·작품 역할이 [SemanticRole.entityTypes]에 실리면
     * 이 파일을 고치지 않아도 섹션이 저절로 다시 선다.
     *
     * **이미 저장된 역할이 지금 종류에서 성립하지 않아도 목록에서 빼지 않는다** — 빼면 다음
     * 저장에서 그 역할이 조용히 지워진다(개발 의도 2번: 말없는 유실 금지). 보이게 두고
     * 사용자가 직접 '없음'으로 내리게 한다.
     */
    private val semanticRoleOptions: List<SemanticRole> by lazy {
        val applicable = SemanticRole.forEntityType(currentEntityType())
        val saved = existingField?.let { SemanticRole.fromConfig(it.config) }
        if (saved != null && saved !in applicable) applicable + saved else applicable
    }

    private fun setupSemanticRoleSpinner(binding: DialogFieldEditBinding) {
        // 성립하는 역할이 0개면 세울 것이 없다 — 타입 스피너 쪽 가시성도 같은 조건을 본다.
        if (semanticRoleOptions.isEmpty()) {
            binding.semanticRoleLayout.visibility = View.GONE
            return
        }
        val roleLabels = listOf(getString(R.string.label_semantic_role_none)) +
            semanticRoleOptions.map { it.label }
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
                    val role = semanticRoleOptions[position - 1]
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

    private fun setupRandomSection(binding: DialogFieldEditBinding) {
        // 소수점 자릿수 스피너
        val decimalOptions = arrayOf("0", "1", "2")
        binding.spinnerDecimalPlaces.adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, decimalOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.switchRandomEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateRandomNumberOptionsVisibility(binding)
        }
    }

    private fun updateRandomNumberOptionsVisibility(binding: DialogFieldEditBinding) {
        val typePos = binding.spinnerFieldType.selectedItemPosition
        val selectedType = FieldType.entries.getOrNull(typePos)
        val showNumberOptions = binding.switchRandomEnabled.isChecked && selectedType == FieldType.NUMBER
        binding.randomNumberOptions.visibility = if (showNumberOptions) View.VISIBLE else View.GONE
    }

    private fun collectRandomConfig(binding: DialogFieldEditBinding, type: FieldType): com.novelcharacter.app.data.model.RandomConfig {
        if (!binding.switchRandomEnabled.isChecked) return com.novelcharacter.app.data.model.RandomConfig()
        return when (type) {
            FieldType.NUMBER -> {
                val min = binding.editRandomMin.text.toString().toDoubleOrNull()
                val max = binding.editRandomMax.text.toString().toDoubleOrNull()
                val dp = binding.spinnerDecimalPlaces.selectedItemPosition
                com.novelcharacter.app.data.model.RandomConfig(
                    enabled = true,
                    min = min?.let { max?.let { mx -> minOf(it, mx) } ?: it } ?: min,
                    max = max?.let { min?.let { mn -> maxOf(it, mn) } ?: it } ?: max,
                    decimalPlaces = dp
                )
            }
            FieldType.SELECT, FieldType.GRADE -> com.novelcharacter.app.data.model.RandomConfig(enabled = true)
            else -> com.novelcharacter.app.data.model.RandomConfig()
        }
    }

    /** 이 다이얼로그가 편집 중인 필드가 붙는 대상 (character / event / novel) */
    private fun currentEntityType(): String =
        existingField?.entityType
            ?: arguments?.getString(ARG_ENTITY_TYPE)
            ?: FieldDefinition.ENTITY_CHARACTER

    /**
     * '필수 입력'의 강도 (B-90).
     *
     * 강도는 필수가 켜져 있을 때만 뜻이 있으므로 꺼져 있으면 감춘다 — '고를 수 있는데 아무
     * 일도 일어나지 않는 자리'를 만들지 않는다(R-24). 이 다이얼로그가 **종류를 가리지 않는
     * 것이 요점이다**: 표식은 캐릭터·사건·작품에서 모두 같은 뜻이라 감출 이유가 없다.
     */
    private fun setupRequiredSection(binding: DialogFieldEditBinding) {
        binding.switchRequired.setOnCheckedChangeListener { _, _ ->
            updateRequiredEnforcementVisibility(binding)
        }
        updateRequiredEnforcementVisibility(binding)
    }

    private fun updateRequiredEnforcementVisibility(binding: DialogFieldEditBinding) {
        binding.switchRequiredBlocksSave.visibility =
            if (binding.switchRequired.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * 목록 카드 표시 설정 (B-5).
     * 지금 이 설정을 읽는 카드는 연표 사건 카드뿐이라 사건 필드에만 노출한다 —
     * 캐릭터 목록 카드가 같은 설정을 쓰게 되면 조건만 넓히면 된다(설정 자체는 필드 종류를 가리지 않는다).
     */
    private fun setupCardDisplaySection(binding: DialogFieldEditBinding) {
        val isEventField = currentEntityType() == FieldDefinition.ENTITY_EVENT
        binding.cardDisplayLayout.visibility = if (isEventField) View.VISIBLE else View.GONE
        // 상한은 상수가 단일 소스다 — 문구에 숫자를 박아 두면 상한을 옮길 때 안내가 거짓이 된다.
        binding.textCardDisplayDesc.text = getString(
            R.string.label_card_display_desc,
            com.novelcharacter.app.data.model.CardDisplayConfig.MAX_ON_CARD
        )
    }

    /**
     * `?` 앱 도움말 배선 (P3 파일럿 — U-8 첫 적용).
     * 본문 원문의 단일 소스는 docs/text_style_guide_2026-07.md 9-2 승인분이고,
     * 주제 키는 [com.novelcharacter.app.ui.common.HelpDialog.Topic] enum이라 리소스 누락이
     * 컴파일에서 잡힌다. `ⓘ`(사용자가 쓴 필드 설명)와 아이콘·진입이 다르다.
     */
    private fun setupHelpButtons(binding: DialogFieldEditBinding) {
        val ctx = requireContext()
        val help = com.novelcharacter.app.ui.common.HelpDialog
        binding.fieldKeyLayout.setEndIconOnClickListener {
            help.showHelp(ctx, com.novelcharacter.app.ui.common.HelpDialog.Topic.FIELD_KEY)
        }
        binding.initialValuesLayout.setEndIconOnClickListener {
            help.showHelp(ctx, com.novelcharacter.app.ui.common.HelpDialog.Topic.INITIAL_VALUES)
        }
        mapOf(
            binding.labelDisplayFormat to com.novelcharacter.app.ui.common.HelpDialog.Topic.DISPLAY_FORMAT,
            binding.labelNarrativeMode to com.novelcharacter.app.ui.common.HelpDialog.Topic.NARRATIVE_MODE,
            binding.labelGradeMapping to com.novelcharacter.app.ui.common.HelpDialog.Topic.GRADE_VALUES,
            binding.labelSemanticRole to com.novelcharacter.app.ui.common.HelpDialog.Topic.SEMANTIC_ROLE,
            binding.labelStructuredInput to com.novelcharacter.app.ui.common.HelpDialog.Topic.STRUCTURED_INPUT,
            binding.captionPercentile to com.novelcharacter.app.ui.common.HelpDialog.Topic.PERCENTILE,
            binding.captionRandom to com.novelcharacter.app.ui.common.HelpDialog.Topic.RANDOM_GENERATION,
            binding.captionStats to com.novelcharacter.app.ui.common.HelpDialog.Topic.STATS_ANALYSIS,
            binding.labelStatsGroupBy to com.novelcharacter.app.ui.common.HelpDialog.Topic.STATS_GROUPING,
            binding.labelInputMode to com.novelcharacter.app.ui.common.HelpDialog.Topic.INPUT_MODE
        ).forEach { (view, topic) ->
            view.setOnClickListener { help.showHelp(ctx, topic) }
        }
    }

    /**
     * 수식 입력란 아래 안내 — 쓸 수 있는 함수는 [FormulaLexer.FUNCTIONS]가 단일 소스다.
     * 문구에 이름을 박아 두면 함수를 늘릴 때 안내가 거짓이 된다(실제로 `avg`가 빠져 있었다).
     */
    private fun setupFormulaHelp(binding: DialogFieldEditBinding) {
        val signatures = FormulaLexer.FUNCTIONS.entries.joinToString(", ") { (name, arity) ->
            val args = ('a' until 'a' + arity).joinToString(",")
            "$name($args)"
        }
        binding.textFormulaHelp.text = getString(R.string.text_formula_help, signatures)
    }

    /**
     * 필드 설명(A-2) + AI 추천 토글(A-1) 섹션.
     * AI 추천 경로가 있는 것은 **캐릭터 필드뿐**이라 그 외 종류에는 토글을 노출하지 않는다
     * (R-24 — 사건은 B-43, 작품은 확-3 잔여). 설명은 AI와 무관하게 인앱에서 값을 가지므로
     * 모든 종류에 노출한다. 조건을 '캐릭터인가'로 쓰는 이유는 종류가 늘 때 이 자리가
     * 조용히 뒤처지지 않게 하기 위해서다.
     */
    private fun setupAiAndDescriptionSection(binding: DialogFieldEditBinding) {
        binding.fieldDescriptionLayout.counterMaxLength =
            com.novelcharacter.app.data.model.FieldDescription.MAX_CHARS
        binding.editFieldDescription.filters = arrayOf(
            android.text.InputFilter.LengthFilter(com.novelcharacter.app.data.model.FieldDescription.MAX_CHARS)
        )
        val isCharacterField = currentEntityType() == FieldDefinition.ENTITY_CHARACTER
        binding.aiSectionLayout.visibility = if (isCharacterField) View.VISIBLE else View.GONE
    }

    private fun setupStatsSection(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density

        // 통계 토글
        binding.switchStatsEnabled.setOnCheckedChangeListener { _, isChecked ->
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.analysisListContainer.visibility = visibility
            binding.btnAddAnalysis.visibility = visibility
        }

        // 분석 추가 버튼
        binding.btnAddAnalysis.setOnClickListener {
            addAnalysisRow(binding.analysisListContainer, density)
        }

        // 값 데이터 라이브러리 섹션 — 표시 라벨·별칭·카테고리 편집은 라이브러리로 이관됨
        setupFieldLibrarySection(binding)

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

        // 등급 추가 버튼 (B-69) + 새 필드의 기본 표 — 기존 필드는 populateFields가 config로 대체한다
        binding.btnAddGrade.setOnClickListener {
            addGradeRow(binding.gradeRowsContainer, density)
        }
        com.novelcharacter.app.util.GradeTable.DEFAULT_ROWS.forEach { (label, value) ->
            addGradeRow(binding.gradeRowsContainer, density, label, value)
        }

        // 기본 분석 1개 추가
        addAnalysisRow(binding.analysisListContainer, density)

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
            refreshPartSlotRows(binding)
        }

        binding.btnAddStructuredPart.setOnClickListener {
            addStructuredPartRow(binding.structuredPartsContainer, density,
                onPartsChanged = { refreshPartSlotRows(binding) })
            refreshPartSlotRows(binding)
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
            setImageResource(R.drawable.ic_delete)
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

    /**
     * 값 데이터 라이브러리 섹션 (검토 A11):
     * - 편집 모드: 라이브러리 값 수 요약 + [열기] (표시 라벨·별칭·카테고리는 라이브러리가 단일 소스)
     * - 생성 모드: 값 사전 등록 입력 (콤마 구분) — 저장 시 라이브러리에 등재되어
     *   restricted 모드도 다이얼로그 한 번으로 완결된다 (원칙 04)
     * - 입력 모드 스피너: 제안(기본)/자유/제한 — config "valueLibrary"에 저장
     */
    private fun setupFieldLibrarySection(binding: DialogFieldEditBinding) {
        val modeLabels = listOf(
            getString(R.string.field_library_input_mode_suggest),
            getString(R.string.field_library_input_mode_free),
            getString(R.string.field_library_input_mode_restricted)
        )
        binding.spinnerInputMode.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, modeLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val existing = existingField
        // id == 0L: 프리셋/템플릿 편집기 경로 — DB에 없는 필드라 라이브러리 드릴다운 대상이 아니다
        if (existing != null && existing.id != 0L) {
            binding.btnOpenFieldLibrary.visibility =
                if (com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(existing)) View.VISIBLE else View.GONE
            binding.initialValuesLayout.visibility = View.GONE
            binding.btnOpenFieldLibrary.setOnClickListener {
                val nav = runCatching {
                    androidx.navigation.fragment.NavHostFragment.findNavController(requireParentFragment())
                }.getOrNull()
                dismissAllowingStateLoss()
                nav?.navigate(
                    R.id.fieldValueListFragment,
                    bundleOf("fieldDefinitionId" to existing.id)
                )
            }
            lifecycleScope.launch {
                val count = runCatching {
                    (requireActivity().application as com.novelcharacter.app.NovelCharacterApp)
                        .database.fieldValueEntryDao().getByField(existing.id).size
                }.getOrDefault(0)
                if (isAdded) {
                    binding.fieldLibrarySummary.text =
                        getString(R.string.field_library_summary_edit, count)
                }
            }
        } else if (existing != null) {
            // 프리셋/템플릿 필드(id=0) — 라이브러리는 실제 DB 필드에만 존재하므로 섹션 최소화
            binding.fieldLibrarySummary.text = getString(R.string.field_library_summary_preset)
            binding.initialValuesLayout.visibility = View.GONE
            binding.btnOpenFieldLibrary.visibility = View.GONE
        } else {
            binding.fieldLibrarySummary.text = getString(R.string.field_library_summary_create)
            binding.initialValuesLayout.visibility = View.VISIBLE
            binding.btnOpenFieldLibrary.visibility = View.GONE
        }

        val mode = com.novelcharacter.app.data.model.FieldValueLibraryConfig
            .fromConfig(existing?.config ?: "{}").inputMode
        binding.spinnerInputMode.setSelection(
            com.novelcharacter.app.data.model.FieldValueLibraryConfig.MODES.indexOf(mode).coerceAtLeast(0)
        )
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
            setImageResource(R.drawable.ic_delete)
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

    /**
     * 등급 행 추가 (B-69) — 구간 행(addBinRangeRow)과 같은 방식의 동적 행.
     * @param lockedLabel 체계 참조 중(U-1)에는 라벨이 체계의 것이라 편집·삭제를 잠근다(R-24).
     */
    private fun addGradeRow(
        container: LinearLayout, density: Float, label: String = "", value: String = "",
        lockedLabel: Boolean = false
    ) {
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
            hint = getString(R.string.hint_grade_label)
            textSize = 13f
            if (label.isNotEmpty()) setText(label)
            isEnabled = !lockedLabel
        }

        val editValue = EditText(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = getString(R.string.hint_grade_value)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            textSize = 13f
            if (value.isNotEmpty()) setText(value)
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            visibility = if (lockedLabel) View.GONE else View.VISIBLE
            setOnClickListener {
                container.removeView(row)
                gradeRows.removeAll { it.container == row }
            }
        }

        row.addView(editLabel)
        row.addView(editValue)
        row.addView(btnRemove)
        container.addView(row)
        gradeRows.add(GradeRow(row, editLabel, editValue))
    }

    /**
     * [onPartsChanged]는 칸의 이름·종류·존재가 바뀔 때마다 불린다 — 파트 연결 행이 그 목록을
     * 따라 다시 그려져야 하기 때문이다(방금 만든 칸을 연결할 수 없으면 기능이 반쪽이 된다).
     */
    private fun addStructuredPartRow(container: LinearLayout, density: Float,
                                      label: String = "", suffix: String = "", inputType: String = "text",
                                      onPartsChanged: (() -> Unit)? = null) {
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
            onPartsChanged?.let { notify -> doOnTextChanged { _, _, _, _ -> notify() } }
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
            onPartsChanged?.let { notify ->
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = notify()
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
        }

        val btnRemove = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
            setImageResource(R.drawable.ic_delete)
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener {
                container.removeView(row)
                structuredPartRows.removeAll { it.container == row }
                onPartsChanged?.invoke()
            }
        }

        row.addView(editLabel)
        row.addView(editSuffix)
        row.addView(spinnerInputType)
        row.addView(btnRemove)
        container.addView(row)
        structuredPartRows.add(StructuredPartRow(row, editLabel, editSuffix, spinnerInputType))
    }

    // ── 파트 연결 (설계 5-4-5) ──────────────────────────────────────────────

    /** 드롭다운 라벨 — 부위 이름은 실루엣·폴백과 같은 리소스이고, 단위만 여기서 덧붙인다. */
    private fun partSlotChoiceLabel(slot: BodySlot): String = when (slot) {
        BodySlot.NONE -> getString(R.string.body_part_slot_none)
        BodySlot.SHOULDER -> getString(
            R.string.body_part_slot_width, getString(R.string.silhouette_slot_shoulder)
        )
        else -> getString(
            R.string.body_part_slot_circumference,
            when (slot) {
                BodySlot.BUST -> getString(R.string.silhouette_slot_bust)
                BodySlot.UNDERBUST -> getString(R.string.silhouette_slot_underbust)
                BodySlot.WAIST -> getString(R.string.silhouette_slot_waist)
                else -> getString(R.string.silhouette_slot_hip)
            }
        )
    }

    /**
     * 구조화 입력의 칸 목록을 따라 연결 행을 다시 그린다.
     *
     * 칸은 같은 다이얼로그 아래쪽에서 지금도 편집되는 중이므로(추가·삭제·이름 변경),
     * 행 목록은 그때마다 따라와야 한다 — 안 그러면 방금 만든 칸이 여기 없어 연결할 길이 없다.
     * 다시 그리는 동안 **사용자가 이미 고른 선택은 인덱스로 보존한다.**
     */
    private fun refreshPartSlotRows(binding: DialogFieldEditBinding) {
        val density = resources.displayMetrics.density
        val ctx = requireContext()
        val container = binding.partSlotContainer

        val structuredOn = binding.switchStructuredInput.isChecked
        val labels = if (structuredOn) structuredPartRows.map { it.editLabel.text.toString().trim() } else emptyList()
        val numeric = if (structuredOn) structuredPartRows.map { it.spinnerInputType.selectedItemPosition == 1 } else emptyList()

        // 이전 선택 보존 — 행을 다시 만들기 전에 읽어 둔다.
        val previous = partSlotRows.map { partSlotChoices.getOrElse(it.selectedItemPosition) { BodySlot.NONE } }
        val previousInferred = inferredPartSlots

        inferredPartSlots = com.novelcharacter.app.util.BodyMeasurements.inferSlotsForParts(labels, numeric)
        container.removeAllViews()
        partSlotRows.clear()

        if (labels.isEmpty()) {
            // 칸이 없으면 연결할 대상도 없다. 무엇을 먼저 해야 하는지 말하고 끝낸다(원칙 04).
            binding.partSlotNotice.text = getString(R.string.body_part_slot_empty)
            binding.partSlotNotice.visibility = View.VISIBLE
            return
        }

        // 화면에 있던 선택이 추론과 다르면 사용자가 고른 것이다 — 칸을 고쳤다고 그것을 지우지 않는다.
        val userChose = previous.isNotEmpty() && previous != previousInferred
        val choiceLabels = partSlotChoices.map { partSlotChoiceLabel(it) }
        for (index in labels.indices) {
            // 우선순위: 사용자가 고른 것 → 저장돼 있던 명시 연결 → 추론(새로 생긴 칸이 여기로 온다).
            val selected = (if (userChose) previous.getOrNull(index) else null)
                ?: loadedPartSlots.getOrNull(index)
                ?: inferredPartSlots.getOrNull(index)
                ?: BodySlot.NONE

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * density).toInt() }
            }
            row.addView(TextView(ctx).apply {
                text = labels[index].ifEmpty { getString(R.string.body_part_slot_unnamed, index + 1) }
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val spinner = Spinner(ctx).apply {
                // 48dp — 설계 5-4-6의 최소 터치 영역.
                layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1.4f)
                adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, choiceLabels).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(partSlotChoices.indexOf(selected).coerceAtLeast(0))
            }
            row.addView(spinner)
            container.addView(row)
            partSlotRows.add(spinner)
        }

        // 지금 보이는 것이 자동 판단인지 사용자가 정한 것인지 밝힌다 — 둘은 칸 이름을 고쳤을 때
        // 따라오느냐 마느냐가 갈리므로, 한쪽 문구로 뭉뚱그리면 화면이 거짓을 말하게 된다.
        val chosen = userChose || loadedPartSlots.isNotEmpty()
        binding.partSlotNotice.text = getString(
            if (chosen) R.string.body_part_slot_chosen_notice else R.string.body_part_slot_auto_notice
        )
        binding.partSlotNotice.visibility = View.VISIBLE
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
            hint = ctx.getString(R.string.body_rib_offset_hint)
            setText(BodyAnalysisConfig.DEFAULT_RIB_OFFSET.toString())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (4 * density).toInt() }
        }
        val ribOffsetLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.body_rib_offset_label)
            textSize = 12f
            alpha = 0.7f
        }
        binding.insightTogglesContainer.addView(ribOffsetLabel, 0)
        binding.insightTogglesContainer.addView(ribOffsetEdit, 1)
        // Store reference for later use
        ribOffsetEdit.tag = "ribOffsetEdit"

        // ── 목표 비율 (P8 — 종전 '골든비율') ──
        // 겹 셋: 전부 비우면 장르 기준 자동 → 이상 몸(치수)을 적으면 그 몸에서 계산 →
        // 비율 칸에 적은 키는 그 값으로 고정(가장 구체적인 것이 이긴다).
        // 힌트의 참고 숫자는 파생 함수에서 그때그때 계산한다 — 적어 두면 낡는다(B-92의 교훈).
        val genreRef = com.novelcharacter.app.util.BodyGenerator.genreTargetIdeals()
        fun refOf(key: String) = String.format(java.util.Locale.US, "%.2f", genreRef[key] ?: 0.0)
        val baseHeight = com.novelcharacter.app.util.BodySilhouetteSpec.BASE.height.toInt()

        // 이상 몸(치수) — 창작자는 비율이 아니라 "165에 88-58-88"로 생각한다(2026.08.02 요청).
        val idealBodyLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.body_ideal_body_title)
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        binding.insightTogglesContainer.addView(idealBodyLabel, 2)
        val idealBodyRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (2 * density).toInt() }
        }
        val idealBodyFields = listOf(
            "idealBody_bust" to ctx.getString(R.string.body_ideal_body_bust),
            "idealBody_waist" to ctx.getString(R.string.body_ideal_body_waist),
            "idealBody_hip" to ctx.getString(R.string.body_ideal_body_hip),
            "idealBody_height" to ctx.getString(R.string.body_ideal_body_height, baseHeight)
        )
        for ((tagName, hint) in idealBodyFields) {
            idealBodyRow.addView(EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                this.hint = hint
                textSize = 13f
                tag = tagName
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        binding.insightTogglesContainer.addView(idealBodyRow, 3)

        // 비율 직접 고정 — 이상 몸보다 세밀한 경로(키별 병용 가능).
        val idealEntries = listOf(
            "whr" to ctx.getString(R.string.body_ideal_ratio_whr, refOf("whr")),
            "bustHipRatio" to ctx.getString(R.string.body_ideal_ratio_bust_hip, refOf("bustHipRatio")),
            "waistHeight" to ctx.getString(R.string.body_ideal_ratio_waist_height, refOf("waistHeight")),
            "bustHeight" to ctx.getString(R.string.body_ideal_ratio_bust_height, refOf("bustHeight"))
        )
        val goldenIdealEdits = mutableMapOf<String, EditText>()
        val idealLabel = TextView(ctx).apply {
            text = ctx.getString(R.string.body_ideal_ratio_title)
            textSize = 12f
            alpha = 0.7f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        }
        binding.insightTogglesContainer.addView(idealLabel, 4)
        var insertIdx = 5
        for ((key, hint) in idealEntries) {
            val edit = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                this.hint = hint
                tag = "goldenIdeal_$key"
                // 비워 두는 것이 기본이다 — 미리 채우면 '직접 정한 값'과 구분되지 않는다.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (2 * density).toInt() }
            }
            binding.insightTogglesContainer.addView(edit, insertIdx++)
            goldenIdealEdits[key] = edit
        }

        // 인사이트 토글 생성 — 라벨은 **읽기 화면과 같은 리소스**를 쓴다(B-61 해소, 설계 6장).
        // 새 이름을 짓지 않는 것이 요지다: 두 화면이 같은 문자열을 가리키면 이름이 갈릴 수 없고,
        // 코드에서 한국어 리터럴이 사라져 텍스트 검사의 대상이 된다.
        val insightKeys = listOf(
            BodyAnalysisConfig.INSIGHT_BODY_TAGS to getString(R.string.body_tags_label),
            BodyAnalysisConfig.INSIGHT_BODY_TYPE to getString(R.string.body_type_label),
            BodyAnalysisConfig.INSIGHT_SILHOUETTE to getString(R.string.body_silhouette_label),
            BodyAnalysisConfig.INSIGHT_CUP_SIZE to getString(R.string.cup_size_label),
            BodyAnalysisConfig.INSIGHT_FRAME_SIZE to getString(R.string.body_frame_label),
            // 토글은 하나, 읽기 행은 둘 — 두 이름을 조합해 어느 쪽이 바뀌어도 함께 움직이게 한다.
            BodyAnalysisConfig.INSIGHT_PROPORTION to getString(
                R.string.body_proportion_label,
                getString(R.string.body_volume_label), getString(R.string.body_curves_label)
            ),
            BodyAnalysisConfig.INSIGHT_BWH_DIFF to getString(R.string.body_bwh_diff_label),
            BodyAnalysisConfig.INSIGHT_NORMALIZED_RATIO to getString(R.string.body_normalized_ratio_label),
            BodyAnalysisConfig.INSIGHT_BMI to getString(R.string.bmi_label),
            BodyAnalysisConfig.INSIGHT_WHR to getString(R.string.whr_label),
            BodyAnalysisConfig.INSIGHT_HEIGHT_RELATIVE to getString(R.string.body_height_relative_label),
            BodyAnalysisConfig.INSIGHT_GOLDEN_RATIO to getString(R.string.body_target_ratio_label),
            BodyAnalysisConfig.INSIGHT_RANKING to getString(R.string.body_ranking_label)
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
            hint = ctx.getString(R.string.body_cup_mapping_diff_hint)
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
            setImageResource(R.drawable.ic_delete)
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
        // 기존 config에서 UI 미노출 필드 보존
        val existingConfig = existingField?.let { BodyAnalysisConfig.fromConfig(it.config) }

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
        val ribOffset = ribOffsetView?.text?.toString()?.toDoubleOrNull()?.coerceIn(0.0, 10.0)
            ?: BodyAnalysisConfig.DEFAULT_RIB_OFFSET

        // 목표 비율 이상값 — 적힌 칸만 담는다(빈 칸 = 자동, P8)
        val goldenIdeals = mutableMapOf<String, Double>()
        for (key in listOf("whr", "bustHipRatio", "waistHeight", "bustHeight")) {
            val edit = binding.insightTogglesContainer.findViewWithTag<EditText>("goldenIdeal_$key")
            edit?.text?.toString()?.toDoubleOrNull()?.let { goldenIdeals[key] = it }
        }

        // 이상 몸 — 적힌 그대로 담는다(부분 입력도 보존 — R-27. 효력은 셋이 갖춰질 때).
        fun idealBodyNum(tagName: String): Double? =
            binding.insightTogglesContainer.findViewWithTag<EditText>(tagName)
                ?.text?.toString()?.toDoubleOrNull()
        val idealBody = BodyAnalysisConfig.IdealBody(
            bust = idealBodyNum("idealBody_bust"),
            waist = idealBodyNum("idealBody_waist"),
            hip = idealBodyNum("idealBody_hip"),
            heightCm = idealBodyNum("idealBody_height")
        ).takeUnless { it.isEmpty }

        // 파트 연결 — 추론과 같으면 싣지 않는다(설계 3-1). 행을 못 그리는 상태(구조화 입력이
        // 꺼져 있거나 칸이 0개)에서는 **저장돼 있던 것을 그대로 둔다** — 화면에 없다는 이유로
        // 사용자의 설정을 지우면 말없는 유실이다.
        val partSlots = if (partSlotRows.isEmpty()) loadedPartSlots else {
            val selected = partSlotRows.map { partSlotChoices.getOrElse(it.selectedItemPosition) { BodySlot.NONE } }
            com.novelcharacter.app.util.BodyMeasurements.slotsToStore(selected, inferredPartSlots)
        }

        return BodyAnalysisConfig(
            cupMapping = cupMapping,
            // 아래 둘은 UI가 없다 — 기존값을 이어받지 않으면 저장할 때마다 조용히 기본값으로 돌아간다.
            underbustEstimation = existingConfig?.underbustEstimation ?: BodyAnalysisConfig.DEFAULT.underbustEstimation,
            defaultBodyType = existingConfig?.defaultBodyType ?: BodyAnalysisConfig.DEFAULT.defaultBodyType,
            bodyTypeRules = bodyTypeRules,
            enabledInsights = enabledInsights.ifEmpty { BodyAnalysisConfig.DEFAULT_ENABLED_INSIGHTS },
            ribOffset = ribOffset,
            bodyTagRules = existingConfig?.bodyTagRules ?: emptyList(),  // UI 미노출 → 기존값 보존
            goldenRatioIdeals = goldenIdeals,
            partSlots = partSlots,
            idealBody = idealBody
        )
    }

    private fun populateFields(binding: DialogFieldEditBinding) {
        // 편집 중인 필드가 없으면 프리필 초안으로 채운다(생성 모드는 그대로 유지된다).
        val field = existingField ?: prefillField ?: return

        binding.editFieldName.setText(field.name)
        binding.editFieldKey.setText(field.key)
        binding.editGroupName.setText(field.groupName)
        binding.switchRequired.isChecked = field.isRequired
        // 강도 복원 — 설정이 없는 옛 필드는 종류에 따라 갈린다(캐릭터는 막던 자리, 사건·작품은
        // 막은 적이 없다). [RequiredEnforcement.legacyDefaultFor]가 그 판정을 든다 (B-90).
        binding.switchRequiredBlocksSave.isChecked =
            RequiredEnforcement.resolve(field.config, field.entityType) == RequiredEnforcement.BLOCK
        updateRequiredEnforcementVisibility(binding)
        // 필드 설명(A-2) + AI 추천 토글(A-1) — 목록 행 스위치와 같은 소스(config 키)
        binding.editFieldDescription.setText(
            com.novelcharacter.app.data.model.FieldDescription.fromConfig(field.config)
        )
        val aiModeIndex = com.novelcharacter.app.data.model.FieldAiPolicy.SuggestMode.entries
            .indexOf(com.novelcharacter.app.data.model.FieldAiPolicy.suggestMode(field.config))
        if (aiModeIndex >= 0) binding.spinnerAiSuggest.setSelection(aiModeIndex)
        binding.switchImageTagVocab.isChecked =
            com.novelcharacter.app.data.model.FieldAiPolicy.isImageTagVocabEnabled(field.config)

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

        // GRADE 표 (B-69) — config의 등급 전부를 행으로. 종전 C·B·A·S 4칸은 그 밖의 등급
        // (엑셀 필드정의로 넣은 SS·D 등)을 보여 주지도 고치게 하지도 못했다.
        val gradeRowsFromConfig =
            com.novelcharacter.app.util.GradeTable.fromConfigRows(field.config)
        if (gradeRowsFromConfig.isNotEmpty()) {
            val gradeContainer = binding.gradeRowsContainer
            gradeContainer.removeAllViews()
            gradeRows.clear()
            val density = resources.displayMetrics.density
            gradeRowsFromConfig.forEach { (label, value) ->
                addGradeRow(gradeContainer, density, label, value)
            }
        }
        // 체계 참조 (U-1) — 목록이 비동기라 code만 적어 두고, 로드 완료가 선택으로 바꾼다.
        pendingGradeSystemCode =
            com.novelcharacter.app.data.model.GradeSystemRef.codeFromConfig(field.config)
        // 대결 등급 산정 (B-113) — 같은 배선. 흔적(lastApplied)은 화면이 만들지 않고 그대로
        // 실어 나른다. 여기서 버리면 다음 반영이 직전 반영값을 전부 '손값'으로 읽는다.
        com.novelcharacter.app.data.model.DuelGradeRef.fromConfig(field.config)?.let { spec ->
            pendingDuelAxisCode = spec.axisCode
            duelGradeCuts = spec.cuts
            duelGradeLastApplied = spec.lastApplied
            binding.switchDuelGrade.isChecked = true
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

        val narrativeIndex = NarrativeMode.entries.indexOf(NarrativeMode.fromConfig(field.config))
        if (narrativeIndex >= 0) binding.spinnerNarrativeMode.setSelection(narrativeIndex)

        // Semantic role
        val semanticRole = SemanticRole.fromConfig(field.config)
        if (semanticRole != null) {
            // 목록은 저장된 역할을 반드시 담는다(성립하지 않는 역할도 남긴다 —
            // [semanticRoleOptions]). 그래서 여기서 -1이 나오지 않는다.
            val roleIdx = semanticRoleOptions.indexOf(semanticRole) + 1 // +1 for "없음"
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

        // Random config
        val randomConfig = com.novelcharacter.app.data.model.RandomConfig.fromConfig(field.config)
        binding.switchRandomEnabled.isChecked = randomConfig.enabled
        if (randomConfig.min != null) binding.editRandomMin.setText(randomConfig.min.toString())
        if (randomConfig.max != null) binding.editRandomMax.setText(randomConfig.max.toString())
        binding.spinnerDecimalPlaces.setSelection(randomConfig.decimalPlaces.coerceIn(0, 2))
        updateRandomNumberOptionsVisibility(binding)

        // 목록 카드 표시 (B-5)
        binding.switchCardDisplay.isChecked =
            com.novelcharacter.app.data.model.CardDisplayConfig.fromConfig(field.config).show

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

        // 값 라벨·카테고리는 값 데이터 라이브러리로 이관됨 (setupFieldLibrarySection이 요약 표시)

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
            // 목표 비율 이상값 복원 — 직접 정한 키만 채워진다(빈 칸 = 자동)
            for ((key, value) in bodyConfig.goldenRatioIdeals) {
                val edit = binding.insightTogglesContainer.findViewWithTag<EditText>("goldenIdeal_$key")
                edit?.setText(value.toString())
            }
            // 이상 몸 복원 — 적었던 값 그대로(부분 입력 포함)
            bodyConfig.idealBody?.let { body ->
                fun restore(tagName: String, value: Double?) {
                    value?.let {
                        binding.insightTogglesContainer.findViewWithTag<EditText>(tagName)
                            ?.setText(com.novelcharacter.app.util.BodyEditorModel.formatValue(it))
                    }
                }
                restore("idealBody_bust", body.bust)
                restore("idealBody_waist", body.waist)
                restore("idealBody_hip", body.hip)
                restore("idealBody_height", body.heightCm)
            }
        }

        // 구조화 입력 복원
        val structuredConfig = StructuredInputConfig.fromConfig(field.config)
        if (structuredConfig.enabled) {
            binding.switchStructuredInput.isChecked = true
            binding.editStructuredSeparator.setText(structuredConfig.separator)
            for (part in structuredConfig.parts) {
                addStructuredPartRow(binding.structuredPartsContainer, density,
                    part.label, part.suffix, part.inputType,
                    onPartsChanged = { refreshPartSlotRows(binding) })
            }
        }

        // 저장돼 있던 명시 연결 — 행을 그리는 것은 호출부(onCreateDialog)가 한다.
        // 새 필드(populateFields가 일찍 반환하는 경로)에서도 행은 그려져야 하기 때문이다.
        loadedPartSlots = BodyAnalysisConfig.fromConfig(field.config).partSlots
    }

    /** @return true면 저장이 동기적으로 완료되어 다이얼로그를 닫아도 된다. false면 유지(검증 실패 또는 비동기 처리 진행 중). */
    private fun saveField(binding: DialogFieldEditBinding): Boolean {
        val name = binding.editFieldName.text.toString().trim()
        val key = binding.editFieldKey.text.toString().trim()
        val groupName = binding.editGroupName.text.toString().trim().ifEmpty { getString(R.string.default_group_name) }
        val isRequired = binding.switchRequired.isChecked

        if (name.isEmpty() || key.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), getString(R.string.field_name_key_required), android.widget.Toast.LENGTH_SHORT).show()
            return false
        }

        val types = FieldType.entries.toTypedArray()
        val selectedType = types[binding.spinnerFieldType.selectedItemPosition]

        // 등급 표 검증 (B-69) — 실패 시 다이얼로그를 닫지 않는다(R-27). 조용히 버리거나
        // 기본값으로 대체하면 사용자의 입력이 무통보 유실된다(변수 제어).
        if (selectedType == FieldType.GRADE) {
            val outcome = com.novelcharacter.app.util.GradeTable.build(
                gradeRows.map { it.editLabel.text.toString() to it.editValue.text.toString() }
            )
            val problem = outcome.problems.firstOrNull()
            if (problem != null) {
                android.widget.Toast.makeText(
                    requireContext(), gradeProblemMessage(problem), android.widget.Toast.LENGTH_LONG
                ).show()
                return false
            }
        }

        // 대결 등급 산정을 **켜 두었는데 성립하지 않으면 저장을 막는다** (2026.08.07 사용자 보고).
        //
        // 종전에는 그대로 저장이 진행됐고, `applyDuelGradeConfig`가 축을 못 집으면 키를 쓰지
        // 않았다 — 새 필드에서는 그것이 곧 **켠 스위치가 말없이 꺼지는 것**이다(다시 열면
        // 꺼져 있다). 사유 줄은 떠 있었지만 저장은 아무 말도 하지 않았고, 사용자는 저장이
        // 됐다고 믿는다. 켜짐을 config에 남길 수는 없다 — 축 없는 약속은 어느 순위를 나눌지
        // 말하지 못해 실행할 수 없고, 반쯤 살아 있는 상태로 두면 화면이 그것을 켜진 것처럼
        // 그린다(`DuelGradeRef.fromConfig`가 그렇게 정해 둔 자리다).
        // 그래서 **버리는 대신 막고 사유를 말한다**(변수 제어: 검증 → 알림 → 교정 경로.
        // 교정 경로는 바로 그 자리의 [축 만들기]다). 창은 닫히지 않는다(R-27).
        if (binding.duelGradeLayout.visibility == View.VISIBLE && binding.switchDuelGrade.isChecked) {
            val problem = duelGradeProblem(binding)
            if (problem != null) {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.duel_grade_save_blocked, problem),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return false
            }
        }

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
                isRequired = isRequired,
                entityType = arguments?.getString(ARG_ENTITY_TYPE)
                    ?: FieldDefinition.ENTITY_CHARACTER
            )
        }

        // 생성 모드의 값 사전 등록분 — 필드 저장 후 FieldManageFragment가 라이브러리에 등재
        stagedInitialValues = if (existingField == null) {
            binding.editInitialValues.text.toString()
        } else ""

        // 전역 기본 필드 스위치(B-119) — [deliverResult]에는 binding이 없으므로 여기서 담는다
        // (`stagedInitialValues`와 같은 이유·같은 자리). 스위치를 열지 않은 호출부는 늘 false다.
        stagedDefaultField = allowDefaultField && binding.switchDefaultField.isChecked

        // 수식 검증 — 차단하지 않고 경고 (아직 만들지 않은 필드를 나중에 만드는 작업 순서 존중)
        if (selectedType == FieldType.CALCULATED) {
            val problems = validateFormula(binding.editFormula.text.toString().trim(), key)
            if (problems.isNotEmpty()) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.formula_warn_title)
                    .setMessage(getString(R.string.formula_warn_message, problems.joinToString("\n· ", prefix = "· ")))
                    .setPositiveButton(R.string.formula_warn_save_anyway) { _, _ ->
                        if (finishSave(field)) dismissAllowingStateLoss()
                    }
                    .setNegativeButton(R.string.formula_warn_fix, null)
                    .show()
                return false
            }
        }

        // 대결 등급 산정을 켰는데 그 축의 산출 필드가 아니면 함께 등재할지 묻는다(설계 4-2).
        // 연동을 두 곳에서 따로 관리하게 하지 않는다 — 등재해 두면 순위표가 이 필드의 어긋난
        // 자리를 짚어 주고, 그 고지가 곧 "반영할 때가 됐다"의 신호가 된다(4-3).
        if (promptDuelOutcomeRegistration(binding, field)) return false

        return finishSave(field)
    }

    /** 한 번 물었으면 다시 묻지 않는다 — 되묻기는 마찰이고, 사용자는 이미 답했다. */
    private var duelOutcomeAsked = false

    /** @return true면 물었다(저장은 답을 받은 뒤 이어진다 — 수식 경고와 같은 비동기 관례). */
    private fun promptDuelOutcomeRegistration(
        binding: DialogFieldEditBinding,
        field: FieldDefinition
    ): Boolean {
        if (duelOutcomeAsked || !binding.switchDuelGrade.isChecked) return false
        if (binding.duelGradeLayout.visibility != View.VISIBLE) return false
        val axis = selectedDuelAxis(binding) ?: return false
        if (axis.fieldLinks.outcomes.any { it.key == field.key }) return false
        duelOutcomeAsked = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.duel_grade_register_outcome_title)
            .setMessage(getString(R.string.duel_grade_register_outcome, axis.name))
            .setPositiveButton(R.string.duel_grade_register_outcome_yes) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                        // 축을 **다시 읽고** 얹는다 — 이 다이얼로그가 열려 있는 동안 축이 편집됐을
                        // 수 있고, 화면에 든 사본으로 덮으면 그사이의 연결이 사라진다.
                        val fresh = app.duelRepository.axisByCode(axis.code)
                        if (fresh != null && fresh.fieldLinks.outcomes.none { it.key == field.key }) {
                            app.duelRepository.saveAxis(
                                fresh.copy(
                                    outcomeFieldKeys = com.novelcharacter.app.util.DuelFieldLinks.encode(
                                        fresh.fieldLinks.outcomes +
                                            com.novelcharacter.app.util.DuelFieldLinks.Link(field.key)
                                    )
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("FieldEditDialog", "Failed to register duel outcome field", e)
                    }
                    if (!isAdded) return@launch
                    if (finishSave(field)) dismissAllowingStateLoss()
                }
            }
            .setNegativeButton(R.string.duel_grade_register_outcome_no) { _, _ ->
                if (finishSave(field)) dismissAllowingStateLoss()
            }
            .show()
        return true
    }

    /** 타입 변경 영향 분석(비동기) 또는 즉시 전달로 저장을 마무리한다. @return true면 다이얼로그를 닫아도 된다. */
    private fun finishSave(field: FieldDefinition): Boolean {
        val oldType = existingField?.type
        return if (existingField != null && oldType != null && oldType != field.type) {
            // 분석 중 재클릭으로 인한 중복 실행을 막고, 완료 시점에 checkTypeChangeImpact가 저장·닫기를 처리한다
            setSaveButtonEnabled(false)
            checkTypeChangeImpact(field, oldType, field.type)
            false
        } else {
            deliverResult(field) // 콜백이 저장을 거부하면(false) 다이얼로그를 유지한다
        }
    }

    /**
     * 수식의 잠재 문제 목록 (빈 리스트면 통과). 경고 용도이며 저장을 차단하지 않는다.
     *
     * 판정은 [FormulaValidator]가 하고 여기서는 문구만 입힌다 — 검증기는 평가기와 같은
     * 어휘 분석([FormulaLexer])을 보므로, 함수를 추가해도 검사가 뒤처지지 않는다.
     */
    private fun validateFormula(formula: String, currentKey: String): List<String> =
        FormulaValidator.validate(formula, currentKey, universeFieldKeys, calculatedFormulas)
            .map { problem ->
                when (problem) {
                    is FormulaValidator.Problem.UnbalancedParen ->
                        getString(R.string.formula_warn_paren)
                    is FormulaValidator.Problem.SelfReference ->
                        getString(R.string.formula_warn_self_ref, problem.key)
                    is FormulaValidator.Problem.CircularReference ->
                        getString(R.string.formula_warn_circular, problem.path.joinToString(" → "))
                    is FormulaValidator.Problem.UnknownKeys ->
                        getString(R.string.formula_warn_missing_keys, problem.keys.joinToString(", "))
                    is FormulaValidator.Problem.PaddedKeys ->
                        getString(R.string.formula_warn_padded_keys, problem.keys.joinToString(", ") { "\"$it\"" })
                    is FormulaValidator.Problem.UnknownFunctions ->
                        getString(
                            R.string.formula_warn_unknown_function,
                            problem.names.joinToString(", "),
                            FormulaLexer.KNOWN_NAMES.joinToString(", ")
                        )
                    is FormulaValidator.Problem.MalformedCalls ->
                        getString(R.string.formula_warn_malformed_call, problem.names.joinToString(", "))
                    is FormulaValidator.Problem.UnrecognizedText ->
                        getString(R.string.formula_warn_unrecognized, problem.fragments.joinToString(", ") { "\"$it\"" })
                }
            }

    /** 필드 설정에서 수식만 꺼낸다 (없거나 읽히지 않으면 null). */
    private fun formulaOf(def: FieldDefinition): String? = try {
        Gson().fromJson<Map<String, Any>>(def.config, Map::class.java)
            ?.get("formula") as? String
    } catch (_: Exception) { null }

    private fun checkTypeChangeImpact(field: FieldDefinition, oldType: String, newType: String) {
        val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
        val fieldValueDao = app.database.characterFieldValueDao()

        lifecycleScope.launch {
            try {
                val values = withContext(Dispatchers.IO) {
                    fieldValueDao.getValuesByFieldDef(field.id)
                }

                val nonEmptyValues = values.filter { it.value.isNotBlank() }
                if (nonEmptyValues.isEmpty()) {
                    // 기존 값이 없으면 바로 저장
                    completeSave(field)
                    return@launch
                }

                val compatible = nonEmptyValues.count { isValueCompatible(it.value, newType) }
                val incompatible = nonEmptyValues.size - compatible

                if (incompatible == 0) {
                    completeSave(field)
                    return@launch
                }

                val ctx = context ?: return@launch
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(getString(R.string.field_type_change_title))
                    .setMessage(getString(R.string.field_type_change_message,
                        oldType, newType, nonEmptyValues.size, compatible, incompatible))
                    .setPositiveButton(getString(R.string.field_type_change_proceed)) { _, _ ->
                        resetIncompatibleValuesAndSave(app, field, nonEmptyValues, newType)
                    }
                    .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                        setSaveButtonEnabled(true)
                    }
                    .setOnCancelListener { setSaveButtonEnabled(true) }
                    .show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("FieldEditDialog", "Type change impact check failed", e)
                context?.let { android.widget.Toast.makeText(it, R.string.save_failed, android.widget.Toast.LENGTH_SHORT).show() }
                setSaveButtonEnabled(true)
            }
        }
    }

    /** 호환 불가 값을 트랜잭션으로 일괄 초기화한 뒤 저장을 완결한다. 실패 시 다이얼로그를 유지하고 저장 버튼을 되살린다. */
    private fun resetIncompatibleValuesAndSave(
        app: com.novelcharacter.app.NovelCharacterApp,
        field: FieldDefinition,
        nonEmptyValues: List<CharacterFieldValue>,
        newType: String
    ) {
        val fieldValueDao = app.database.characterFieldValueDao()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 초기화가 중간에 끊겨 일부 값만 지워지는 일이 없도록 트랜잭션으로 묶는다
                app.database.withTransaction {
                    val toReset = nonEmptyValues.filter { !isValueCompatible(it.value, newType) }
                    toReset.forEach { fv ->
                        fieldValueDao.update(fv.copy(value = ""))
                    }
                }
                withContext(Dispatchers.Main) {
                    completeSave(field)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("FieldEditDialog", "Incompatible value reset failed", e)
                withContext(Dispatchers.Main) {
                    context?.let { android.widget.Toast.makeText(it, R.string.save_failed, android.widget.Toast.LENGTH_SHORT).show() }
                    setSaveButtonEnabled(true)
                }
            }
        }
    }

    /**
     * 판정 원문은 [com.novelcharacter.app.util.FieldTypeCompatibility]로 옮겼다 (B-119).
     * 전역 기본 필드의 전파 미리보기가 **같은 물음을 세계관마다** 던지므로, 두 벌로 두면
     * *"미리보기가 괜찮다고 한 전파가 값을 지우는"* 어긋남이 생긴다(R-7).
     */
    private fun isValueCompatible(value: String, newType: String): Boolean =
        com.novelcharacter.app.util.FieldTypeCompatibility.isValueCompatible(value, newType)

    /**
     * '전역 기본 필드' 스위치 (B-119 — 설계 1-4).
     *
     * 스위치가 말하는 것은 **"이 필드가 모든 세계관에 심기는가"**이고, 그 실행은 별도 표를
     * 건드리는 일이라 config가 아니라 [RESULT_DEFAULT_FIELD]로 나간다.
     *
     * 배너는 *이미 심긴 필드를 편집하는 경우*에만 뜬다 — **막지 않는다**(설계 1-3). 여기서
     * 고치면 그 세계관만 달라지고, 그 사실을 말해 주는 것이 배너의 몫이다.
     */
    private fun setupDefaultFieldSection(binding: DialogFieldEditBinding) {
        if (!allowDefaultField) {
            binding.defaultFieldLayout.visibility = View.GONE
            return
        }
        val config = existingField?.config ?: prefillField?.config
        val linkedCode = config?.let {
            com.novelcharacter.app.data.model.DefaultFieldRef.codeFromConfig(it)
        }
        binding.switchDefaultField.isChecked = linkedCode != null

        // 이름은 DB에 있으므로 비동기로 채운다 — 못 찾으면(템플릿이 이미 지워진 잔재)
        // 배너를 띄우지 않는다. 없는 것을 가리키는 안내는 안내가 아니다.
        if (linkedCode == null) return
        lifecycleScope.launch {
            try {
                val app = requireContext().applicationContext as com.novelcharacter.app.NovelCharacterApp
                val template = app.defaultFieldTemplateRepository.getByCode(linkedCode)
                if (template != null && isAdded) {
                    binding.textDefaultFieldBanner.text =
                        getString(R.string.default_field_banner, template.name)
                    binding.textDefaultFieldBanner.visibility = View.VISIBLE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("FieldEditDialog", "Failed to resolve default field template", e)
            }
        }
    }

    /** @return 결과가 수락되었으면 true. onSave 콜백이 false를 반환하면(예: 키 중복 거부) 다이얼로그를 유지해야 한다. */
    private fun deliverResult(field: FieldDefinition): Boolean {
        // Support both callback (for non-rotation case) and FragmentResult (survives rotation)
        val listener = onSave
        return if (listener != null) {
            listener(field)
        } else {
            if (isAdded) {
                setFragmentResult(RESULT_KEY, bundleOf(
                    RESULT_FIELD_JSON to Gson().toJson(field),
                    RESULT_INITIAL_VALUES to stagedInitialValues,
                    // 전역 기본 필드(B-119)는 config가 아니라 **별도 표**를 건드리는 조작이라
                    // 필드 JSON에 담기지 않는다. 스위치 상태를 그대로 넘기고, 승격·해제는
                    // 저장 뒤 호출부가 저장소를 통해 한다.
                    // 스위치를 열지 않은 호출부는 늘 false라 아무 일도 일어나지 않는다.
                    RESULT_DEFAULT_FIELD to stagedDefaultField
                ))
            }
            true
        }
    }

    /** 비동기 경로의 저장 완결 처리: 결과가 수락되면 다이얼로그를 닫고, 거부되면 저장 버튼을 되살린다. */
    private fun completeSave(field: FieldDefinition) {
        if (deliverResult(field)) {
            dismissAllowingStateLoss()
        } else {
            setSaveButtonEnabled(true)
        }
    }

    private fun setSaveButtonEnabled(enabled: Boolean) {
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
    }

    /** 등급 표 문제 → 화면 문구 (B-69) — 등급 체계 편집(U-1)과 공유하는 공용 매퍼를 쓴다. */
    private fun gradeProblemMessage(problem: com.novelcharacter.app.util.GradeTable.Problem): String =
        com.novelcharacter.app.ui.common.gradeProblemMessage(requireContext(), problem)

    private fun buildConfig(binding: DialogFieldEditBinding, type: FieldType): String {
        val config = mutableMapOf<String, Any>()

        // 필수 강도는 **항상 적는다** (B-90). 적지 않으면 그 필드는 계속
        // [RequiredEnforcement.legacyDefaultFor]에 걸려, 화면에서 고른 값과 실제 동작이 갈린다.
        config[RequiredEnforcement.CONFIG_KEY] =
            (if (binding.switchRequiredBlocksSave.isChecked) RequiredEnforcement.BLOCK
            else RequiredEnforcement.NOTIFY).key

        // Display format (TEXT, MULTI_TEXT only)
        if (type == FieldType.TEXT || type == FieldType.MULTI_TEXT) {
            val formats = DisplayFormat.entries
            val selectedFormat = formats[binding.spinnerDisplayFormat.selectedItemPosition]
            if (selectedFormat != DisplayFormat.PLAIN) {
                config["displayFormat"] = selectedFormat.key
            }
        }

        // 서술형 여부 (TEXT 전용). AUTO는 키를 쓰지 않는다 — 기본값과 명시적 자동을 구분할 이유가 없다.
        if (NarrativeMode.isEligibleType(type.name)) {
            val mode = NarrativeMode.entries[binding.spinnerNarrativeMode.selectedItemPosition]
            if (mode != NarrativeMode.AUTO) config["narrativeMode"] = mode.key
        }

        when (type) {
            FieldType.SELECT -> {
                val optionsText = binding.editSelectOptions.text.toString().trim()
                if (optionsText.isNotEmpty()) {
                    config["options"] = optionsText.split(",").map { it.trim() }
                }
            }
            FieldType.GRADE -> {
                // B-69: 화면의 행이 곧 등급 표 전부다. 종전의 "C/B/A/S 밖 키 보존"은 화면이
                // 그 키들을 보여 주지 못하던 시절의 보호막이었다 — 이제 전부 행으로 보이므로
                // 행을 지운 것은 의도된 삭제다. 검증은 saveField가 이미 마쳤다(문제 시 여기 안 온다).
                val outcome = com.novelcharacter.app.util.GradeTable.build(
                    gradeRows.map { it.editLabel.text.toString() to it.editValue.text.toString() }
                )
                config["grades"] = outcome.grades
                config["allowNegative"] = binding.switchAllowNegative.isChecked
                // 체계 참조 (U-1) — 실효 표(grades)와 함께 참조·재정의를 기록한다. 재정의는
                // 체계 기본과 다른 숫자만 남는다(GradeSystemRef가 규칙의 단일 소스).
                selectedGradeSystem?.let { system ->
                    val defaults = com.novelcharacter.app.data.model.GradeSystemRef
                        .gradesFromJson(system.gradesJson)
                    val overrides = com.novelcharacter.app.data.model.GradeSystemRef
                        .deriveOverrides(outcome.grades, defaults)
                    config[com.novelcharacter.app.data.model.GradeSystemRef.CONFIG_KEY] = system.code
                    if (overrides.isNotEmpty()) {
                        config[com.novelcharacter.app.data.model.GradeSystemRef.OVERRIDES_KEY] = overrides
                    }
                }
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
            // 섹션이 감춰진 종류에서는 어댑터가 없어 위치가 INVALID_POSITION(-1)이라 null로 떨어진다.
            val rolePos = binding.spinnerSemanticRole.selectedItemPosition
            val role = if (rolePos > 0 && rolePos - 1 < semanticRoleOptions.size)
                semanticRoleOptions[rolePos - 1] else null
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
                // 구조화 입력 경로에서도 Random config 적용
                val withRandom = if (type in listOf(FieldType.NUMBER, FieldType.SELECT, FieldType.GRADE)) {
                    com.novelcharacter.app.data.model.RandomConfig.applyToConfig(withStats, collectRandomConfig(binding, type))
                } else withStats
                val withBody = if (type == FieldType.BODY_SIZE) {
                    BodyAnalysisConfig.applyToConfig(withRandom, collectBodyAnalysisConfig(binding))
                } else withRandom
                return applyDuelGradeConfig(
                    binding, type,
                    applyAiAndDescriptionConfig(
                        binding, applyCardDisplayConfig(binding, applyInputModeConfig(binding, withBody))
                    )
                )
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
        // Random config (NUMBER, SELECT, GRADE)
        val withRandom = if (type == FieldType.NUMBER || type == FieldType.SELECT || type == FieldType.GRADE) {
            com.novelcharacter.app.data.model.RandomConfig.applyToConfig(withStats, collectRandomConfig(binding, type))
        } else withStats
        val withBody = if (type == FieldType.BODY_SIZE) {
            BodyAnalysisConfig.applyToConfig(withRandom, collectBodyAnalysisConfig(binding))
        } else withRandom
        return applyAiAndDescriptionConfig(
            binding, applyCardDisplayConfig(binding, applyInputModeConfig(binding, withBody))
        )
    }

    /**
     * 대결 등급 산정을 config에 기록한다 (B-113) — **두 buildConfig 출구가 공유한다.**
     *
     * 저장 직전에 컷을 **최종 등급 표에 다시 맞춘다.** 슬라이더는 등급 행의 글자 편집까지
     * 좇지 않으므로, 사용자가 라벨을 고쳐 놓고 바로 저장하면 컷이 유령 라벨을 가리킨 채
     * 저장될 수 있다 — 그러면 반영 직전 재검증이 막고, 사용자가 한 것은 이름 바꾸기뿐인데
     * 기능이 죽는다. 여기서 맞추면 **저장된 config는 언제나 검증을 통과한다.**
     *
     * 꺼져 있으면 키를 걷어낸다([DuelGradeRef.remove]) — 끈 것을 남겨 두면 다음에 켤 때
     * 옛 축이 되살아나고, 그 축은 그사이 지워졌을 수 있다.
     */
    private fun applyDuelGradeConfig(
        binding: DialogFieldEditBinding,
        type: FieldType,
        configJson: String
    ): String {
        val eligible = type == FieldType.GRADE && universeId != 0L &&
            currentEntityType() == FieldDefinition.ENTITY_CHARACTER
        if (!eligible || !binding.switchDuelGrade.isChecked) {
            return com.novelcharacter.app.data.model.DuelGradeRef.remove(configJson)
        }
        // **집을 수 없을 때는 지우지 않고 그대로 싣는다.**
        //
        // 축을 못 집는 경우는 둘이다 — 목록 조회가 실패했거나(위 `catch`가 삼킨다) 가리키던
        // 축이 지워졌거나. 어느 쪽이든 **사용자가 끈 것이 아니다.** 여기서 키를 지우면
        // *필드 이름만 고치고 저장해도* 세워 둔 컷이 말없이 사라진다(개발 의도 2번 — 어떤
        // 경우에도 데이터가 말없이 유실되지 않는다). 지워진 축은 휴지통에서 되살아날 수 있고,
        // 그때 컷이 남아 있으면 그대로 다시 들어맞는다. **끄는 것은 스위치가 하는 일이다.**
        // 등급이 둘 미만일 때도 같다 — 나눌 경계가 없다는 것은 컷을 버릴 이유가 아니다.
        val axis = selectedDuelAxis(binding)
        val labels = currentGradeLabels()
        if (axis == null || labels.size < 2) return keepStoredDuelGrade(configJson)
        val reconciled = com.novelcharacter.app.util.DuelGradeAssign.reconcile(
            duelGradeCuts.ifEmpty { com.novelcharacter.app.util.DuelGradeAssign.evenCuts(labels) },
            labels, labels
        )
        return com.novelcharacter.app.data.model.DuelGradeRef.write(
            configJson,
            com.novelcharacter.app.data.model.DuelGradeRef.Spec(
                axisCode = axis.code,
                cuts = reconciled.cuts,
                lastApplied = duelGradeLastApplied
            )
        )
    }

    /**
     * 저장된 대결 등급 산정 약속을 **그대로 옮겨 싣는다** — 화면이 그것을 판정할 수 없을 때.
     *
     * 새 필드거나 원래도 없었으면 걷어낸 결과와 같다(넣을 것이 없다).
     */
    private fun keepStoredDuelGrade(configJson: String): String {
        val stored = existingField?.config
            ?.let { com.novelcharacter.app.data.model.DuelGradeRef.fromConfig(it) }
            ?: return com.novelcharacter.app.data.model.DuelGradeRef.remove(configJson)
        return com.novelcharacter.app.data.model.DuelGradeRef.write(configJson, stored)
    }

    /**
     * 필드 설명(A-2)과 AI 추천 토글(A-1)을 config에 기록 — 두 buildConfig 출구가 공유한다.
     * 사건 필드는 설정이 노출되지 않고 스피너 기본 선택(전부)이 유지되므로 키가 남지 않는다(R-24).
     */
    private fun applyAiAndDescriptionConfig(binding: DialogFieldEditBinding, configJson: String): String {
        val withDescription = com.novelcharacter.app.data.model.FieldDescription.applyToConfig(
            configJson, binding.editFieldDescription.text?.toString().orEmpty()
        )
        val withSuggest = com.novelcharacter.app.data.model.FieldAiPolicy.applyMode(
            withDescription,
            com.novelcharacter.app.data.model.FieldAiPolicy.SuggestMode.entries
                .getOrElse(binding.spinnerAiSuggest.selectedItemPosition) {
                    com.novelcharacter.app.data.model.FieldAiPolicy.SuggestMode.DEFAULT
                }
        )
        return com.novelcharacter.app.data.model.FieldAiPolicy.applyImageTagVocabToConfig(
            withSuggest, binding.switchImageTagVocab.isChecked
        )
    }

    /**
     * 목록 카드 표시 여부를 config "cardDisplay"에 기록 (B-5).
     * 스위치를 노출하지 않는 캐릭터 필드는 기본값(표시)이 그대로 들어가고,
     * `applyToConfig`가 기본값일 때 키를 남기지 않으므로 config가 부풀지 않는다.
     */
    private fun applyCardDisplayConfig(binding: DialogFieldEditBinding, configJson: String): String {
        return com.novelcharacter.app.data.model.CardDisplayConfig.applyToConfig(
            configJson,
            com.novelcharacter.app.data.model.CardDisplayConfig(binding.switchCardDisplay.isChecked)
        )
    }

    /** 입력 모드(제안/자유/제한)를 config "valueLibrary"에 기록 */
    private fun applyInputModeConfig(binding: DialogFieldEditBinding, configJson: String): String {
        val modes = com.novelcharacter.app.data.model.FieldValueLibraryConfig.MODES
        val mode = modes.getOrElse(binding.spinnerInputMode.selectedItemPosition) { modes[0] }
        return com.novelcharacter.app.data.model.FieldValueLibraryConfig.applyToConfig(
            configJson, com.novelcharacter.app.data.model.FieldValueLibraryConfig(mode)
        )
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

        val statsGroupBy = when (binding.spinnerStatsGroupBy.selectedItemPosition) {
            1 -> "category"
            2 -> "both"
            else -> "value"
        }

        // valueLabels/valueCategories는 값 데이터 라이브러리가 단일 소스 — config에는 더 이상 쓰지 않는다.
        // (구버전 엑셀의 config는 임포트 시 라이브러리로 자동 이관됨)
        return FieldStatsConfig(enabled, analyses, binning, emptyMap(), emptyMap(), statsGroupBy)
    }

    companion object {
        const val RESULT_KEY = "field_edit_result"
        const val RESULT_FIELD_JSON = "field_json"
        const val RESULT_INITIAL_VALUES = "initial_values"
        /** 저장 시점의 '전역 기본 필드' 스위치 상태 (B-119). 스위치를 열지 않은 호출부는 늘 false다. */
        const val RESULT_DEFAULT_FIELD = "default_field"
        private const val ARG_UNIVERSE_ID = "universeId"
        private const val ARG_FIELD_JSON = "fieldJson"
        private const val ARG_ENTITY_TYPE = "entityType"
        private const val ARG_PREFILL_JSON = "prefillJson"
        private const val ARG_ALLOW_DEFAULT_FIELD = "allowDefaultField"

        /** 대결 등급 컷 스테퍼 한 칸 — 목업이 정한 정밀 경로의 눈금이다(B-113). */
        private const val STEP_PERCENT = 0.5

        /**
         * @param allowDefaultField '전역 기본 필드' 스위치를 보일 것인가 (B-119).
         *
         * **기본이 false인 것이 요점이다.** 이 다이얼로그는 필드 관리 말고도 여러 자리에서
         * 열린다 — 프리셋 필드 편집(`universeId = 0`)·사건 편집의 즉석 필드 생성처럼
         * *저장 뒤 저장소를 부르지 않는* 경로가 있다. 거기서 스위치를 보이면 **켜도 아무 일도
         * 일어나지 않는 조작**이 되고, 그것은 조용한 무동작이라 변수 제어(개발 의도 2번)에
         * 어긋난다. 그래서 켜는 쪽이 *"나는 이 결과를 처리한다"*고 밝히게 한다.
         */
        fun newInstance(
            universeId: Long,
            field: FieldDefinition?,
            entityType: String = FieldDefinition.ENTITY_CHARACTER,
            prefill: FieldDefinition? = null,
            allowDefaultField: Boolean = false
        ): FieldEditDialog {
            return FieldEditDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_UNIVERSE_ID, universeId)
                    putBoolean(ARG_ALLOW_DEFAULT_FIELD, allowDefaultField)
                    putString(ARG_ENTITY_TYPE, field?.entityType ?: prefill?.entityType ?: entityType)
                    if (field != null) {
                        putString(ARG_FIELD_JSON, Gson().toJson(field))
                    } else if (prefill != null) {
                        // 생성 모드를 유지한 채 폼만 채운다 — 값 사전 등록이 살아 있어야 하기 때문이다.
                        putString(ARG_PREFILL_JSON, Gson().toJson(prefill.copy(id = 0)))
                    }
                }
            }
        }
    }
}
