package com.novelcharacter.app.util

/**
 * **걸러낼 이미지 후보의 판정** (B-104 소비처 ⓒ — 설계 `docs/duel_system_design_2026-08.md` 13-5).
 *
 * 백로그 원문이 이미지 축을 만든 이유가 이것이다 — *"걸러낼 이미지의 근거를 쌓는"*.
 * 그 근거가 쌓였을 때 **누구를 후보로 부를 것인가**를 여기서 정한다.
 *
 * ## 조용히 지우지 않는다 — 제안까지가 끝이다
 * 백로그 원문의 못이다. 이 파일은 **목록을 낼 뿐** 아무것도 지우지 않고, 화면도 기존
 * 일괄 선택·삭제 동선에 얹기만 한다(새 조작을 만들지 않는다 — 원칙 04).
 *
 * ## 기준 둘 (사용자 확정 2026.08.09 — *"하위권 + 최소 판 수, 두 값 다 설정 조절"*)
 * 1. **하위권** — 그 캐릭터의 점수 있는 그림 중 아래 [Options.bottomPercent]%.
 * 2. **최소 판 수** — 그보다 적게 겨룬 그림은 후보가 되지 않는다. 판이 적으면 점수가
 *    흔들리고(순위표가 `±오차`로 이미 말하는 그 사실), 흔들리는 수로 *"지우시죠"*라고
 *    말하면 앱이 지어낸 근거가 된다(원칙 02).
 *
 * ## 하위권은 **비율 그대로** 읽는다 — 특례를 두지 않는다
 * *"하위 20%"*는 다섯 장이 있어야 한 장이 거기 든다는 뜻이고, 넉 장짜리 캐릭터에서
 * 아무도 후보가 되지 않는 것은 **결함이 아니라 그 말의 뜻**이다. 작은 집합에 억지로 한 장을
 * 세우는 특례(올림·최소 1장)를 두면 그림 둘뿐인 캐릭터에서 **절반이 후보**가 되는데,
 * 그것은 사용자가 요구한 판정이 아니다(방편식 패치 금지).
 *
 * 동점은 [DuelScoreIndex.rankWithinScored]가 같은 등수를 주므로 **함께 들거나 함께 빠진다** —
 * 같은 점수인데 하나만 후보가 되는 일은 생기지 않는다.
 *
 * ## 여기 없는 것 — 점수를 내는 일
 * 점수·등수는 순위표가 보이는 그 수여야 한다([DuelScoreIndex]의 계약 1). 이 파일은
 * 그 표를 **읽기만** 하고, 만드는 것은 `DuelRepository.imageScoresByCharacter`다.
 */
object DuelImagePrune {

    /** 기본값 — 하위 20%. *"다섯 중 한 장"*이라 한 판씩만 돌려도 곧 말이 되는 크기다. */
    const val DEFAULT_BOTTOM_PERCENT = 20

    /**
     * 기본값 — 세 판. 그림 다섯 장을 한 바퀴 돌리면(짝 열 개) 그림마다 네 판이라
     * **한 바퀴로 닿는 값**이다. 더 크게 잡으면 기능이 오래도록 아무 말도 하지 않는다.
     */
    const val DEFAULT_MIN_PLAYED = 3

    val BOTTOM_PERCENT_CHOICES = listOf(10, 20, 30, 40, 50)
    val MIN_PLAYED_CHOICES = listOf(1, 2, 3, 5, 10)

    /**
     * @property bottomPercent 하위 몇 %를 후보로 볼 것인가. 1~50으로 접는다 —
     *   절반을 넘기면 *"걸러낼 후보"*가 아니라 *"절반 넘게 지우기"*라 뜻이 달라진다.
     * @property minPlayed 후보가 되기 위한 최소 판 수. 1 미만은 1로 접는다(0으로 두면
     *   한 판도 안 친 그림이 후보가 되는데, 그쪽은 애초에 점수 표에 없다).
     */
    data class Options(
        val bottomPercent: Int = DEFAULT_BOTTOM_PERCENT,
        val minPlayed: Int = DEFAULT_MIN_PLAYED
    ) {
        /** 값이 밖에서 편집돼 들어와도(설정 파일·엑셀) 판정이 이상해지지 않게 접는다. */
        val percent: Int get() = bottomPercent.coerceIn(1, 50)
        val played: Int get() = minPlayed.coerceAtLeast(1)
    }

    /**
     * 후보 한 장.
     *
     * **왜 이만큼 담는가:** 화면이 *"왜 이것이 후보인가"*를 말할 수 있어야 한다(원칙 02 —
     * 근거 없는 제안은 겉핥기다). 등수·전체·판 수가 있으면 *"12장 중 11위 · 7판"*이 그대로 나온다.
     */
    data class Candidate(
        val path: String,
        val score: Int,
        val rank: Int,
        val scored: Int,
        val played: Int
    )

    /**
     * 한 캐릭터의 그림 중 걸러낼 후보. 점수가 낮은 순(가장 먼저 지울 만한 것이 앞)이다.
     *
     * @param scores 그 **캐릭터 몫**의 점수표 — 이미지 축은 캐릭터마다 따로 놀므로(13-2)
     *   축 전체를 넘기면 캐릭터를 넘는 등수가 되어 아무 판도 근거하지 않은 순위가 된다.
     */
    fun candidates(
        scores: DuelScoreIndex.AxisScores,
        options: Options = Options()
    ): List<Candidate> {
        val scored = scores.scored
        if (scored <= 0) return emptyList()
        val ranks = DuelScoreIndex.rankWithinScored(scores)
        val percent = options.percent
        val minPlayed = options.played

        val out = ArrayList<Candidate>()
        for (entry in scores.byCode.values) {
            if (entry.played < minPlayed) continue
            val rank = ranks[entry.code] ?: continue
            // 아래에서 몇 번째인가(꼴찌가 1). 그것이 전체의 percent% 안에 들어야 후보다.
            // 정수로만 셈해 반올림이 경계를 흔들지 않게 한다.
            val fromBottom = scored - rank + 1
            if (fromBottom * 100 > scored * percent) continue
            out.add(
                Candidate(
                    path = entry.code,
                    score = entry.score,
                    rank = rank,
                    scored = scored,
                    played = entry.played
                )
            )
        }
        return out.sortedWith(compareBy({ it.score }, { it.path }))
    }
}
