package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * [BirthdayHelper.todayBirthdays] — **판정과 함께 맞은 행을 돌려주는가**.
 *
 * 이 시험이 서 있는 이유는 콜드 검토가 잡은 자리 때문이다(2026.08.20): 부르는 쪽이 id만
 * 받아 자기가 행을 다시 고르면, 생일 행이 둘인 캐릭터에서 **오늘 맞지 않은 행**을 집는다.
 *
 * 오늘 날짜에 기대는 시험이라 **날짜를 고정할 수 없다** — 그래서 오늘을 읽어 재료를 짓는다
 * (`Calendar`를 시험이 쓰는 것이 아니라, 앱과 같은 오늘을 보게 하는 것이다).
 */
class BirthdayHelperTest {

    private val today = Calendar.getInstance(Locale.US)
    private val todayMonth = today.get(Calendar.MONTH) + 1
    private val todayDay = today.get(Calendar.DAY_OF_MONTH)

    private fun birth(charId: Long, month: Int?, day: Int?, year: Int = 1000, code: String = "B$charId-$month-$day") =
        CharacterStateChange(
            characterId = charId, year = year, month = month, day = day,
            fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "$year", code = code
        )

    /** 오늘이 아닌 달 — 12월이면 11월, 아니면 12월(윤년·말일 걱정이 없다). */
    private val otherMonth = if (todayMonth == 12) 11 else 12

    @Test
    fun `오늘 생일인 행만 돌려준다`() {
        val matched = BirthdayHelper.todayBirthdays(
            listOf(birth(1, todayMonth, todayDay), birth(2, otherMonth, 1))
        )
        assertEquals(listOf(1L), matched.map { it.characterId })
    }

    @Test
    fun `월일이 없는 행은 맞지 않는다`() {
        assertTrue(BirthdayHelper.todayBirthdays(listOf(birth(1, null, null))).isEmpty())
    }

    /**
     * **콜드 검토가 잡은 자리.** 한 캐릭터가 생일 행을 둘 들 수 있다(엑셀이 만든다).
     * 돌려주는 행은 **오늘 맞은 그 행**이어야 한다 — 아무 행이나 집으면 창이 딴 날짜를 적는다.
     */
    @Test
    fun `생일 행이 둘이면 오늘 맞은 행을 돌려준다`() {
        val other = birth(1, otherMonth, 1, code = "B1-other")
        val todayRow = birth(1, todayMonth, todayDay, code = "B1-today")
        for (rows in listOf(listOf(other, todayRow), listOf(todayRow, other))) {
            val matched = BirthdayHelper.todayBirthdays(rows)
            assertEquals(1, matched.size)
            assertEquals("B1-today", matched[0].code)
            assertEquals(todayMonth, matched[0].month)
            assertEquals(todayDay, matched[0].day)
        }
    }

    @Test
    fun `같은 캐릭터가 여러 행으로 맞아도 한 번만 센다`() {
        val matched = BirthdayHelper.todayBirthdays(
            listOf(
                birth(1, todayMonth, todayDay, year = 1000, code = "a"),
                birth(1, todayMonth, todayDay, year = 1200, code = "b")
            )
        )
        assertEquals(1, matched.size)
        // 처음 맞은 행이다 — 종전 `distinct()`가 남기던 것과 같다.
        assertEquals("a", matched[0].code)
    }

    /** id 목록은 이 함수에서 파생된다 — 두 벌이 되면 규칙이 갈린다. */
    @Test
    fun `id 목록과 행 목록이 같은 것을 말한다`() {
        val rows = listOf(
            birth(1, todayMonth, todayDay), birth(2, otherMonth, 1), birth(3, todayMonth, todayDay)
        )
        assertEquals(
            BirthdayHelper.todayBirthdays(rows).map { it.characterId },
            BirthdayHelper.getTodayBirthdayCharacterIds(rows)
        )
    }
}
