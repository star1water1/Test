package com.novelcharacter.app.ui.character

import android.app.Dialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.databinding.BottomSheetBodySilhouetteEditorBinding
import com.novelcharacter.app.util.BodyEditorModel
import com.novelcharacter.app.util.BodyGenerator
import com.novelcharacter.app.util.BodyGenerator.BodyCategory
import com.novelcharacter.app.util.BodyMeasurements
import com.novelcharacter.app.util.BodySilhouetteSpec
import com.novelcharacter.app.util.BodySilhouetteSpec.Measures

/**
 * 실루엣 편집기 (설계 `docs/body_visual_redesign_2026-07.md` 5-2 · 5-4-4).
 *
 * 하나의 수치 상태를 **세 조작 경로가 공유한다** — 실루엣 핸들 · 슬라이더 · 수치 칸.
 * 셋은 같은 [current]를 고쳐 쓰고 [syncAll]이 나머지 둘을 따라 그린다(3자 동기).
 * 여기에 🎲 패널이 네 번째 경로로 붙는다(러프 → 정밀의 이중 경로 — 원칙 03·04).
 *
 * **쓰기 대상은 폼 위젯뿐이다**(5-2). 저장·검증·보존 고지는 기존 저장 파이프라인 그대로이며,
 * [적용]을 누르기 전에는 무엇도 폼에 닿지 않는다 — 시트를 굴리다 닫아도 잃는 것이 없다.
 *
 * 계산은 하지 않는다: 그림은 [BodySilhouetteSpec], 범위·되쓰기·평균은 [BodyEditorModel],
 * 생성은 [BodyGenerator]가 든다. 여기 수식을 새로 적으면 증명할 수단이 없다.
 */
class BodySilhouetteEditorSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBodySilhouetteEditorBinding? = null
    private val binding get() = _binding!!

    // ── 호스트가 채우는 입력 ────────────────────────────────────────────────

    /** 편집을 시작할 수치(폼 위젯에서 해석한 것). */
    var initial: BodyMeasurements = BodyMeasurements(
        values = emptyMap(), mode = BodyMeasurements.MappingMode.NONE,
        partSlots = emptyList(), partValues = emptyList(), unmappedParts = emptyList()
    )

    /** 되쓸 자리 — 파트 인덱스 순. [BodyEditorModel.writableSlots]가 정한다. */
    var writableSlots: List<BodySlot> = emptyList()

    /** 파트 원문(되쓰지 않는 칸을 그대로 돌려주기 위해 든다). */
    var partValues: List<String> = emptyList()

    /** 키·체중 필드가 폼에 있는가. 없으면 그 행을 만들지 않고 사실을 고지한다(5-2). */
    var hasHeightField: Boolean = false
    var hasWeightField: Boolean = false

    /** 위치 폴백으로 자리를 정했는가 — 그렇다면 그 사실을 화면이 말한다. */
    var positionalFallback: Boolean = false

    /** 열자마자 🎲 패널을 펼칠 것인가(폼의 🎲 버튼으로 들어온 경우 — 설계 5-2). */
    var openWithGenerator: Boolean = false

    var analysisConfig: BodyAnalysisConfig = BodyAnalysisConfig.DEFAULT

    /** 같은 작품 캐릭터 — 상대 생성·분포·작품 평균 오버레이의 재료다. */
    private var novelCharacters: List<Character> = emptyList()
    private var peerMeasurements: Map<Long, BodyMeasurements> = emptyMap()

    /**
     * 작품 캐릭터 수치를 붙인다. **시트가 열린 뒤에 도착해도 된다** — 조회를 기다리느라
     * 여는 것이 늦어지지 않게 호스트가 비동기로 부르며, 이미 그려진 뒤라면 그 자리에서
     * 상대 생성·분포·평균 오버레이를 마저 세운다(도착 시점에 따라 기능이 사라지지 않게).
     */
    fun setPeers(characters: List<Character>, measurements: Map<Long, BodyMeasurements>) {
        novelCharacters = characters
        peerMeasurements = measurements
        if (_binding == null) return
        setupRelative(resources.displayMetrics.density)
        showDistribution()
        setupOverlayToggle()
    }

    /**
     * [적용]이 돌려주는 것 — 파트 원문 목록과 키·체중(폼에 필드가 있을 때만 non-null).
     * 호스트는 이것을 폼 위젯에 적기만 한다.
     */
    var onApply: ((parts: List<String>, heightCm: Double?, weightKg: Double?) -> Unit)? = null

    // ── 편집 상태 ───────────────────────────────────────────────────────────

    private lateinit var start: Measures
    private lateinit var current: Measures
    private var startHeight: Double? = null
    private var startWeight: Double? = null
    private var weightKg: Double? = null

    /** 🎲 [직전으로] 한 단계 — 굴리기 연타 비교용(5-4-4). */
    private var previousRoll: Pair<Measures, Double?>? = null

    private val sliders = mutableMapOf<BodySlot, Slider>()
    private val valueFields = mutableMapOf<BodySlot, TextInputEditText>()
    private var heightSlider: Slider? = null
    private var heightField: TextInputEditText? = null
    private var weightSlider: Slider? = null
    private var weightField: TextInputEditText? = null

    /** 되먹임 억제 — 슬라이더가 칸을 고치고 칸이 다시 슬라이더를 고치는 고리를 끊는다. */
    private var syncing = false

    /**
     * 사용자가 이 자리에서 만든 값 — **비어 있던 칸에 무엇을 적어도 되는가**의 기준이다.
     *
     * 편집기는 값이 없어도 기준 몸을 띄우므로(초안), 그 초안을 그대로 되쓰면 사용자가
     * 적은 적 없는 수치가 빈 칸에 들어간다. 그래서 되쓰는 것은 **원래 있던 값과 여기
     * 담긴 값뿐**이다(개발 의도 2번 — 만들지 않은 데이터를 만들어 넣지 않는다).
     */
    private val touched = mutableSetOf<BodySlot>()

    private val generatorOptions = BodyGenerator.GenerationPreset()
    private var cupMode = false
    private var cupSizeDiffs: List<Pair<String, Double>> = emptyList()
    private var overlayOn = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetBodySilhouetteEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        // 시트를 끌어내리는 제스처는 끈다 — 실루엣 드래그와 충돌한다(5-4-4).
        // 닫기는 ✕·취소·뒤로가기로만 하며, 그 셋은 전부 변경 확인을 거친다.
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.layoutParams = it.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                BottomSheetBehavior.from(it).apply {
                    state = BottomSheetBehavior.STATE_EXPANDED
                    isDraggable = false
                    skipCollapsed = true
                }
            }
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                closeWithConfirm(); true
            } else false
        }
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        start = BodyEditorModel.draftMeasures(initial)
        current = start
        startHeight = initial.heightCm
        startWeight = initial.weightKg
        weightKg = initial.weightKg

        setupSilhouette()
        buildRows()
        setupGeneratorPanel()
        setupNotices()

        binding.btnClose.setOnClickListener { closeWithConfirm() }
        binding.btnCancel.setOnClickListener { closeWithConfirm() }
        binding.btnApply.setOnClickListener { applyAndDismiss() }

        syncAll(from = null)
    }

    // ── 실루엣 ──────────────────────────────────────────────────────────────

    private fun setupSilhouette() {
        binding.silhouette.apply {
            measures = current
            interactive = true
            showLabels = true
            onHandleDrag = { slot, value ->
                current = withSlot(current, slot, value)
                touched.add(slot)
                syncAll(from = SyncSource.HANDLE)
            }
            onHandleDragEnd = { syncAll(from = null) }
        }

        setupOverlayToggle()

        binding.btnToggleSide.setOnClickListener {
            val toSide = binding.silhouette.facing == SilhouetteView.Facing.FRONT
            binding.silhouette.facing =
                if (toSide) SilhouetteView.Facing.SIDE else SilhouetteView.Facing.FRONT
            // 측면은 표시 전용이다(P10) — 핸들이 없으므로 라벨만 남는다.
            binding.silhouette.interactive = !toSide
            binding.btnToggleSide.setText(if (toSide) R.string.silhouette_view_front else R.string.silhouette_view_side)
        }
    }

    /** 작품 평균 오버레이 토글 — 재료(작품 캐릭터 수치)가 있을 때만 존재한다(P3 · 기본 끔). */
    private fun setupOverlayToggle() {
        val average = BodyEditorModel.peerAverage(peerMeasurements.values.toList())
        if (average == null) {
            // 켜도 아무것도 안 나타나는 토글은 구색이다 — 재료가 없으면 내놓지 않는다.
            binding.btnToggleOverlay.visibility = View.GONE
            return
        }
        binding.btnToggleOverlay.visibility = View.VISIBLE
        binding.btnToggleOverlay.alpha = if (overlayOn) 1f else .6f
        binding.silhouette.overlayMeasures = if (overlayOn) average else null
        binding.btnToggleOverlay.setOnClickListener {
            overlayOn = !overlayOn
            binding.silhouette.overlayMeasures = if (overlayOn) average else null
            binding.btnToggleOverlay.alpha = if (overlayOn) 1f else .6f
        }
    }

    // ── 부위 행 (슬라이더 + 수치 칸) ────────────────────────────────────────

    private fun buildRows() {
        val density = resources.displayMetrics.density
        val container = binding.rowsContainer
        for (slot in BodyEditorModel.EDITABLE_SLOTS) {
            // 밑가슴은 되쓸 자리가 있을 때만 — 없는 칸에 적은 값은 갈 곳이 없다.
            if (slot == BodySlot.UNDERBUST && slot !in writableSlots) continue
            container.addView(buildRow(density, slotName(slot), BodyEditorModel.sliderRange(slot, current.height)) { s, f ->
                sliders[slot] = s; valueFields[slot] = f
            })
        }
        if (hasHeightField) {
            container.addView(
                buildRow(density, getString(R.string.silhouette_row_height), BodyEditorModel.HEIGHT_RANGE) { s, f ->
                    heightSlider = s; heightField = f
                }
            )
        }
        if (hasWeightField) {
            container.addView(
                buildRow(density, getString(R.string.silhouette_row_weight), BodyEditorModel.WEIGHT_RANGE) { s, f ->
                    weightSlider = s; weightField = f
                }
            )
        }
    }

    private fun buildRow(
        density: Float,
        label: String,
        range: ClosedFloatingPointRange<Double>,
        bind: (Slider, TextInputEditText) -> Unit
    ): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            setTextAppearance(R.style.TextAppearance_App_BodySmall)
            layoutParams = LinearLayout.LayoutParams((56 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val slider = Slider(requireContext()).apply {
            valueFrom = range.start.toFloat()
            valueTo = range.endInclusive.toFloat()
            stepSize = 0f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(slider)
        val inputLayout = TextInputLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams((84 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val field = TextInputEditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine()
        }
        inputLayout.addView(field)
        row.addView(inputLayout)
        bind(slider, field)

        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || syncing) return@addOnChangeListener
            onValueEdited(slider, value.toDouble(), SyncSource.SLIDER)
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (syncing) return
                // 수치 칸은 슬라이더 범위 밖도 받는다 — 극단값은 유효 입력이다(5-4-4).
                val v = s?.toString()?.toDoubleOrNull() ?: return
                onValueEdited(slider, v, SyncSource.FIELD)
            }
        })
        return row
    }

    /** 어느 행이 바뀌었는지 위젯 신원으로 되찾는다 — 행마다 콜백을 복제하지 않기 위해서다. */
    private fun onValueEdited(slider: Slider, value: Double, source: SyncSource) {
        if (value <= 0) return
        when {
            slider === heightSlider -> current = current.copy(height = value, heightKnown = true)
            slider === weightSlider -> weightKg = value
            else -> {
                val slot = sliders.entries.firstOrNull { it.value === slider }?.key ?: return
                current = withSlot(current, slot, value)
                touched.add(slot)
            }
        }
        syncAll(from = source)
    }

    private fun withSlot(m: Measures, slot: BodySlot, value: Double): Measures = when (slot) {
        BodySlot.SHOULDER -> m.copy(shoulder = value)
        BodySlot.BUST -> m.copy(bust = value)
        BodySlot.WAIST -> m.copy(waist = value)
        BodySlot.HIP -> m.copy(hip = value)
        BodySlot.UNDERBUST -> m.copy(underbust = value)
        BodySlot.NONE -> m
    }

    private enum class SyncSource { HANDLE, SLIDER, FIELD }

    /**
     * 세 경로를 같은 값으로 맞춘다. [from]이 가리키는 경로는 사용자가 지금 만지는 중이라
     * 건드리지 않는다(수치 칸을 다시 쓰면 커서가 튀고, 슬라이더는 손가락과 싸운다).
     */
    private fun syncAll(from: SyncSource?) {
        syncing = true
        val values = BodyEditorModel.valuesOf(current)
        for ((slot, slider) in sliders) {
            // 밑가슴은 실측이 없으면 그림이 쓰는 근사를 보여 준다 — 허리를 따라 움직이다가
            // 사용자가 만지는 순간 그 값으로 고정된다(실측이 이긴다 — 설계 3-1).
            val v = values[slot]
                ?: (if (slot == BodySlot.UNDERBUST) BodySilhouetteSpec.figureUnderbust(current) else null)
                ?: continue
            if (from != SyncSource.SLIDER) slider.value = v.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
            if (from != SyncSource.FIELD) valueFields[slot]?.setText(BodyEditorModel.formatValue(v))
        }
        heightSlider?.let { s ->
            if (from != SyncSource.SLIDER) s.value = current.height.toFloat().coerceIn(s.valueFrom, s.valueTo)
            if (from != SyncSource.FIELD) heightField?.setText(BodyEditorModel.formatValue(current.height))
        }
        weightSlider?.let { s ->
            val w = weightKg
            if (w != null) {
                if (from != SyncSource.SLIDER) s.value = w.toFloat().coerceIn(s.valueFrom, s.valueTo)
                if (from != SyncSource.FIELD) weightField?.setText(BodyEditorModel.formatValue(w))
            }
        }
        if (from != SyncSource.HANDLE) binding.silhouette.measures = current
        else binding.silhouette.invalidate()

        val summary = BodySilhouetteSpec.axisSummary(current)
        binding.axisSummary.text =
            getString(R.string.silhouette_axis_summary, summary.torso, summary.cup, summary.hip, summary.line)
        binding.clampCaption.visibility = if (binding.silhouette.isClamped) View.VISIBLE else View.GONE
        binding.btnApply.isEnabled = hasChanges()
        syncing = false
    }

    private fun hasChanges(): Boolean =
        current != start || weightKg != startWeight || (hasHeightField && current.height != startHeight)

    // ── 고지 ────────────────────────────────────────────────────────────────

    private fun setupNotices() {
        val notices = buildList {
            if (positionalFallback) add(getString(R.string.silhouette_editor_positional_notice))
            if (!hasHeightField && !hasWeightField) add(getString(R.string.silhouette_editor_no_body_field))
        }
        if (notices.isEmpty()) return
        binding.mappingNotice.text = notices.joinToString("\n")
        binding.mappingNotice.visibility = View.VISIBLE
    }

    // ── 🎲 패널 ─────────────────────────────────────────────────────────────

    private fun setupGeneratorPanel() {
        val density = resources.displayMetrics.density
        buildPresetRow(density)
        buildAxisGroup(binding.rgHeight, generatorOptions.heightOptions.map { it.label }, checked = 1, density, anyOption = true)
        buildAxisGroup(binding.rgTorso, generatorOptions.torsoOptions.map { it.label }, checked = 1, density)
        buildAxisGroup(binding.rgBust, generatorOptions.bustOptions.map { it.label }, checked = 1, density)
        buildAxisGroup(binding.rgHip, generatorOptions.hipOptions.map { it.label }, checked = 1, density)
        buildCupOptions(density)
        setupRelative(density)
        showDistribution()

        binding.generatorHeader.setOnClickListener { toggleGeneratorPanel() }
        binding.btnRoll.setOnClickListener { roll() }
        binding.btnUndoRoll.setOnClickListener { undoRoll() }
        if (openWithGenerator) toggleGeneratorPanel()
    }

    private fun toggleGeneratorPanel() {
        val open = binding.generatorPanel.visibility != View.VISIBLE
        binding.generatorPanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.generatorChevron.setImageResource(
            if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun buildPresetRow(density: Float) {
        for ((i, preset) in generatorOptions.bodyPresets.withIndex()) {
            val btn = MaterialButton(
                requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = preset.label
                minWidth = 0; minimumWidth = 0
                setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (6 * density).toInt() }
                setOnClickListener { applyPreset(i) }
            }
            binding.presetRow.addView(btn)
        }
    }

    /** 프리셋은 세 축 셀렉터를 움직인다 — 러프 경로가 정밀 경로를 **덮지 않고 세운다**(원칙 04). */
    private fun applyPreset(index: Int) {
        val preset = generatorOptions.bodyPresets.getOrNull(index) ?: return
        check(binding.rgTorso, preset.torso)
        check(binding.rgBust, preset.bust)
        check(binding.rgHip, preset.hip)
        if (cupMode) {
            cupMode = false
            binding.switchCupMode.isChecked = false
        }
        roll()
    }

    private fun buildAxisGroup(
        group: RadioGroup, labels: List<String>, checked: Int, density: Float, anyOption: Boolean = false
    ) {
        if (anyOption) {
            group.addView(RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = getString(R.string.body_gen_any)
                textSize = 12f
                tag = -1
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            })
        }
        for ((i, label) in labels.withIndex()) {
            val rb = RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = label
                textSize = 12f
                tag = i
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            group.addView(rb)
            if (i == checked) rb.isChecked = true
        }
    }

    private fun buildCupOptions(density: Float) {
        val mapping = analysisConfig.cupMapping.filter { it.maxDiff < 900 }.sortedBy { it.maxDiff }
        var prevMax = 0.0
        cupSizeDiffs = mapping.map { entry ->
            val mid = (prevMax + entry.maxDiff) / 2.0
            prevMax = entry.maxDiff
            entry.label to mid
        }
        binding.rgCupSize.addView(RadioButton(requireContext()).apply {
            id = View.generateViewId()
            text = getString(R.string.body_gen_cup_none)
            textSize = 12f
            tag = -1
            isChecked = true
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        })
        for ((i, pair) in cupSizeDiffs.withIndex()) {
            binding.rgCupSize.addView(RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = pair.first
                textSize = 12f
                tag = i
                setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            })
        }
        binding.switchCupMode.setOnCheckedChangeListener { _, isChecked ->
            cupMode = isChecked
            binding.cupSizeContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            // 컵을 직접 지정하면 가슴 축은 할 일이 없다 — 두 경로가 동시에 살아 있으면 어느 쪽이 이겼는지 모른다.
            binding.rgBust.alpha = if (isChecked) .4f else 1f
            for (i in 0 until binding.rgBust.childCount) binding.rgBust.getChildAt(i).isEnabled = !isChecked
        }
    }

    private var relativeBuilt = false

    private fun setupRelative(density: Float) {
        // 재료가 늦게 도착하면 한 번 더 불린다 — 두 번 세우면 라디오가 두 벌이 된다.
        if (novelCharacters.isEmpty() || relativeBuilt) return
        relativeBuilt = true
        binding.relativeLabel.visibility = View.VISIBLE
        binding.spinnerBaseCharacter.visibility = View.VISIBLE
        val names = mutableListOf(getString(R.string.body_gen_no_base))
        names.addAll(novelCharacters.map { it.name })
        binding.spinnerBaseCharacter.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, names
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.spinnerBaseCharacter.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    binding.relativeOptionsContainer.visibility = if (pos > 0) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        for ((i, entry) in BodyGenerator.RELATIVE_MULTIPLIERS.withIndex()) {
            for (group in listOf(binding.rgRelativeHeight, binding.rgRelativeVolume)) {
                val rb = RadioButton(requireContext()).apply {
                    id = View.generateViewId(); text = entry.first; textSize = 11f; tag = i
                    setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)
                }
                group.addView(rb)
                if (i == 2) rb.isChecked = true
            }
        }
    }

    private fun showDistribution() {
        if (peerMeasurements.isEmpty()) return
        var slim = 0; var normal = 0; var voluptuous = 0
        for (m in peerMeasurements.values) {
            val b = m.bust; val w = m.waist; val h = m.hip
            if (b == null || w == null || h == null) continue
            when (BodyGenerator.categorize(b, w, h, m.heightCm)) {
                BodyCategory.SLIM -> slim++
                BodyCategory.NORMAL -> normal++
                BodyCategory.VOLUPTUOUS -> voluptuous++
            }
        }
        if (slim + normal + voluptuous == 0) return
        val summary = BodyGenerator.DistributionSummary(slim, normal, voluptuous, slim + normal + voluptuous)
        val recLabel = when (summary.recommendation) {
            BodyCategory.SLIM -> getString(R.string.body_gen_cat_slim)
            BodyCategory.NORMAL -> getString(R.string.body_gen_cat_normal)
            BodyCategory.VOLUPTUOUS -> getString(R.string.body_gen_cat_voluptuous)
            null -> ""
        }
        binding.distributionText.text = getString(
            R.string.body_gen_distribution, voluptuous, normal, slim,
            if (recLabel.isEmpty()) "" else " ← $recLabel ${getString(R.string.body_gen_recommend)}"
        )
        binding.distributionText.visibility = View.VISIBLE
    }

    private fun roll() {
        // 생성이 실패하면(기준 캐릭터에 수치가 없는 상대 생성) 되돌릴 자리도 만들지 않는다.
        val body = generateBody() ?: return
        previousRoll = current to weightKg
        current = current.copy(
            height = body.height,
            bust = body.bust,
            waist = body.waist,
            hip = body.hip,
            heightKnown = true,
            // 굴린 수치는 전부 실측 자리에 앉는다 — 추정 표시(점선·비활성)를 남기면 만질 수 없다.
            estimated = emptySet()
        )
        weightKg = body.weight
        touched.addAll(listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP))
        rebuildSliderRanges()
        binding.btnUndoRoll.isEnabled = true
        syncAll(from = null)
    }

    private fun undoRoll() {
        val (m, w) = previousRoll ?: return
        current = m
        weightKg = w
        previousRoll = null
        rebuildSliderRanges()
        binding.btnUndoRoll.isEnabled = false
        syncAll(from = null)
    }

    /**
     * 슬라이더 범위는 키에 연동돼 있다 — 키가 바뀌면 함께 다시 잡는다.
     *
     * 순서가 중요하다: `Slider`는 현재 값이 새 범위 밖이면 **예외로 죽는다.** 그래서
     * 옛 범위와 새 범위의 합집합으로 먼저 넓히고, 값을 새 범위 안으로 옮긴 뒤 좁힌다.
     */
    private fun rebuildSliderRanges() {
        val values = BodyEditorModel.valuesOf(current)
        for ((slot, slider) in sliders) {
            val range = BodyEditorModel.sliderRange(slot, current.height)
            val lo = range.start.toFloat()
            val hi = range.endInclusive.toFloat()
            val target = (values[slot] ?: BodySilhouetteSpec.figureUnderbust(current)).toFloat()
            slider.valueFrom = minOf(slider.valueFrom, lo)
            slider.valueTo = maxOf(slider.valueTo, hi)
            slider.value = target.coerceIn(lo, hi)
            slider.valueFrom = lo
            slider.valueTo = hi
        }
    }

    private fun generateBody(): BodyGenerator.GeneratedBody? {
        val basePos = binding.spinnerBaseCharacter.selectedItemPosition
        if (basePos > 0 && basePos - 1 < novelCharacters.size) {
            val base = novelCharacters[basePos - 1]
            val bm = peerMeasurements[base.id] ?: return null
            val bust = bm.bust; val waist = bm.waist; val hip = bm.hip
            if (bust == null || waist == null || hip == null) return null
            val hMul = BodyGenerator.RELATIVE_MULTIPLIERS.getOrNull(selectedIndex(binding.rgRelativeHeight))?.second ?: 1.0
            val vMul = BodyGenerator.RELATIVE_MULTIPLIERS.getOrNull(selectedIndex(binding.rgRelativeVolume))?.second ?: 1.0
            return BodyGenerator.generateRelative(
                baseHeight = bm.heightCm ?: current.height,
                baseWaist = waist, baseBust = bust, baseHip = hip,
                baseWeight = bm.weightKg ?: 55.0,
                heightMultiplier = hMul, volumeMultiplier = vMul
            )
        }
        val hIdx = selectedIndex(binding.rgHeight)
        val heightOption = if (hIdx < 0) generatorOptions.heightOptions.random()
        else generatorOptions.heightOptions.getOrNull(hIdx) ?: generatorOptions.heightOptions[1]
        val cupDiff = if (cupMode) cupSizeDiffs.getOrNull(selectedIndex(binding.rgCupSize))?.second else null
        return BodyGenerator.generate(
            heightOption,
            generatorOptions.torsoOptions.getOrNull(selectedIndex(binding.rgTorso)) ?: generatorOptions.torsoOptions[1],
            generatorOptions.bustOptions.getOrNull(selectedIndex(binding.rgBust)) ?: generatorOptions.bustOptions[1],
            generatorOptions.hipOptions.getOrNull(selectedIndex(binding.rgHip)) ?: generatorOptions.hipOptions[1],
            cupDiff,
            analysisConfig.ribOffset
        )
    }

    private fun selectedIndex(group: RadioGroup): Int {
        val checkedId = group.checkedRadioButtonId
        if (checkedId == -1) return 0
        return (group.findViewById<RadioButton>(checkedId)?.tag as? Int) ?: 0
    }

    private fun check(group: RadioGroup, index: Int) {
        for (i in 0 until group.childCount) {
            val rb = group.getChildAt(i) as? RadioButton ?: continue
            if ((rb.tag as? Int) == index) { rb.isChecked = true; return }
        }
    }

    // ── 닫기·적용 ───────────────────────────────────────────────────────────

    /** 변경이 있으면 묻고 닫는다 — 끌어 만든 몸이 뒤로가기 한 번에 사라지지 않게(R-27 결). */
    private fun closeWithConfirm() {
        if (!hasChanges()) { dismiss(); return }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.silhouette_editor_discard)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.silhouette_editor_discard_confirm) { _, _ -> dismiss() }
            .show()
    }

    private fun applyAndDismiss() {
        // 원래 있던 값과 이 자리에서 만든 값만 되쓴다 — 초안이 빈 칸을 채우지 않게.
        val values = BodyEditorModel.valuesOf(current)
            .filterKeys { it in touched || initial.values.containsKey(it) }
        val parts = BodyEditorModel.writeBack(writableSlots, partValues, values)
        onApply?.invoke(
            parts,
            if (hasHeightField) current.height else null,
            if (hasWeightField) weightKg else null
        )
        dismiss()
    }

    private fun slotName(slot: BodySlot): String = getString(
        when (slot) {
            BodySlot.SHOULDER -> R.string.silhouette_slot_shoulder
            BodySlot.BUST -> R.string.silhouette_slot_bust
            BodySlot.UNDERBUST -> R.string.silhouette_slot_underbust
            BodySlot.WAIST -> R.string.silhouette_slot_waist
            else -> R.string.silhouette_slot_hip
        }
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "body_silhouette_editor"
    }
}
