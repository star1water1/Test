package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FormulaEvaluator]의 계약 고정 (색출 개혁 로드맵 5 / S-11).
 *
 * 이 파일이 생기기 전까지 수식 평가에는 테스트가 **한 건도 없었다**. 로드맵 5가 어휘 분석을
 * [FormulaLexer]로 떼어내면서 평가의 겉보기 동작이 바뀌지 않았음을 여기서 붙든다.
 *
 * **일부러 '틀린 답'을 고정한 케이스가 있다.** `sqrt(4)`는 4를 돌려준다 — 이름이 버려지고
 * 괄호만 남기 때문이다. 평가기는 버려진 글자를 되살릴 수 없으므로(어휘 목록에만 남는다)
 * 이 자리를 막는 것은 저장 시점의 [FormulaValidator]다. 두 방어선의 경계를 눈에 보이게
 * 두려고 현재 값을 그대로 적어 둔다.
 */
class FormulaEvaluatorTest {

    private val defs = listOf(
        def(1, "str", "NUMBER"),
        def(2, "dex", "NUMBER"),
        def(3, "selfref", "CALCULATED", """{"formula":"field(selfref) + 1"}"""),
        def(4, "a", "CALCULATED", """{"formula":"field(b) + 1"}"""),
        def(5, "b", "CALCULATED", """{"formula":"field(a) * 2"}"""),
        def(6, "rank", "GRADE", """{"grades":{"C":0.5,"B":1,"A":2,"S":3}}""")
    )

    private fun def(id: Long, key: String, type: String, config: String = "{}") =
        FieldDefinition(id = id, universeId = 1, name = key, key = key, type = type, config = config)

    private fun eval(formula: String, values: Map<String, String> = mapOf("str" to "10", "dex" to "3")) =
        FormulaEvaluator(values, defs).evaluate(formula)

    // ── 정상 경로 ──

    @Test
    fun `필드 참조와 사칙연산`() {
        assertEquals(13.0, eval("field(str) + field(dex)"), 0.0001)
        assertEquals(7.0, eval("field(str) - field(dex)"), 0.0001)
        assertEquals(30.0, eval("field(str) * field(dex)"), 0.0001)
        assertEquals(5.0, eval("field(str) / 2"), 0.0001)
    }

    @Test
    fun `따옴표 있는 키와 없는 키가 같다`() {
        assertEquals(10.0, eval("field(str)"), 0.0001)
        assertEquals(10.0, eval("field('str')"), 0.0001)
        assertEquals(10.0, eval("""field("str")"""), 0.0001)
    }

    @Test
    fun `연산자 우선순위와 괄호`() {
        assertEquals(16.0, eval("field(str) + 2 * field(dex)"), 0.0001)
        assertEquals(26.0, eval("(field(str) + field(dex)) * 2"), 0.0001)
    }

    @Test
    fun `단항 부정은 곱셈보다 강하게 묶인다`() {
        assertEquals(-30.0, eval("field(str) * -field(dex)"), 0.0001)
        // 부호가 연달아 붙으면 빼기 개수가 홀수일 때만 부정이다
        assertEquals(3.0, eval("--field(dex)"), 0.0001)
        assertEquals(-3.0, eval("---field(dex)"), 0.0001)
        assertEquals(3.0, eval("- -field(dex)"), 0.0001)
    }

    @Test
    fun `함수는 인자 수대로 계산한다`() {
        assertEquals(3.0, eval("abs(-field(dex))"), 0.0001)
        assertEquals(10.0, eval("max(field(str), field(dex))"), 0.0001)
        assertEquals(3.0, eval("min(field(str), field(dex))"), 0.0001)
        assertEquals(6.5, eval("avg(field(str), field(dex))"), 0.0001)
    }

    @Test
    fun `등급 필드는 등급값으로 환산된다`() {
        val values = mapOf("str" to "10", "dex" to "3", "rank" to "A")
        assertEquals(2.0, eval("field(rank)", values), 0.0001)
        assertEquals(2.0, eval("sum_all_grades()", values), 0.0001)
    }

    @Test
    fun `값이 없는 필드는 0으로 친다`() {
        assertEquals(10.0, eval("field(str) + field(dex)", mapOf("str" to "10")), 0.0001)
    }

    // ── 오류가 NaN으로 드러나는 경로 (U-9의 '오류' 표시가 닿는 자리) ──

    @Test
    fun `0으로 나누면 NaN`() {
        assertTrue(eval("field(str) / 0").isNaN())
    }

    @Test
    fun `자기참조는 NaN`() {
        assertTrue(eval("field(selfref)").isNaN())
    }

    @Test
    fun `전이 순환 참조는 NaN`() {
        assertTrue(eval("field(a)").isNaN())
    }

    @Test
    fun `여는 괄호가 남으면 NaN`() {
        assertTrue(eval("((field(str))").isNaN())
    }

    @Test
    fun `연산자가 모자라면 NaN`() {
        assertTrue(eval("field(str) +").isNaN())
    }

    @Test
    fun `연산자가 빠져 값이 둘 남으면 NaN`() {
        // 종전에는 마지막 값(3)을 그대로 돌려줘 그럴듯한 오답이 됐다.
        assertTrue(eval("field(str) field(dex)").isNaN())
        assertTrue(eval("field(str) & field(dex)").isNaN())
        // pow가 버려지면 앞의 "2 *"가 갈 곳을 잃는다 — 종전 결과는 6이었다.
        assertTrue(eval("2 * pow(3,2)").isNaN())
    }

    // ── 평가기가 구제할 수 없는 자리 (FormulaValidator가 저장 시점에 막는다) ──

    @Test
    fun `미지 함수는 이름만 버려지고 괄호 안 값이 남는다`() {
        // 버려진 이름은 평가 토큰에 흔적이 없다 — 그래서 여기서는 오류로 만들 수 없다.
        assertEquals(4.0, eval("sqrt(4)"), 0.0001)
        assertEquals(14.0, eval("field(str) + sqrt(4)"), 0.0001)
        FormulaValidatorAssert.flagsUnknownFunction("sqrt(4)")
    }

    @Test
    fun `함수 이름과 여는 괄호 사이 공백은 이름을 버린다`() {
        // abs가 버려져 절댓값이 적용되지 않는다 — 음수였다면 답이 뒤집힌다.
        assertEquals(-3.0, eval("abs (-field(dex))"), 0.0001)
        FormulaValidatorAssert.flagsMalformedCall("abs (-field(dex))")
    }

    @Test
    fun `키 앞뒤 공백은 다른 키로 읽혀 0이 된다`() {
        assertEquals(0.0, eval("field( str )"), 0.0001)
        FormulaValidatorAssert.flagsPaddedKey("field( str )", setOf("str"))
    }

    /** 평가기가 못 막는 자리를 검증기가 막는다는 것을 같은 케이스로 이어 확인한다. */
    private object FormulaValidatorAssert {
        fun flagsUnknownFunction(formula: String) = assertTrue(
            "검증기가 미지 함수를 잡아야 한다: $formula",
            FormulaValidator.validate(formula, "x", null)
                .any { it is FormulaValidator.Problem.UnknownFunctions }
        )

        fun flagsMalformedCall(formula: String) = assertTrue(
            "검증기가 함수 표기 오류를 잡아야 한다: $formula",
            FormulaValidator.validate(formula, "x", null)
                .any { it is FormulaValidator.Problem.MalformedCalls }
        )

        fun flagsPaddedKey(formula: String, keys: Set<String>) = assertTrue(
            "검증기가 키 앞뒤 공백을 잡아야 한다: $formula",
            FormulaValidator.validate(formula, "x", keys)
                .any { it is FormulaValidator.Problem.PaddedKeys }
        )
    }
}
