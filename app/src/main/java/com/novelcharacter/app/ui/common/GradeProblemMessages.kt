package com.novelcharacter.app.ui.common

import android.content.Context
import com.novelcharacter.app.R
import com.novelcharacter.app.util.GradeTable

/**
 * [GradeTable.Problem] → 화면 문구 (B-69 → U-1).
 *
 * 순수 계층(GradeTable)은 타입만 돌려주고 문구는 여기서 번역한다. 필드 편집의 등급 표와
 * 세계관의 등급 체계 편집이 **같은 검증 규칙**을 쓰므로 문구도 한 곳이어야 한다 —
 * 두 벌이 되면 같은 문제가 화면마다 다른 말을 한다.
 */
fun gradeProblemMessage(context: Context, problem: GradeTable.Problem): String = when (problem) {
    is GradeTable.Problem.BlankLabel ->
        context.getString(R.string.grade_error_blank_label, problem.valueText)
    is GradeTable.Problem.BadNumber ->
        context.getString(R.string.grade_error_bad_number, problem.label, problem.valueText)
    is GradeTable.Problem.DuplicateLabel ->
        context.getString(R.string.grade_error_duplicate, problem.label)
    is GradeTable.Problem.SignPrefixedLabel ->
        context.getString(R.string.grade_error_sign_prefix, problem.label)
    GradeTable.Problem.Empty ->
        context.getString(R.string.grade_error_empty)
}

/**
 * 수치가 겹치는 등급 무리 → 화면 문구. 겹침이 없으면 **null**이라 부르는 쪽이 줄을 숨긴다.
 *
 * [GradeTable.Problem]과 달리 저장을 막지 않는다 — 사유는 [GradeTable.duplicateValues]의
 * KDoc에 있다. 문구가 여기 사는 이유는 위 [gradeProblemMessage]와 같다: 필드 편집과
 * 등급 체계 편집이 같은 판정을 쓰므로 같은 말을 해야 한다.
 */
fun gradeValueCollisionMessage(
    context: Context,
    groups: List<GradeTable.DuplicateValueGroup>
): String? {
    if (groups.isEmpty()) return null
    val joined = groups.joinToString(", ") { group ->
        context.getString(
            R.string.grade_value_collision_group,
            group.labels.joinToString("·"),
            GradeTable.formatValue(group.value)
        )
    }
    return context.getString(R.string.grade_value_collision_notice, joined)
}
