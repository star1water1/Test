package com.novelcharacter.app.util

/**
 * 링크 그룹("같은 캐릭터의 이미지" 묶음) 해석의 순수 로직 (Android 무의존 — JVM 테스트로 검증).
 *
 * 핵심 계약: **배정은 그룹 단위로 확장된다** — 선택에 링크된 이미지가 있으면 그 그룹 전원이
 * 함께 배정 대상이 된다(사용자 명시 요구). 선택 밖에서 추가된 경로는 [Expansion.addedByLink]로
 * 구분해 사전 확인 다이얼로그가 노출할 수 있게 한다(변수 제어 — 조용한 확대 금지).
 */
object ImageLinkResolver {

    /** 해석에 필요한 최소 메타: 경로 + 그룹 토큰(null=미링크). */
    data class Meta(val path: String, val groupId: String?)

    /**
     * @param allPaths 선택 + 링크 확장 전체(순서 보존: 선택 먼저, 확장분 뒤)
     * @param addedByLink 선택엔 없었지만 링크로 딸려 온 경로들
     * @param groupIds 관여한 그룹 토큰들
     */
    data class Expansion(
        val allPaths: LinkedHashSet<String>,
        val addedByLink: Set<String>,
        val groupIds: Set<String>
    )

    /** 선택을 링크 그룹 전체로 확장한다. 링크 없는 선택은 그대로 통과. */
    fun expand(selected: Collection<String>, metas: List<Meta>): Expansion {
        val byPath = metas.associateBy { it.path }
        val byGroup = HashMap<String, MutableList<String>>()
        for (m in metas) {
            val g = m.groupId ?: continue
            byGroup.getOrPut(g) { mutableListOf() }.add(m.path)
        }
        val all = LinkedHashSet(selected)
        val groups = selected.mapNotNullTo(LinkedHashSet()) { byPath[it]?.groupId }
        for (g in groups) byGroup[g]?.forEach { all.add(it) }
        val added = all.filterNotTo(LinkedHashSet()) { it in selected }
        return Expansion(all, added, groups)
    }

    /**
     * 링크 생성 계획: 선택이 이미 몇 개 그룹에 걸치는지 판정한다.
     * 2개 이상 그룹에 걸치면 병합이므로 사용자 확인이 필요하다.
     */
    data class LinkPlan(val groupsInvolved: Set<String>, val needsMergeConfirm: Boolean)

    fun planLink(selected: Collection<String>, metas: List<Meta>): LinkPlan {
        val byPath = metas.associateBy { it.path }
        val groups = selected.mapNotNullTo(LinkedHashSet()) { byPath[it]?.groupId }
        return LinkPlan(groups, groups.size >= 2)
    }
}
