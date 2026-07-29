package com.novelcharacter.app.util

/**
 * 이미지 탭 필터/검색의 순수 매칭 로직 (Android 무의존 — JVM 단위 테스트로 검증).
 *
 * 네 차원을 AND로 조합한다: 기본 필터(소유 유형/상태) × **링크 상태** × 태그(선택 태그 중
 * 하나라도 — OR, 캐릭터 태그 필터와 동일 의미론) × 검색어(파일명·소유자명·태그 부분일치, 소문자).
 * 항목 타입에 비의존적으로 [Facts] 추출 람다를 받는다.
 */
object ImageFilterHelper {

    enum class BaseFilter { ALL, CHARACTER, NOVEL, UNIVERSE, UNASSIGNED, ORPHAN, TRASH }
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

    data class Criteria(
        val base: BaseFilter = BaseFilter.ALL,
        val link: LinkFilter = LinkFilter.ANY,
        val tags: Set<String> = emptySet(),
        val query: String = ""
    ) {
        val isActive: Boolean
            get() = base != BaseFilter.ALL || link != LinkFilter.ANY ||
                tags.isNotEmpty() || query.isNotBlank()
    }

    /** 매칭에 필요한 항목 사실 — 호출측이 자기 모델에서 추출한다. */
    data class Facts(
        val fileName: String,
        val ownerNames: List<String>,
        val tags: List<String>,
        val ownerKinds: Set<OwnerKind>,
        val status: StatusKind,
        /** 링크 그룹 토큰. null=미링크, `char:` 접두=캐릭터 자동 링크, 그 밖=수동 묶음. */
        val linkGroupId: String? = null
    )

    fun <T> apply(items: List<T>, criteria: Criteria, facts: (T) -> Facts): List<T> {
        if (!criteria.isActive) return items
        val query = criteria.query.trim().lowercase()
        return items.filter { item ->
            val f = facts(item)
            matchesBase(f, criteria.base) && matchesLink(f, criteria.link) &&
                matchesTags(f, criteria.tags) && matchesQuery(f, query)
        }
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
        BaseFilter.UNASSIGNED -> f.status == StatusKind.UNASSIGNED
        BaseFilter.ORPHAN -> f.status == StatusKind.ORPHAN
        BaseFilter.TRASH -> f.status == StatusKind.TRASH
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
