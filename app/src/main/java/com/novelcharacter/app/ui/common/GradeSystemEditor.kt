package com.novelcharacter.app.ui.common

import android.content.Context
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.GradeSystemRef
import com.novelcharacter.app.data.repository.GradeSystemRepository
import com.novelcharacter.app.util.GradeTable
import com.novelcharacter.app.util.cappedScrollView
import com.novelcharacter.app.util.setValidatedPositiveButton
import com.novelcharacter.app.util.showInlineError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * **등급 체계를 만들고 고치는 창** — 세계관 편집과 필드 편집이 함께 쓴다 (B-71).
 *
 * ## 왜 뗐는가
 *
 * 종전에는 이 본체가 `UniverseListFragment` 안에만 있었다. 그래서 GRADE 필드를 편집하다가
 * 체계가 하나도 없다는 것을 알면 **필드 편집을 닫고**(입력 유실) 세계관 편집 → 체계 추가 →
 * 필드 재편집까지 화면 2개·다이얼로그 3개를 왕복해야 했다 — 약 9~10단계다(원칙 04 위반).
 * 같은 앱의 대결 축은 이미 필드 편집에서 인라인으로 만들 수 있었고(B-113의
 * `createDuelAxis`), 그 선례와 어긋나 있던 것이 이 항목이다.
 *
 * **베끼지 않고 뗀 것이 요점이다.** 창을 하나 더 쓰면 검증 규칙([GradeTable])·개명 추적·
 * 전파 고지가 두 벌이 되고, 그중 한 벌만 고쳐지는 날이 온다. 실제로 이 창은 셋을 한 번에
 * 한다 — 이름 중복 검사, **라벨 개명 추적**(재정의가 개명을 따라가게 하는 유일한 근거),
 * 그리고 저장이 참조 필드에 퍼진 결과의 고지.
 *
 * ## 저장이 곧 전파다
 *
 * [GradeSystemRepository.saveSystem]은 참조 중인 필드의 등급 표를 함께 다시 쓴다. 그래서
 * 이 창은 저장소를 직접 부르고, **두 호출부가 같은 경로를 지난다** — 한쪽이 DAO를 직접
 * 건드리면 그 화면에서만 전파가 빠진다.
 */
object GradeSystemEditor {

    /**
     * @param existing null이면 **새로 만들기**다. 필드 편집의 인라인 생성이 이 갈래를 쓴다.
     * @param onSaved 저장된 체계. 부르는 쪽이 목록을 다시 읽고, 필드 편집은 **그것을 고른
     *   상태로** 돌아간다(만들고 다시 고르게 하면 마찰이 남는다 — `createDuelAxis`와 같은 규약).
     */
    fun show(
        context: Context,
        scope: CoroutineScope,
        universeId: Long,
        existing: GradeSystem?,
        onSaved: (GradeSystem) -> Unit
    ) {
        val ctx = context
        val dp = ctx.resources.displayMetrics.density
        val app = ctx.applicationContext as NovelCharacterApp
        val repository = GradeSystemRepository(app.database)
        scope.launch {
            // 이름 유니크 검증용 형제 목록 — DB 유니크 인덱스가 최후의 방어선이고, 여기서는
            // 창을 닫기 전에 잡아 입력을 지키는 것이 목적이다(R-27).
            val siblings = try {
                app.database.gradeSystemDao().getByUniverseList(universeId)
            } catch (_: Exception) {
                emptyList()
            }

            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val dp24 = (24 * dp).toInt()
                setPadding(dp24, (16 * dp).toInt(), dp24, (8 * dp).toInt())
            }
            val nameEdit = EditText(ctx).apply {
                hint = ctx.getString(R.string.grade_system_name_hint)
                existing?.let { setText(it.name) }
            }
            layout.addView(nameEdit)

            val rowsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (8 * dp).toInt(), 0, 0)
            }
            layout.addView(rowsContainer)

            val rows = mutableListOf<SystemRow>()

            fun addRow(label: String = "", value: String = "", originalLabel: String? = null) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (4 * dp).toInt() }
                }
                val editLabel = EditText(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f)
                    hint = ctx.getString(R.string.hint_grade_label)
                    textSize = 13f
                    if (label.isNotEmpty()) setText(label)
                }
                val editValue = EditText(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    hint = ctx.getString(R.string.hint_grade_value)
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    textSize = 13f
                    if (value.isNotEmpty()) setText(value)
                }
                val entry = SystemRow(editLabel, editValue, originalLabel)
                val btnRemove = TextView(ctx).apply {
                    text = "✕"
                    setTextColor(ctx.getColor(R.color.primary))
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), 0, 0)
                    setOnClickListener {
                        rowsContainer.removeView(row)
                        rows.remove(entry)
                    }
                }
                row.addView(editLabel)
                row.addView(editValue)
                row.addView(btnRemove)
                rowsContainer.addView(row)
                rows.add(entry)
            }

            val initialGrades = existing?.let { GradeSystemRef.gradesFromJson(it.gradesJson) }
            if (initialGrades != null && initialGrades.isNotEmpty()) {
                initialGrades.entries.sortedBy { it.value }.forEach { (label, value) ->
                    addRow(label, GradeTable.formatValue(value), originalLabel = label)
                }
            } else {
                GradeTable.DEFAULT_ROWS.forEach { (label, value) -> addRow(label, value) }
            }

            val addGradeBtn = TextView(ctx).apply {
                text = ctx.getString(R.string.label_add_grade)
                setTextColor(ctx.getColor(R.color.primary))
                setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
                setOnClickListener { addRow() }
            }
            layout.addView(addGradeBtn)

            // 등급 줄은 '등급 추가'로 무한히 는다 — 상한 없이는 추가한 줄이 화면 밖으로 밀린다(B-91).
            val scroll = cappedScrollView(ctx).apply { addView(layout) }
            val dialog = MaterialAlertDialogBuilder(ctx)
                .setTitle(if (existing == null) R.string.grade_system_add_title else R.string.grade_system_edit_title)
                .setView(scroll)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()

            dialog.setValidatedPositiveButton {
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    nameEdit.showInlineError(ctx.getString(R.string.grade_system_name_required_error))
                    return@setValidatedPositiveButton false
                }
                if (siblings.any { it.name == name && it.id != (existing?.id ?: 0L) }) {
                    nameEdit.showInlineError(ctx.getString(R.string.grade_system_name_duplicate_error))
                    return@setValidatedPositiveButton false
                }
                val outcome = GradeTable.build(
                    rows.map { it.editLabel.text.toString() to it.editValue.text.toString() }
                )
                val problem = outcome.problems.firstOrNull()
                if (problem != null) {
                    // 오류는 고칠 자리에 붙인다(B-28) — 문제의 행을 라벨로 되찾고, 못 찾으면 이름 칸.
                    (targetFor(problem, rows) ?: nameEdit)
                        .showInlineError(gradeProblemMessage(ctx, problem))
                    return@setValidatedPositiveButton false
                }

                // 라벨 개명 추적 — 행의 정체(originalLabel)로 재정의가 개명을 따라가게 한다.
                val renames = rows.mapNotNull { row ->
                    val original = row.originalLabel ?: return@mapNotNull null
                    val current = row.editLabel.text.toString().trim()
                    if (current.isNotEmpty() && current != original) original to current else null
                }.toMap()

                val system = (existing ?: GradeSystem(
                    universeId = universeId, name = name, displayOrder = siblings.size
                )).copy(
                    name = name,
                    gradesJson = GradeSystemRef.gradesToJson(outcome.grades)
                )
                scope.launch {
                    val saved = try {
                        repository.saveSystem(system, renames)
                    } catch (e: Exception) {
                        android.util.Log.e("GradeSystemEditor", "Failed to save grade system", e)
                        Toast.makeText(
                            ctx, ctx.getString(R.string.result_grade_system_save_failed, system.name),
                            Toast.LENGTH_LONG
                        ).show()
                        null
                    } ?: return@launch
                    if (existing != null && saved.propagatedFields > 0) {
                        Toast.makeText(
                            ctx, ctx.getString(R.string.grade_system_saved_toast, name, saved.propagatedFields),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // 등급을 지우면 대결 등급 산정의 그 구간이 다음 등급에 합쳐진다 —
                    // 배정 폭이 바뀐 사실을 말하지 않으면 사용자는 다음 반영에서야 안다.
                    if (saved.duelCutsMerged.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            ctx.getString(
                                R.string.grade_system_duel_cuts_merged,
                                saved.duelCutsMerged.joinToString(", ")
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    // **저장된 체계를 그대로 돌려준다** — 새로 만든 경우 부르는 쪽이 그것을
                    // 고른 상태로 돌아가야 하고, code는 저장 뒤에야 확정된다.
                    onSaved(saved.system)
                }
                true
            }
            dialog.show()
        }
    }

    private data class SystemRow(
        val editLabel: EditText,
        val editValue: EditText,
        val originalLabel: String?
    )

    /** 문제를 고칠 칸 — 못 찾으면 null이고 부르는 쪽이 이름 칸으로 떨어뜨린다. */
    private fun targetFor(
        problem: GradeTable.Problem,
        rows: List<SystemRow>
    ): EditText? = when (problem) {
        is GradeTable.Problem.BadNumber ->
            rows.firstOrNull { it.editLabel.text.toString().trim() == problem.label }?.editValue
        is GradeTable.Problem.DuplicateLabel ->
            rows.lastOrNull { it.editLabel.text.toString().trim() == problem.label }?.editLabel
        is GradeTable.Problem.SignPrefixedLabel ->
            rows.lastOrNull { it.editLabel.text.toString().trim() == problem.label }?.editLabel
        is GradeTable.Problem.BlankLabel ->
            rows.firstOrNull {
                it.editLabel.text.toString().isBlank() &&
                    it.editValue.text.toString().trim() == problem.valueText
            }?.editLabel
        GradeTable.Problem.Empty -> null
    }
}
