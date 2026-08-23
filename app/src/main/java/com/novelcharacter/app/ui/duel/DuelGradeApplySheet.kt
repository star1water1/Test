package com.novelcharacter.app.ui.duel

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.DuelGradeRef
import com.novelcharacter.app.util.DuelGradeAssign
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **[등급 반영] 미리보기** — 대결 순위에서 나온 등급을 필드 값으로 쓰기 **직전**의 화면
 * (B-113, 설계 4-3 · 목업 `docs/mockups/duel_grade_ui_2026-08.html` ②).
 *
 * ## 왜 자동이 아닌가
 *
 * 판 하나가 전원의 점수를 움직이는 시스템에서 자동 반영은 **사용자 데이터가 소리 없이 계속
 * 바뀐다**는 뜻이다(변수 제어 위반). 그래서 흐름은 *누른다 → 무엇이 바뀌는지 전부 본다 →
 * 고른 것만 쓴다*이고, 이 시트가 가운데 단계다.
 *
 * ## 세 묶음의 기본 체크가 다르다 (Q1 사용자 확정)
 *
 * - **빈 칸** → 채우는 것이라 켜짐.
 * - **지난 반영값** → 갱신이라 켜짐. *(2차 반영부터 전 행을 손으로 켜는 마찰을 없앤다)*
 * - **직접 적은 값** → **꺼짐.** 손값은 사용자의 명시적 판단이라 덮는 쪽이 예외여야 한다.
 *
 * 판별은 [DuelGradeRef.LastApplied]가 든다. **그 기록이 없으면 묶음을 둘로 줄이고 그 사실을
 * 말한다** — 없는 근거로 "지난 반영값"이라 이름 붙이면, 그 오판은 손값을 켜진 채로 덮는
 * 방향으로 틀린다.
 */
class DuelGradeApplySheet : BottomSheetDialogFragment() {

    private var fieldId: Long = 0L
    private var axisCode: String = ""

    private var preview: DuelGradeAssign.Preview? = null
    private var assignments: Map<String, String> = emptyMap()
    private val checked = LinkedHashSet<String>()
    private var nameByCode: Map<String, String> = emptyMap()

    /**
     * **회전을 넘긴 체크 상태** — 미리보기를 다시 세우기 전까지 들고 있는 임시 자리.
     *
     * 이 시트는 세울 때마다 DB에서 미리보기를 다시 계산하므로(`load`), 회전 뒤의 목록이
     * 회전 전과 다를 수 있다. 그래서 되살릴 때 **지금 목록에 있는 줄만** 되살린다 —
     * 없는 코드를 체크에 남기면 [반영 (N)]의 N이 화면의 체크 수와 갈린다.
     */
    private var restoredChecked: List<String>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_duel_grade_apply, container, false)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // **고른 것은 화면보다 오래 산다.** 종전에는 회전 한 번에 기본 체크로 통째로
        // 되돌아갔고 아무 말도 없었다 — 손값 묶음에서 하나씩 훑어 끈 사용자가 그 판단을
        // 잃는다(원칙 04 · 개발 의도 2번).
        //
        // **다만 «아직 안 정해졌다»와 «전부 껐다»를 가른다.** `checked`는 [render]가
        // 불려야 채워지는데 그 앞에 `load`의 중단 구간이 있다(DB에서 미리보기를 다시
        // 계산한다 — 캐릭터가 많은 세계관일수록 넓다). 그 창에서 회전하면 **빈 목록**이
        // 실리고, 되살릴 때 `if (restored != null)`은 빈 리스트도 non-null이라 기본 체크
        // 갈래로 못 가 **[반영 (0)]이 비활성으로 선다** — 빈 칸·지난 반영값 묶음이 켜져
        // 있어야 한다는 확정(Q1)과 어긋난다. 미리보기가 서기 전에는 들고 있던 복원값을
        // 그대로 넘겨, 아직 정해지지 않았다는 사실이 다음 회차까지 간다.
        val save = if (preview != null) checked.toList() else restoredChecked
        if (save != null) outState.putStringArrayList(KEY_CHECKED, ArrayList(save))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        fieldId = arguments?.getLong(ARG_FIELD_ID) ?: 0L
        axisCode = arguments?.getString(ARG_AXIS_CODE).orEmpty()
        restoredChecked = savedInstanceState?.getStringArrayList(KEY_CHECKED)
        view.findViewById<MaterialButton>(R.id.applyButton).isEnabled = false
        load(view)
    }

    /**
     * 미리보기를 세운다. **반영 직전에 컷을 다시 검증한다** — 저장한 뒤 등급 체계가 편집돼
     * 실효 표가 바뀌었을 수 있고, 유령 라벨로 배정하면 어떤 등급에도 없는 문자열이 캐릭터
     * 값으로 심긴다(설계 4-2).
     */
    private fun load(view: View) {
        val summary = view.findViewById<TextView>(R.id.summaryText)
        val app = requireContext().applicationContext as NovelCharacterApp
        // **문구를 미리 굳힌다** — 아래 블록은 배경에서 도는데 `getString`은 프래그먼트를
        // 거치므로(`requireContext()`) 그 사이에 화면이 사라지면 배경 스레드에서
        // `IllegalStateException`이 난다. 상세 화면의 PDF 경로가 같은 이유로 이미 굳힌다.
        val foreignAxisText = getString(R.string.duel_grade_axis_foreign)
        val fixCutsText = getString(R.string.duel_grade_apply_fix_cuts)
        viewLifecycleOwner.lifecycleScope.launch {
            // **점수 적합을 주 스레드에서 돌리지 않는다.** `DuelViewModel`의 KDoc이 그
            // 이유를 실측으로 든다 — 900명·18,000판에서 *적합 128ms*이고 캐릭터 수의
            // 제곱에 붙는다. 그 계층은 전부 `Dispatchers.Default`로 넘기는데 **이 시트와
            // 필드 편집의 [경계 제안]만 그 밖에 있었다.** Room의 중단 함수는 스스로 자기
            // 실행기로 옮기므로 이 감싸기와 겹치지 않는다.
            val loaded = withContext(Dispatchers.Default) {
                try {
                    val field = app.database.fieldDefinitionDao().getFieldById(fieldId)
                    val axis = app.duelRepository.axisByCode(axisCode)
                    val spec = field?.let { DuelGradeRef.fromConfig(it.config) }
                    if (field == null || axis == null || spec == null) {
                        null
                    } else if (axis.universeId != field.universeId) {
                        // 소속 검사(설계 4-2 ⓑ) — 축 code는 전역 유니크라 **남의 세계관 축을
                        // 정확히 찾는다.** 강등이 한 경로라도 빠졌을 때의 두 번째 방어선이다.
                        Loaded.Blocked(foreignAxisText)
                    } else {
                        val labels = DuelGradeAssign.orderedLabels(
                            com.novelcharacter.app.data.model.GradeSystemRef.gradesFromConfig(field.config)
                        )
                        val problems = DuelGradeAssign.validate(spec.cuts, labels)
                        if (problems.isNotEmpty()) {
                            Loaded.Blocked(fixCutsText)
                        } else {
                            val characters = app.characterRepository
                                .getCharactersByUniverseList(field.universeId)
                            val scores = app.duelRepository.scoresOf(axis, characters.map { it.code })
                            val assigned = DuelGradeAssign.assign(scores, spec.cuts, labels)
                            // **저장소를 거친다** — DAO를 직접 부르면 `IN (:ids)`가 SQLite 변수
                            // 상한(999)에 걸려, 캐릭터가 많은 세계관에서 미리보기가 예외로 죽는다.
                            // 저장소 쪽이 그 청크 분할을 들고 있다.
                            val codeById = characters.associate { it.id to it.code }
                            val currentValues = app.characterRepository
                                .getValuesForCharacters(characters.map { it.id })
                                .filter { it.fieldDefinitionId == fieldId }
                                .mapNotNull { value ->
                                    codeById[value.characterId]?.let { it to value.value }
                                }
                                .toMap()
                            Loaded.Ready(
                                axisName = axis.name,
                                fieldName = field.name,
                                scored = scores.scored,
                                unplayed = scores.unplayed,
                                assignments = assigned.associate { it.code to it.label },
                                preview = DuelGradeAssign.preview(assigned, currentValues, spec.lastApplied),
                                nameByCode = characters.associate { it.code to it.displayName }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to build duel grade preview", e)
                    null
                }
            }
            if (!isAdded) return@launch
            when (loaded) {
                null -> {
                    summary.text = getString(R.string.duel_grade_apply_load_failed)
                    return@launch
                }
                is Loaded.Blocked -> {
                    // 막을 때는 사유를 말한다 — "안 됩니다"만 남기면 고칠 자리를 못 찾는다.
                    summary.text = loaded.reason
                    return@launch
                }
                is Loaded.Ready -> render(view, loaded)
            }
        }
    }

    private fun render(view: View, data: Loaded.Ready) {
        preview = data.preview
        assignments = data.assignments
        nameByCode = data.nameByCode
        checked.clear()
        val restored = restoredChecked
        restoredChecked = null
        if (restored != null) {
            // 되살릴 때는 **지금 목록에 있는 줄만** 받는다 — 미리보기는 다시 계산된 것이다.
            val live = data.preview.rows.mapTo(HashSet()) { it.code }
            restored.filterTo(checked) { it in live }
        } else {
            data.preview.defaultChecked.forEach { checked.add(it.code) }
        }

        view.findViewById<TextView>(R.id.titleText).text =
            getString(R.string.duel_grade_apply_title, data.axisName, data.fieldName)
        view.findViewById<TextView>(R.id.summaryText).text = buildString {
            append(
                if (data.unplayed > 0) {
                    getString(R.string.duel_grade_apply_summary, data.scored, data.unplayed)
                } else {
                    getString(R.string.duel_grade_apply_summary_scored, data.scored)
                }
            )
            if (data.preview.unchanged > 0) {
                append(" ")
                append(getString(R.string.duel_grade_apply_unchanged, data.preview.unchanged))
            }
        }
        val traceNote = view.findViewById<TextView>(R.id.traceNote)
        traceNote.visibility = if (data.preview.traceable) View.GONE else View.VISIBLE

        val groups = view.findViewById<LinearLayout>(R.id.groupList)
        groups.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        // 묶음 차례는 기본 체크가 켜진 것부터다 — 훑는 사람이 "확인만 하면 되는 것"을 먼저 보고,
        // 판단이 필요한 손값 묶음을 마지막에 만난다.
        renderGroup(inflater, groups, DuelGradeAssign.Bucket.EMPTY, R.string.duel_grade_group_empty, view)
        renderGroup(inflater, groups, DuelGradeAssign.Bucket.REAPPLY, R.string.duel_grade_group_reapply, view)
        renderGroup(inflater, groups, DuelGradeAssign.Bucket.MANUAL, R.string.duel_grade_group_manual, view)

        view.findViewById<TextView>(R.id.emptyText).visibility =
            if (data.preview.rows.isEmpty()) View.VISIBLE else View.GONE
        renderApplyButton(view)
        view.findViewById<MaterialButton>(R.id.applyButton).setOnClickListener { apply(view) }
    }

    private fun renderGroup(
        inflater: LayoutInflater,
        container: LinearLayout,
        bucket: DuelGradeAssign.Bucket,
        titleRes: Int,
        root: View
    ) {
        val rows = preview?.rowsOf(bucket).orEmpty()
        if (rows.isEmpty()) return
        val group = inflater.inflate(R.layout.item_duel_grade_group, container, false)
        group.findViewById<TextView>(R.id.groupTitle).text = getString(titleRes, rows.size)
        val toggle = group.findViewById<TextView>(R.id.groupToggle)
        val rowBox = group.findViewById<LinearLayout>(R.id.rowBox)
        val boxes = ArrayList<MaterialCheckBox>(rows.size)

        for (row in rows) {
            val item = inflater.inflate(R.layout.item_duel_grade_preview, rowBox, false)
            val box = item.findViewById<MaterialCheckBox>(R.id.rowCheck)
            box.isChecked = row.code in checked
            box.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(row.code) else checked.remove(row.code)
                renderApplyButton(root)
            }
            boxes.add(box)
            item.findViewById<TextView>(R.id.rowName).text = nameByCode[row.code] ?: row.code
            item.findViewById<TextView>(R.id.rowChange).text = if (row.current.isBlank()) {
                getString(R.string.duel_grade_change_new, row.next, formatPercent(row.percentile))
            } else {
                getString(R.string.duel_grade_change, row.current, row.next, formatPercent(row.percentile))
            }
            // 경계 표식 — 배정을 막지는 않는다(마찰 최소). 오차 안의 차이는 표본 소음이라
            // "이 등급이 확정"이라고 말하는 쪽이 거짓말이다.
            item.findViewById<TextView>(R.id.rowBadge).visibility =
                if (row.onBoundary) View.VISIBLE else View.GONE
            rowBox.addView(item)
        }

        fun refreshToggleLabel() {
            val allOn = rows.all { it.code in checked }
            toggle.text = getString(
                if (allOn) R.string.duel_grade_group_uncheck_all else R.string.duel_grade_group_check_all
            )
        }
        refreshToggleLabel()
        toggle.setOnClickListener {
            val allOn = rows.all { it.code in checked }
            boxes.forEach { it.isChecked = !allOn }
            refreshToggleLabel()
            renderApplyButton(root)
        }
        container.addView(group)
    }

    /** [반영 (N)]의 N은 체크된 줄 수와 **항상 같다** — 누르기 전에 몇 건이 쓰이는지 보인다. */
    private fun renderApplyButton(root: View) {
        val button = root.findViewById<MaterialButton>(R.id.applyButton)
        button.text = getString(R.string.duel_grade_apply_count, checked.size)
        button.isEnabled = checked.isNotEmpty()
    }

    private fun apply(root: View) {
        val button = root.findViewById<MaterialButton>(R.id.applyButton)
        button.isEnabled = false
        val app = requireContext().applicationContext as NovelCharacterApp
        val selected = LinkedHashSet(checked)
        val all = assignments
        val fieldIdNow = fieldId
        val failedText = getString(R.string.duel_grade_apply_failed)
        viewLifecycleOwner.lifecycleScope.launch {
            // **반영은 시작하면 끝까지 가고, 끝난 사실도 끝까지 간다.**
            //
            // 종전에는 이 구간이 화면 수명 그대로였다. 트랜잭션 자체는 Room이 롤백해 주므로
            // 반쪽 쓰기는 안 났지만, **커밋된 뒤 돌아오는 길에 취소가 던져지면 이력도 토스트도
            // 통째로 사라졌다** — 사용자는 수십 명의 등급이 바뀐 것을 어디서도 듣지 못한다.
            // 그래서 쓰기와 고지를 한 `NonCancellable` 블록에 함께 넣고, 고지는 앱 컨텍스트로
            // 보낸다(`Fragment.logOperation`은 화면이 사라지면 조용히 아무것도 안 적는다).
            val result = withContext(NonCancellable) {
                val written = try {
                    app.duelRepository.applyGrades(fieldIdNow, all, selected, System.currentTimeMillis())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply duel grades", e)
                    null
                }
                if (written == null) {
                    app.operationLogRepository.logAsync(
                        OpResult.failure(OpResult.CAT_DUEL, failedText)
                    )
                } else {
                    // 이력에 남긴다 — 되돌릴 자리(휴지통)를 찾는 사람이 "언제 무엇이 바뀌었나"를
                    // 여기서 짚는다(B-113 ②의 답).
                    app.operationLogRepository.logAsync(
                        OpResult.success(
                            OpResult.CAT_DUEL,
                            app.getString(R.string.duel_grade_op_applied, written)
                        )
                    )
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            app,
                            app.getString(R.string.duel_grade_apply_done, written),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
                written
            }
            if (!isAdded) return@launch
            if (result == null) {
                notifyResult(OpResult.failure(OpResult.CAT_DUEL, failedText))
                button.isEnabled = true
                return@launch
            }
            dismiss()
        }
    }

    private fun formatPercent(value: Double): String {
        val rounded = DuelGradeRef.roundPercent(value)
        return if (rounded == rounded.toInt().toDouble()) rounded.toInt().toString() else rounded.toString()
    }

    private sealed class Loaded {
        /** 반영할 수 없는 상태 — 사유를 그대로 화면에 싣는다. */
        data class Blocked(val reason: String) : Loaded()

        data class Ready(
            val axisName: String,
            val fieldName: String,
            val scored: Int,
            val unplayed: Int,
            val assignments: Map<String, String>,
            val preview: DuelGradeAssign.Preview,
            val nameByCode: Map<String, String>
        ) : Loaded()
    }

    companion object {
        private const val TAG = "DuelGradeApplySheet"
        private const val ARG_FIELD_ID = "fieldId"
        private const val ARG_AXIS_CODE = "axisCode"
        private const val KEY_CHECKED = "checkedCodes"

        fun newInstance(fieldId: Long, axisCode: String) = DuelGradeApplySheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_FIELD_ID, fieldId)
                putString(ARG_AXIS_CODE, axisCode)
            }
        }
    }
}
