package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.util.FieldValueTypeMismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가져오기의 타입 경고 계약 (2026.11.21 — 워크스루 S4 관찰의 수리).
 *
 * 이 시험이 지키는 것 둘:
 * - **판정이 통계의 정본과 글자 그대로 같다.** 종전에는 가져오기가 손으로 적은 `when`을
 *   들고 있었고 그것이 정본과 세 자리에서 갈려, *경고 없이 들여온 값*을 사용자가 나중에
 *   건강도 화면에서 처음 듣는 경로가 있었다(R-34).
 * - **사유마다 다른 말을 한다.** 사용자가 할 일이 사유마다 다르다(숫자로 고쳐라 /
 *   등급표에 넣어라 / 부위 수치로 적어라).
 */
class FieldValueTypeWarningTest {

    private fun field(type: FieldType, name: String = "필드", config: String = "{}") = FieldDefinition(
        id = 1, universeId = 1, key = "k", name = name, type = type.name, config = config
    )

    // ===== 판정은 정본에 위임한다 =====

    /**
     * **이 시험이 이 판의 핵심이다.** 두 판정이 갈리는 것이 종전의 결함이었으므로,
     * 문구를 내는 자리와 통계가 세는 자리가 **같은 답**을 내는지 축마다 대조한다.
     */
    @Test
    fun `문구를 내는 판정과 통계가 세는 판정이 같다`() {
        val cases = listOf(
            field(FieldType.NUMBER) to "twenty",
            field(FieldType.NUMBER) to "20",
            field(FieldType.GRADE, config = """{"grades":{"S":10}}""") to "S",
            field(FieldType.GRADE, config = """{"grades":{"S":10}}""") to "Z",
            // 등급표가 통째로 없는 필드 — 정본은 불일치로 본다(그 값은 수식에서 0.0이 된다)
            field(FieldType.GRADE) to "S",
            field(FieldType.BODY_SIZE) to "90-60-90",
            field(FieldType.BODY_SIZE) to "모름",
            field(FieldType.TEXT) to "아무 글자",
            field(FieldType.SELECT) to "목록에 없는 값",
            field(FieldType.MULTI_TEXT) to "a, b",
            field(FieldType.CALCULATED) to "123"
        )
        for ((f, value) in cases) {
            val mismatched = FieldValueTypeMismatch.reasonFor(f, value) != null
            val warned = FieldValueTypeWarning.messageOf(f, value) != null
            assertEquals("'$value' (${f.type})", mismatched, warned)
        }
    }

    // ===== 사유별 문구 =====

    @Test
    fun `숫자 필드의 글자 값은 통계 제외를 말한다`() {
        val msg = FieldValueTypeWarning.messageOf(field(FieldType.NUMBER, name = "나이"), "twenty")
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains("숫자 필드 '나이'"))
        assertTrue(msg, msg.contains("twenty"))
        assertTrue(msg, msg.contains("통계에서 제외될 수 있습니다"))
    }

    @Test
    fun `등급 문구는 이 필드의 등급표를 가리킨다`() {
        val f = field(FieldType.GRADE, name = "전투력", config = """{"grades":{"S":10,"A":8}}""")
        val msg = FieldValueTypeWarning.messageOf(f, "Z")
        assertNotNull(msg)
        assertTrue(msg!!, msg.contains("등급표"))
        assertTrue(msg, msg.contains("'전투력'"))
    }

    /**
     * 종전 문구는 *"값에 숫자가 없어"*였고 종전 판정도 `value.any { it.isDigit() }`였다.
     * 그런데 판정이 실제로 보는 것은 **부위별 수치**다 — `1a2`는 숫자를 품었지만 한 토막에
     * 수가 둘이라 어느 부위로도 읽히지 않는다([BodyMeasurements] 규약). 종전 규칙은 이 값을
     * 통과시켰고, 문구가 *"숫자가 없어"*라 말하면 사용자는 **숫자를 한 글자 끼워 넣고
     * 고쳤다고 믿는다.**
     */
    @Test
    fun `체형 문구는 숫자 유무가 아니라 부위 수치를 말한다`() {
        val mixed = FieldValueTypeWarning.messageOf(field(FieldType.BODY_SIZE), "1a2")
        assertNotNull("숫자를 품었어도 부위를 못 읽으면 걸린다", mixed)
        assertTrue(mixed!!, mixed.contains("부위 수치"))

        val noDigit = FieldValueTypeWarning.messageOf(field(FieldType.BODY_SIZE), "모름")
        assertNotNull(noDigit)
        assertTrue(noDigit!!, noDigit.contains("부위 수치"))
    }

    /** 단위 접미사는 견딘다 — 그것까지 걸면 정상 값이 경고로 뜬다([BodyMeasurements] 규약). */
    @Test
    fun `단위를 붙인 부위 수치는 경고하지 않는다`() {
        assertNull(FieldValueTypeWarning.messageOf(field(FieldType.BODY_SIZE), "86cm-60cm-90cm"))
    }

    // ===== 경계 =====

    @Test
    fun `빈 값과 공백은 경고하지 않는다 — 비움은 잘못이 아니다`() {
        for (v in listOf("", " ", "\t")) {
            assertNull(v, FieldValueTypeWarning.messageOf(field(FieldType.NUMBER), v))
        }
    }

    @Test
    fun `타입을 모르는 필드는 잣대가 없어 경고하지 않는다`() {
        val unknown = FieldDefinition(
            id = 1, universeId = 1, key = "k", name = "필드", type = "WHAT_IS_THIS", config = "{}"
        )
        assertNull(FieldValueTypeWarning.messageOf(unknown, "아무 값"))
    }

    // ===== 꼬리 문구 =====

    /**
     * 꼬리는 **고칠 자리를 가리켜야** 한다 — 상한에 걸려 줄이 사라진 몫은 그 화면에서만
     * 전수를 볼 수 있다. 가리키는 이름은 실제 화면 이름(`stats_type_mismatch`)이다.
     */
    @Test
    fun `꼬리 한 줄은 남은 수와 전체 수와 고칠 자리를 함께 말한다`() {
        val tail = FieldValueTypeWarning.foldTail("캐릭터 필드값", remaining = 37, total = 47)
        assertTrue(tail, tail.startsWith("캐릭터 필드값: "))
        assertTrue(tail, tail.contains("37건"))
        assertTrue(tail, tail.contains("47건"))
        assertTrue(tail, tail.contains("타입이 안 맞는 값"))
    }
}
