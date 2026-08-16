package com.novelcharacter.app.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `util/SqlInChunks.kt` — `IN (:목록)` 질의를 바인드 변수 상한 아래로 가르는 통로 (B-242).
 *
 * **무엇을 재는가: 나눈 뒤에도 답이 같은가.** 나누기가 틀리는 방향은 셋이고 **셋 다 조용하다.**
 * ⓐ 조각이 겹치거나 빠지면 **일부 행이 처리되지 않는데 오류가 없다** ⓑ 계수 질의를 나눠 놓고
 * 조각별 답을 더하지 않으면 **화면이 적은 수를 말한다**(마지막 조각의 수가 전체인 척한다)
 * ⓒ 한 덩이에 들어가는데도 쪼개면 왕복이 공연히 는다. 전부 예외를 던지지 않으므로
 * 시험이 아니면 잡히지 않는다.
 *
 * **상한을 넘는 목록을 실제로 만들어 잰다** — [SqlInChunks.LIMIT]보다 긴 입력을 주지 않으면
 * 이 파일이 지키려는 코드 경로가 한 줄도 돌지 않는다.
 */
class SqlInChunksTest {

    /** 조각을 그대로 기록하는 가짜 질의 — 무엇이 몇 번 넘어갔는지가 재는 대상이다. */
    private class Recorder {
        val calls = ArrayList<List<Long>>()
        fun record(chunk: List<Long>): List<Long> {
            calls.add(chunk.toList())   // 조각은 뷰다 — 복사해 둬야 나중 비교가 흔들리지 않는다
            return chunk
        }
    }

    private fun ids(n: Int, from: Long = 1L): List<Long> = (from until from + n).toList()

    // ── ① 한 덩이에 들어가면 쪼개지 않는다 (안전한 길이 곧 빠른 길이다) ──

    @Test
    fun `상한 이하면 원본을 그대로 한 번만 넘긴다`() = runBlocking {
        val rec = Recorder()
        val input = ids(SqlInChunks.LIMIT)
        SqlInChunks.flat(input) { rec.record(it) }
        assertEquals(1, rec.calls.size)
        assertEquals(input, rec.calls[0])
    }

    @Test
    fun `쪼갤 필요가 없으면 새 리스트를 만들지 않는다`() = runBlocking {
        val input = ids(10)
        var seen: List<Long>? = null
        SqlInChunks.flat(input) { seen = it; it }
        // 같은 인스턴스여야 한다 — 손으로 적던 `.chunked(900).flatMap{}`은 이 흔한 경우에도
        // 리스트 둘을 새로 만들었다. 통로로 옮기는 것이 비용을 **더는** 쪽이라는 근거다.
        assertSame(input, seen)
    }

    @Test
    fun `빈 목록이면 질의를 아예 부르지 않는다`() = runBlocking {
        val rec = Recorder()
        assertEquals(emptyList<Long>(), SqlInChunks.flat(emptyList<Long>()) { rec.record(it) })
        SqlInChunks.each(emptyList<Long>()) { rec.record(it) }
        assertEquals(0, SqlInChunks.sum(emptyList<Long>()) { rec.record(it).size })
        assertEquals(0, rec.calls.size)
    }

    // ── ② 상한을 넘으면 빠짐도 겹침도 없이 가른다 ──

    @Test
    fun `상한을 넘으면 조각으로 가르고 모든 원소를 정확히 한 번씩 넘긴다`() = runBlocking {
        val rec = Recorder()
        val input = ids(SqlInChunks.LIMIT * 2 + 7)
        SqlInChunks.flat(input) { rec.record(it) }
        assertEquals(3, rec.calls.size)
        assertTrue(rec.calls.all { it.size <= SqlInChunks.LIMIT })
        assertTrue(rec.calls.none { it.isEmpty() })
        // 순서까지 보존한다 — 이어 붙이면 원본과 글자 그대로 같아야 한다
        assertEquals(input, rec.calls.flatten())
    }

    @Test
    fun `flat은 조각의 답을 순서대로 잇는다`() = runBlocking {
        val input = ids(SqlInChunks.LIMIT + 1)
        assertEquals(input, SqlInChunks.flat(input) { it })
    }

    @Test
    fun `each도 같은 조각으로 가른다`() = runBlocking {
        val rec = Recorder()
        val input = ids(SqlInChunks.LIMIT + 1)
        SqlInChunks.each(input) { rec.record(it) }
        assertEquals(2, rec.calls.size)
        assertEquals(input, rec.calls.flatten())
    }

    // ── ③ 계수는 **더한다** — 이어 붙이는 것이 아니다 ──

    @Test
    fun `sum은 조각별 답을 더한다`() = runBlocking {
        val input = ids(SqlInChunks.LIMIT * 2)
        // 조각 크기를 그대로 돌려주는 질의 — 합은 전체 길이여야 한다.
        // 마지막 조각 값을 그대로 쓰면 LIMIT가 나오고, 그것이 화면에 실리던 거짓 계수다.
        assertEquals(input.size, SqlInChunks.sum(input) { it.size })
    }

    @Test
    fun `sum은 한 덩이일 때도 같은 답을 낸다`() = runBlocking {
        val input = ids(5)
        assertEquals(5, SqlInChunks.sum(input) { it.size })
    }

    // ── ④ 목록이 둘인 질의 — 예약분을 밝힌다 ──

    @Test
    fun `sizeFor는 예약분을 빼고 최소 1을 지킨다`() {
        assertEquals(SqlInChunks.LIMIT, SqlInChunks.sizeFor(0))
        assertEquals(SqlInChunks.LIMIT - 10, SqlInChunks.sizeFor(10))
        // 예약분이 상한을 넘어도 0이나 음수를 내지 않는다 — 0이면 조각이 영영 안 나아가
        // **무한 루프**가 되고, 그것은 상한 초과보다 나쁘다.
        assertEquals(1, SqlInChunks.sizeFor(SqlInChunks.LIMIT))
        assertEquals(1, SqlInChunks.sizeFor(SqlInChunks.LIMIT + 500))
    }

    @Test
    fun `예약분이 있으면 그만큼 짧게 가른다`() = runBlocking {
        val rec = Recorder()
        val input = ids(SqlInChunks.LIMIT)
        SqlInChunks.each(input, reservedBinds = 100) { rec.record(it) }
        assertEquals(2, rec.calls.size)
        assertTrue(rec.calls.all { it.size <= SqlInChunks.LIMIT - 100 })
        assertEquals(input, rec.calls.flatten())
    }

    @Test
    fun `예약분이 상한 이상이어도 끝까지 나아간다`() = runBlocking {
        val rec = Recorder()
        val input = ids(3)
        SqlInChunks.each(input, reservedBinds = SqlInChunks.LIMIT + 1) { rec.record(it) }
        assertEquals(3, rec.calls.size)
        assertEquals(input, rec.calls.flatten())
    }

    // ── ⑤ List가 아닌 Collection도 받는다 ──

    @Test
    fun `Set을 넘겨도 순회 순서대로 가른다`() = runBlocking {
        val rec = Recorder()
        val input = LinkedHashSet(ids(SqlInChunks.LIMIT + 3))
        SqlInChunks.flat(input) { rec.record(it) }
        assertEquals(2, rec.calls.size)
        assertEquals(input.toList(), rec.calls.flatten())
    }
}
