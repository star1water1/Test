package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.dao.TrashSnapshotSummary

/**
 * 휴지통 목록의 **종류·기간 필터와 이름 검색** — 순수 로직 (B-50).
 *
 * UI가 아니라 여기 있는 이유는 [TrashGrouping]과 같다: 이 판정이 *무엇이 보이는가*를 정하고,
 * 보이지 않는 항목은 사용자에게 **없는 것과 구별되지 않는다.** 조용히 빠뜨리는 것이 곧
 * 개발 의도 2번이 금지한 유실이므로, Fragment 안에 두면 실행 검증이 불가능한 자리다.
 *
 * ## 걸러도 묶음은 쪼개지 않는다 — 이 파일의 핵심 규칙
 *
 * 필터는 **항목**에 걸리지만 결과는 **묶음**으로 돌려준다. 한 항목이라도 걸리면 그 묶음이
 * 남고, 남은 묶음은 *걸린 항목 수*와 *묶음 전체 항목 수*를 **둘 다** 든다([Match]).
 *
 * **둘 다 드는 것이 요점이다.** 머리글의 '전체 복원'·'묶음 영구 삭제'는 언제나 **작업 전체**에
 * 작용한다(R-9 — 한 작업은 통째로 남거나 통째로 사라진다). 걸린 3건만 세어 "항목 3개"라고
 * 적으면 누르는 순간 214개가 복원되고, 그것은 이 저장소가 반복해서 막아 온 거짓 고지다.
 * 그래서 필터가 켜져 있을 때 화면은 `3/214`로 적고, 확인 창의 수는 언제나 전체를 든다.
 */
object TrashFilter {

    /** 기간 필터의 갈래 — 경계는 [since]가 정한다. */
    enum class Period {
        /** 기간 제한 없음 */
        ALL,
        TODAY_24H,
        LAST_7_DAYS,
        LAST_30_DAYS;

        /**
         * 이 갈래가 남기는 하한 시각. [ALL]은 null(제한 없음)이다.
         *
         * `now`를 인자로 받는 것은 일부러다 — 안에서 시계를 읽으면 이 판정을 실행 검증할 수 없고,
         * 검증할 수 없는 판정이 곧 조용히 빠뜨리는 자리가 된다.
         */
        fun since(now: Long): Long? = when (this) {
            ALL -> null
            TODAY_24H -> now - DAY_MS
            LAST_7_DAYS -> now - 7 * DAY_MS
            LAST_30_DAYS -> now - 30 * DAY_MS
        }
    }

    /**
     * @param types 남길 엔티티 종류. **빈 집합은 '전체'다** — 아무것도 안 고른 상태에서
     *   목록이 비어 버리면 사용자는 휴지통이 빈 것과 구별할 수 없다.
     * @param since 이 시각 **이후**에 지운 것만. null이면 기간 제한 없음.
     * @param query 이름 부분 일치(대소문자 무시). 종류는 [types]가 맡으므로 여기서 보지 않는다 —
     *   '캐릭터'를 쳐서 종류가 걸리게 하면 그 이름을 가진 세계관이 조용히 사라진다.
     */
    data class Criteria(
        val types: Set<String> = emptySet(),
        val since: Long? = null,
        val query: String = ""
    ) {
        /** 하나라도 걸려 있는가 — 화면이 '걸러진 상태'임을 말해야 하는지의 기준. */
        val isActive: Boolean
            get() = types.isNotEmpty() || since != null || query.isNotBlank()
    }

    /**
     * 걸러진 묶음 하나.
     *
     * @param matched 조건에 걸린 항목만. 접힌 머리글을 펼치면 이것이 보인다.
     * @param totalCount 묶음 전체 항목 수 — **일괄 동작이 실제로 건드리는 수**다.
     */
    data class Match(
        val group: TrashGrouping.Group,
        val matched: List<TrashSnapshotSummary>,
        val totalCount: Int
    ) {
        /** 필터 때문에 가려진 항목이 있는가 — 있으면 화면이 `걸린 수/전체 수`로 적는다. */
        val isPartial: Boolean get() = matched.size < totalCount
    }

    fun matches(item: TrashSnapshotSummary, criteria: Criteria): Boolean {
        if (criteria.types.isNotEmpty() && item.entityType !in criteria.types) return false
        val since = criteria.since
        if (since != null && item.deletedAt < since) return false
        val q = criteria.query.trim()
        if (q.isNotEmpty() && !item.entityName.contains(q, ignoreCase = true)) return false
        return true
    }

    /**
     * 묶음 목록에 조건을 건다. 걸린 항목이 하나도 없는 묶음은 통째로 빠지고,
     * 남은 묶음은 **원래 순서**(=복원 순서)를 그대로 지킨다.
     */
    fun apply(groups: List<TrashGrouping.Group>, criteria: Criteria): List<Match> {
        if (!criteria.isActive) {
            return groups.map { Match(it, it.items, it.items.size) }
        }
        val out = ArrayList<Match>(groups.size)
        for (group in groups) {
            val matched = group.items.filter { matches(it, criteria) }
            if (matched.isEmpty()) continue
            out.add(Match(group, matched, group.items.size))
        }
        return out
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
