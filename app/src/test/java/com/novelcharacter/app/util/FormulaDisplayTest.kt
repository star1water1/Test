package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * P2(U-9) 회귀 고정 — CALCULATED 표시의 두 계약.
 *
 * 종전 결함:
 *  1. 평가가 실패(NaN·Inf·예외)하면 대부분의 표시 경로가 그 필드를 **결과에서 빼 버려**
 *     칸이 그냥 비어 보였다 — 사용자는 수식이 고장 났다는 것 자체를 알 수 없었다.
 *  2. `"%.2f".format(v)`가 **기본 로케일**을 써서, 소수점이 콤마인 환경에서 같은 수식이
 *     `12,34`가 됐다. 그 문자열은 통계에서 분포 키·드릴다운 키로 쓰이고 되파싱까지 되므로
 *     로케일이 달라지면 항목이 조용히 갈라지거나 사라진다.
 */
class FormulaDisplayTest {

    // ===== 오류 표식 =====

    @Test
    fun `NaN은 오류 표식으로 나온다`() {
        assertEquals(
            FormulaDisplay.FORMULA_ERROR_DISPLAY,
            FormulaDisplay.evaluateForDisplay("x") { Double.NaN }
        )
    }

    @Test
    fun `무한대도 오류 표식으로 나온다`() {
        assertEquals(
            FormulaDisplay.FORMULA_ERROR_DISPLAY,
            FormulaDisplay.evaluateForDisplay("x") { Double.POSITIVE_INFINITY }
        )
        assertEquals(
            FormulaDisplay.FORMULA_ERROR_DISPLAY,
            FormulaDisplay.evaluateForDisplay("x") { Double.NEGATIVE_INFINITY }
        )
    }

    @Test
    fun `평가 중 예외가 나도 빈 문자열이 아니라 오류 표식이다`() {
        assertEquals(
            FormulaDisplay.FORMULA_ERROR_DISPLAY,
            FormulaDisplay.evaluateForDisplay("x") { throw IllegalStateException("boom") }
        )
    }

    @Test
    fun `정상 값은 그대로 서식된다`() {
        assertEquals("3", FormulaDisplay.evaluateForDisplay("x") { 3.0 })
        assertEquals("3.50", FormulaDisplay.evaluateForDisplay("x") { 3.5 })
    }

    // ===== 서식 =====

    @Test
    fun `정수는 소수점 없이 나온다`() {
        assertEquals("0", FormulaDisplay.format(0.0))
        assertEquals("42", FormulaDisplay.format(42.0))
        assertEquals("-7", FormulaDisplay.format(-7.0))
    }

    @Test
    fun `소수는 둘째 자리까지 반올림된다`() {
        assertEquals("23.46", FormulaDisplay.format(23.456))
        assertEquals("0.50", FormulaDisplay.format(0.5))
    }

    /**
     * **로케일 무관 계약**: 기본 로케일이 소수점에 콤마를 쓰는 곳(독일·프랑스 등)이어도
     * 같은 문자열이어야 한다. 이 문자열은 표시용이 아니라 값의 정체성이기 때문이다.
     */
    @Test
    fun `기본 로케일이 콤마 소수점이어도 같은 문자열이다`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("23.46", FormulaDisplay.format(23.456))
            assertEquals("3.50", FormulaDisplay.evaluateForDisplay("x") { 3.5 })

            Locale.setDefault(Locale.FRANCE)
            assertEquals("23.46", FormulaDisplay.format(23.456))

            Locale.setDefault(Locale.KOREA)
            assertEquals("23.46", FormulaDisplay.format(23.456))
        } finally {
            Locale.setDefault(original)
        }
    }

    /** 오류 표식도 로케일과 무관하게 한 문자열이어야 한다(문구가 갈리면 되읽기가 깨진다). */
    @Test
    fun `오류 표식은 로케일과 무관하게 같다`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val a = FormulaDisplay.evaluateForDisplay("x") { Double.NaN }
            Locale.setDefault(Locale.KOREA)
            val b = FormulaDisplay.evaluateForDisplay("x") { Double.NaN }
            assertEquals(a, b)
        } finally {
            Locale.setDefault(original)
        }
    }
}
