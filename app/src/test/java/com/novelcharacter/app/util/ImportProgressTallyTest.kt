package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가져오기 진행 셈의 계약 — 막대는 **뒤로 가지 않고**, 분모를 넘지 않으며,
 * **한 줄도 안 돈 회차를 100%라 말하지 않는다**.
 */
class ImportProgressTallyTest {

    @Test
    fun `분모가 0 이하이면 1로 본다`() {
        assertEquals(1, ImportProgressTally(0).totalRows)
        assertEquals(1, ImportProgressTally(-5).totalRows)
    }

    @Test
    fun `보인 값은 뒤로 가지 않는다`() {
        val t = ImportProgressTally(1000)
        assertEquals(300, t.show("캐릭터", 300))
        // 같은 시트를 두 번 도는 자리(2패스)의 두 번째 회차는 셈이 0에서 다시 시작한다.
        assertEquals(300, t.show("캐릭터", 40))
        assertEquals(420, t.show("캐릭터", 420))
    }

    @Test
    fun `보인 값은 분모를 넘지 않는다`() {
        val t = ImportProgressTally(100)
        assertEquals(100, t.show("관계", 250))
    }

    @Test
    fun `빈 이름표는 이름을 덮지 않는다`() {
        val t = ImportProgressTally(100)
        t.show("이름 은행", 10)
        t.show("", 20)
        assertEquals("이름 은행", t.lastPhase)
    }

    @Test
    fun `중간 보고는 주기마다 한 번이고 첫 행에서는 내지 않는다`() {
        val t = ImportProgressTally(100)
        assertFalse(t.shouldTick(0))
        assertFalse(t.shouldTick(1))
        assertFalse(t.shouldTick(ImportProgressTally.TICK_ROWS - 1))
        assertTrue(t.shouldTick(ImportProgressTally.TICK_ROWS))
        assertFalse(t.shouldTick(ImportProgressTally.TICK_ROWS + 1))
        assertTrue(t.shouldTick(ImportProgressTally.TICK_ROWS * 2))
    }

    /**
     * **시트 몫을 0으로 얹는 구간 뒤에도 막대가 계속 움직인다** (2026.08.23 콜드 검토).
     *
     * 캐릭터 시트는 세계관마다 여러 장인데 묶음 끝에서 한 번, 그것도 `0`으로 보고한다.
     * 중간 보고가 보인 값을 누계가 흡수하지 않으면 다음 시트의 출발점이 보인 값보다 낮고,
     * 단조 빗장이 그것을 눌러 **막대가 그 뒤로 통째로 얼어붙는다.**
     */
    @Test
    fun `0으로 보고하는 구간 뒤에도 막대가 이어진다`() {
        val t = ImportProgressTally(10_000)
        // 시트 하나를 돌며 중간 보고가 6,100까지 보였다(누계는 아직 100).
        assertEquals(6_100, t.show("캐릭터", t.sheetBase(100) + 6_000))
        // 그 구간의 끝맺음은 자기 몫을 0으로 얹는다 — 누계가 보인 값을 흡수해야 한다.
        val after = t.advance(100, 0)
        assertEquals(6_100, after)
        // 다음 시트의 출발점이 보인 값 위에서 이어진다.
        assertEquals(6_100, t.sheetBase(after))
        assertEquals(6_164, t.show("관계", t.sheetBase(after) + 64))
    }

    @Test
    fun `누계를 미는 것은 이미 보인 값 아래로 내려가지 않는다`() {
        val t = ImportProgressTally(1_000)
        t.show("캐릭터", 400)
        assertEquals(400, t.advance(100, 50))
        assertEquals(500, t.advance(400, 100))
    }

    @Test
    fun `끝맺음은 막대를 분모까지 올린다`() {
        // 분모는 파일의 모든 시트를 세는데 회차는 고른 범주만 돈다 —
        // 다 끝나고도 60%에 서 있던 자리다.
        val t = ImportProgressTally(1000)
        t.show("캐릭터", 600)
        assertEquals(1000, t.settle())
        assertEquals(1000, t.lastShownRows)
        assertEquals("캐릭터", t.lastPhase)
    }

    @Test
    fun `한 줄도 안 돈 회차는 굳히지 않는다`() {
        // 이름표조차 없으면 알릴 구간이 없었다는 뜻이다 — 그때 100%는 *하지 않은 일*이다.
        val t = ImportProgressTally(1000)
        assertNull(t.settle())
        assertEquals(0, t.lastShownRows)
    }
}
