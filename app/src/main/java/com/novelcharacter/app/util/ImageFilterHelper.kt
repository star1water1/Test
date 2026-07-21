package com.novelcharacter.app.util

/**
 * 이미지 탭 필터/검색의 순수 매칭 로직 (Android 무의존 — JVM 단위 테스트로 검증).
 *
 * 세 차원을 AND로 조합한다: 기본 필터(소유 유형/상태) × 태그(선택 태그 중 하나라도 — OR,
 * 캐릭터 태그 필터와 동일 의미론) × 검색어(파일명·소유자명·태그 부분일치, 소문자).
 * 항목 타입에 비의존적으로 [Facts] 추출 람다를 받는다.
 */
object ImageFilterHelper {

    enum class BaseFilter { ALL, CHARACTER, NOVEL, UNIVERSE, UNASSIGNED, ORPHAN, TRASH }
    enum class OwnerKind { CHARACTER, NOVEL, UNIVERSE }
    enum class StatusKind { REFERENCED, ORPHAN, TRASH, UNASSIGNED }

    data class Criteria(
        val base: BaseFilter = BaseFilter.ALL,
        val tags: Set<String> = emptySet(),
        val query: String = ""
    ) {
        val isActive: Boolean get() = base != BaseFilter.ALL || tags.isNotEmpty() || query.isNotBlank()
    }

    /** 매칭에 필요한 항목 사실 — 호출측이 자기 모델에서 추출한다. */
    data class Facts(
        val fileName: String,
        val ownerNames: List<String>,
        val tags: List<String>,
        val ownerKinds: Set<OwnerKind>,
        val status: StatusKind
    )

    fun <T> apply(items: List<T>, criteria: Criteria, facts: (T) -> Facts): List<T> {
        if (!criteria.isActive) return items
        val query = criteria.query.trim().lowercase()
        return items.filter { item ->
            val f = facts(item)
            matchesBase(f, criteria.base) && matchesTags(f, criteria.tags) && matchesQuery(f, query)
        }
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
