package com.novelcharacter.app.util

/**
 * 이미지 탭 필터/검색의 순수 매칭 로직 (Android 무의존 — JVM 단위 테스트로 검증).
 *
 * 네 차원을 AND로 조합한다: 기본 필터(소유 유형/상태) × **링크 상태** × 태그(선택 태그 중
 * 하나라도 — OR, 캐릭터 태그 필터와 동일 의미론) × 검색어(파일명·소유자명·태그 부분일치, 소문자).
 * 항목 타입에 비의존적으로 [Facts] 추출 람다를 받는다.
 */
object ImageFilterHelper {

    /**
     * [UNASSIGNED]와 [DETACHED]는 **배타다** — 같은 이미지가 두 칩에 동시에 잡히지 않는다.
     *
     * 이것이 B-107 요청의 본체다(*"뗀 것을 따로 관리"* = 섞이지 않게 하라). 그래서 링크 축을
     * 독립으로 둔 [LinkFilter]의 선례를 따르지 않고 [UNASSIGNED] 쪽을 **좁혔다**: 두 칩이
     * 겹치면 갈랐다고 말만 한 것이 된다.
     */
    enum class BaseFilter { ALL, CHARACTER, NOVEL, UNIVERSE, UNASSIGNED, DETACHED, ORPHAN, TRASH }
    enum class OwnerKind { CHARACTER, NOVEL, UNIVERSE }
    enum class StatusKind { REFERENCED, ORPHAN, TRASH, UNASSIGNED }

    /**
     * 링크 상태 축 — [BaseFilter]에 값을 더하지 않고 **독립 축**으로 둔다.
     *
     * `BaseFilter`는 소유·상태의 배타 분류(캐릭터냐 고아냐 휴지통이냐)인데 링크 여부는 그와
     * **직교**한다. "캐릭터에 배정됐고 링크는 없는 것"은 두 축의 교집합이라, 한 enum에
     * 욱여넣으면 값이 곱으로 늘어난다.
     *
     * [AUTO]를 [LINKED]에서 가르는 근거: 자동 링크(`char:` 접두)는 사용자가 만든 묶음이
     * 아니다. 둘을 합치면 "내가 묶은 것"을 찾을 수 없다.
     */
    enum class LinkFilter { ANY, LINKED, UNLINKED, AUTO }

    /**
     * 태그 **유무** 축 — [Criteria.tags](어느 태그인가)와 갈라 둔다.
     *
     * 갈라야 하는 이유는 둘이 다른 물음이기 때문이다: `tags`는 *"이 태그가 붙은 것"*을 고르고
     * [UNTAGGED]는 *"아무 태그도 없는 것"*을 고른다. 후자는 태그 이름으로 표현할 수 없어
     * (없는 것에는 이름이 없다) 칩 목록에 담기지 않는다.
     *
     * **왜 이것이 있는가:** 일괄 AI 태깅(B-121)의 실제 동선이 *"아직 태그 없는 것들을 한꺼번에"*다.
     * 이 축이 없으면 수백 장에서 무태그를 눈으로 찾아 하나씩 골라야 한다(원칙 04).
     *
     * 둘을 함께 걸면 결과가 빈다 — 태그가 없는 이미지가 특정 태그를 가질 수는 없으므로
     * **AND의 정직한 결과**이며 특례를 두지 않는다. 화면은 둘을 배타로 조작하게 한다.
     */
    enum class TagFilter { ANY, UNTAGGED }

    /**
     * **걸러낼 후보** 축 (B-104 소비처 ⓒ — 설계 `duel_system_design_2026-08.md` 13-5).
     *
     * 다른 축들과 마찬가지로 **직교**라 독립 축이다 — *"캐릭터에 배정됐고 걸러낼 후보인 것"*은
     * 두 축의 교집합이고, [BaseFilter]에 값으로 넣으면 소유·상태 분류와 뒤섞인다.
     *
     * **판정이 이 파일에 없는 것이 [LinkFilter]와 다른 점이다.** 링크 여부는 항목이 스스로
     * 들고 있지만(그래서 [Facts.linkGroupId] 한 칸이면 된다) 걸러낼 후보는 **기준 축의 순위
     * 전체를 적합해야** 나온다([DuelImagePrune]). 그래서 [Facts.pruneCandidate]는 *계산된
     * 사실*을 받아 오는 칸이고, 그 계산은 호출부(ViewModel)가 켜져 있을 때만 한다 —
     * 꺼져 있으면 질의조차 열지 않는다.
     */
    enum class PruneFilter { ANY, CANDIDATE }

    data class Criteria(
        val base: BaseFilter = BaseFilter.ALL,
        val link: LinkFilter = LinkFilter.ANY,
        val tags: Set<String> = emptySet(),
        val query: String = "",
        val tagPresence: TagFilter = TagFilter.ANY,
        val prune: PruneFilter = PruneFilter.ANY
    ) {
        val isActive: Boolean
            get() = base != BaseFilter.ALL || link != LinkFilter.ANY ||
                tags.isNotEmpty() || query.isNotBlank() || tagPresence != TagFilter.ANY ||
                prune != PruneFilter.ANY
    }

    /** 매칭에 필요한 항목 사실 — 호출측이 자기 모델에서 추출한다. */
    data class Facts(
        val fileName: String,
        val ownerNames: List<String>,
        val tags: List<String>,
        val ownerKinds: Set<OwnerKind>,
        val status: StatusKind,
        /** 링크 그룹 토큰. null=미링크, `char:` 접두=캐릭터 자동 링크, 그 밖=수동 묶음. */
        val linkGroupId: String? = null,
        /**
         * 캐릭터에서 뗀 시각. null=뗀 적 없음
         * ([com.novelcharacter.app.data.model.ImageMeta.detachedAt] 그대로).
         *
         * **작품·세계관이 아직 쓰는 이미지도 값이 있을 수 있다** — 캐릭터 축의 이력이라
         * 현재 소유와 독립이다(설계 D2). 그래서 [BaseFilter.DETACHED]는 [status]를 보지 않는다.
         */
        val detachedAt: Long? = null,
        /**
         * 기준 이미지 축에서 **걸러낼 후보로 오른 그림인가** (B-104 ⓒ).
         *
         * 항목이 스스로 아는 사실이 아니라 **호출부가 계산해 넣는 값**이다(위 [PruneFilter] 참조).
         * 기본값 false는 *"계산하지 않았다"*와 *"후보가 아니다"*를 같게 다루는데, 둘의 처분이
         * 같아서 그렇다 — 칩이 꺼져 있으면 이 칸을 아무도 읽지 않는다.
         */
        val pruneCandidate: Boolean = false
    )

    fun <T> apply(items: List<T>, criteria: Criteria, facts: (T) -> Facts): List<T> {
        if (!criteria.isActive) return items
        val query = criteria.query.trim().lowercase()
        return items.filter { item ->
            val f = facts(item)
            matchesBase(f, criteria.base) && matchesLink(f, criteria.link) &&
                matchesTagPresence(f, criteria.tagPresence) &&
                matchesPrune(f, criteria.prune) &&
                matchesTags(f, criteria.tags) && matchesQuery(f, query)
        }
    }

    private fun matchesPrune(f: Facts, prune: PruneFilter): Boolean = when (prune) {
        PruneFilter.ANY -> true
        PruneFilter.CANDIDATE -> f.pruneCandidate
    }

    private fun matchesLink(f: Facts, link: LinkFilter): Boolean = when (link) {
        LinkFilter.ANY -> true
        LinkFilter.UNLINKED -> f.linkGroupId == null
        // 자동 묶음은 '링크됨'에도 든다 — 묶여 있는 것은 사실이고, 사용자는 보통
        // "묶인 것 전부"를 먼저 찾은 뒤 출처로 좁힌다.
        LinkFilter.LINKED -> f.linkGroupId != null
        LinkFilter.AUTO -> AutoLinkPlanner.isAutoToken(f.linkGroupId)
    }

    private fun matchesBase(f: Facts, base: BaseFilter): Boolean = when (base) {
        BaseFilter.ALL -> true
        BaseFilter.CHARACTER -> OwnerKind.CHARACTER in f.ownerKinds
        BaseFilter.NOVEL -> OwnerKind.NOVEL in f.ownerKinds
        BaseFilter.UNIVERSE -> OwnerKind.UNIVERSE in f.ownerKinds
        // 뗀 것은 여기서 빠진다 — 이 한 줄이 두 칩의 배타성을 만든다.
        BaseFilter.UNASSIGNED -> f.status == StatusKind.UNASSIGNED && f.detachedAt == null
        // 소유 상태를 보지 않는다: 캐릭터에서 뗐지만 작품이 아직 쓰는 이미지도 뗀 것이다(D2).
        BaseFilter.DETACHED -> f.detachedAt != null
        BaseFilter.ORPHAN -> f.status == StatusKind.ORPHAN
        BaseFilter.TRASH -> f.status == StatusKind.TRASH
    }

    private fun matchesTagPresence(f: Facts, presence: TagFilter): Boolean = when (presence) {
        TagFilter.ANY -> true
        // 공백만 든 태그는 태그가 아니다 — 편집 경로가 막지만 옛 데이터·엑셀 들이기가 남긴다.
        TagFilter.UNTAGGED -> f.tags.none { it.isNotBlank() }
    }

    private fun matchesTags(f: Facts, tags: Set<String>): Boolean =
        tags.isEmpty() || f.tags.any { it in tags }

    private fun matchesQuery(f: Facts, query: String): Boolean {
        if (query.isEmpty()) return true
        if (f.fileName.lowercase().contains(query)) return true
        if (f.ownerNames.any { it.lowercase().contains(query) }) return true
        return f.tags.any { it.lowercase().contains(query) }
    }
}
