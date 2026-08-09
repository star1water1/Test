package com.novelcharacter.app.util

/**
 * **"지금 무엇을 물을 것인가"의 단일 소스** — 대결 화면이 손에 든 대기열의 상태 (B-104 화면 계층).
 *
 * [DuelPairing]이 *어떤 짝이 값진가*를 정하고, 이 파일은 그 대기열을 **사람이 누르는 속도로
 * 소비하는 규칙**을 정한다. 둘이 갈리는 이유는 시간이다 — 한 판을 기록하면 점수가 달라져
 * 대기열도 달라지는데, 그 다시 계산이 끝날 때까지 손을 멈추게 하면 원칙 04(조작 마찰
 * 최소화)를 정면으로 어긴다. 그래서 화면은 **손에 든 대기열로 계속 나아가고**, 새 계획은
 * 준비되는 대로 갈아 끼운다.
 *
 * **이 파일이 pure인 것은 일부러다.** 「세션 착수 규칙」 4번이 적은 대로 이 저장소의 자동
 * 검증은 화면을 보지 못한다. 그러니 화면이 **판단**하는 것은 전부 여기로 내려 실행으로
 * 검증하고, `Fragment`에는 *"들은 것을 그리는 일"*만 남긴다.
 *
 * ## 지키는 규칙 넷
 *
 * 1. **떠 있는 짝은 답하기 전에 바뀌지 않는다.** 배경에서 새 계획이 도착해도 [State.current]는
 *    그대로다 — 고르려던 둘이 손가락 아래에서 갈리면 그 누름은 사용자가 뜻한 것이 아니다.
 * 2. **다시 계산이 못 따라온 사이에 답한 짝은 다시 내지 않는다.** 계획은 *언제까지의 기록을
 *    보았는가*([refresh]의 `planSeq`)를 달고 오므로, 그보다 뒤에 답한 짝만 걸러 낸다.
 *    전부 걸러 내면 **모델이 더 물어야 한다고 판단한 짝까지 사라진다**(천적은 세 판을 봐야
 *    후보가 된다 — [DuelCounterRelations.Options.minSamples]).
 * 3. **되돌리면 그 짝을 다시 묻는다.** 층 B ①(*"잘못 눌렀다"*)은 판을 지우는 것으로 끝나지
 *    않는다 — 지우기만 하면 그 짝은 답이 없는 채로 대기열 뒤편에 남고, 사용자는 자기가
 *    무엇을 고치려 했는지 잃는다.
 * 4. **바닥나면 바닥났다고 말한다.** 접힘·상성 확정으로 낼 짝이 없는 상태는 오류가 아니라
 *    *"이 축은 지금 물을 것이 없다"*이며, 화면은 그것을 진행률과 함께 보여야 한다.
 */
object DuelSession {

    /**
     * 이번 자리에서 답한 한 짝.
     *
     * @property seq 몇 번째 답인가. [refresh]가 *"이 계획이 이 답을 보았는가"*를 가리는 데 쓴다.
     * @property alsoHandled [candidate] 말고 **이 한 번의 답이 함께 답한 짝들**
     *   (k지선다 — [DuelRound.handledPairs]가 낸다). 비어 있으면 1:1이다.
     *   이것을 담지 않으면 방금 답한 짝이 곧바로 다시 뜬다.
     */
    data class Answered(
        val candidate: DuelPairing.Candidate,
        val seq: Long,
        val alsoHandled: Set<DuelRating.PairKey> = emptySet()
    ) {
        /** 이 답이 걷어 낸 짝 전부 — 머리 짝과 거들린 것들. */
        val pairs: Set<DuelRating.PairKey> get() = alsoHandled + candidate.pair
    }

    /**
     * @property current 화면에 떠 있는 짝. null이면 낼 짝이 없다([exhausted]).
     * @property queue 아직 내지 않은 후보 — [current]는 들어 있지 않다.
     * @property history 이번 자리에서 답한 순서. 되돌리기가 뒤에서부터 꺼낸다.
     * @property seq 지금까지 답한 수. 다시 계산을 띄울 때 이 값을 함께 넘겨 두었다가
     *   [refresh]에 되돌려 주면 *"그 계획이 어디까지 본 것인가"*가 된다.
     */
    data class State(
        val current: DuelPairing.Candidate? = null,
        val queue: List<DuelPairing.Candidate> = emptyList(),
        val history: List<Answered> = emptyList(),
        val seq: Long = 0L
    ) {
        /** 낼 짝이 없다 — 전부 접혔거나 상성으로 확정됐거나 참가자가 둘 미만이다. */
        val exhausted: Boolean get() = current == null && queue.isEmpty()

        /** 되돌릴 것이 있는가 — 화면의 '방금 그거 취소'가 이 값으로 켜고 끈다. */
        val canUndo: Boolean get() = history.isNotEmpty()

        /** 가장 최근 답. 되돌리기가 집는 대상이다. */
        val lastAnswered: Answered? get() = history.lastOrNull()
    }

    /** 첫 대기열을 받는다. */
    fun begin(plan: DuelPairing.Plan): State = State(
        current = plan.queue.firstOrNull(),
        queue = plan.queue.drop(1)
    )

    /**
     * 떠 있던 짝에 답했다 — 다음 짝을 올린다.
     *
     * 답한 짝을 대기열에서 걷어 내는 것은 **이 대기열에 한해서**다. 다시 계산이 그 짝을 또
     * 내면 그때는 낸다 — 모델이 *"아직 더 봐야 한다"*고 말한 것이라, 화면이 자기 판단으로
     * 막으면 천적 검출에 필요한 표본이 영영 모이지 않는다.
     *
     * @param alsoHandled 이 한 번의 답이 머리 짝과 **함께** 답한 짝들(k지선다).
     *   [DuelRound.handledPairs]를 그대로 넘긴다. 1:1이면 비운다.
     */
    fun answer(
        state: State,
        alsoHandled: Set<DuelRating.PairKey> = emptySet()
    ): State {
        val answered = state.current ?: return state
        val seq = state.seq + 1
        val history = state.history + Answered(answered, seq, alsoHandled)
        val handled = history.flatMapTo(HashSet()) { it.pairs }
        val next = state.queue.indexOfFirst { !handled.contains(it.pair) }
        return State(
            current = if (next < 0) null else state.queue[next],
            queue = if (next < 0) emptyList() else state.queue.drop(next + 1),
            history = history,
            seq = seq
        )
    }

    /**
     * 층 B ①의 화면 쪽 절반 — **되돌린 짝을 다시 올린다**(판을 지우는 것은 저장소의 몫).
     *
     * 떠 있던 짝은 버리지 않고 대기열 맨 앞으로 돌려보낸다. 되돌리기는 *"방금 것을 고치겠다"*
     * 이지 *"그다음에 물으려던 것을 취소하겠다"*가 아니다.
     *
     * @return 되돌릴 것이 없으면 받은 상태 그대로.
     */
    fun undo(state: State): State {
        val last = state.history.lastOrNull() ?: return state
        return State(
            current = last.candidate,
            queue = listOfNotNull(state.current) + state.queue,
            history = state.history.dropLast(1),
            // 차례는 되돌리지 않는다 — 되돌린 뒤에 답하면 그것은 **새 답**이고, 배경에서 돌던
            // 옛 계획이 그 답을 본 것으로 오해하면 안 된다.
            seq = state.seq
        )
    }

    /**
     * 새로 계산된 대기열을 갈아 끼운다.
     *
     * @param planSeq 이 계획이 **몇 번째 답까지 반영한 것인가**. 다시 계산을 띄울 때의
     *   [State.seq]를 그대로 넘긴다. 그보다 **뒤에** 답한 짝은 이 계획이 모르므로 걸러 낸다.
     * @return 떠 있는 짝은 그대로 둔 채 뒤만 갈린 상태. 떠 있는 짝이 없었다면 새 대기열의
     *   맨 앞이 올라온다(바닥났던 화면이 스스로 되살아나는 경로다).
     */
    fun refresh(state: State, plan: DuelPairing.Plan, planSeq: Long): State {
        val unseen = state.history.filter { it.seq > planSeq }.flatMapTo(HashSet()) { it.pairs }
        val fresh = plan.queue.filter { it.pair !in unseen && it.pair != state.current?.pair }
        if (state.current != null) return state.copy(queue = fresh)

        // 대기열이 말라 이 새 계획에서 다음 짝을 고르는 자리다. **방금 답한 짝은 뒤로 미룬다** —
        // 모델은 그 짝을 더 보고 싶어 할 수 있지만(그래서 대기열 맨 앞에 있다), 사용자에게는
        // 누른 직후 같은 둘이 다시 뜨는 것이라 *"내 누름이 먹었나"*가 된다. 다른 짝이 하나도
        // 없을 때만 그대로 낸다 — **미루는 것이지 빼는 것이 아니다.**
        //
        // k지선다에서는 **그 화면이 답한 짝 전부**를 미룬다(머리 짝만 미루면 방금 함께 뜬
        // 셋째·넷째와의 짝이 곧바로 되돌아와, 사용자에게는 같은 얼굴이 연속으로 뜬다).
        val justAnswered = state.history.lastOrNull()?.pairs.orEmpty()
        val index = fresh.indexOfFirst { it.pair !in justAnswered }.takeIf { it >= 0 } ?: 0
        return state.copy(
            current = fresh.getOrNull(index),
            queue = fresh.filterIndexed { i, _ -> i != index }
        )
    }
}
