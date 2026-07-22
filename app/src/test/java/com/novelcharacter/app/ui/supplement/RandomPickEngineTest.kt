package com.novelcharacter.app.ui.supplement

import com.novelcharacter.app.ui.supplement.RandomPickEngine.Entry
import com.novelcharacter.app.ui.supplement.RandomPickEngine.PickMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomPickEngineTest {

    private fun entries(vararg ids: Long): List<Entry> =
        ids.map { Entry(id = it, updatedAt = it * 10, isIncomplete = false) }

    private fun engine(seed: Int = 42): RandomPickEngine = RandomPickEngine(Random(seed))

    // ===== 셔플백 =====

    @Test
    fun `한 사이클 동안 중복 없이 전원 뽑는다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        val drawn = (1..10).map { e.next()!! }
        assertEquals(10, drawn.distinct().size)
        assertEquals((1L..10L).toSet(), drawn.toSet())
        assertFalse(e.lastDrawCompletedCycle)
    }

    @Test
    fun `가방 소진 후 재충전되며 한 바퀴 신호가 켜진다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3))
        repeat(3) { e.next() }
        val fourth = e.next()
        assertTrue(e.lastDrawCompletedCycle)
        assertTrue(fourth in listOf(1L, 2L, 3L))
    }

    @Test
    fun `재충전 직후 직전 뽑기가 바로 반복되지 않는다`() {
        // 여러 시드에서 확인 — 풀 크기 2 이상이면 사이클 경계에서 연속 중복 없음
        for (seed in 0..20) {
            val e = engine(seed)
            e.updatePool(entries(1, 2, 3, 4, 5))
            var prev: Long? = null
            repeat(15) {
                val cur = e.next()!!
                if (prev != null) assertNotEquals("seed=$seed", prev, cur)
                prev = cur
            }
        }
    }

    @Test
    fun `풀이 1명이면 같은 캐릭터가 반복 뽑힌다`() {
        val e = engine()
        e.updatePool(entries(7))
        assertEquals(7L, e.next())
        assertEquals(7L, e.next())
        assertEquals(7L, e.current())
    }

    @Test
    fun `빈 풀에서는 null을 반환한다`() {
        val e = engine()
        e.updatePool(emptyList())
        assertNull(e.next())
        assertNull(e.current())
        assertFalse(e.canGoBack)
        assertFalse(e.canGoForward)
    }

    // ===== 히스토리 =====

    @Test
    fun `이전-다음 이동이 히스토리를 따라간다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5))
        val first = e.next()!!
        val second = e.next()!!
        val third = e.next()!!

        assertEquals(second, e.goBack())
        assertEquals(first, e.goBack())
        assertFalse(e.canGoBack)
        assertNull(e.goBack())

        assertTrue(e.canGoForward)
        assertEquals(second, e.goForward())
        assertEquals(third, e.goForward())
        assertFalse(e.canGoForward)
        assertNull(e.goForward())
    }

    @Test
    fun `뒤로 간 상태에서 새로 뽑으면 앞쪽 분기가 잘린다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5))
        e.next(); e.next(); e.next()
        e.goBack(); e.goBack() // 첫 뽑기 위치로
        val fresh = e.next()!!
        assertFalse(e.canGoForward)
        assertEquals(fresh, e.current())
        // 뒤로 한 번 가면 첫 뽑기가 나온다 (2·3번째는 잘렸다)
        e.goBack()
        assertFalse(e.canGoBack)
    }

    // ===== 풀 갱신 =====

    @Test
    fun `updatePool은 삭제된 id를 가방과 히스토리에서 정리한다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5))
        val first = e.next()!!
        val second = e.next()!!

        // 현재(second)를 풀에서 제거
        val remaining = listOf(1L, 2L, 3L, 4L, 5L).filter { it != second }
        val result = e.updatePool(entries(*remaining.toLongArray()))

        assertFalse(result.currentSurvived)
        assertFalse(result.poolEmpty)
        // 현재는 직전 생존 항목(first)으로 물러난다
        assertEquals(first, e.current())
        // 이후 뽑기에서 삭제된 id는 나오지 않는다
        repeat(10) { assertNotEquals(second, e.next()) }
    }

    @Test
    fun `updatePool에서 현재 뽑기가 살아남으면 위치가 유지된다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5))
        e.next(); e.next()
        val cur = e.current()!!
        val result = e.updatePool(entries(1, 2, 3, 4, 5))
        assertTrue(result.currentSurvived)
        assertEquals(cur, e.current())
    }

    @Test
    fun `풀이 전부 사라지면 poolEmpty가 참이고 current는 null이 된다`() {
        val e = engine()
        e.updatePool(entries(1, 2))
        e.next()
        val result = e.updatePool(emptyList())
        assertTrue(result.poolEmpty)
        assertFalse(result.currentSurvived)
        assertNull(e.current())
    }

    // ===== 미흡 우선 모드 =====

    @Test
    fun `미흡 우선 모드는 미흡 캐릭터만 뽑는다`() {
        val e = engine()
        val pool = listOf(
            Entry(1, 10, isIncomplete = true),
            Entry(2, 20, isIncomplete = false),
            Entry(3, 30, isIncomplete = true),
            Entry(4, 40, isIncomplete = false),
            Entry(5, 50, isIncomplete = true)
        )
        e.setMode(PickMode.INCOMPLETE_FIRST)
        e.updatePool(pool)
        repeat(9) {
            val id = e.next()!!
            assertTrue("미흡 아닌 캐릭터가 뽑힘: $id", id in setOf(1L, 3L, 5L))
            assertFalse(e.lastDrawFellBackToFullPool)
        }
    }

    @Test
    fun `미흡 캐릭터가 없으면 전체 풀로 폴백하고 신호를 켠다`() {
        val e = engine()
        e.setMode(PickMode.INCOMPLETE_FIRST)
        e.updatePool(entries(1, 2, 3)) // 전원 완성
        val id = e.next()
        assertTrue(id in listOf(1L, 2L, 3L))
        assertTrue(e.lastDrawFellBackToFullPool)
    }

    @Test
    fun `폴백 사이클 중 미흡 캐릭터가 생기면 다음 뽑기부터 미흡을 우선한다`() {
        val e = engine()
        e.setMode(PickMode.INCOMPLETE_FIRST)
        e.updatePool(entries(1, 2, 3, 4, 5)) // 전원 완성 → 폴백 사이클 시작
        e.next()
        assertTrue(e.lastDrawFellBackToFullPool)

        // 외부 편집 등으로 2번이 미흡해짐 — 풀 갱신 후에는 폴백 가방을 버리고 미흡을 우선한다
        val updated = listOf(
            Entry(1, 10, isIncomplete = false),
            Entry(2, 20, isIncomplete = true),
            Entry(3, 30, isIncomplete = false),
            Entry(4, 40, isIncomplete = false),
            Entry(5, 50, isIncomplete = false)
        )
        e.updatePool(updated)
        repeat(3) {
            assertEquals(2L, e.next())
            assertFalse(e.lastDrawFellBackToFullPool)
        }
    }

    @Test
    fun `가방에 있던 캐릭터가 미흡 해소되면 건너뛴다`() {
        val e = engine()
        val pool = listOf(
            Entry(1, 10, isIncomplete = true),
            Entry(2, 20, isIncomplete = true),
            Entry(3, 30, isIncomplete = true)
        )
        e.setMode(PickMode.INCOMPLETE_FIRST)
        e.updatePool(pool)
        val first = e.next()!!

        // first를 보충 완료 처리 (저장 후 감사 리로드 시나리오)
        val updated = pool.map { if (it.id == first) it.copy(isIncomplete = false) else it }
        e.updatePool(updated)

        // 남은 미흡 2명만 계속 뽑힌다
        repeat(6) {
            val id = e.next()!!
            assertNotEquals(first, id)
            assertFalse(e.lastDrawFellBackToFullPool)
        }
    }

    // ===== 오래 안 본 순 모드 =====

    @Test
    fun `오래 안 본 순 모드는 updatedAt 오름차순으로 뽑는다`() {
        val e = engine()
        e.setMode(PickMode.LEAST_RECENT)
        e.updatePool(
            listOf(
                Entry(1, updatedAt = 300, isIncomplete = false),
                Entry(2, updatedAt = 100, isIncomplete = false),
                Entry(3, updatedAt = 200, isIncomplete = false)
            )
        )
        assertEquals(2L, e.next())
        assertEquals(3L, e.next())
        assertEquals(1L, e.next())
    }

    @Test
    fun `모드 변경은 사이클을 리셋하지만 히스토리는 유지한다`() {
        val e = engine()
        e.updatePool(entries(1, 2, 3, 4, 5))
        e.next(); e.next()
        val cur = e.current()!!

        e.setMode(PickMode.LEAST_RECENT)
        assertEquals(cur, e.current())
        assertTrue(e.canGoBack)

        // updatedAt = id*10 이므로 가장 오래된 순서. 현재 캐릭터는 맨 뒤로 밀린다.
        val expectedFirst = listOf(1L, 2L, 3L, 4L, 5L).filter { it != cur }.minBy { it }
        assertEquals(expectedFirst, e.next())
        // 모드 변경 직후 재충전은 한 바퀴 완료 신호가 아니다
        assertFalse(e.lastDrawCompletedCycle)
    }

    @Test
    fun `히스토리는 상한을 넘지 않는다`() {
        val e = engine()
        e.updatePool(entries(*(1L..10L).toList().toLongArray()))
        repeat(RandomPickEngine.MAX_HISTORY + 50) { e.next() }
        // 상한만큼 뒤로 갈 수 있고 그 이상은 불가
        var backSteps = 0
        while (e.canGoBack) {
            e.goBack(); backSteps++
        }
        assertTrue(backSteps <= RandomPickEngine.MAX_HISTORY)
    }
}
