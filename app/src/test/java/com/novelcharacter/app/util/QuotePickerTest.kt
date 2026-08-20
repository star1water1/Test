package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * [QuotePicker] — 사용자 확정 셋을 그대로 잠근다.
 *
 * 이 시험이 실제로 지키는 것은 **같은 날 두 자리가 같은 대사를 집는가**다. 그것이 깨지면
 * 화면에서만 보이고(생일 모달과 대결 카드가 다른 대사) 어떤 컴파일러도 못 본다.
 */
class QuotePickerTest {

    private fun q(id: Long, text: String, occasion: String = "", charId: Long = 1L) =
        CharacterQuote(
            id = id, characterId = charId, text = text, occasionKey = occasion,
            sortOrder = id.toInt(), createdAt = 0L, code = "Q$id"
        )

    // ── 빈 목록 ──

    @Test
    fun `대사가 없으면 null이다`() {
        assertNull(QuotePicker.pick(emptyList(), 1L, 20260820L))
        assertNull(QuotePicker.forBirthday(emptyList(), 1L, 20260820L))
        assertNull(QuotePicker.forDisplay(emptyList(), 1L, 20260820L))
    }

    @Test
    fun `하나뿐이면 셈 없이 그것이다`() {
        val only = q(1, "하나")
        for (day in 20260101L..20260131L) {
            assertEquals(only, QuotePicker.pick(listOf(only), 1L, day))
        }
    }

    // ── 사용자 확정 ①: 생일 전용이 없으면 일반으로 되돌아간다 ──

    @Test
    fun `생일 대사가 있으면 생일 대사에서 고른다`() {
        val quotes = listOf(
            q(1, "일반1"),
            q(2, "생일1", CharacterQuote.KEY_BIRTHDAY),
            q(3, "생일2", CharacterQuote.KEY_BIRTHDAY)
        )
        for (day in 20260101L..20260228L) {
            val picked = QuotePicker.forBirthday(quotes, 1L, day)
            assertNotNull(picked)
            assertTrue("생일 자리인데 일반 대사가 떴다: ${picked?.text}", picked!!.isBirthday)
        }
    }

    @Test
    fun `생일 대사가 없으면 일반 명대사를 대신 띄운다`() {
        val quotes = listOf(q(1, "일반1"), q(2, "일반2"))
        val picked = QuotePicker.forBirthday(quotes, 1L, 20260820L)
        assertNotNull("생일 대사가 없다고 빈 줄을 내면 안 된다", picked)
        assertTrue(picked!!.text.startsWith("일반"))
    }

    @Test
    fun `생일 대사도 일반 대사도 없으면 null이다`() {
        // 사용자가 만든 상황('전투')만 있는 경우 — 그것은 생일 자리에서 쓸 수 있다.
        val onlyScene = listOf(q(1, "전투 대사", "전투"))
        assertNotNull(QuotePicker.forBirthday(onlyScene, 1L, 20260820L))
        // 정말로 아무것도 없을 때만 null.
        assertNull(QuotePicker.forBirthday(emptyList(), 1L, 20260820L))
    }

    // ── 사용자 확정 ②: 생일 전용은 평소 자리에 안 뜬다 ──

    @Test
    fun `평소 자리에서는 생일 전용 대사가 빠진다`() {
        val quotes = listOf(
            q(1, "생일만", CharacterQuote.KEY_BIRTHDAY),
            q(2, "평소")
        )
        for (day in 20260101L..20260331L) {
            assertEquals("평소", QuotePicker.forDisplay(quotes, 1L, day)?.text)
        }
    }

    @Test
    fun `생일 전용밖에 없으면 평소 자리는 비어 있다`() {
        val quotes = listOf(q(1, "생일만", CharacterQuote.KEY_BIRTHDAY))
        assertNull(QuotePicker.forDisplay(quotes, 1L, 20260820L))
    }

    @Test
    fun `사용자가 만든 상황은 평소 자리에서 빠지지 않는다`() {
        val quotes = listOf(q(1, "전투에서 한 말", "전투"))
        assertEquals("전투에서 한 말", QuotePicker.forDisplay(quotes, 1L, 20260820L)?.text)
    }

    // ── 사용자 확정 ③: 같은 날은 같은 대사, 다른 날은 바뀐다 ──

    @Test
    fun `같은 날 같은 캐릭터면 몇 번을 불러도 같은 대사다`() {
        val quotes = (1L..7L).map { q(it, "대사$it") }
        val first = QuotePicker.pick(quotes, 42L, 20260820L)
        repeat(50) {
            assertEquals(first, QuotePicker.pick(quotes, 42L, 20260820L))
        }
    }

    @Test
    fun `생일 모달과 대결 카드가 같은 날 같은 대사를 집는다`() {
        // 생일 대사가 없는 캐릭터 — 두 자리 다 일반 목록에서 고르므로 답이 같아야 한다.
        val quotes = (1L..5L).map { q(it, "대사$it") }
        for (day in 20260801L..20260831L) {
            assertEquals(
                "같은 날인데 두 자리가 다른 대사를 집었다 (day=$day)",
                QuotePicker.forBirthday(quotes, 9L, day),
                QuotePicker.forDisplay(quotes, 9L, day)
            )
        }
    }

    @Test
    fun `날이 바뀌면 대사도 바뀐다 — 한 달에 최소 절반은 다른 것이 뜬다`() {
        val quotes = (1L..5L).map { q(it, "대사$it") }
        val seen = (1..31).map { day ->
            QuotePicker.pick(quotes, 3L, 20260800L + day)?.text
        }
        // 다섯 중에서 고르는데 서른한 날이 한 대사에 몰리면 무작위가 아니다.
        assertTrue("서른한 날에 뜬 대사가 ${seen.distinct().size}종뿐이다", seen.distinct().size >= 3)
    }

    @Test
    fun `캐릭터가 다르면 같은 날에도 대사 차례가 갈린다`() {
        val quotes = (1L..5L).map { q(it, "대사$it") }
        val picks = (1L..40L).map { QuotePicker.pick(quotes, it, 20260820L)?.text }
        assertTrue("마흔 명이 전부 같은 대사를 집었다", picks.distinct().size >= 3)
    }

    @Test
    fun `어떤 씨앗에서도 목록 범위를 벗어나지 않는다`() {
        val quotes = (1L..3L).map { q(it, "대사$it") }
        // 음수·거대 씨앗까지 — mix가 음수를 내면 여기서 IndexOutOfBounds로 죽는다.
        val seeds = listOf(0L, -1L, -20260820L, Long.MIN_VALUE, Long.MAX_VALUE, 20260820L)
        for (day in seeds) {
            for (charId in seeds) {
                assertNotNull(QuotePicker.pick(quotes, charId, day))
            }
        }
    }

    // ── 날짜 씨앗 ──

    @Test
    fun `오늘 씨앗은 yyyyMMdd다`() {
        val cal = GregorianCalendar(2026, Calendar.AUGUST, 20)
        assertEquals(20260820L, QuotePicker.todayStamp(cal))
    }

    @Test
    fun `같은 날의 다른 시각은 같은 씨앗이다`() {
        val morning = GregorianCalendar(2026, Calendar.AUGUST, 20, 6, 0)
        val night = GregorianCalendar(2026, Calendar.AUGUST, 20, 23, 59)
        assertEquals(QuotePicker.todayStamp(morning), QuotePicker.todayStamp(night))
    }

    @Test
    fun `자정을 넘기면 씨앗이 바뀐다`() {
        val today = GregorianCalendar(2026, Calendar.AUGUST, 20, 23, 59)
        val tomorrow = GregorianCalendar(2026, Calendar.AUGUST, 21, 0, 1)
        assertTrue(QuotePicker.todayStamp(today) != QuotePicker.todayStamp(tomorrow))
    }

    @Test
    fun `목록 차례가 답을 정한다 — 뒤집으면 다른 대사를 집을 수 있다`() {
        // 계약을 명시로 잠근다: 같은 씨앗이어도 목록이 다르면 답이 다를 수 있다.
        // (그래서 DAO가 어디서든 같은 정렬로 낸다.)
        val quotes = (1L..4L).map { q(it, "대사$it") }
        val picked = QuotePicker.pick(quotes, 1L, 20260820L)
        val pickedFromReversed = QuotePicker.pick(quotes.reversed(), 1L, 20260820L)
        assertNotNull(picked)
        assertNotNull(pickedFromReversed)
        // 둘 다 목록 안의 것이어야 한다는 것만은 언제나 참이다.
        assertTrue(picked in quotes && pickedFromReversed in quotes)
    }
}
