package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BirthDateFormat] — 생일 글자의 **읽기(넓게)와 저장 모양(하나로)**.
 *
 * 실측이 세운 계약이다(2026.08.24 사용자가 내보낸 파일): 캐릭터 시트의 `5-30`·`6-7`이
 * '캐릭터 상태변화' 시트에서는 `05-30`·`06-07`이었다 — 같은 사실의 표기가 한 파일 안에서
 * 둘이었다. 아래 두 축이 그 갈림을 막는다.
 */
class BirthDateFormatTest {

    // ── 읽기: 종전 수용 범위를 좁히지 않는다 ──

    @Test
    fun `MM-DD를 읽는다`() {
        assertEquals(5 to 30, BirthDateFormat.parse("05-30"))
        assertEquals(12 to 1, BirthDateFormat.parse("12-01"))
    }

    @Test
    fun `0이 빠진 M-D도 읽는다`() {
        assertEquals(5 to 30, BirthDateFormat.parse("5-30"))
        assertEquals(6 to 7, BirthDateFormat.parse("6-7"))
    }

    @Test
    fun `구분자는 하이픈 슬래시 점을 다 받는다`() {
        assertEquals(5 to 30, BirthDateFormat.parse("5/30"))
        assertEquals(5 to 30, BirthDateFormat.parse("5.30"))
    }

    @Test
    fun `연도가 붙으면 버리고 월 일만 읽는다`() {
        // 엑셀이 날짜 셀로 바꾸면 연도가 붙는다.
        assertEquals(5 to 30, BirthDateFormat.parse("2026-05-30"))
        assertEquals(5 to 30, BirthDateFormat.parse("1900/5/30"))
    }

    @Test
    fun `앞뒤 공백을 다듬는다`() {
        assertEquals(5 to 30, BirthDateFormat.parse("  05-30 "))
    }

    @Test
    fun `실재하지 않는 날은 읽지 않는다`() {
        assertNull(BirthDateFormat.parse("13-01"))
        assertNull(BirthDateFormat.parse("00-10"))
        assertNull(BirthDateFormat.parse("04-31"))
        assertNull(BirthDateFormat.parse("02-30"))
    }

    @Test
    fun `윤년을 모르는 자리라 2월 29일은 받는다`() {
        // 해가 없는 '월/일' 값이다 — [isRealMonthDay]의 근거 그대로.
        assertEquals(2 to 29, BirthDateFormat.parse("02-29"))
    }

    @Test
    fun `읽을 수 없는 글자는 null이다`() {
        assertNull(BirthDateFormat.parse(null))
        assertNull(BirthDateFormat.parse(""))
        assertNull(BirthDateFormat.parse("   "))
        assertNull(BirthDateFormat.parse("봄"))
        assertNull(BirthDateFormat.parse("5"))
        assertNull(BirthDateFormat.parse("5-30-1-2"))
        assertNull(BirthDateFormat.parse("오-삼십"))
    }

    // ── 저장 모양: 만드는 자리가 셋이라 한 함수를 지난다 ──

    @Test
    fun `저장 모양은 언제나 MM-DD다`() {
        assertEquals("05-30", BirthDateFormat.of(5, 30))
        assertEquals("12-01", BirthDateFormat.of(12, 1))
    }

    @Test
    fun `0이 빠진 글자를 저장 모양으로 올린다`() {
        assertEquals("05-30", BirthDateFormat.canonicalOrNull("5-30"))
        assertEquals("06-07", BirthDateFormat.canonicalOrNull("6-7"))
    }

    @Test
    fun `구분자와 연도가 달라도 같은 저장 모양으로 모인다`() {
        assertEquals("05-30", BirthDateFormat.canonicalOrNull("5/30"))
        assertEquals("05-30", BirthDateFormat.canonicalOrNull("2026-05-30"))
    }

    @Test
    fun `읽을 수 없는 글자는 저장 모양이 없다`() {
        assertNull(BirthDateFormat.canonicalOrNull("봄"))
        assertNull(BirthDateFormat.canonicalOrNull(null))
    }

    // ── 정리 대상 판정: 고칠 것만 고친다 ──

    @Test
    fun `이미 규격이면 고치지 않는다`() {
        assertFalse(BirthDateFormat.needsRepair("05-30"))
        assertFalse(BirthDateFormat.needsRepair("12-01"))
    }

    @Test
    fun `0이 빠졌으면 고친다`() {
        assertTrue(BirthDateFormat.needsRepair("5-30"))
        assertTrue(BirthDateFormat.needsRepair("6-7"))
        assertTrue(BirthDateFormat.needsRepair("5/30"))
    }

    @Test
    fun `읽을 수 없는 글자는 정리 대상이 아니다`() {
        // 우리가 못 읽는다고 사용자가 적어 둔 것을 바꾸지 않는다(개발 의도 2번).
        assertFalse(BirthDateFormat.needsRepair("봄"))
        assertFalse(BirthDateFormat.needsRepair("13-40"))
        assertFalse(BirthDateFormat.needsRepair(""))
        assertFalse(BirthDateFormat.needsRepair(null))
    }

    @Test
    fun `정리는 멱등이다`() {
        val once = BirthDateFormat.canonicalOrNull("5-30")
        assertFalse(BirthDateFormat.needsRepair(once))
        assertEquals(once, BirthDateFormat.canonicalOrNull(once))
    }
}
