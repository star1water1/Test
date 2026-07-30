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
