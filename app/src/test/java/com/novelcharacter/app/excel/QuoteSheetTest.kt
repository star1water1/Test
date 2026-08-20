package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.util.QuoteIndexes
import com.novelcharacter.app.util.QuoteNaturalKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 명대사 시트와 그 정체성 색인 (사용자 요청 2026.08.20).
 *
 * **이 파일이 지키는 것은 왕복에서 대사가 뒤바뀌지 않는가**다. 가져오기 본체는 DB·Android에
 * 매달려 순수 하네스에 못 올라오므로, 올릴 수 있는 두 축 — 시트 정체와 자연키 — 을 잠근다.
 */
class QuoteSheetTest {

    private fun quote(id: Long, charId: Long, text: String, code: String? = "Q$id", order: Int = 0) =
        CharacterQuote(
            id = id, characterId = charId, text = text,
            sortOrder = order, createdAt = 0L, code = code
        )

    // ── 시트 정체 ──

    @Test
    fun `예약 시트명이라 세계관 시트가 이름을 뺏지 못한다`() {
        assertTrue(quoteSpec().sheetName in RESERVED_SHEET_NAMES)
    }

    @Test
    fun `첫 열이 캐릭터 시트와 겹치지 않는다`() {
        // 앞 열이 '이름'이면 캐릭터 시트로 오인된다 — 형제 기록 시트가 전부 '캐릭터'인 이유다.
        assertEquals("캐릭터", quoteSpec().firstColumnHeader)
    }

    @Test
    fun `형제 기록 시트와 지문이 겹치지 않는다`() {
        val quote = quoteSpec()
        val siblings = listOf(
            stateChangeSpec(), relationshipSpec(), relationshipChangeSpec(),
            timelineSpec(emptyList())
        )
        val quoteHeaders = quote.columns.map { it.header }
        for (other in siblings) {
            assertFalse(
                "${quote.sheetName}이 ${other.sheetName}으로 읽힌다",
                headersMatchSpec(quoteHeaders, other)
            )
        }
    }

    @Test
    fun `대사와 캐릭터가 필수 열이다`() {
        val required = quoteSpec().columns.filter { it.required }.map { it.header }
        assertEquals(listOf("캐릭터", "대사"), required)
    }

    @Test
    fun `코드 계열은 읽기 전용이다`() {
        // 사람이 고칠 칸이 아니다 — 고치면 같은 대사가 새 행이 된다.
        val readOnly = quoteSpec().columns.filter { it.readOnly }.map { it.header }
        assertEquals(listOf("캐릭터코드", "코드", "생성일"), readOnly)
    }

    @Test
    fun `대사와 출처는 줄바꿈이 보이게 그려진다`() {
        val wrapped = quoteSpec().columns.filter { it.wrap }.map { it.header }
        assertEquals(listOf("대사", "출처"), wrapped)
    }

    @Test
    fun `생성일은 숫자 서식이라 과학표기로 뭉개지지 않는다`() {
        assertTrue(quoteSpec().columns.first { it.header == "생성일" }.millis)
    }

    // ── 자연키 — 여기가 이 시트의 위험한 자리다 ──

    @Test
    fun `대사 글자가 자연키라 순서를 바꿔도 짝이 안 뒤바뀐다`() {
        // **차례를 키로 잡았을 때 나는 사고를 막는 시험이다.** 사용자가 목록을 끌어 옮기면
        // 같은 캐릭터의 1번과 2번이 자리를 바꾸는데, 그때 차례로 이으면 두 대사의 내용이
        // 서로 바뀐다.
        val rows = listOf(
            quote(1, 10, "첫째 대사", order = 0),
            quote(2, 10, "둘째 대사", order = 1)
        )
        val index = QuoteIndexes(rows)
        assertEquals(1L, index.byNaturalKey.first(QuoteNaturalKey(10, "첫째 대사"))?.id)
        assertEquals(2L, index.byNaturalKey.first(QuoteNaturalKey(10, "둘째 대사"))?.id)
    }

    @Test
    fun `캐릭터가 다르면 같은 대사도 다른 행이다`() {
        val index = QuoteIndexes(listOf(quote(1, 10, "같은 말"), quote(2, 20, "같은 말")))
        assertEquals(1L, index.byNaturalKey.first(QuoteNaturalKey(10, "같은 말"))?.id)
        assertEquals(2L, index.byNaturalKey.first(QuoteNaturalKey(20, "같은 말"))?.id)
    }

    @Test
    fun `코드로 잡으면 대사 글자를 고쳐도 같은 행이다`() {
        // 코드 축이 있어야 **외부 편집**이 새 행으로 읽히지 않는다.
        val index = QuoteIndexes(listOf(quote(1, 10, "옛 대사", code = "CQ-1")))
        assertEquals(1L, index.byCode.first("CQ-1")?.id)
        // 글자를 고친 행으로 갱신하면 새 글자로도 찾힌다.
        index.remember(quote(1, 10, "새 대사", code = "CQ-1"))
        assertEquals(1L, index.byNaturalKey.first(QuoteNaturalKey(10, "새 대사"))?.id)
    }

    @Test
    fun `옛 자연키는 갱신 뒤에 끊긴다`() {
        // 끊지 않으면 뒤 행이 **이미 다른 대사가 된 행**을 옛 글자로 다시 잡아 덮어쓴다.
        val index = QuoteIndexes(listOf(quote(1, 10, "옛 대사", code = "CQ-1")))
        index.remember(quote(1, 10, "새 대사", code = "CQ-1"))
        assertNull(index.byNaturalKey.first(QuoteNaturalKey(10, "옛 대사")))
    }

    @Test
    fun `코드가 빈 행은 코드 축에 실리지 않는다`() {
        // 옛 스냅샷에서 되살아난 무코드 행 — 빈 코드로 서로를 잡으면 안 된다.
        val index = QuoteIndexes(listOf(quote(1, 10, "가", code = null), quote(2, 10, "나", code = "")))
        assertNull(index.byCode.first(""))
        // 자연키로는 여전히 각자 찾힌다.
        assertNotNull(index.byNaturalKey.first(QuoteNaturalKey(10, "가")))
        assertNotNull(index.byNaturalKey.first(QuoteNaturalKey(10, "나")))
    }

    @Test
    fun `같은 자연키가 둘이면 id가 작은 쪽이 답이다`() {
        // 두 조회가 LIMIT 1이라 순서가 곧 답이다 — 색인이 그 순서를 흉내 내야 미리보기와
        // 가져오기가 같은 행을 고른다.
        val index = QuoteIndexes(listOf(quote(5, 10, "겹치는 대사"), quote(2, 10, "겹치는 대사")))
        assertEquals(2L, index.byNaturalKey.first(QuoteNaturalKey(10, "겹치는 대사"))?.id)
    }

    // ── 상황 칸 ──

    @Test
    fun `상황 예약 글자는 시트에 그대로 실린다`() {
        // 이 상수가 시트 값이다 — '생일'로 바꿔 적으면 왕복 한 번에 축하 창에서 사라진다.
        assertEquals("__birthday", CharacterQuote.KEY_BIRTHDAY)
        assertEquals("", CharacterQuote.KEY_GENERAL)
    }

    @Test
    fun `상황 칸은 드롭다운으로 닫히지 않는다`() {
        // 사용자가 자기 상황을 적을 수 있어야 한다(원칙 01) — 목록으로 닫으면 그 길이 막힌다.
        assertNull(quoteSpec().columns.first { it.header == "상황" }.dropdownOptions)
    }
}
