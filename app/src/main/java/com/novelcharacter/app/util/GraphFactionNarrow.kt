package com.novelcharacter.app.util

/**
 * 관계도의 **세력 좁힘 축** — 순수 판정 (2026.08.21 사용자 판정).
 *
 * ## 왜 화면에서 떼어 냈나
 *
 * `RelationshipGraphFragment`는 **로컬 검사가 하나도 닿지 않는 자리**다: 순수 시험도
 * 실클래스패스 프로브도 `ui/` 아래를 통째로 빼고, 커스텀 뷰 프로브가 보는 것은
 * `RelationshipGraphView`뿐이다. 그런데 이 판의 좁힘 축은 **순서가 곧 정확성**이라
 * (좁힘 → 연결 수 집계 → 노드 상한) 다음 판이 조용히 되돌리기 쉽다.
 * 그래서 *"누가 남는가"*만 여기로 내려 시험이 실행으로 잠그게 한다
 * (B-213이 `GraphForceLayout`을 `util/`로 내린 것과 같은 이유).
 *
 * ## 이 축이 하는 일과 하지 않는 일
 *
 * 하는 일은 **인물을 줄이는 것** 하나다. 강조(2차 흐림)는 화면이 그대로 든다 —
 * 두 축은 함께 켜지지 않으므로([narrowedIds]가 `null`이면 강조, 아니면 좁힘)
 * 한 칩이 두 가지 일을 하는 것처럼 보이지 않는다.
 */
object GraphFactionNarrow {

    /**
     * 좁힘이 살아 있을 때 **남는 인물의 id**, 아니면 `null`(거를 것이 없다 = 강조 축).
     *
     * @param narrows 사용자가 '좁히기'를 켰는가.
     * @param selected 고른 세력. **빈 셋은 '전체'다** — 이 축의 근본 계약이라 좁히지 않는다
     *   (빈 셋에서 좁히면 화면이 통째로 빈다).
     * @param membership 캐릭터 id → 소속 세력 id들. 시간뷰에서는 그 시점 기준으로 재구성된
     *   맵이 들어온다 — 그래서 좁힘도 강조와 **같은 시점**을 본다.
     * @param allCharacterIds 판정 대상 전량.
     */
    fun narrowedIds(
        narrows: Boolean,
        selected: Set<Long>,
        membership: Map<Long, List<Long>>,
        allCharacterIds: List<Long>,
    ): Set<Long>? {
        if (!narrows || selected.isEmpty()) return null
        return allCharacterIds
            .filter { UnassignedFilter.matchesFaction(membership[it], selected) }
            .toHashSet()
    }

    /**
     * 좁힘을 관계 목록에 적용한다 — **양 끝이 모두 남아야** 그린다.
     *
     * 한쪽만 남은 관계를 그리면 없는 노드로 선이 뻗는다(노드 상한이 이미 쓰는 규칙과 같다).
     *
     * **부르는 자리를 하나로 모아 둔 것이 요점이다**: 전체 갱신(`updateGraph`) 하나가
     * 시점 슬라이더까지 든다. 2026.08.22 이전에는 슬라이더가 엣지만 갈아 끼우는 빠른 경로를
     * 따로 들었는데, 그 경로가 이것을 안 지나면 좁혀서 없앤 인물로 뻗는 엣지가 드래그마다
     * 되살아났다 — 상한이 안 걸린 회차에서는 `shownNodeIds`가 `null`이라 아무도 그것을
     * 거르지 않기 때문이다. **경로를 나누면 그 갈림이 이런 모양으로 돌아온다.**
     */
    fun <T> apply(items: List<T>, keep: Set<Long>?, ends: (T) -> Pair<Long, Long>): List<T> {
        if (keep == null) return items
        return items.filter { val (a, b) = ends(it); a in keep && b in keep }
    }

    /**
     * 지금 화면을 비운 것이 **무엇 때문인가** — 빈 상태 문구가 원인을 잘못 지목하지 않게 한다.
     *
     * 종전 빈 문구는 언제나 *"선택한 유형의 관계가 없습니다"*였다. 세력으로 좁혀서 비었을
     * 때 그렇게 말하면 사용자는 유형 칩을 만지러 가고, 거기서는 아무 일도 일어나지 않는다.
     */
    enum class EmptyCause {
        /** 아직 관계를 하나도 만들지 않았다. */
        NO_DATA,

        /** 세력 좁힘이 다 걷어냈다. */
        FACTION,

        /** 관계 유형 필터가 다 걷어냈다. */
        REL_TYPE,

        /** 세계관·작품 필터를 포함해 그 밖의 이유. */
        OTHER,
    }

    /**
     * @param anyRelationships 앱에 관계가 하나라도 있는가.
     * @param afterTypeFilter 유형 필터까지 지난 뒤 남은 관계 수.
     * @param narrowing 좁힘이 살아 있는가([narrowedIds]가 `null`이 아닌가).
     * @param typeFiltering 유형 필터가 살아 있는가.
     */
    fun emptyCause(
        anyRelationships: Boolean,
        afterTypeFilter: Int,
        narrowing: Boolean,
        typeFiltering: Boolean,
    ): EmptyCause = when {
        !anyRelationships -> EmptyCause.NO_DATA
        // 유형까지는 남아 있었는데 지금 비었다면 걷어낸 것은 좁힘이다.
        narrowing && afterTypeFilter > 0 -> EmptyCause.FACTION
        typeFiltering -> EmptyCause.REL_TYPE
        else -> EmptyCause.OTHER
    }
}
