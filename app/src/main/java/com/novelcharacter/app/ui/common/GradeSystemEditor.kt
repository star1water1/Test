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
import com.novelcharacter.app.util.OpResult
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

            // 수치가 겹치면 그 체계를 참조하는 **필드 전체**가 두 등급의 우열을 못 가린다 —
            // 필드 편집 화면과 같은 판정·같은 문구를 쓴다(`gradeValueCollisionMessage`).
            // 저장은 막지 않는다: 겹침은 못 쓰는 표가 아니라 뜻이 무너진 표라, 처분은 알림이다.
            val collisionNotice = TextView(ctx).apply {
                textSize = 12f
                setTextColor(ctx.getColor(R.color.error))
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                visibility = android.view.View.GONE
            }

            fun renderCollisionNotice() {
                val grades = LinkedHashMap<String, Double>()
                for (row in rows) {
                    val label = row.editLabel.text.toString().trim()
                    val value = row.editValue.text.toString().trim().toDoubleOrNull()
                    if (label.isEmpty() || value == null || !value.isFinite()) continue
                    grades[label] = value
                }
                val text = gradeValueCollisionMessage(ctx, GradeTable.duplicateValues(grades))
                collisionNotice.text = text.orEmpty()
                collisionNotice.visibility =
                    if (text == null) android.view.View.GONE else android.view.View.VISIBLE
            }

            // **글자마다가 아니라 포커스를 뗄 때다** — 타이핑 중간의 반쪽 숫자로 경고가
            // 붙었다 떨어지면 신호가 아니라 소음이 된다(등급 행의 다른 갱신과 같은 잣대).
            val notifyOnFocusLost = android.view.View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) renderCollisionNotice()
            }

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
                editLabel.onFocusChangeListener = notifyOnFocusLost
                editValue.onFocusChangeListener = notifyOnFocusLost
                val entry = SystemRow(editLabel, editValue, originalLabel)
                val btnRemove = TextView(ctx).apply {
                    text = "✕"
                    setTextColor(ctx.getColor(R.color.primary))
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), 0, 0)
                    setOnClickListener {
                        rowsContainer.removeView(row)
                        rows.remove(entry)
                        renderCollisionNotice()
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

            // 겹친 체계는 **열자마자** 보여야 한다 — 행을 건드려야 뜨는 안내는
            // 이미 겹친 체계에서 영영 안 뜬다.
            layout.addView(collisionNotice)
            renderCollisionNotice()

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

            /**
             * 저장이 도는 중인가 — **두 번 눌러도 한 번만 저장한다.**
             * 이 창은 저장이 끝난 뒤에야 닫히므로 그사이 버튼이 살아 있다.
             */
            var saving = false

            dialog.setValidatedPositiveButton {
                if (saving) return@setValidatedPositiveButton false
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
                saving = true
                scope.launch {
                    val saved = try {
                        repository.saveSystem(system, renames)
                    } catch (e: Exception) {
                        android.util.Log.e("GradeSystemEditor", "Failed to save grade system", e)
                        // **작업 이력에도 남긴다.** 종전 경로는 `UniverseViewModel.saveGradeSystem`이
                        // `reportResult`를 불렀고 그 함수가 *알림 + 이력 기록*을 한 몸으로 했다
                        // (`ResultReporting` KDoc — "이력 기록을 중복 배선하지 않기 위한 단일 진입점").
                        // 뗀 뒤 토스트만 남기면 **실패가 작업 이력에서 사라진다** — 사용자가 나중에
                        // *"그때 왜 안 저장됐지"*를 되짚을 자리가 없어진다(개발 의도 2번).
                        // 이 창은 ViewModel도 Fragment도 아니라 저장소를 직접 부른다
                        // (`logOperation`·`logResult`가 안에서 하는 것과 같은 일이다).
                        val failure = OpResult.failure(
                            OpResult.CAT_UNIVERSE,
                            ctx.getString(R.string.result_grade_system_save_failed, system.name),
                            e.message
                        )
                        app.operationLogRepository.logAsync(failure)
                        Toast.makeText(ctx, failure.summary, Toast.LENGTH_LONG).show()
                        null
                    }
                    saving = false
                    // **실패하면 창을 열어 둔다** — 적어 둔 등급 표가 그대로 남아야 다시 누를 수 있다(R-27).
                    if (saved == null) return@launch
                    dialog.dismiss()
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
                // **여기서 닫지 않는다** — 저장이 끝난 뒤 위 코루틴이 닫는다.
                //
                // 지금 닫으면 저장은 `scope`에 매달린 채 창만 사라진다. 그 스코프가 화면 수명을
                // 타므로(호출부가 `viewLifecycleOwner.lifecycleScope`·`lifecycleScope`를 넘긴다),
                // 저장을 누른 직후 회전·이동으로 화면이 사라지면 **코루틴이 취소돼 체계가
                // 저장되지 않는다.** 오류도 안 뜨므로 사용자는 저장된 줄 안다(조용한 유실).
                // 같은 자리를 이 저장소가 이미 한 번 겪었다(로드맵 9판 — 프리셋 저장).
                // 비동기 완료 시점에 창을 직접 닫는 것은 `FieldEditDialog`의 타입 변경 영향
                // 분석이 세워 둔 규약과 같다.
                false
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
