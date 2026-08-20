package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.data.model.CharacterStateChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BirthdayCelebration] — 축하 창에 **무엇이 뜨는가**.
 *
 * 이 계층이 화면이 아니라 여기 있는 이유가 이 파일이다: 대사 되돌아감·날짜 없는 줄·
 * 순서 같은 것은 기기에서 보면 *"왜 저게 떴지"*로만 보이고, 여기서는 물어볼 수 있다.
 */
class BirthdayCelebrationTest {

    private fun character(id: Long, name: String, novelId: Long? = null) =
        Character(id = id, name = name, novelId = novelId)

    private fun birth(charId: Long, month: Int?, day: Int?, year: Int = 1000) =
        CharacterStateChange(
            characterId = charId, year = year, month = month, day = day,
            fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "$year", code = "B$charId"
        )

    private fun quote(id: Long, charId: Long, text: String, occasion: String = "", note: String = "") =
        CharacterQuote(
            id = id, characterId = charId, text = text, occasionKey = occasion,
            note = note, sortOrder = id.toInt(), createdAt = 0L, code = "Q$id"
        )

    private val day = 20260820L

    @Test
    fun `오늘 생일이 없으면 쪽이 없다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = emptyList(),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        assertTrue(pages.isEmpty())
    }

    @Test
    fun `생일인 캐릭터의 이름과 날짜가 담긴다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "이서린")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        assertEquals(1, pages.size)
        assertEquals("이서린", pages[0].name)
        assertEquals(8, pages[0].month)
        assertEquals(20, pages[0].day)
    }

    @Test
    fun `월일을 모르는 캐릭터는 쪽을 만들지 않는다`() {
        // 연도만 적힌 __birth — 축하 창은 '오늘'을 말하는 자리라 날짜 없는 줄은 세우지 않는다.
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L, 2L),
            characters = listOf(character(1, "날짜있음"), character(2, "연도만")),
            birthChanges = listOf(birth(1, 8, 20), birth(2, null, null)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        assertEquals(1, pages.size)
        assertEquals("날짜있음", pages[0].name)
    }

    @Test
    fun `목록에 없는 캐릭터는 조용히 빠진다`() {
        // 지워진 캐릭터의 __birth가 남아 있어도 창이 죽지 않아야 한다.
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L, 99L),
            characters = listOf(character(1, "살아있음")),
            birthChanges = listOf(birth(1, 8, 20), birth(99, 8, 20)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        assertEquals(1, pages.size)
    }

    // ── 대사 ──

    @Test
    fun `생일 전용 대사가 있으면 그것이 뜬다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = mapOf(
                1L to listOf(
                    quote(1, 1, "평소 대사"),
                    quote(2, 1, "생일 축하 고마워", CharacterQuote.KEY_BIRTHDAY)
                )
            ),
            dayStamp = day
        )
        assertEquals("생일 축하 고마워", pages[0].quoteText)
    }

    @Test
    fun `생일 대사가 없으면 일반 명대사가 대신 뜬다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = mapOf(1L to listOf(quote(1, 1, "나는 멈추지 않아"))),
            dayStamp = day
        )
        assertEquals("나는 멈추지 않아", pages[0].quoteText)
    }

    @Test
    fun `대사가 하나도 없으면 대사 자리가 비어 있다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        // null이라야 화면이 상자를 통째로 감춘다 — 빈 문자열이면 빈 인용 상자가 그려진다.
        assertNull(pages[0].quoteText)
    }

    @Test
    fun `대사의 출처가 비어 있으면 null로 담긴다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = mapOf(1L to listOf(quote(1, 1, "대사", note = "   "))),
            dayStamp = day
        )
        assertNull(pages[0].quoteNote)
    }

    @Test
    fun `대사의 출처가 있으면 담긴다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "주인공")),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = mapOf(1L to listOf(quote(1, 1, "대사", note = "3화"))),
            dayStamp = day
        )
        assertEquals("3화", pages[0].quoteNote)
    }

    @Test
    fun `같은 날 축하 창과 대결 카드가 같은 대사를 집는다`() {
        // 생일 대사가 없는 캐릭터 — 두 자리가 같은 목록에서 고르므로 답이 같아야 한다.
        val quotes = (1L..5L).map { quote(it, 1, "대사$it") }
        for (d in 20260801L..20260831L) {
            val pages = BirthdayCelebration.pagesOf(
                todayIds = listOf(1L),
                characters = listOf(character(1, "주인공")),
                birthChanges = listOf(birth(1, 8, 20)),
                quotesByCharacter = mapOf(1L to quotes),
                dayStamp = d
            )
            assertEquals(
                "축하 창과 카드가 다른 대사를 집었다 (day=$d)",
                QuotePicker.forDisplay(quotes, 1L, d)?.text,
                pages[0].quoteText
            )
        }
    }

    // ── 곁들이 ──

    @Test
    fun `작품 제목은 있을 때만 담긴다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L, 2L),
            characters = listOf(character(1, "소속", novelId = 7L), character(2, "무소속")),
            birthChanges = listOf(birth(1, 8, 20), birth(2, 8, 20)),
            quotesByCharacter = emptyMap(),
            novelTitles = mapOf(7L to "밤의 도시"),
            dayStamp = day
        )
        assertEquals("밤의 도시", pages[0].novelTitle)
        assertNull(pages[1].novelTitle)
    }

    @Test
    fun `작품이 지워졌으면 제목 자리가 빈다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L),
            characters = listOf(character(1, "소속", novelId = 7L)),
            birthChanges = listOf(birth(1, 8, 20)),
            quotesByCharacter = emptyMap(),
            novelTitles = emptyMap(),
            dayStamp = day
        )
        assertNull(pages[0].novelTitle)
    }

    @Test
    fun `쪽의 차례는 오늘치 목록의 차례를 따른다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(3L, 1L, 2L),
            characters = listOf(character(1, "가"), character(2, "나"), character(3, "다")),
            birthChanges = listOf(birth(1, 8, 20), birth(2, 8, 20), birth(3, 8, 20)),
            quotesByCharacter = emptyMap(),
            dayStamp = day
        )
        assertEquals(listOf("다", "가", "나"), pages.map { it.name })
    }

    @Test
    fun `여럿이면 쪽도 여럿이다`() {
        val pages = BirthdayCelebration.pagesOf(
            todayIds = listOf(1L, 2L, 3L),
            characters = listOf(character(1, "가"), character(2, "나"), character(3, "다")),
            birthChanges = listOf(birth(1, 8, 20), birth(2, 8, 20), birth(3, 8, 20)),
            quotesByCharacter = mapOf(
                2L to listOf(quote(1, 2, "나의 생일 대사", CharacterQuote.KEY_BIRTHDAY))
            ),
            dayStamp = day
        )
        assertEquals(3, pages.size)
        // 대사를 적은 사람만 대사가 있다 — 나머지 쪽은 상자가 사라진다.
        assertNull(pages[0].quoteText)
        assertEquals("나의 생일 대사", pages[1].quoteText)
        assertNull(pages[2].quoteText)
    }
}
