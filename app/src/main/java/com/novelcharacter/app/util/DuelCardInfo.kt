package com.novelcharacter.app.util

/**
 * **대결 카드 한 장에 무엇이 뜨는가** (B-122).
 *
 * 카드가 *'그림 위 스크림 한 줄'*에서 **'그림 + 정보 패널'**로 갈리면서 화면이 정해야 할 것이
 * 늘었다 — 어느 줄이 어느 구역에 가는가, 무엇이 접히는가, 무엇이 빠지는가. 그것을 화면에
 * 두지 않는 것은 **이 저장소의 자동 검증이 화면을 못 보기 때문이다**(「세션 착수 규칙」 4번).
 * 여기서 정하면 실행으로 잰다.
 *
 * ## 구역이 셋인 것은 재료의 성질이 다르기 때문이다
 *
 * | 구역 | 무엇 | 없으면 |
 * |---|---|---|
 * | **트리오**([Card.gender]·[Card.age]) | *누구인지*의 최소치 — 시맨틱 역할이 집는다 | 그 조각만 빠진다 |
 * | **프로필**([Card.profiles]) | 알아보는 재료 — 소속·출신 | 줄이 빠진다(값 없는 줄은 자리를 안 쓴다) |
 * | **영향**([Card.influences]) | 판단 재료 — 순위 번호가 붙는다 | **줄이 남는다**(아래) |
 *
 * **영향 필드만 빈 값에도 줄을 남긴다.** 대결 화면에서 사용자가 하는 일이 *같은 줄끼리
 * 견주는 것*이라 두 카드의 자리가 맞아야 하고, 한쪽에서 빈 줄을 빼면 그 아래가 통째로
 * 어긋나 엉뚱한 줄끼리 마주 본다. 프로필은 견주는 재료가 아니므로(설계 3-1 표 — *"예측에
 * 쓰나: 안 쓴다"*) 그 제약이 없고, 좁은 카드에서 빈 줄이 상한 넷을 잡아먹지 않는 편이 낫다.
 *
 * ## 프로필에서 빠지는 셋
 * 1. **산출 필드**([DuelFieldLinks.Axis.profileBlocked]) — 물음과 답을 한 화면에 두지 않는다.
 * 2. **영향 필드** — 이미 아래 구역에 순위 번호까지 달고 뜬다. 같은 값을 두 번 보이면
 *    좁은 카드에서 상한 넷이 헛돈다.
 * 3. **트리오 역할 필드** — 둘째 줄에 이미 있다.
 *
 * **셋 다 값을 지우는 것이 아니라 자리를 하나로 두는 것**이다. 1번만은 사유를 말해야 하므로
 * (사용자가 고른 것이 안 보이는 유일한 경우다) 축 편집 창이 [DuelFieldLinks.Axis.profileBlocked]로
 * 알린다 — 2·3번은 *다른 구역에 보이고 있어* 사라진 것이 없다.
 */
object DuelCardInfo {

    /**
     * 카드에 뜨는 프로필 줄의 상한 (R-14 — 표시 계층 상한).
     *
     * 넷인 것은 목업이 정한 카드 높이에서 이름·트리오·영향 구역을 뺀 자리가 그만큼이기
     * 때문이다. 넘긴 만큼은 [Card.profileOverflow]로 세어 *"외 N개"*가 되고, 펼침이
     * [Card.allProfiles] 전량을 연다 — **러프하게 보고 필요하면 정밀히**의 이중 경로(원칙 04).
     */
    const val PROFILE_LIMIT = 4

    /**
     * 한 줄.
     *
     * @property key 필드 키. 화면이 다시 값을 집을 일은 없지만 시험이 *어느 필드가 어느
     *   구역에 갔는가*를 이름이 아니라 키로 재게 한다(이름은 세계관마다 다르다).
     * @property value 빈 문자열이면 **값이 없다** — 그 표시(`—`)는 화면의 몫이다. 여기서
     *   글자를 지어내면 순수 계층이 말글에 묶인다.
     */
    data class Line(val key: String, val label: String, val value: String)

    /** 영향 줄 — [rank]가 영향력 순위다(1부터). */
    data class RankedLine(val rank: Int, val key: String, val label: String, val value: String)

    /**
     * 카드 한 장.
     *
     * @property gender 트리오의 성별. **null이면 그 조각이 없다** — 역할을 지정한 필드가
     *   이 세계관에 없거나 이 캐릭터가 값을 안 적었다. 둘을 여기서 가르지 않는 것은 카드가
     *   할 일이 같기 때문이고(조각을 뺀다), *왜 없는가*는 프로필 고르기 창이 말한다(R-17).
     * @property age 트리오의 나이. 단위(`세`)는 붙이지 않는다 — 말글은 화면의 몫이다.
     * @property profiles 카드에 뜨는 프로필 — 값이 있는 것만, [PROFILE_LIMIT]까지.
     * @property profileOverflow 카드에서 접힌 프로필 수. 0이면 *"외 N개"*가 뜨지 않는다.
     * @property allProfiles 펼침 시트가 여는 전량 — **값이 빈 것도 담는다.** 좁은 카드와
     *   달리 여기서는 *"그 필드가 걸려 있는데 비었다"*가 알 값어치가 있는 사실이다.
     * @property influences 영향 줄 — 순위 순, 빈 값도 남는다. **접힌 카드([build]의 `compact`)
     *   에서는 비어 있고** 전량은 [allInfluences]에 있다.
     * @property allInfluences 펼침 시트가 여는 영향 줄 전량. 보기 설정으로 **끈** 경우에는
     *   여기도 비어 있다 — 자리가 없어 접은 것과 사용자가 안 보겠다고 한 것은 다르다.
     * @property compact 이 카드가 접혀 있는가(k지선다). 그리는 쪽이 *"이름+트리오만 남긴다"*를
     *   자기 판단으로 다시 세지 않게 [build]가 받은 그대로 담아 둔다.
     */
    data class Card(
        val name: String,
        val gender: String? = null,
        val age: String? = null,
        val profiles: List<Line> = emptyList(),
        val profileOverflow: Int = 0,
        val allProfiles: List<Line> = emptyList(),
        val influences: List<RankedLine> = emptyList(),
        val allInfluences: List<RankedLine> = emptyList(),
        val compact: Boolean = false
    ) {
        /** 트리오 줄을 그릴 것이 있는가 — 둘 다 없으면 그 줄 자체가 뜨지 않는다. */
        val hasTrio: Boolean get() = gender != null || age != null

        /**
         * *"외 N개"* 줄을 띄우는가.
         *
         * **접힌 카드에서는 안 띄운다** — 위에 아무 줄도 없이 *"외 3개"*만 남으면 무엇의
         * 나머지인지 알 수 없다. 그 카드에서 더 있다는 신호는 펼침(⌄)이 든다.
         * 접히지 않은 카드에서는 종전 그대로 **값이 빈 프로필만 있는 경우에도 띄운다** —
         * *"필드는 걸려 있는데 이 캐릭터는 다 비었다"*가 알 값어치가 있는 사실이라서다.
         */
        val showProfileMore: Boolean get() = profileOverflow > 0 && !compact

        /**
         * 펼침(⌄)을 띄울 것인가 — **접힌 것이 있을 때만**이다(눌러도 같은 것만 나오면 마찰이다).
         *
         * 두 구역을 함께 본다. k지선다의 접힌 카드는 프로필이 하나도 없어도 **영향 줄이
         * 통째로 접혀 있어** 펼칠 것이 있다 — 프로필만 세면 그 카드의 판단 재료가 화면
         * 어디에서도 닿을 수 없게 된다.
         */
        val canExpand: Boolean
            get() = allProfiles.size > profiles.size || allInfluences.size > influences.size
    }

    /**
     * 카드 한 장을 짠다.
     *
     * @param values 이 참가자의 필드값(`필드 키 → 값`). 없는 키는 값이 없는 것이다.
     * @param labels `필드 키 → 사람이 읽는 이름`. **없는 키는 키를 그대로 쓴다** — 지워진
     *   필드나 엑셀로 들어온 낯선 키를 조용히 감추지 않는다(개발 의도 2번). 영향 필드가
     *   이미 그렇게 하고 있고 같은 규칙을 프로필에도 쓴다.
     * @param genderKey [com.novelcharacter.app.data.model.SemanticRole.GENDER]를 지정한 필드의
     *   키. 그 역할의 필드가 없으면 null이다.
     * @param ageKey 같은 방식의 나이 역할 필드 키.
     * @param showProfiles 보기 설정 — 끄면 프로필 구역이 통째로 빈다(펼침도 함께 사라진다).
     * @param showInfluences 보기 설정 — 끄면 영향 구역이 통째로 빈다.
     * @param compact **카드에 뜨는 것을 이름+트리오로 접는다** (k지선다 — [DuelCardGrid.Plan.compact]).
     *   셋 이상이면 카드가 화면의 4분의 1이라 프로필·영향 줄을 넣을 높이가 없다.
     *   **거두는 것이 아니라 옮기는 것**이라 [Card.allProfiles]·[Card.allInfluences]는 그대로
     *   차고, 펼침(⌄)이 전량을 연다 — 러프하게 보고 필요하면 정밀히(원칙 04의 이중 경로).
     *   보기 설정으로 끈 구역은 여기서도 비어 있다(끈 것을 접었다고 되살리지 않는다).
     */
    fun build(
        name: String,
        values: Map<String, String>,
        labels: Map<String, String>,
        links: DuelFieldLinks.Axis,
        genderKey: String? = null,
        ageKey: String? = null,
        showProfiles: Boolean = true,
        showInfluences: Boolean = true,
        profileLimit: Int = PROFILE_LIMIT,
        compact: Boolean = false
    ): Card {
        fun valueOf(key: String?): String? =
            key?.let { values[it] }?.trim()?.takeIf { it.isNotEmpty() }

        fun labelOf(key: String): String = labels[key] ?: key

        val influences = if (!showInfluences) {
            emptyList()
        } else {
            links.influences.mapIndexed { index, link ->
                RankedLine(
                    rank = index + 1,
                    key = link.key,
                    label = labelOf(link.key),
                    // 빈 값도 줄을 남긴다 — 두 카드의 자리를 맞추는 것이 이 화면의 일이다.
                    value = values[link.key]?.trim().orEmpty()
                )
            }
        }

        val allProfiles = if (!showProfiles) {
            emptyList()
        } else {
            val hidden = HashSet<String>()
            // **일하는 산출만 감춘다**([DuelFieldLinks.Axis.effectiveOutcomes] — B-167).
            // 산출 자리에 시스템 열이 적혀 있어도 그것은 답을 만들지 않으므로,
            // *"물음과 답을 한 화면에 두지 않는다"*는 근거가 성립하지 않는다 —
            // 근거 없이 사용자가 고른 프로필을 빼면 그것이 곧 조용한 유실이다.
            hidden += links.effectiveOutcomes.map { it.key }
            hidden += links.influences.map { it.key }
            genderKey?.let { hidden += it }
            ageKey?.let { hidden += it }
            links.profiles
                .filter { it.key !in hidden }
                .map { Line(it.key, labelOf(it.key), values[it.key]?.trim().orEmpty()) }
        }

        // 카드에는 **값이 있는 것만** 올린다. 접힌 수는 *전량 − 올린 것*이라, 값이 빈 줄도
        // "외 N개"에 세어진다 — 펼치면 그것이 비어 있음을 그대로 보여 주므로 거짓말이 아니다.
        val shown = if (compact) {
            emptyList()
        } else {
            allProfiles.filter { it.value.isNotEmpty() }.take(profileLimit.coerceAtLeast(0))
        }

        return Card(
            name = name,
            gender = valueOf(genderKey),
            age = valueOf(ageKey),
            profiles = shown,
            profileOverflow = allProfiles.size - shown.size,
            allProfiles = allProfiles,
            influences = if (compact) emptyList() else influences,
            allInfluences = influences,
            compact = compact
        )
    }

    /**
     * 카드가 읽어야 할 필드 키 전부 — 값을 **한 번에** 읽는 질의가 이것을 받는다.
     *
     * 짝이 뜰 때마다 둘씩 읽으면 수백 판을 누르는 동안 질의가 그만큼 늘어난다
     * (`DuelViewModel.fieldValuesOf`의 계약). 그래서 어느 키가 필요한지를 **화면이 아니라
     * 여기서** 센다 — [build]가 보는 것과 이 목록이 갈리면 카드에 값이 빈 줄이 뜨고,
     * 그것은 *"값을 안 적었다"*와 구별되지 않는다.
     */
    fun keysToLoad(
        links: DuelFieldLinks.Axis,
        genderKey: String? = null,
        ageKey: String? = null
    ): Set<String> {
        val keys = LinkedHashSet<String>()
        links.influences.forEach { keys += it.key }
        links.profiles.forEach { keys += it.key }
        genderKey?.let { keys += it }
        ageKey?.let { keys += it }
        // 산출 필드는 **일부러 빠진다** — 읽어 두면 언젠가 무심코 띄운다(층 C의 그 근거).
        return keys
    }
}
