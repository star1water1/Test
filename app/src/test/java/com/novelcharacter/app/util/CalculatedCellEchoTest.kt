package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 계산 열의 셀이 **앱 자신의 출력**인가 (워크스루 3-ㄱ-2).
 *
 * 내보내기는 계산 열에 평가된 값을 실제로 쓴다. 되읽는 쪽이 *"비었는가"*로만 판정하면
 * **한 글자도 안 고친 왕복**이 '건너뜀'과 "값을 직접 넣으려면 타입을 바꾸세요" 경고를 낸다 —
 * 정상 파일이 상한 파일처럼 보여 사용자가 진짜 경고를 잡음 속에서 잃는다.
 */
class CalculatedCellEchoTest {

    private fun field(key: String, type: FieldType, config: String = "{}") =
        FieldDefinition(id = key.hashCode().toLong(), universeId = 1L, key = key, name = key,
            type = type.name, config = config)

    private val power = field("power", FieldType.NUMBER)
    private val agility = field("agility", FieldType.NUMBER)
    private val total = field("total", FieldType.CALCULATED, """{"formula":"field(power) + field(agility)"}""")
    private val scope = listOf(power, agility, total)

    @Test
    fun `내보내기가 쓴 값 그대로면 앱의 출력이다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        // 내보내기는 FormulaDisplay.format을 지나 쓴다 — 같은 글자여야 한다.
        val written = FormulaDisplay.evaluateForDisplay("field(power) + field(agility)") {
            FormulaEvaluator(stored, scope).evaluate(it)
        }
        assertTrue(CalculatedCellEcho.isAppOutput(total, written, scope, stored))
    }

    /**
     * **셀 왕복이 표시 서식을 잃어도 앱의 출력이다** (콜드 검토 2026.08.21).
     *
     * 내보내기는 계산 값을 **숫자 셀**로 싣는다. `FormulaDisplay.format`이 소수를
     * `%.2f`로 적으므로 `3.5`는 `"3.50"`으로 쓰이는데, 되읽기는 그 숫자를 최단 십진
     * 표현으로 되돌려 `"3.5"`가 온다. 글자만 견주면 **소수 결과인 필드마다** 무편집
     * 왕복이 '건너뜀'으로 세어지고 경고가 붙는다 — 이 판이 없애기로 한 그 잡음이다.
     */
    @Test
    fun `소수 결과는 셀 왕복으로 뒤 0을 잃어도 앱의 출력이다`() {
        val stored = mapOf("power" to "7.5", "agility" to "0")
        val written = FormulaDisplay.evaluateForDisplay("field(power) + field(agility)") {
            FormulaEvaluator(stored, scope).evaluate(it)
        }
        assertEquals("내보내기가 쓰는 글자는 서식이 붙은 쪽이다", "7.50", written)
        // 숫자 셀을 되읽으면 최단 십진 표현으로 온다 — 뒤의 0이 사라진다.
        assertTrue(CalculatedCellEcho.isAppOutput(total, "7.5", scope, stored))
        // 서식이 붙은 원문도 그대로 통과한다
        assertTrue(CalculatedCellEcho.isAppOutput(total, written, scope, stored))
    }

    /** 수가 **다르면** 여전히 사용자의 입력이다 — 수치 비교가 판정을 느슨하게 만들지 않는다. */
    @Test
    fun `수치가 다르면 소수라도 앱의 출력이 아니다`() {
        val stored = mapOf("power" to "7.5", "agility" to "0")
        assertFalse(CalculatedCellEcho.isAppOutput(total, "7.6", scope, stored))
    }

    /**
     * **입력 필드만 고친 파일도 침묵해야 한다.** 견주는 상대가 *지금 파일의 값*이면 이 줄이
     * 어긋나 새 잡음이 된다 — 사용자는 계산 열을 건드린 적이 없는데 그렇다고 말하게 된다.
     */
    @Test
    fun `입력만 고친 파일에서도 계산 열은 앱의 출력 그대로다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        val written = FormulaDisplay.evaluateForDisplay("field(power) + field(agility)") {
            FormulaEvaluator(stored, scope).evaluate(it)
        }
        // 파일에서 power를 99로 고쳤어도, 계산 셀은 여전히 **저장값으로 계산한 값**이다.
        assertTrue(CalculatedCellEcho.isAppOutput(total, written, scope, stored))
    }

    @Test
    fun `사람이 직접 적은 값은 앱의 출력이 아니다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        assertFalse(CalculatedCellEcho.isAppOutput(total, "직접 적음", scope, stored))
        assertFalse(CalculatedCellEcho.isAppOutput(total, "999", scope, stored))
    }

    /** 앞뒤 공백은 엑셀이 흔히 붙인다 — 그것만으로 사용자의 입력이 되지 않는다. */
    @Test
    fun `앞뒤 공백은 판정을 뒤집지 않는다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        val written = FormulaDisplay.evaluateForDisplay("field(power) + field(agility)") {
            FormulaEvaluator(stored, scope).evaluate(it)
        }
        assertTrue(CalculatedCellEcho.isAppOutput(total, "  $written  ", scope, stored))
    }

    /**
     * **수식이 없으면 단정하지 않는다** — 견줄 것이 없다. 침묵 쪽으로 기울면 진짜 입력이
     * 조용히 버려지므로, 종전대로 사용자의 입력으로 다뤄 말이 나가게 한다.
     */
    @Test
    fun `수식이 없으면 앱의 출력이라고 하지 않는다`() {
        val noFormula = field("total", FieldType.CALCULATED, "{}")
        assertFalse(CalculatedCellEcho.isAppOutput(noFormula, "12.00", listOf(noFormula), emptyMap()))
        val broken = field("total", FieldType.CALCULATED, "not json")
        assertFalse(CalculatedCellEcho.isAppOutput(broken, "12.00", listOf(broken), emptyMap()))
    }

    // ── 신규 소유자 — 재료를 행에서 짓는다 ─────────────────────────────────────

    /**
     * **저장값이 없는 행에서도 무편집 왕복은 침묵해야 한다.**
     *
     * 새 캐릭터에게는 저장된 값이 없어 재료가 통째로 빈다. 그 상태로 평가하면 수식이
     * 재료 없이 돌아 셀과 어긋나고, 한 글자도 안 고친 파일에서 **새 행마다** 거짓 경고가
     * 났다(빈 DB 복원이면 전 행이 그렇다).
     */
    @Test
    fun `신규 소유자는 행의 입력 칸이 재료다`() {
        val cells = listOf(power to "10", agility to "5", total to "15")
        val materials = CalculatedCellEcho.materialsFromRow(cells)
        assertEquals(mapOf("power" to "10", "agility" to "5"), materials)
        // 그 재료로 보면 내보내기가 쓴 값과 맞아떨어진다 — 침묵한다.
        val written = FormulaDisplay.evaluateForDisplay("field(power) + field(agility)") {
            FormulaEvaluator(materials, scope).evaluate(it)
        }
        assertTrue(CalculatedCellEcho.isAppOutput(total, written, scope, materials))
    }

    @Test
    fun `계산 칸과 빈 칸은 재료가 아니다`() {
        // 계산 칸은 산출물이라 재료가 될 수 없고, 빈 칸은 내보내기의 평가 재료에서도 빠진다.
        val materials = CalculatedCellEcho.materialsFromRow(
            listOf(power to "10", agility to "   ", total to "10")
        )
        assertEquals(mapOf("power" to "10"), materials)
    }

    /** 신규 행이라도 **사람이 계산 열을 고쳤으면** 여전히 말한다 — 침묵으로 기울지 않는다. */
    @Test
    fun `신규 소유자도 손으로 고친 계산 열은 앱의 출력이 아니다`() {
        val materials = CalculatedCellEcho.materialsFromRow(listOf(power to "10", agility to "5"))
        assertFalse(CalculatedCellEcho.isAppOutput(total, "999", scope, materials))
    }

    /** 깨진 수식은 내보내기가 **오류 표식**을 쓴다 — 그것도 앱의 출력이다. */
    @Test
    fun `깨진 수식의 오류 표식도 앱의 출력이다`() {
        val bad = field("total", FieldType.CALCULATED, """{"formula":"power + "}""")
        val scopeBad = listOf(power, bad)
        val written = FormulaDisplay.evaluateForDisplay("power + ") {
            FormulaEvaluator(mapOf("power" to "10"), scopeBad).evaluate(it)
        }
        assertTrue(CalculatedCellEcho.isAppOutput(bad, written, scopeBad, mapOf("power" to "10")))
    }
}
