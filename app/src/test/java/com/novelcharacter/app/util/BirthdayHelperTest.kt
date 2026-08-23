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

    // ── 남은 일수는 역일(曆日)이다 — 서머타임이 셈을 흔들지 않는다 ──────────────
    //
    // 종전에는 두 시각을 정오로 맞춘 뒤 밀리초 차를 정수 나눗셈했다. 정오 앵커는 *부호*를
    // 지키지만 **절삭을 막지 못한다** — 봄 전환을 지나는 구간은 경과가 23시간 짧아 몫이 한
    // 칸 내려간다. **하네스와 CI는 UTC(무DST)라 이 축을 원리적으로 못 본다**(R-70), 그래서
    // 아래 시험도 시간대가 아니라 *역일 산술 자체*를 잰다.

    @Test
    fun `오늘 생일은 0이다`() {
        assertEquals(0, BirthdayHelper.daysUntilBirthday(3, 8, 2026, 3, 8))
    }

    @Test
    fun `내일 생일은 1이다`() {
        assertEquals(1, BirthdayHelper.daysUntilBirthday(3, 7, 2026, 3, 8))
    }

    /** 미국 봄 전환일(2026-03-08)을 걸치는 구간 — 종전 구현이 0을 냈다. */
    @Test
    fun `봄 전환을 걸쳐도 하루가 하루다`() {
        assertEquals(1, BirthdayHelper.daysUntilBirthday(3, 7, 2026, 3, 8))
        assertEquals(2, BirthdayHelper.daysUntilBirthday(3, 6, 2026, 3, 8))
        assertEquals(7, BirthdayHelper.daysUntilBirthday(3, 1, 2026, 3, 8))
    }

    /** 전환을 지나는 **모든** 구간이 어긋났다 — 긴 구간도 잰다. */
    @Test
    fun `긴 구간도 역일과 같다`() {
        assertEquals(151, BirthdayHelper.daysUntilBirthday(1, 15, 2026, 6, 15))
        assertEquals(37, BirthdayHelper.daysUntilBirthday(2, 1, 2026, 3, 10))
    }

    @Test
    fun `올해 생일이 지났으면 내년으로 넘어간다`() {
        // 2026-12-31 → 2027-01-01
        assertEquals(1, BirthdayHelper.daysUntilBirthday(12, 31, 2026, 1, 1))
        // 2026-03-09 → 2027-03-08 (2027은 평년)
        assertEquals(364, BirthdayHelper.daysUntilBirthday(3, 9, 2026, 3, 8))
    }

    /** 2/29 생일은 평년에 2/28로 접힌다 — 그 규칙이 역일 산술에서도 그대로다. */
    @Test
    fun `윤년 생일이 평년에 접힌다`() {
        assertEquals(0, BirthdayHelper.daysUntilBirthday(2, 28, 2026, 2, 29))
        assertEquals(1, BirthdayHelper.daysUntilBirthday(2, 28, 2024, 2, 29))
    }

    /** 어떤 조합에서도 0..365 밖으로 나가지 않는다. */
    @Test
    fun `범위를 벗어나지 않는다`() {
        for (year in 2024..2027) {
            for (month in 1..12) {
                for (day in listOf(1, 15, 28)) {
                    for (bm in 1..12) {
                        for (bd in listOf(1, 15, 28, 29, 31)) {
                            val d = BirthdayHelper.daysUntilBirthday(month, day, year, bm, bd)
                            assertTrue("$year-$month-$day → $bm/$bd = $d", d in 0..365)
                        }
                    }
                }
            }
        }
    }

    // ===== 실재하지 않는 월·일 (월드패키지가 열어 둔 문) =====

    private fun birth(id: Long, month: Int?, day: Int?) = CharacterStateChange(
        characterId = id,
        year = 1000,
        month = month,
        day = day,
        fieldKey = CharacterStateChange.KEY_BIRTH,
        newValue = ""
    )

    /**
     * **크래시 회귀 방지** — `filterUpcoming`은 `LocalDate.of`에 월·일을 그대로 넘기므로
     * 범위 밖 값 하나가 홈 대시보드를 열 때마다 `DateTimeException`으로 죽였다.
     *
     * 앱의 쓰기 문은 전부 범위를 막지만 **월드패키지 가져오기만 그 밖**이라 그런 행이
     * 실제로 들어올 수 있다. 조용히 보정하지 않고 **배제**하는 것이 규약이다 —
     * 엉뚱한 날짜를 축하하면 그것이 곧 거짓 고지다.
     */
    @Test
    fun `실재하지 않는 월일은 던지지 않고 빠진다`() {
        val bad = listOf(
            birth(1L, 13, 1),
            birth(2L, 0, 15),
            birth(3L, -1, 3),
            birth(4L, 3, 0),
            birth(5L, 3, -5),
            birth(6L, 2, 30),
            birth(7L, 4, 31),
            birth(8L, null, 5),
            birth(9L, 5, null)
        )
        assertTrue("실재하지 않는 날이 결과에 남았다", BirthdayHelper.filterUpcoming(bad, 366).isEmpty())
        assertTrue("todayBirthdays도 집으면 안 된다", BirthdayHelper.todayBirthdays(bad).isEmpty())
    }

    /** 배제가 지나치지 않은가 — 열두 달의 1일은 366일 창에 전부 걸린다. */
    @Test
    fun `실재하는 날은 그대로 걸린다`() {
        val good = (1..12).map { m -> birth(m.toLong(), m, 1) }
        assertEquals(12, BirthdayHelper.filterUpcoming(good, 366).size)
        // 2월 29일은 이 앱이 판타지 역법 때문에 언제나 허용한다(maxDayOfMonth의 계약).
        assertEquals(1, BirthdayHelper.filterUpcoming(listOf(birth(99L, 2, 29)), 366).size)
    }
}
