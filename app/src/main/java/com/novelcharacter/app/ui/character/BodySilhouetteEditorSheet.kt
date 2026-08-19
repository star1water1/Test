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
import com.novelcharacter.app.util.BodyEditorState
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
    //
    // ⚠️ 여기 `var`를 더하면 **호스트의 한 자리에도 함께 등재한다** —
    // `DynamicFieldFormBuilder.bindSilhouetteEditor`. 그 함수가 처음 열 때와 회전 뒤 다시 꽂을
    // 때 **둘 다**를 든다(R-38·R-41). 한쪽만 채우면 회전 뒤에만 비는 칸이 생기고, 그것은
    // 실기기에서만 보인다. `tools/check_silhouette_rebind.sh`가 이 등재를 기계로 본다.

    /** 어느 BODY_SIZE 필드의 편집기인가 — 회전 뒤 호스트가 다시 꽂을 자리를 이것으로 찾는다. */
    var bodySizeFieldId: Long = 0L

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

    /**
     * 되쓸 자리가 하나도 없는가 — 설정에서 모든 칸을 '사용 안 함'으로 정하면 그렇게 된다.
     * 편집기는 열리되(그림은 볼 수 있다) 적용이 아무 데도 닿지 않으므로 그 사실을 먼저 말한다.
     */
    var noWritableSlot: Boolean = false

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
        // 아직 안 그렸으면 재료만 받아 둔다 — [buildEditor]가 그때 함께 세운다.
        if (_binding == null || !built) return
        setupRelative(resources.displayMetrics.density)
        showDistribution()
        setupOverlayToggle()
        // 상대 생성 줄은 **재료가 와야 비로소 선다** — 복원 시점에는 없던 라디오라
        // 그때 고른 자리를 여기서 다시 얹는다(안 얹으면 기준 배수가 기본값으로 돌아간다).
        //
        // **한 번만 얹는다.** 재료는 폼이 다시 설 때마다 다시 조회되므로 이 함수는 여러 번
        // 불릴 수 있는데, 그때마다 얹으면 **복원 뒤에 사용자가 새로 고른 배수를 옛 값이
        // 덮는다** — 되살리려고 만든 코드가 방금 고른 것을 지우는 자리다.
        pendingRelativeSelection?.let { (heightIndex, volumeIndex) ->
            check(binding.rgRelativeHeight, heightIndex)
            check(binding.rgRelativeVolume, volumeIndex)
            pendingRelativeSelection = null
        }
        applyPendingBaseCharacter()
    }

    /**
     * [적용]이 돌려주는 것 — 파트 원문 목록과 키·체중(폼에 필드가 있을 때만 non-null).
     * 호스트는 이것을 폼 위젯에 적기만 한다.
     */
    var onApply: ((parts: List<String>, heightCm: Double?, weightKg: Double?) -> Unit)? = null

    /**
     * 🎲 축·프리셋을 **세계관 설정에 담는 자리**(B-93) — 호스트가 필드 config에 쓴다.
     *
     * 결과를 콜백으로 되돌려 받는 것은 쓰기가 비동기이고 **실패할 수 있기 때문이다**:
     * 성공을 기다리지 않고 화면을 세우면, 저장되지 않은 축을 보여 주다가 다음에 열 때
     * 조용히 옛 축으로 돌아간다(개발 의도 2번 — 말없는 유실이자 거짓 고지).
     *
     * null이면 편집 단추가 서지 않는다 — 저장할 자리가 없는 화면이 있을 수 있다.
     */
    var onSaveGeneration: ((BodyAnalysisConfig.GenerationPreset, (Boolean) -> Unit) -> Unit)? = null

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

    /**
     * 🎲가 쓰는 축·프리셋 — **세계관 설정에서 그때그때 읽는다**(B-93).
     *
     * 값으로 굳혀 두지 않는 것은 이 시트에서 축을 고칠 수 있기 때문이다([editGeneration]):
     * 굳히면 방금 고친 축과 화면의 축이 갈리고, 그 갈림은 저장 뒤 다시 열기 전까지 안 보인다.
     */
    private val generatorOptions: BodyAnalysisConfig.GenerationPreset
        get() = analysisConfig.generation.usable
    private var cupMode = false
    private var cupSizeDiffs: List<Pair<String, Double>> = emptyList()
    private var overlayOn = false

    // ── 회전 보존 (B-201 · 확정 15장 5번 · R-41) ─────────────────────────────

    /**
     * 회전 직전의 편집 상태 — 되살릴 것이 없으면 null.
     *
     * **왜 즉시 그리지 않고 들고 있는가:** 이 시트가 다시 세워지는 시점에 호스트는 아직 폼을
     * 못 세웠다(필드 정의가 DB 조회다). 그래서 `analysisConfig`와 콜백 둘이 비어 있는데,
     * 그 상태로 그리면 **설정을 모르는 축·컵으로 한 번 그렸다가 다시 그리게 되고**, 그 사이에
     * [적용]을 누르면 아무 데도 닿지 않는다. 호스트가 [onInputsBound]로 알릴 때 비로소 그린다.
     */
    private var restored: BodyEditorState? = null

    /**
     * 호스트가 입력을 다 꽂았는가 — 처음 열 때는 `show()` 전에 채워져 참이고,
     * 회전 뒤에는 호스트가 폼을 다시 세운 뒤에야 참이 된다.
     */
    private var inputsReady: Boolean = false

    /** 되살릴 것을 들고 호스트를 기다리는 중인가 — 호스트가 이것으로 *고아 시트*를 가른다. */
    val isAwaitingRestore: Boolean get() = restored != null && !built

    /** 되살릴 편집기가 어느 필드의 것인가 — 호스트가 그 필드를 못 찾으면 이 시트를 닫는다. */
    val restoredFieldId: Long? get() = restored?.fieldId

    private var built = false

    /** 주입이 끝나기 전에 도착한 축·프리셋 저장 결과 — [onInputsBound]가 마저 든다(B-260). */
    private var pendingGenerationUpdate: BodyAnalysisConfig.GenerationPreset? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // **아래 이른 반환보다 앞이다** — 처음 열 때도 회전 뒤에도 듣고 있어야 한다.
        listenGenerationEdit()
        val state = BodyEditorState.decode(savedInstanceState?.getString(STATE_KEY)) ?: return
        restored = state
        // 열 때 뜬 스냅샷은 **여기서 곧바로 되꽂는다** — 폼 위젯의 값에서 나온 것이라
        // 호스트가 다시 읽으려면 값이 채워지기를 기다려야 하고(정지 함수), 그 사이에 읽으면
        // 빈 값이 되쓰기의 바탕이 된다(`BodyEditorState` 머리 주석).
        bodySizeFieldId = state.fieldId
        initial = state.initial
        partValues = state.partValues
        writableSlots = state.writableSlots
        hasHeightField = state.hasHeightField
        hasWeightField = state.hasWeightField
        positionalFallback = state.positionalFallback
        noWritableSlot = state.noWritableSlot
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetBodySilhouetteEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * 호스트가 입력을 다 꽂은 뒤 부른다 — 그때 비로소 편집기를 세운다.
     *
     * 처음 열 때도 회전 뒤에도 같은 함수를 지난다(호스트에 갈래가 없다). 여러 번 불려도
     * 한 번만 세운다 — 폼이 여러 경로로 다시 서므로 **멱등이 아니면 세우는 도중에 다시
     * 세우게 되고, 그러면 사용자가 방금 만진 값이 스냅샷으로 되돌아간다.**
     */
    fun onInputsBound() {
        inputsReady = true
        if (_binding != null && !built) buildEditor()
        // 주입을 기다리던 저장 결과가 있으면 이제 든다(위 [applyGenerationUpdate]).
        pendingGenerationUpdate?.let { applyGenerationUpdate(it) }
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
        // 다시 세워졌는데 담긴 것을 못 읽었으면 **되쓸 바탕이 없다** — 원문도 되쓸 자리도
        // 그 번들에 함께 실려 있었기 때문이다. 그 상태의 편집기는 기준 몸 초안을 그린 채
        // [적용]이 아무 데도 닿지 않는 껍데기이므로, 이 저장소가 일곱 자리에서 쓰는
        // **안전 종료**가 옳은 부류다(다시 열면 그만이고, 잃은 것은 이미 없다).
        if (savedInstanceState != null && restored == null) {
            dismissAllowingStateLoss()
            return
        }
        // 회전 뒤에는 호스트가 아직 폼을 못 세웠다 — [onInputsBound]가 부를 때 세운다.
        if (inputsReady) buildEditor()
    }

    /**
     * 편집기를 세운다 — 처음 열 때와 회전 복원이 **같은 한 벌을 지난다**(R-41).
     *
     * 복원이면 [restored]가 수치·축·컵·본 자리를 덮어쓴다. 시작값([start])은 덮지 않는다 —
     * 그것은 *폼에 원래 있던 것*이라 스냅샷에서 그대로 나오고, [hasChanges]가 그것과 견주어
     * [적용]을 켠다. 복원 뒤에도 "고친 것이 있는가"가 회전 전과 같은 답이어야 한다.
     */
    private fun buildEditor() {
        built = true
        start = BodyEditorModel.draftMeasures(initial, analysisConfig.ribOffset)
        current = start
        startHeight = initial.heightCm
        startWeight = initial.weightKg
        weightKg = initial.weightKg

        val state = restored
        if (state != null) {
            current = state.current
            weightKg = state.weightKg
            previousRoll = state.previous
            touched.clear()
            touched.addAll(state.touched)
            cupMode = state.cupMode
            overlayOn = state.overlayOn
        }

        setupSilhouette()
        buildRows()
        setupGeneratorPanel()
        setupNotices()

        binding.btnClose.setOnClickListener { closeWithConfirm() }
        binding.btnCancel.setOnClickListener { closeWithConfirm() }
        binding.btnApply.setOnClickListener { applyAndDismiss() }

        if (state != null) applyRestoredSelections(state)

        // 굴린 뒤 회전했으면 [직전으로]가 살아 있어야 한다 — 그러지 않으면 되돌릴 한 단계가
        // 남아 있는데 단추만 꺼져 있다(있는 것을 없다고 말하는 화면).
        binding.btnUndoRoll.isEnabled = previousRoll != null
        syncAll(from = null)
    }

    /**
     * 라디오·스피너·펼침 상태를 되돌린다 — **자동 복원이 원리적으로 닿지 못하는 자리다.**
     *
     * 축 라디오는 `View.generateViewId()`로 세우므로 id가 회전마다 달라지고, 안드로이드의
     * 뷰 상태 복원은 id로 짝을 찾는다. 그래서 담아 둔 자리로 우리가 다시 고른다.
     */
    private fun applyRestoredSelections(state: BodyEditorState) {
        check(binding.rgHeight, state.axisHeight)
        check(binding.rgTorso, state.axisTorso)
        check(binding.rgBust, state.axisBust)
        check(binding.rgHip, state.axisHip)
        check(binding.rgCupSize, state.cupSizeIndex)

        // 상대 생성 줄은 **재료(작품 캐릭터)가 있어야 선다.** 이미 서 있으면 지금 얹고,
        // 아니면 [setPeers]가 세울 때 얹도록 들고 있는다 — 둘 중 하나만 걸리게 갈라 둔다.
        // (양쪽에 다 걸면 들고 있던 값이 남아 *복원 뒤에 사용자가 고른 것*을 덮는다.)
        if (binding.rgRelativeHeight.childCount > 0) {
            check(binding.rgRelativeHeight, state.relativeHeightIndex)
            check(binding.rgRelativeVolume, state.relativeVolumeIndex)
        } else {
            pendingRelativeSelection = state.relativeHeightIndex to state.relativeVolumeIndex
        }

        // 컵 지정은 스위치가 가슴 축을 잠그는 부수 효과를 함께 든다 — 리스너를 태워 그 잠금까지
        // 같이 되살린다(값만 넣으면 흐려 보이는데 눌리는 줄이 생긴다).
        if (state.cupMode) binding.switchCupMode.isChecked = true

        if (state.generatorOpen) toggleGeneratorPanel()
        if (state.sideFacing) setFacing(side = true)
        if (state.overlayOn && binding.btnToggleOverlay.visibility == View.VISIBLE) {
            // 재료(작품 캐릭터)가 아직 없으면 토글 자체가 없다 — 그때는 [setupRelative]가
            // 다시 불릴 때 [overlayOn]을 보고 세운다.
            binding.silhouette.overlayMeasures =
                BodyEditorModel.peerAverage(peerMeasurements.values.toList(), analysisConfig.ribOffset)
            binding.btnToggleOverlay.alpha = 1f
        }
        pendingBaseCharacterId = state.baseCharacterId
        applyPendingBaseCharacter()
    }

    /** 재료가 와야 서는 라디오의 복원 몫 — 한 번 얹으면 비운다(위 [setPeers] 주석). */
    private var pendingRelativeSelection: Pair<Int, Int>? = null

    /** 기준 캐릭터는 목록이 늦게 도착한다 — 도착할 때까지 id로 들고 있다가 그때 고른다. */
    private var pendingBaseCharacterId: Long? = null

    private fun applyPendingBaseCharacter() {
        val id = pendingBaseCharacterId ?: return
        val index = novelCharacters.indexOfFirst { it.id == id }
        if (index < 0) return
        binding.spinnerBaseCharacter.setSelection(index + 1)
        pendingBaseCharacterId = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentState()?.let { outState.putString(STATE_KEY, it.encode()) }
    }

    /**
     * 지금 담을 것 — **세우기 전에 회전이 또 나면 들고 있던 것을 그대로 다시 담는다.**
     *
     * 호스트가 폼을 세우는 동안 사용자가 한 번 더 돌리는 갈래다. 그때 화면을 읽으면
     * 아직 안 그린 위젯에서 기본값을 읽어 **되살리려던 것을 우리 손으로 지운다** —
     * 게다가 [current]는 `lateinit`이라 읽는 순간 죽는다. 그래서 세우기 전에는
     * 들고 있던 것을 그대로 돌려주고, 그것마저 없으면 담지 않는다(담을 것이 없다).
     */
    private fun currentState(): BodyEditorState? {
        if (!built || _binding == null) return restored
        return BodyEditorState(
            fieldId = bodySizeFieldId,
            initial = initial,
            partValues = partValues,
            writableSlots = writableSlots,
            hasHeightField = hasHeightField,
            hasWeightField = hasWeightField,
            positionalFallback = positionalFallback,
            noWritableSlot = noWritableSlot,
            current = current,
            weightKg = weightKg,
            previous = previousRoll,
            touched = touched.toSet(),
            cupMode = cupMode,
            overlayOn = overlayOn,
            generatorOpen = binding.generatorPanel.visibility == View.VISIBLE,
            sideFacing = binding.silhouette.facing == SilhouetteView.Facing.SIDE,
            axisHeight = selectedIndex(binding.rgHeight),
            axisTorso = selectedIndex(binding.rgTorso),
            axisBust = selectedIndex(binding.rgBust),
            axisHip = selectedIndex(binding.rgHip),
            cupSizeIndex = selectedIndex(binding.rgCupSize),
            // 아직 안 선 라디오(재료 미도착)는 화면을 읽으면 0이 나온다 — 들고 있던 값이 있으면
            // 그것을 담는다. 그러지 않으면 재료가 늦은 기기에서 회전할 때마다 배수가 초기화된다.
            relativeHeightIndex = pendingRelativeSelection?.first
                ?: selectedIndex(binding.rgRelativeHeight),
            relativeVolumeIndex = pendingRelativeSelection?.second
                ?: selectedIndex(binding.rgRelativeVolume),
            baseCharacterId = novelCharacters.getOrNull(
                binding.spinnerBaseCharacter.selectedItemPosition - 1
            )?.id ?: pendingBaseCharacterId
        )
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
            setFacing(side = binding.silhouette.facing == SilhouetteView.Facing.FRONT)
        }
    }

    /**
     * 앞/옆을 정한다 — **단추와 복원이 같은 통로를 지난다.**
     *
     * 복원이 `performClick()`으로 단추를 흉내 내지 않는 이유가 둘이다: ⓐ 그것은 *누른 척*이라
     * 클릭음·햅틱이 회전할 때마다 울린다 ⓑ **현재 상태를 뒤집는** 동작이라, 나중에 시작
     * 방향이 바뀌면 복원이 조용히 반대로 선다(되살리려던 것을 되살리지 않는데 아무도 말하지 않는다).
     * 여기는 *뒤집기*가 아니라 *정하기*라 몇 번을 불러도 같은 곳에 선다.
     */
    private fun setFacing(side: Boolean) {
        binding.silhouette.facing =
            if (side) SilhouetteView.Facing.SIDE else SilhouetteView.Facing.FRONT
        // 측면은 표시 전용이다(P10) — 핸들이 없으므로 라벨만 남는다.
        binding.silhouette.interactive = !side
        binding.btnToggleSide.setText(
            if (side) R.string.silhouette_view_front else R.string.silhouette_view_side
        )
    }

    /** 작품 평균 오버레이 토글 — 재료(작품 캐릭터 수치)가 있을 때만 존재한다(P3 · 기본 끔). */
    private fun setupOverlayToggle() {
        val average = BodyEditorModel.peerAverage(peerMeasurements.values.toList(), analysisConfig.ribOffset)
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

        val summary = BodySilhouetteSpec.axisSummary(current, analysisConfig)
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
            if (noWritableSlot) add(getString(R.string.silhouette_editor_no_slot))
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
        buildAxes(density)
        buildCupOptions(density)
        setupRelative(density)
        showDistribution()

        binding.generatorHeader.setOnClickListener { toggleGeneratorPanel() }
        binding.btnRoll.setOnClickListener { roll() }
        binding.btnUndoRoll.setOnClickListener { undoRoll() }
        binding.btnEditGeneration.setOnClickListener { editGeneration() }
        // 저장할 자리가 없으면 고칠 것도 없다 — 호스트가 저장을 붙이지 않은 화면에서는
        // 단추를 세우지 않는다(눌러서 아무 일도 안 일어나는 자리를 만들지 않는다).
        binding.btnEditGeneration.visibility =
            if (onSaveGeneration == null) View.GONE else View.VISIBLE
        if (openWithGenerator) toggleGeneratorPanel()
    }

    /**
     * 축·프리셋 줄을 세운다 — **다시 세울 수 있어야 한다**(편집 뒤 그 자리에서 새로 선다).
     *
     * 다시 세울 때 **고르던 자리를 지킨다** — 개수는 열지 않으므로(확정 ㄴ1) 자리의 뜻이
     * 그대로이고, 이름 하나 고쳤다고 고르던 축이 말없이 옮겨 가면 그 자체가 유실이다.
     * 처음 세울 때는 지킬 것이 없으므로 기본 선택(가운데)을 그대로 둔다.
     */
    private fun buildAxes(density: Float) {
        val options = generatorOptions
        val groups = listOf(binding.rgHeight, binding.rgTorso, binding.rgBust, binding.rgHip)
        val keep = if (binding.rgTorso.childCount > 0) groups.map { selectedIndex(it) } else null
        binding.presetRow.removeAllViews()
        for (group in groups) group.removeAllViews()
        buildPresetRow(density, options)
        buildAxisGroup(binding.rgHeight, options.heightOptions.map { it.label }, checked = 1, density, anyOption = true)
        buildAxisGroup(binding.rgTorso, options.torsoOptions.map { it.label }, checked = 1, density)
        buildAxisGroup(binding.rgBust, options.bustOptions.map { it.label }, checked = 1, density)
        buildAxisGroup(binding.rgHip, options.hipOptions.map { it.label }, checked = 1, density)
        keep?.forEachIndexed { i, index -> check(groups[i], index) }
        // 컵 지정이 켜져 있으면 **새로 선 가슴 축도 잠가 둔다** — 다시 세운 라디오는
        // 기본이 '사용 가능'이라, 흐려 보이는데 눌리는 줄이 생긴다(화면이 거짓을 말한다).
        if (cupMode) {
            for (i in 0 until binding.rgBust.childCount) binding.rgBust.getChildAt(i).isEnabled = false
        }
    }

    /**
     * 축·프리셋 편집 창을 연다 — 저장은 호스트가 필드 config에 담는다([onSaveGeneration]).
     *
     * **저장 결과를 기다려 화면을 세운다** — 먼저 세우고 저장에 실패하면 화면이 저장되지
     * 않은 축을 보여 주게 되고, 다음에 열 때 조용히 되돌아간다(거짓 고지).
     */
    private fun editGeneration() {
        if (onSaveGeneration == null) return
        BodyGenerationEditDialog.newInstance(generatorOptions)
            .show(childFragmentManager, BodyGenerationEditDialog.TAG)
    }

    /**
     * 축·프리셋 편집 창의 결과를 받는다 — **콜백을 꽂지 않는 이유가 여기 있다**(B-260 · R-41-a).
     *
     * 창에 람다를 꽂으면 회전이 그것을 지우고, 되살아난 창의 [저장]은 아무 데도 닿지 않는다
     * (B-201이 이 시트에서 겪은 그 모양). 결과는 프래그먼트 매니저가 들고 있다가 **다시 선
     * 시트에** 주므로 되살리는 배선 자체가 없다 — 없는 배선은 빠뜨릴 수도 없다.
     *
     * 등록 자리가 `onCreate`인 것도 그 때문이다: 회전 뒤 창이 결과를 보낼 때 시트가 이미
     * 듣고 있어야 한다(뷰가 서기를 기다리면 그 사이에 온 결과를 놓친다).
     */
    private fun listenGenerationEdit() {
        childFragmentManager.setFragmentResultListener(
            BodyGenerationEditDialog.RESULT_KEY, this
        ) { _, bundle ->
            val updated = BodyAnalysisConfig.generationFromJsonString(
                bundle.getString(BodyGenerationEditDialog.RESULT_GENERATION_JSON)
            ) ?: return@setFragmentResultListener
            applyGenerationUpdate(updated)
        }
    }

    /**
     * 창이 저장한 한 벌을 필드 config에 담는다.
     *
     * **주입이 아직이면 들고 기다린다.** 결과는 호스트가 [onSaveGeneration]을 다시 꽂기
     * *전에* 도착할 수 있다 — 회전 직후에는 시트가 먼저 서고 폼은 그 뒤에 다시 서기
     * 때문이다. 그때 그냥 버리면 **사용자가 누른 [저장]이 말없이 사라진다**(개발 의도 2번).
     * [onInputsBound]가 주입이 끝난 자리이므로 거기서 마저 든다.
     */
    private fun applyGenerationUpdate(updated: BodyAnalysisConfig.GenerationPreset) {
        val save = onSaveGeneration
        if (save == null) {
            pendingGenerationUpdate = updated
            return
        }
        pendingGenerationUpdate = null
        // **저장 결과를 기다려 화면을 세운다** — 먼저 세우고 저장에 실패하면 화면이
        // 저장되지 않은 축을 보여 주게 되고, 다음에 열 때 조용히 되돌아간다(거짓 고지).
        save(updated) { ok ->
            if (_binding == null) return@save
            if (!ok) return@save
            analysisConfig = analysisConfig.copy(generation = updated)
            buildAxes(resources.displayMetrics.density)
            syncAll(null)
        }
    }

    private fun toggleGeneratorPanel() {
        val open = binding.generatorPanel.visibility != View.VISIBLE
        binding.generatorPanel.visibility = if (open) View.VISIBLE else View.GONE
        binding.generatorChevron.setImageResource(
            if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more
        )
    }

    private fun buildPresetRow(density: Float, options: BodyAnalysisConfig.GenerationPreset) {
        for ((i, preset) in options.bodyPresets.withIndex()) {
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
        val cupDiff = if (cupMode) cupSizeDiffs.getOrNull(selectedIndex(binding.rgCupSize))?.second else null
        // 축은 **인덱스로** 넘긴다 — 구간이 목록에서의 자리에서 파생되므로(B-93) 옵션 객체만
        // 넘기면 겨눌 폭을 생성기가 알 수 없다. 목록 밖·미선택의 처분도 그쪽 한 자리에 있다.
        return BodyGenerator.generate(
            options = generatorOptions,
            heightIndex = selectedIndex(binding.rgHeight),
            torsoIndex = selectedIndex(binding.rgTorso),
            bustIndex = selectedIndex(binding.rgBust),
            hipIndex = selectedIndex(binding.rgHip),
            targetCupDiff = cupDiff,
            ribOffset = analysisConfig.ribOffset
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
        // 뷰만 다시 서는 갈래(인스턴스는 살아 있고 번들을 지나지 않는다)에서도 되살아나게
        // 지금 것을 들고 내려간다 — 그러지 않으면 그 길에서만 편집이 사라진다.
        currentState()?.let { restored = it }
        built = false
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "body_silhouette_editor"

        /** 회전 보존 번들의 칸 이름 — 담는 것의 전수는 [BodyEditorState]가 든다. */
        private const val STATE_KEY = "body_editor_state"
    }
}
