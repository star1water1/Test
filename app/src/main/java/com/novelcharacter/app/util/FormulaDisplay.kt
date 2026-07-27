package com.novelcharacter.app.util

import java.util.Locale

/**
 * CALCULATED(수식) 값을 **사람에게 보이는 문자열**로 바꾸는 단일 소스 (U-9).
 *
 * ## 왜 한 곳으로 모았나
 *
 * 종전에는 평가·서식이 일곱 곳에 각자 복제돼 있었다(통계·엑셀 내보내기·캐릭터 상세·
 * 동적 필드 렌더러·상태 시점 해석·캐릭터 비교·PDF 내보내기). 그 결과 두 가지가 갈렸다:
 *
 * 1. **오류가 보이지 않았다.** 수식이 깨져 NaN·Inf가 나오면 대부분의 자리는 그 필드를
 *    결과에서 **빼 버렸다**. 화면·엑셀·PDF에서 그 칸이 그냥 비어 보이므로, 사용자는
 *    수식이 고장 났다는 사실 자체를 알 수 없었다. 오류를 말하는 곳은 [TimeStateResolver]
 *    하나뿐이었다.
 * 2. **로케일에 따라 값이 달라졌다.** `"%.2f".format(v)`는 기본 로케일을 쓰므로 소수점이
 *    콤마인 환경에서 `12,34`가 된다. 로케일을 고정한 곳은 통계 하나뿐이었고 그마저
 *    `private`이라 아무도 쓸 수 없었다.
 *
 * 표시가 필요한 자리는 전부 [evaluateForDisplay]를, 수치 서식만 필요한 자리는 [format]을
 * 부른다. **여기 없는 서식 규칙을 호출부에 따로 두지 않는다** — 그것이 위 두 결함의 원인이다.
 *
 * ## 수치가 필요한 경로는 이 파일을 쓰지 않는다
 *
 * 정렬·순위·백분위는 원시 `Double`이 필요하다(서식 문자열을 되파싱하면 `%.2f` 반올림이
 * 경계를 바꾼다). 그런 자리는 `FormulaEvaluator.evaluate`를 직접 부르고 유한성만 확인한다.
 */
object FormulaDisplay {

    /** 수식이 평가되지 않을 때(NaN·무한대·예외) 그 자리에 보이는 표식. */
    const val FORMULA_ERROR_DISPLAY = "오류"

    /**
     * 수치를 표시 문자열로 — **로케일 고정**.
     *
     * 이 문자열은 표시용으로만 쓰이지 않는다: 통계에서는 분포의 키가 되고, 드릴다운 매칭 키가
     * 되고, 레거시 수치 요약에서 다시 `toFloat`로 파싱된다. 기본 로케일을 쓰면 소수점이 콤마인
     * 환경에서 `12,34`가 되어 되파싱이 실패하고 그 캐릭터가 요약에서 조용히 사라진다.
     */
    fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else String.format(Locale.US, "%.2f", value)

    /**
     * 수식을 평가해 표시 문자열로. 평가에 실패하면 [FORMULA_ERROR_DISPLAY]를 돌려준다.
     *
     * **빈 문자열이 아니라 오류 표식을 돌려주는 이유**: 빈 칸은 '값이 없음'과 구분되지 않아
     * 고장 난 수식이 조용히 묻힌다. 사용자가 잘못을 바로잡으려면 먼저 잘못이 있다는 것을
     * 볼 수 있어야 한다.
     *
     * @param evaluate 수식 하나를 수치로 평가하는 함수 — 보통 `evaluator::evaluate`.
     */
    inline fun evaluateForDisplay(formula: String, evaluate: (String) -> Double): String =
        try {
            val value = evaluate(formula)
            if (value.isNaN() || value.isInfinite()) FORMULA_ERROR_DISPLAY else format(value)
        } catch (_: Exception) {
            FORMULA_ERROR_DISPLAY
        }
}
