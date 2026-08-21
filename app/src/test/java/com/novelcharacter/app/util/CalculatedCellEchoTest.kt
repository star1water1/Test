package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertFalse
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
    private val total = field("total", FieldType.CALCULATED, """{"formula":"power + agility"}""")
    private val scope = listOf(power, agility, total)

    @Test
    fun `내보내기가 쓴 값 그대로면 앱의 출력이다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        // 내보내기는 FormulaDisplay.format을 지나 쓴다 — 같은 글자여야 한다.
        val written = FormulaDisplay.evaluateForDisplay("power + agility") {
            FormulaEvaluator(stored, scope).evaluate(it)
        }
        assertTrue(CalculatedCellEcho.isAppOutput(total, written, scope, stored))
    }

    /**
     * **입력 필드만 고친 파일도 침묵해야 한다.** 견주는 상대가 *지금 파일의 값*이면 이 줄이
     * 어긋나 새 잡음이 된다 — 사용자는 계산 열을 건드린 적이 없는데 그렇다고 말하게 된다.
     */
    @Test
    fun `입력만 고친 파일에서도 계산 열은 앱의 출력 그대로다`() {
        val stored = mapOf("power" to "10", "agility" to "5")
        val written = FormulaDisplay.evaluateForDisplay("power + agility") {
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
        val written = FormulaDisplay.evaluateForDisplay("power + agility") {
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
