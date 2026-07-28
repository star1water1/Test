package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FormulaValidator]의 계약 고정 (색출 개혁 로드맵 5 / S-11).
 *
 * 핵심은 두 방향을 함께 붙드는 것이다: **잘못을 놓치지 않는다**와 **정상 수식에 경고를 붙이지
 * 않는다.** 뒤쪽이 무너지면 사용자는 경고를 읽지 않게 되고, 그러면 앞쪽도 함께 무의미해진다.
 */
class FormulaValidatorTest {

    private val keys = setOf("str", "dex", "hp", "total")

    private fun validate(
        formula: String,
        currentKey: String = "total",
        knownKeys: Set<String>? = keys,
        calculated: Map<String, String> = emptyMap()
    ) = FormulaValidator.validate(formula, currentKey, knownKeys, calculated)

    private inline fun <reified T> hasProblem(problems: List<FormulaValidator.Problem>) =
        problems.any { it is T }

    // ── 정상 수식은 조용하다 ──

    @Test
    fun `정상 수식에는 경고가 없다`() {
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("field(str) + field(dex)"))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("(field(str) + field(dex)) * 2"))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("max(field(str), field(dex))"))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("abs(-field(dex))"))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("sum_all_grades()"))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("field('str') / field(\"dex\")"))
    }

    @Test
    fun `빈 수식은 검사하지 않는다`() {
        assertEquals(emptyList<FormulaValidator.Problem>(), validate(""))
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("   "))
    }

    @Test
    fun `키 목록을 못 읽었으면 키 검사만 건너뛴다`() {
        // 있는 키를 없다고 알리는 것보다 묻지 않는 편이 낫다.
        assertEquals(emptyList<FormulaValidator.Problem>(), validate("field(무엇이든)", knownKeys = null))
        // 그래도 문법 검사는 살아 있다.
        assertTrue(hasProblem<FormulaValidator.Problem.UnknownFunctions>(validate("sqrt(4)", knownKeys = null)))
    }

    // ── 괄호 ──

    @Test
    fun `괄호 짝이 맞지 않으면 알린다`() {
        assertTrue(hasProblem<FormulaValidator.Problem.UnbalancedParen>(validate("((field(str))")))
        // 닫는 괄호가 더 많은 쪽도 짝이 맞지 않는 것은 같다 (평가는 조용히 버린다)
        assertTrue(hasProblem<FormulaValidator.Problem.UnbalancedParen>(validate("field(str))")))
    }

    @Test
    fun `키에 든 괄호를 짝으로 세지 않는다`() {
        // 원문의 괄호를 세면 거짓 경고가 난다 — 어휘로 세기 때문에 조용하다.
        assertTrue(!hasProblem<FormulaValidator.Problem.UnbalancedParen>(validate("field(str) + 1")))
    }

    // ── 자기참조·순환 참조 ──

    @Test
    fun `자기 자신을 참조하면 알린다`() {
        val problems = validate("field(total) + 1", currentKey = "total")
        val self = problems.filterIsInstance<FormulaValidator.Problem.SelfReference>().single()
        assertEquals("total", self.key)
        // 자기참조는 '없는 키'로 중복 보고하지 않는다
        assertTrue(!hasProblem<FormulaValidator.Problem.UnknownKeys>(problems))
    }

    @Test
    fun `수식끼리 돌아오면 경로를 알린다`() {
        // total → a → b → total
        val problems = validate(
            "field(a)",
            currentKey = "total",
            knownKeys = keys + setOf("a", "b"),
            calculated = mapOf("a" to "field(b)", "b" to "field(total)")
        )
        val cycle = problems.filterIsInstance<FormulaValidator.Problem.CircularReference>().single()
        assertEquals(listOf("total", "a", "b", "total"), cycle.path)
    }

    @Test
    fun `내가 끼지 않은 순환도 내 값을 무너뜨리므로 알린다`() {
        // total → a, 그리고 a ↔ b 가 이미 돌고 있다
        val problems = validate(
            "field(a)",
            currentKey = "total",
            knownKeys = keys + setOf("a", "b"),
            calculated = mapOf("a" to "field(b)", "b" to "field(a)")
        )
        val cycle = problems.filterIsInstance<FormulaValidator.Problem.CircularReference>().single()
        assertEquals(listOf("a", "b", "a"), cycle.path)
    }

    @Test
    fun `수식이 아닌 필드를 거치면 순환이 아니다`() {
        // total → hp 는 값에서 끝난다. hp가 total이라는 이름의 값을 갖고 있어도 돌아오지 않는다.
        val problems = validate("field(hp)", currentKey = "total", calculated = emptyMap())
        assertTrue(!hasProblem<FormulaValidator.Problem.CircularReference>(problems))
    }

    @Test
    fun `긴 참조 사슬도 끝까지 따라간다`() {
        val chain = (1..30).associate { "c$it" to "field(c${it + 1})" } + ("c31" to "field(total)")
        val problems = validate(
            "field(c1)",
            currentKey = "total",
            knownKeys = keys + chain.keys,
            calculated = chain
        )
        assertTrue(hasProblem<FormulaValidator.Problem.CircularReference>(problems))
    }

    // ── 필드 키 ──

    @Test
    fun `없는 키를 알린다`() {
        val problems = validate("field(str) + field(없는키)")
        assertEquals(listOf("없는키"), problems.filterIsInstance<FormulaValidator.Problem.UnknownKeys>().single().keys)
    }

    @Test
    fun `키 앞뒤 공백은 없는 키와 나눠서 알린다`() {
        // 눈으로는 옳아 보이는데 평가는 0이 된다 — 고치는 법이 다르므로 문구도 다르다.
        val problems = validate("field( str )")
        assertEquals(listOf(" str "), problems.filterIsInstance<FormulaValidator.Problem.PaddedKeys>().single().keys)
        assertTrue(!hasProblem<FormulaValidator.Problem.UnknownKeys>(problems))
    }

    @Test
    fun `아직 저장되지 않은 새 필드의 키는 없는 키가 아니다`() {
        // 필드를 만들면서 자기 키를 목록에서 찾을 수 없는 것은 정상이다.
        val problems = validate("field(신규) * 2", currentKey = "신규")
        assertTrue(!hasProblem<FormulaValidator.Problem.UnknownKeys>(problems))
    }

    // ── 계산에서 버려지는 것 ──

    @Test
    fun `없는 함수 이름을 알린다`() {
        val problems = validate("sqrt(4)")
        assertEquals(listOf("sqrt"), problems.filterIsInstance<FormulaValidator.Problem.UnknownFunctions>().single().names)
    }

    @Test
    fun `아는 함수인데 표기가 어긋난 것은 따로 알린다`() {
        // 이름과 여는 괄호 사이 공백
        assertEquals(
            listOf("abs"),
            validate("abs (field(dex))").filterIsInstance<FormulaValidator.Problem.MalformedCalls>().single().names
        )
        // 인자를 받지 않는 함수에 인자
        assertEquals(
            listOf("sum_all_grades"),
            validate("sum_all_grades(1)").filterIsInstance<FormulaValidator.Problem.MalformedCalls>().single().names
        )
        // field도 같은 규칙이다
        assertTrue(hasProblem<FormulaValidator.Problem.MalformedCalls>(validate("field ('str')")))
        // 괄호를 아예 잊은 이름도 '모르는 함수'가 아니라 표기 문제다
        assertEquals(
            listOf("abs"),
            validate("field(str) + abs").filterIsInstance<FormulaValidator.Problem.MalformedCalls>().single().names
        )
    }

    @Test
    fun `연산자 오타와 설명 글자를 알린다`() {
        assertEquals(
            listOf("&"),
            validate("field(str) & field(dex)")
                .filterIsInstance<FormulaValidator.Problem.UnrecognizedText>().single().fragments
        )
        assertEquals(
            listOf("그리고"),
            validate("field(str) 그리고 field(dex)")
                .filterIsInstance<FormulaValidator.Problem.UnrecognizedText>().single().fragments
        )
    }

    @Test
    fun `함수 자리 앞에 붙은 기호는 이름과 나눠서 알린다`() {
        val problems = validate("field(str) &sqrt(4)")
        assertEquals(listOf("sqrt"), problems.filterIsInstance<FormulaValidator.Problem.UnknownFunctions>().single().names)
        assertEquals(listOf("&"), problems.filterIsInstance<FormulaValidator.Problem.UnrecognizedText>().single().fragments)
    }

    @Test
    fun `같은 문제는 한 번만 알린다`() {
        val problems = validate("sqrt(1) + sqrt(2) + sqrt(3)")
        assertEquals(listOf("sqrt"), problems.filterIsInstance<FormulaValidator.Problem.UnknownFunctions>().single().names)
    }

    // ── 어휘 단일 소스 ──

    @Test
    fun `함수 표에 있는 이름은 미지 함수가 아니다`() {
        // 표에 함수를 추가했는데 검증기가 모르면 정상 수식에 거짓 경고가 붙는다.
        for ((name, arity) in FormulaLexer.FUNCTIONS) {
            val args = (1..arity).joinToString(", ") { "field(str)" }
            val problems = validate("$name($args)")
            assertEquals("$name 은 아는 함수여야 한다", emptyList<FormulaValidator.Problem>(), problems)
        }
    }
}
