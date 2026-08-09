package com.novelcharacter.app.util

/**
 * **한 화면이 무엇을 묻고 무엇을 남기는가** — k지선다(B-115)의 순수 계층.
 *
 * [DuelPairing]이 *어떤 짝이 값진가*를 정하고 [DuelSession]이 *그 대기열을 어떤 속도로
 * 소비하는가*를 정한다. 이 파일은 그 사이에 하나를 더 끼운다 — **한 화면에 몇을 올리고,
 * 사용자가 하나를 고르면 그것이 어떤 판들이 되는가.**
 *
 * ## 1:1이 특수한 경우가 아니다
 * 종전 화면은 *"둘 중 하나"*를 손으로 다뤘다(`Winner.A`/`B`/`DRAW`). k지선다를 그 옆에 따로
 * 세우면 **같은 판단이 두 곳에 생겨 갈라진다** — 이 저장소가 [DuelPairing] 머리 주석에서
 * 이행 추론기를 따로 만들지 않은 것과 같은 근거다. 그래서 반대로 했다: **1:1을 k=2로 흡수**해
 * 한 규칙이 둘 다 낸다. 아래 [outcomes]에 `k=2`를 넣으면 종전과 **글자 그대로 같은 것**이
 * 나온다(이긴 경우 판 하나, 비슷함 판 하나) — 그것이 이 일반화가 옳다는 증거이고,
 * `DuelRoundTest`가 그 동치를 못 박는다.
 *
 * ## 고르면 `k−1`판이 남는다
 * `{a,b,c,d}`에서 `a`를 골랐다면 남는 사실은 `a>b`·`a>c`·`a>d` **셋뿐**이다. `b`·`c`·`d`
 * 사이는 **아무것도 안 물었으므로 아무것도 적지 않는다** — 화면에 함께 떴다는 이유로
 * 없는 답을 지어내면 그 순간 순위가 사용자가 말하지 않은 것으로 움직인다.
 *
 * ## '비슷함'은 **전 조합**이다
 * 승자가 없으면 남는 사실은 *"이 화면의 것들이 서로 비슷하다"*이고, 그것은 `k−1`이 아니라
 * `C(k,2)`다(승자 기준으로 셀 수 없으니 기준이 없다). `k=2`에서 그 값이 1이라 종전 동작과
 * 같아지는 것도 같은 규칙에서 나온다.
 *
 * **한 화면에 오르는 것들이 애초에 비슷하다는 점이 이 처분을 정당하게 한다** — 대기열은
 * *모델이 가장 헷갈려 하는 짝*부터 내므로([DuelPairing] ①) 함께 뜬 것들은 이미 예측이
 * 반반인 것들이다. 무작위 넷을 모아 놓고 *"다 비슷하다"*로 적는 것과는 다르다.
 *
 * ## 되돌리기는 화면 단위다
 * 한 화면이 낸 판이 둘 이상이면 [DuelMatch.groupId]로 묶는다. 반쪽 되돌리기가 생길 자리를
 * 없애려는 것이고, **판이 하나면 묶지 않는다** — 그 칸의 계약이 *"null이면 단독 판"*이라
 * 1:1의 저장 모양이 종전과 한 글자도 달라지지 않는다.
 *
 * @see DuelCardGrid 이 판을 화면 어느 자리에 놓는가
 */
object DuelRound {

    /** 한 화면에 올릴 수 있는 참가자 수의 위아래 (설계 2장 표 — *"3~4 중 1등"*). */
    const val MIN_SIZE = 2
    const val MAX_SIZE = 4

    /**
     * 한 화면.
     *
     * @property members 화면에 뜨는 참가자 id — **표시 순서 그대로**다. 앞의 둘이 [lead]의
     *   짝이라 1:1과 자리가 같다(설정을 3으로 올려도 왼쪽 둘은 종전과 같은 자리에 있다).
     * @property lead 이 화면을 연 후보. *왜 이것을 묻는가*([DuelPairing.Reason])와
     *   되돌리기의 열쇠가 여기서 나온다 — [DuelSession]의 대기열은 여전히 짝 단위이고,
     *   이 화면은 그 머리 하나를 **거들 것들과 함께** 보여 주는 것이다.
     * @property requestedSize 이 판을 짤 때 **설정이 몇이었는가**. [members]의 크기와 다를 수
     *   있다(대기열이 말라 덜 올라온 경우). [canReuse]가 *설정이 바뀌었는가*를 이것으로 가른다 —
     *   올라온 수로 가르면 대기열이 마른 판을 매번 다시 짜게 된다.
     */
    data class Round(
        val members: List<Long>,
        val lead: DuelPairing.Candidate,
        val requestedSize: Int
    ) {
        val size: Int get() = members.size

        /** 왜 이 화면인가 — 머리 후보의 이유를 그대로 쓴다. */
        val reason: DuelPairing.Reason get() = lead.reason
    }

    /**
     * 이 화면이 남기는 판 하나.
     *
     * @property winnerId null이면 무승부. [aId]·[bId]의 차례는 **기록되는 차례 그대로**이며,
     *   짝의 정체는 [DuelRating.PairKey]가 든다(둘을 헷갈리지 말 것 — 이쪽은 순서가 있다).
     */
    data class Outcome(val aId: Long, val bId: Long, val winnerId: Long?) {
        val pair: DuelRating.PairKey get() = DuelRating.PairKey.of(aId, bId)
    }

    /**
     * 대기열의 머리에서 화면 하나를 짠다.
     *
     * **거들 참가자는 지어내지 않고 대기열에서 뽑는다.** 순위표에서 아무나 끌어오면 모델이
     * 값어치 없다고 판단한 짝을 화면이 되살리는 셈이고, 그러면 *"적게 눌러도 순위가 빨리
     * 자리 잡는다"*는 이 설계의 약속이 화면에서 새어 나간다.
     *
     * 두 번 훑는 것은 **이어진 것을 먼저 붙이기 위해서**다. 첫 훑기는 이미 올라온 참가자와
     * 짝을 이루는 후보만 받는다 — 그런 후보는 *"이 둘이 헷갈린다"*를 뜻하므로 함께 놓으면
     * 한 번의 누름이 실제로 그 물음에 답한다. 그것으로 자리가 안 차면 둘째 훑기가 남은
     * 후보에서 채운다(낮은 값어치라도 [DuelPairing]이 낸 것 안에서만 고른다).
     *
     * @param size 올릴 참가자 수. [MIN_SIZE]~[MAX_SIZE]로 잘린다.
     * @return 대기열이 모자라면 [Round.members]가 [size]보다 적다 — 축 끝물에서 자연히
     *   1:1로 줄어드는 경로이고, 오류가 아니다.
     */
    fun build(
        current: DuelPairing.Candidate,
        queue: List<DuelPairing.Candidate>,
        size: Int
    ): Round {
        val target = size.coerceIn(MIN_SIZE, MAX_SIZE)
        // **집합으로 모은다.** 같은 참가자가 두 칸에 오르면 카드 둘이 같은 얼굴이 되고,
        // 그것을 고르면 *자기 자신을 이긴 판*이 만들어진다(저장소가 거절하므로 그 누름은
        // 아무 일도 안 하고 화면이 멈춘 것처럼 보인다). 차례는 그대로 지켜야 하므로 LinkedHashSet이다.
        val members = LinkedHashSet<Long>(target)
        members += current.pair.lowId
        members += current.pair.highId

        // ① 이어진 것 먼저 — 이미 올라온 하나와 짝을 이루는 후보.
        for (candidate in queue) {
            if (members.size >= target) break
            val low = candidate.pair.lowId
            val high = candidate.pair.highId
            val hasLow = low in members
            val hasHigh = high in members
            if (hasLow == hasHigh) continue
            members += if (hasLow) high else low
        }

        // ② 그래도 모자라면 남은 후보에서 채운다.
        if (members.size < target) {
            for (candidate in queue) {
                if (members.size >= target) break
                for (id in listOf(candidate.pair.lowId, candidate.pair.highId)) {
                    if (members.size < target) members += id
                }
            }
        }

        return Round(members = members.toList(), lead = current, requestedSize = target)
    }

    /**
     * 떠 있는 판을 **그대로 써도 되는가** — [DuelSession]의 규칙 1을 화면 전체로 넓힌 것.
     *
     * 그 규칙은 *"떠 있는 짝은 답하기 전에 바뀌지 않는다"*이고 근거는 **고르려던 것이 손가락
     * 아래에서 갈리면 그 누름은 사용자가 뜻한 것이 아니다**였다. k지선다에서는 그 위험이
     * 넓어진다 — 거들 참가자를 대기열에서 뽑으므로, 배경에서 새 계획이 도착할 때마다
     * 다시 짜면 **머리 짝은 그대로인데 셋째·넷째 카드만 조용히 갈린다.** 머리 짝만 지키는
     * 것으로는 부족하고, 판 전체가 답할 때까지 그대로여야 한다.
     *
     * 다시 짜는 때는 셋뿐이다: **머리 짝이 바뀌었거나**(답했거나 되돌렸다) · **설정이
     * 바뀌었거나**(사용자가 스스로 한 일이라 놀랄 일이 아니다) · **올라온 참가자 중
     * 코드로 안 풀리는 것이 생겼거나**(그 카드는 이제 못 그린다).
     *
     * @param resolved 그 참가자를 아직 화면에 그릴 수 있는가(코드로 풀리는가).
     */
    fun canReuse(
        existing: Round?,
        lead: DuelPairing.Candidate,
        size: Int,
        resolved: (Long) -> Boolean
    ): Boolean {
        if (existing == null) return false
        if (existing.lead.pair != lead.pair) return false
        if (existing.requestedSize != size.coerceIn(MIN_SIZE, MAX_SIZE)) return false
        return existing.members.all(resolved)
    }

    /**
     * 고른 결과가 남기는 판들.
     *
     * @param winnerId 고른 참가자. **null이면 '비슷함'**이다. [Round.members]에 없는 값이
     *   들어오면 빈 목록을 낸다 — 조용히 아무 판이나 적는 것보다 아무것도 안 적는 편이
     *   낫고, 화면이 그 사실을 말할 수 있다(개발 의도 2번).
     */
    fun outcomes(round: Round, winnerId: Long?): List<Outcome> {
        val members = round.members
        if (members.size < 2) return emptyList()

        if (winnerId == null) {
            // 비슷함 — 기준이 없으므로 전 조합이다.
            val all = ArrayList<Outcome>(members.size * (members.size - 1) / 2)
            for (i in members.indices) {
                for (j in i + 1 until members.size) {
                    all += Outcome(members[i], members[j], null)
                }
            }
            return all
        }

        if (winnerId !in members) return emptyList()
        return members.filter { it != winnerId }.map { Outcome(winnerId, it, winnerId) }
    }

    /**
     * 이 답이 **대기열에서 걷어 낼** 짝들.
     *
     * [DuelSession]의 대기열은 짝 단위인데 한 화면이 여러 짝을 한꺼번에 답하므로, 답한 것을
     * 알려 주지 않으면 **방금 답한 짝이 곧바로 다시 뜬다.** 답한 판이 곧 답한 짝이라
     * [outcomes]에서 그대로 낸다 — 두 벌로 세면 갈린다.
     */
    fun handledPairs(round: Round, winnerId: Long?): Set<DuelRating.PairKey> =
        outcomes(round, winnerId).mapTo(HashSet()) { it.pair }
}
