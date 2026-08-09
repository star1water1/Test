package com.novelcharacter.app.util

/**
 * **카드를 화면 어느 자리에 놓는가** — k지선다(B-115)의 그릇.
 *
 * 슬롯은 넷이고 자리가 고정이다:
 *
 * ```
 *   0 │ 1      ← 윗줄
 *  ───┼───
 *   2 │ 3      ← 아랫줄
 * ```
 *
 * **카드를 옮겨 붙이지 않고 슬롯을 골라 쓴다.** 배치가 바뀔 때마다 뷰를 떼어 다른 부모에
 * 붙이면 그 자리에 붙어 있던 그림·리스너가 함께 흔들리는데, 대결은 **연타하는 화면**이라
 * 그 사이가 실제로 벌어진다. 그래서 카드 넷은 처음부터 제자리에 있고 이 계획이 *어느 것을
 * 쓰고 어느 것을 숨기는가*만 정한다.
 *
 * ## 빈 자리를 '숨김'과 '자리만 비움'으로 가른다
 * 셋일 때 넷째 슬롯은 [spacers]다 — **사라지지 않고 자리를 지킨다.** 없애면 셋째 카드가
 * 아랫줄을 통째로 차지해 **혼자 두 배로 커지고**, 큰 카드는 그 자체로 눈을 끈다. 이 화면은
 * 영향 필드의 우열 강조조차 금지하는 자리다(*"화면이 예측을 대신 말한다"* — 로드맵 3-3).
 * 셋 중 하나만 크게 그리는 것은 그보다 노골적인 편들기다.
 *
 * ## 1:1의 두 배치는 종전 그대로다
 * 나란히는 윗줄 둘(`0·1`), 위아래는 두 줄에 하나씩(`0·2`)이다. 위아래에서 슬롯 1이 아니라
 * 2를 쓰는 것이 요점 — 그래야 **아랫줄이 실제로 아래**이고, 카드 안이 가로로 도는 종전
 * 규칙(B-122)이 그대로 성립한다.
 *
 * @see DuelRound 그 자리에 누구를 놓는가
 */
object DuelCardGrid {

    /** 슬롯 번호 — 위 그림 그대로. */
    const val SLOT_TOP_START = 0
    const val SLOT_TOP_END = 1
    const val SLOT_BOTTOM_START = 2
    const val SLOT_BOTTOM_END = 3

    private val ALL_SLOTS = listOf(SLOT_TOP_START, SLOT_TOP_END, SLOT_BOTTOM_START, SLOT_BOTTOM_END)

    /**
     * @property slots `참가자 차례 → 슬롯 번호`. 크기가 곧 화면에 뜨는 카드 수다.
     * @property spacers 자리는 지키되 비는 슬롯(위 설명).
     * @property hidden 통째로 사라지는 슬롯.
     * @property bottomRow 아랫줄을 쓰는가 — 안 쓰면 줄 자체가 사라져 윗줄이 높이를 다 갖는다.
     * @property inlineVersus 윗줄 **안**의 `vs` (나란히 1:1).
     * @property stackVersus 두 줄 **사이**의 `vs` (위아래 1:1).
     * @property compact 정보 패널을 이름+트리오로 접는가 — 셋 이상이면 카드가 4분의 1이라
     *   프로필·영향 줄을 다 넣을 높이가 없다(로드맵 3-3: *"3장 이상에서는 정보 패널이
     *   이름+트리오 줄로 접히고 펼침 시트가 나머지를 진다"*).
     * @property horizontalCard 카드 **안**이 가로인가(그림 왼쪽 · 패널 오른쪽). 위아래 1:1
     *   에서만 그렇다 — 그 배치에서만 카드가 낮고 넓다(B-122).
     */
    data class Plan(
        val slots: List<Int>,
        val spacers: List<Int>,
        val hidden: List<Int>,
        val bottomRow: Boolean,
        val inlineVersus: Boolean,
        val stackVersus: Boolean,
        val compact: Boolean,
        val horizontalCard: Boolean
    ) {
        val cardCount: Int get() = slots.size
    }

    /**
     * @param count 화면에 올릴 참가자 수([DuelRound.Round.size]).
     * @param stacked 1:1의 '위아래' 배치인가. **셋 이상에서는 무시된다** — 격자가 이미
     *   두 줄이라 위아래/나란히라는 구분이 뜻을 잃는다.
     */
    fun plan(count: Int, stacked: Boolean): Plan {
        // 하나 이하는 정상 경로가 아니다(짝이 없으면 화면이 빈 상태를 띄운다). 그래도
        // 계획을 내는 것은 **그리는 쪽이 null을 다루지 않게** 하려는 것이다.
        if (count <= 1) {
            return Plan(
                slots = if (count == 1) listOf(SLOT_TOP_START) else emptyList(),
                spacers = emptyList(),
                hidden = ALL_SLOTS.drop(count.coerceAtLeast(0)),
                bottomRow = false,
                inlineVersus = false,
                stackVersus = false,
                compact = false,
                horizontalCard = false
            )
        }

        if (count == 2) {
            val slots = if (stacked) {
                listOf(SLOT_TOP_START, SLOT_BOTTOM_START)
            } else {
                listOf(SLOT_TOP_START, SLOT_TOP_END)
            }
            return Plan(
                slots = slots,
                spacers = emptyList(),
                hidden = ALL_SLOTS.filterNot { it in slots },
                bottomRow = stacked,
                inlineVersus = !stacked,
                stackVersus = stacked,
                compact = false,
                horizontalCard = stacked
            )
        }

        val used = ALL_SLOTS.take(count.coerceAtMost(ALL_SLOTS.size))
        return Plan(
            slots = used,
            spacers = ALL_SLOTS.filterNot { it in used },
            hidden = emptyList(),
            bottomRow = true,
            inlineVersus = false,
            stackVersus = false,
            compact = true,
            horizontalCard = false
        )
    }
}
