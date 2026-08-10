package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목록 셀의 결합·분해 규약 (B-27 ② + B-38 · 규약 R-47).
 *
 * **이 시험이 잠그는 것은 개수가 아니라 넷이다:**
 * ① *왕복이 닫힌다* — 붙인 것을 그대로 되쪼갠다(쉼표·따옴표를 품은 값까지).
 * ② *옛 파일이 글자 그대로 산다* — 따옴표가 없으면 종전 구현과 **동일한 결과**여야 한다.
 *    저장·교환 형식 변경이라 되돌리기 어려운 축이고(착수 규칙 3번 셋째), 이 동일성이
 *    무너지면 이번 변경 자체가 왕복을 깨는 변경이 된다.
 * ③ *어긋난 따옴표는 거부가 아니라 옛 규칙으로 수용한다* — 따옴표를 글자로 쓰던 값이 살아난다.
 * ④ *가릴 수 없는 자리를 명시로 못 박는다* — 값 전체를 감싼 옛 값은 CSV 자체가 모호하다.
 */
class SheetSpecCsvTest {

    /** 종전 구현 — ②의 기준선이다. 여기 손대면 그것이 곧 형식 변경이다. */
    private fun legacySplit(value: String): List<String> =
        toHalfWidth(value).split(",").map { it.trim() }.filter { it.isNotBlank() }

    // ── ① 왕복 ──

    @Test
    fun roundtrip_valueWithComma_survives() {
        // B-27 ②가 등재한 파손 그 자체 — 이름·제목에 쉼표가 있으면 두 값으로 갈렸다.
        val values = listOf("Smith, John", "Alice")
        val cell = joinCsv(values)
        assertEquals("\"Smith, John\", Alice", cell)
        assertEquals(values, splitCsv(cell))
    }

    @Test
    fun roundtrip_valueWithQuote_survives() {
        val values = listOf("그는 \"안녕\"이라 말했다", "다른 값")
        assertEquals(values, splitCsv(joinCsv(values)))
    }

    @Test
    fun roundtrip_valueWithBothCommaAndQuote_survives() {
        val values = listOf("\"인용\", 그리고 쉼표", "평범한 값")
        assertEquals(values, splitCsv(joinCsv(values)))
    }

    @Test
    fun roundtrip_selectorOverload_matchesPlainOverload() {
        // 호출부가 `joinToString(", ") { ... }`로 되돌아가지 않게 짝을 둔 것이므로 둘이 같아야 한다.
        data class Tag(val tag: String)
        val tags = listOf(Tag("주인공, 남자"), Tag("조연"))
        assertEquals(joinCsv(tags.map { it.tag }), joinCsv(tags) { it.tag })
    }

    @Test
    fun roundtrip_quoteOnlyWhenNeeded() {
        // 감쌀 필요가 없는 값까지 감싸면 **옛 파일과 새 파일의 글자가 달라져** 사람이 읽는 셀이
        // 이유 없이 바뀐다. 필요한 자리에만 붙는다.
        assertEquals("가나, 다라", joinCsv(listOf("가나", "다라")))
    }

    // ── ② 옛 파일 동일성 (따옴표가 없으면 종전 구현과 글자 그대로 같다) ──

    @Test
    fun legacyEquivalence_whenNoQuotes() {
        val samples = listOf(
            "가나, 다라",
            "  앞뒤 공백  ,  둘째  ",
            "하나",
            "",
            "   ",
            "빈칸이, , 사이에",
            "끝에 쉼표,",
            "가나，다라",          // 전각 쉼표 (F4)
            "Ｙ，Ｎ",              // 전각 영숫자 + 전각 쉼표
            "코드1,코드2,코드3"
        )
        for (s in samples) {
            assertEquals("옛 파일 '$s' 의 해석이 달라졌다", legacySplit(s), splitCsv(s))
        }
    }

    @Test
    fun fullWidthCommaStillSplits() {
        // F4의 관대 수용은 그대로다 — 새 규칙이 이것을 잡아먹으면 CJK 입력기 사용자가 먼저 다친다.
        assertEquals(listOf("가나", "다라"), splitCsv("가나，다라"))
    }

    // ── ③ 어긋난 따옴표는 옛 규칙으로 되돌린다 ──

    @Test
    fun malformedQuote_trailingJunk_fallsBackToLegacy() {
        // 따옴표를 **글자로** 쓰던 옛 값. 닫은 뒤에 군더더기가 붙으므로 감싼 것이 아니다.
        val raw = "\"인용\" 시리즈, 다른 것"
        assertEquals(legacySplit(raw), splitCsv(raw))
        assertEquals(listOf("\"인용\" 시리즈", "다른 것"), splitCsv(raw))
    }

    @Test
    fun malformedQuote_unclosed_fallsBackToLegacy() {
        val raw = "\"닫히지 않음, 둘째"
        assertEquals(legacySplit(raw), splitCsv(raw))
    }

    @Test
    fun quoteInMiddleIsLiteral() {
        // 칸의 첫 글자가 아니면 따옴표는 글자다 — 표준 CSV와 같고, 옛 값이 그대로 산다.
        assertEquals(listOf("가나\"다라"), splitCsv("가나\"다라"))
    }

    // ── ④ 가릴 수 없는 자리 — 명시로 못 박는다 ──

    @Test
    fun fullyQuotedLegacyValue_isReadAsQuoted_documentedAmbiguity() {
        // 옛 파일의 `"전체"`(따옴표까지가 값)와 새 형식의 `"전체"`(감싼 것)는 **같은 글자**라
        // 원리적으로 가릴 수 없다. 엑셀도 똑같이 동작하며, 사용자가 이 규약을 고른 이유가 그 익숙함이다.
        // 값을 바꾸려는 사람이 아니라 **이 동작을 모르고 바꾸려는 사람**을 막으려고 적는다.
        assertEquals(listOf("전체"), splitCsv("\"전체\""))
        // 그리고 새 형식은 그 값을 되살릴 수 있다 — 따옴표까지가 값이면 겹쳐 쓴다.
        assertEquals(listOf("\"전체\""), splitCsv(joinCsv(listOf("\"전체\""))))
    }

    // ── 전각 따옴표·전각 쉼표를 품은 값의 감싸기 ──

    @Test
    fun quoteCsvToken_wrapsValueWithFullWidthComma() {
        // 가져오기가 toHalfWidth 뒤에 파싱하므로, 전각 쉼표를 품은 값을 감싸지 않으면 그쪽에서 쪼개진다.
        val value = "가나，다라"
        assertTrue("전각 쉼표를 품은 값은 감싸야 한다", quoteCsvToken(value).startsWith("\""))
        assertEquals(listOf("가나,다라"), splitCsv(joinCsv(listOf(value))))
    }

    @Test
    fun leadingWhitespaceBeforeQuoteStillCountsAsQuoted() {
        // 엑셀은 줄바꿈(Alt+Enter)·붙여넣기 탭을 칸 앞에 남긴다. 공백만 건너뛰면 사용자가
        // 규약대로 감쌌는데도 **아무 말 없이** 옛 규칙으로 갈린다 — 칸 앞의 공백 뜻을
        // 아래 `trim()`과 맞춰 둔 이유다.
        assertEquals(listOf("Smith, John", "Alice"), splitCsv("\n\"Smith, John\",\t\"Alice\""))
    }

    @Test
    fun whitespaceLeniencyNeverLosesLegacyMeaning() {
        // 넓힌 판정이 실패하면 어차피 옛 규칙으로 되돌아간다 — 넓히는 쪽이 손해가 없다는 근거.
        val raw = "\t\"인용\" 시리즈, 다른 것"
        assertEquals(legacySplit(raw), splitCsv(raw))
    }

    @Test
    fun emptyAndBlankTokensAreDropped() {
        assertEquals(emptyList<String>(), splitCsv(""))
        assertEquals(listOf("가나"), splitCsv("가나, , "))
        assertEquals(listOf("가나"), splitCsv("\"\", 가나"))
    }

    // ── B-38: 쉼표를 쪼개는 **모든** 필드에 안내가 붙는다 ──

    private fun field(name: String, key: String, type: String, config: String = "{}") =
        FieldDefinition(universeId = 1, key = key, name = name, type = type, config = config)

    private fun headersFor(vararg fields: FieldDefinition): List<String> =
        characterSpec(fields.toList(), emptyList()).columns.map { it.header }

    @Test
    fun header_commaHint_onMultiText() {
        assertTrue(headersFor(field("성격", "personality", "MULTI_TEXT")).any { it == "성격 (쉼표 구분)" })
    }

    @Test
    fun header_commaHint_onCommaListText() {
        // B-38이 등재한 결함: 콤마 목록 표시 형식의 TEXT 필드는 앱이 쉼표로 쪼개는데도
        // 헤더가 그 사실을 말하지 않아, 외부 편집자가 뜻을 모른 채 쉼표를 넣었다.
        val f = field("특기", "skills", "TEXT", """{"displayFormat":"comma_list"}""")
        assertTrue(headersFor(f).any { it == "특기 (쉼표 구분)" })
    }

    @Test
    fun header_commaHint_onBulletListText() {
        val f = field("취미", "hobby", "TEXT", """{"displayFormat":"bullet_list"}""")
        assertTrue(headersFor(f).any { it == "취미 (쉼표 구분)" })
    }

    @Test
    fun header_noCommaHint_onPlainText() {
        // 넓히다가 **쪼개지 않는 필드까지** 붙이면 안내가 거짓이 된다 — 그쪽이 더 나쁘다.
        val headers = headersFor(field("메모필드", "note", "TEXT"))
        assertTrue(headers.any { it == "메모필드" })
        assertTrue(headers.none { it == "메모필드 (쉼표 구분)" })
    }

    @Test
    fun header_noCommaHint_onNumber() {
        val headers = headersFor(field("나이", "age", "NUMBER"))
        assertTrue(headers.none { it.contains("(쉼표 구분)") })
    }

    @Test
    fun header_hintSuffixIsStrippableByImport() {
        // 가져오기는 `removeSuffix(" (쉼표 구분)")`로 되짚는다 — 접미사 글자가 바뀌면
        // 그 열이 통째로 '알 수 없는 열'이 되어 값이 조용히 버려진다.
        val header = headersFor(field("성격", "personality", "MULTI_TEXT")).first { it.contains("쉼표") }
        assertEquals("성격", header.removeSuffix(" (쉼표 구분)"))
    }
}
