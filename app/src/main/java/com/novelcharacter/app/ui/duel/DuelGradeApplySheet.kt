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
import com.novelcharacter.app.util.logOperation
import com.novelcharacter.app.util.notifyResult
import kotlinx.coroutines.launch

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_duel_grade_apply, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        fieldId = arguments?.getLong(ARG_FIELD_ID) ?: 0L
        axisCode = arguments?.getString(ARG_AXIS_CODE).orEmpty()
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
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = try {
                val field = app.database.fieldDefinitionDao().getFieldById(fieldId)
                val axis = app.duelRepository.axisByCode(axisCode)
                val spec = field?.let { DuelGradeRef.fromConfig(it.config) }
                if (field == null || axis == null || spec == null) {
                    null
                } else if (axis.universeId != field.universeId) {
                    // 소속 검사(설계 4-2 ⓑ) — 축 code는 전역 유니크라 **남의 세계관 축을
                    // 정확히 찾는다.** 강등이 한 경로라도 빠졌을 때의 두 번째 방어선이다.
                    Loaded.Blocked(getString(R.string.duel_grade_axis_foreign))
                } else {
                    val labels = DuelGradeAssign.orderedLabels(
                        com.novelcharacter.app.data.model.GradeSystemRef.gradesFromConfig(field.config)
                    )
                    val problems = DuelGradeAssign.validate(spec.cuts, labels)
                    if (problems.isNotEmpty()) {
                        Loaded.Blocked(getString(R.string.duel_grade_apply_fix_cuts))
                    } else {
                        val characters = app.characterRepository
                            .getCharactersByUniverseList(field.universeId)
                        val scores = app.duelRepository.scoresOf(axis, characters.map { it.code })
                        val assigned = DuelGradeAssign.assign(scores, spec.cuts, labels)
                        val currentValues = app.database.characterFieldValueDao()
                            .getValuesForCharacters(characters.map { it.id })
                            .filter { it.fieldDefinitionId == fieldId }
                            .associate { value ->
                                characters.first { it.id == value.characterId }.code to value.value
                            }
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
        data.preview.defaultChecked.forEach { checked.add(it.code) }

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
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                app.duelRepository.applyGrades(fieldId, all, selected, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply duel grades", e)
                null
            }
            if (!isAdded) return@launch
            if (result == null) {
                notifyResult(
                    OpResult.failure(OpResult.CAT_DUEL, getString(R.string.duel_grade_apply_failed))
                )
                button.isEnabled = true
                return@launch
            }
            // 이력에 남긴다 — 되돌릴 자리(휴지통)를 찾는 사람이 "언제 무엇이 바뀌었나"를
            // 여기서 짚는다(B-113 ②의 답).
            logOperation(
                OpResult.success(OpResult.CAT_DUEL, getString(R.string.duel_grade_op_applied, result))
            )
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.duel_grade_apply_done, result),
                android.widget.Toast.LENGTH_LONG
            ).show()
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

        fun newInstance(fieldId: Long, axisCode: String) = DuelGradeApplySheet().apply {
            arguments = Bundle().apply {
                putLong(ARG_FIELD_ID, fieldId)
                putString(ARG_AXIS_CODE, axisCode)
            }
        }
    }
}
