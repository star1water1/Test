package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 화면의 대기열 소비(B-104 화면 계층)를 지키는 하네스.
 *
 * **여기가 두꺼운 것은 화면이 얇기 때문이다.** 「세션 착수 규칙」 4번대로 이 저장소의 자동
 * 검증은 화면을 못 보므로, 화면이 *판단*하는 것을 전부 [DuelSession]으로 내리고 그 판단을
 * 여기서 실행으로 잰다. 아래 넷이 깨지면 화면에서 나는 증상은 각각
 * *"고르려는 순간 둘이 바뀐다"* · *"방금 답한 걸 또 묻는다"* · *"취소했는데 안 다시 묻는다"* ·
 * *"물을 게 없는데 빈 화면만 뜬다"*이다.
 */
class DuelSessionTest {

    private fun candidate(a: Long, b: Long, priority: Double = 1.0): DuelPairing.Candidate =
        DuelPairing.Candidate(
            pair = DuelRating.PairKey.of(a, b),
            priority = priority,
            winProbability = 0.5,
            confidence = 0.5,
            played = 0,
            reason = DuelPairing.Reason.CLOSE
        )

    private fun plan(vararg candidates: DuelPairing.Candidate): DuelPairing.Plan =
        DuelPairing.Plan(
            queue = candidates.toList(),
            progress = DuelPairing.Progress(0L, 0L, 0L, 0L, true),
            scannedPairs = 0L,
            scanCapped = false
        )

    @Test
    fun `첫 대기열의 맨 앞이 올라온다`() {
        val state = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))

        assertEquals(DuelRating.PairKey.of(1, 2), state.current?.pair)
        assertEquals(1, state.queue.size)
        assertFalse(state.exhausted)
        assertFalse(state.canUndo)
    }

    @Test
    fun `빈 계획은 바닥난 상태다 — 오류가 아니라 말할 것이 있는 상태`() {
        val state = DuelSession.begin(plan())

        assertNull(state.current)
        assertTrue(state.exhausted)
    }

    @Test
    fun `답하면 다음 짝이 올라오고 되돌릴 것이 생긴다`() {
        val after = DuelSession.answer(DuelSession.begin(plan(candidate(1, 2), candidate(3, 4))))

        assertEquals(DuelRating.PairKey.of(3, 4), after.current?.pair)
        assertEquals(1L, after.seq)
        assertTrue(after.canUndo)
        assertEquals(DuelRating.PairKey.of(1, 2), after.lastAnswered?.candidate?.pair)
    }

    @Test
    fun `한 대기열 안에서 같은 짝을 두 번 묻지 않는다`() {
        // 같은 짝이 대기열에 두 번 든 계획 — 층 3이 접힌 것을 다시 꺼내면 실제로 생길 수 있다.
        val state = DuelSession.begin(plan(candidate(1, 2), candidate(1, 2), candidate(3, 4)))

        val after = DuelSession.answer(state)

        assertEquals(DuelRating.PairKey.of(3, 4), after.current?.pair)
    }

    @Test
    fun `마지막까지 답하면 바닥난다`() {
        var state = DuelSession.begin(plan(candidate(1, 2)))
        state = DuelSession.answer(state)

        assertNull(state.current)
        assertTrue(state.exhausted)
        assertTrue(state.canUndo)
    }

    @Test
    fun `되돌리면 그 짝을 다시 묻는다 — 지우기만 하면 고칠 자리가 사라진다`() {
        val start = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))
        val answered = DuelSession.answer(start)

        val undone = DuelSession.undo(answered)

        assertEquals(DuelRating.PairKey.of(1, 2), undone.current?.pair)
        assertFalse(undone.canUndo)
    }

    @Test
    fun `되돌려도 그다음에 물으려던 짝은 버리지 않는다`() {
        val answered = DuelSession.answer(DuelSession.begin(plan(candidate(1, 2), candidate(3, 4))))

        val undone = DuelSession.undo(answered)
        val again = DuelSession.answer(undone)

        assertEquals(
            "되돌리기는 '방금 것을 고치겠다'이지 '그다음을 취소하겠다'가 아니다",
            DuelRating.PairKey.of(3, 4),
            again.current?.pair
        )
    }

    @Test
    fun `되돌릴 것이 없으면 상태가 그대로다`() {
        val start = DuelSession.begin(plan(candidate(1, 2)))

        assertEquals(start, DuelSession.undo(start))
    }

    @Test
    fun `되돌려도 차례는 되감지 않는다 — 되돌린 뒤의 답은 새 답이다`() {
        val answered = DuelSession.answer(DuelSession.begin(plan(candidate(1, 2), candidate(3, 4))))
        val undone = DuelSession.undo(answered)

        val again = DuelSession.answer(undone)

        assertEquals(2L, again.seq)
    }

    @Test
    fun `새 대기열이 와도 떠 있는 짝은 바뀌지 않는다`() {
        val state = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))

        val refreshed = DuelSession.refresh(state, plan(candidate(5, 6), candidate(7, 8)), state.seq)

        assertEquals(DuelRating.PairKey.of(1, 2), refreshed.current?.pair)
        assertEquals(
            listOf(DuelRating.PairKey.of(5, 6), DuelRating.PairKey.of(7, 8)),
            refreshed.queue.map { it.pair }
        )
    }

    @Test
    fun `새 대기열은 떠 있는 짝을 뒤에 또 담지 않는다`() {
        val state = DuelSession.begin(plan(candidate(1, 2)))

        val refreshed = DuelSession.refresh(state, plan(candidate(1, 2), candidate(3, 4)), state.seq)

        assertEquals(listOf(DuelRating.PairKey.of(3, 4)), refreshed.queue.map { it.pair })
    }

    @Test
    fun `다시 계산이 못 본 답은 새 대기열에서 걸러 낸다`() {
        // 계산을 띄운 시점의 차례를 들고 있다가, 그 뒤에 답한 짝은 새 대기열에서 뺀다.
        val start = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))
        val planSeq = start.seq
        val answered = DuelSession.answer(start)

        val refreshed = DuelSession.refresh(answered, plan(candidate(1, 2), candidate(5, 6)), planSeq)

        assertEquals(
            "계획이 만들어진 뒤에 답한 짝은 그 계획이 모른다",
            listOf(DuelRating.PairKey.of(5, 6)),
            refreshed.queue.map { it.pair }
        )
    }

    @Test
    fun `계산이 본 답은 걸러 내지 않는다 — 모델이 더 물으라 한 것이다`() {
        // 천적은 세 판을 봐야 후보가 된다. 한 번 답했다고 화면이 스스로 막으면 그 표본이
        // 영영 모이지 않는다.
        val start = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))
        val answered = DuelSession.answer(start)

        val refreshed = DuelSession.refresh(answered, plan(candidate(1, 2)), answered.seq)

        assertEquals(listOf(DuelRating.PairKey.of(1, 2)), refreshed.queue.map { it.pair })
    }

    @Test
    fun `바닥났던 화면은 새 대기열로 되살아난다`() {
        var state = DuelSession.begin(plan(candidate(1, 2)))
        state = DuelSession.answer(state)
        assertTrue(state.exhausted)

        val refreshed = DuelSession.refresh(state, plan(candidate(3, 4), candidate(5, 6)), state.seq)

        assertEquals(DuelRating.PairKey.of(3, 4), refreshed.current?.pair)
        assertEquals(1, refreshed.queue.size)
        assertFalse(refreshed.exhausted)
    }

    @Test
    fun `방금 답한 짝을 곧바로 다시 묻지 않는다`() {
        // 대기열이 마른 뒤 새 계획을 받는 자리. 모델은 방금 그 짝을 더 보고 싶어 하지만
        // (그래서 맨 앞에 있다) 누른 직후 같은 둘이 뜨면 사용자는 누름이 먹었는지 알 수 없다.
        var state = DuelSession.begin(plan(candidate(1, 2)))
        state = DuelSession.answer(state)

        val refreshed = DuelSession.refresh(state, plan(candidate(1, 2), candidate(3, 4)), state.seq)

        assertEquals(DuelRating.PairKey.of(3, 4), refreshed.current?.pair)
        assertEquals(
            "미루는 것이지 빼는 것이 아니다",
            listOf(DuelRating.PairKey.of(1, 2)),
            refreshed.queue.map { it.pair }
        )
    }

    @Test
    fun `낼 짝이 그것뿐이면 방금 답한 짝이라도 낸다`() {
        var state = DuelSession.begin(plan(candidate(1, 2)))
        state = DuelSession.answer(state)

        val refreshed = DuelSession.refresh(state, plan(candidate(1, 2)), state.seq)

        assertEquals(DuelRating.PairKey.of(1, 2), refreshed.current?.pair)
        assertTrue(refreshed.queue.isEmpty())
    }

    @Test
    fun `실제 계획과 이어 붙여도 멈춘다 — 답할수록 물을 것이 줄어든다`() {
        // 매 판 뒤 다시 계산해 갈아 끼우는 화면의 실제 경로를 그대로 돌린다.
        // 대기열이 도는 결함은 화면에서 **끝나지 않는 대결**로 나타나고, 그때 사용자가 할 수
        // 있는 일은 앱을 끄는 것뿐이다. 네 명·여섯 짝이 실측 67판에서 마른다.
        val participants = listOf(1L, 2L, 3L, 4L)
        val matches = ArrayList<DuelRating.Match>()
        val asked = ArrayList<DuelRating.PairKey>()
        var state = DuelSession.begin(DuelPairing.plan(DuelRating.fit(participants, matches)))

        while (state.current != null && asked.size < 500) {
            val pair = state.current!!.pair
            asked.add(pair)
            matches.add(DuelRating.Match(pair.lowId, pair.highId, pair.lowId))
            state = DuelSession.answer(state)
            val seq = state.seq
            state = DuelSession.refresh(
                state,
                DuelPairing.plan(DuelRating.fit(participants, matches)),
                seq
            )
        }

        assertTrue("대기열이 스스로 마른다", state.exhausted)
        assertEquals(asked.size, matches.size)
        assertEquals("네 명이면 짝은 여섯이다", 6, asked.distinct().size)
        assertEquals(
            "같은 짝이 연달아 두 번 뜨지 않는다",
            0,
            (1 until asked.size).count { asked[it] == asked[it - 1] }
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // k지선다 — 한 번의 답이 여러 짝을 답한다 (B-115)
    //
    // 대기열은 여전히 짝 단위인데 한 화면이 여러 짝을 한꺼번에 답하므로, **함께 답한 것을
    // 알려 주지 않으면 방금 고른 얼굴이 곧바로 다시 뜬다.**
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `함께 답한 짝은 대기열에서 함께 빠진다`() {
        val state = DuelSession.begin(plan(candidate(1, 2), candidate(1, 3), candidate(4, 5)))

        // {1,2,3}을 띄우고 1을 골랐다 — (1,2)와 (1,3)이 함께 답해졌다.
        val next = DuelSession.answer(
            state,
            setOf(DuelRating.PairKey.of(1, 2), DuelRating.PairKey.of(1, 3))
        )

        assertEquals(DuelRating.PairKey.of(4, 5), next.current?.pair)
        assertTrue(next.queue.isEmpty())
    }

    @Test
    fun `안 물은 짝은 걷어 내지 않는다`() {
        // {1,2,3}에서 3을 골랐으면 (1,2)는 **답이 없다** — 모델이 다시 낼 수 있어야 한다.
        val state = DuelSession.begin(plan(candidate(1, 3), candidate(1, 2), candidate(2, 3)))
        val next = DuelSession.answer(
            state,
            setOf(DuelRating.PairKey.of(1, 3), DuelRating.PairKey.of(2, 3))
        )

        assertEquals(DuelRating.PairKey.of(1, 2), next.current?.pair)
    }

    @Test
    fun `함께 답한 짝은 낡은 계획에서도 걸러진다`() {
        var state = DuelSession.begin(plan(candidate(1, 2), candidate(1, 3)))
        val planSeq = state.seq
        state = DuelSession.answer(
            state,
            setOf(DuelRating.PairKey.of(1, 2), DuelRating.PairKey.of(1, 3))
        )

        // 답하기 전에 뜬 계획이 뒤늦게 도착했다 — 그 계획은 이 답을 못 봤다.
        val stale = plan(candidate(1, 2), candidate(1, 3), candidate(6, 7))
        val refreshed = DuelSession.refresh(state, stale, planSeq)

        assertEquals(DuelRating.PairKey.of(6, 7), refreshed.current?.pair)
        assertTrue(refreshed.queue.isEmpty())
    }

    @Test
    fun `대기열이 마르면 방금 화면의 짝 전부를 뒤로 미룬다`() {
        var state = DuelSession.begin(plan(candidate(1, 2)))
        state = DuelSession.answer(
            state,
            setOf(DuelRating.PairKey.of(1, 2), DuelRating.PairKey.of(1, 3))
        )
        assertNull(state.current)

        // 새 계획이 방금 화면의 짝을 다시 냈다 — 머리 짝만 미루면 (1,3)이 곧바로 떠서
        // 사용자에게는 같은 얼굴이 연속으로 뜨는 것으로 보인다.
        val next = DuelSession.refresh(
            state,
            plan(candidate(1, 3), candidate(8, 9)),
            state.seq
        )

        assertEquals(DuelRating.PairKey.of(8, 9), next.current?.pair)
    }

    @Test
    fun `함께 답한 것이 없으면 종전과 같다`() {
        val state = DuelSession.begin(plan(candidate(1, 2), candidate(3, 4)))

        assertEquals(DuelSession.answer(state), DuelSession.answer(state, emptySet()))
    }
}
