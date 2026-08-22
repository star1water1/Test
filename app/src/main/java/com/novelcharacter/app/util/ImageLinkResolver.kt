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
     *
     * @param groupsInvolved 흡수·병합 대상 — **수동 토큰만** 담는다([autoGroupsTouched] 참조)
     * @param autoGroupsTouched 선택에 딸려 온 자동 토큰들. 흡수하지 않고 **고지에만** 쓴다.
     */
    data class LinkPlan(
        val groupsInvolved: Set<String>,
        val needsMergeConfirm: Boolean,
        val autoGroupsTouched: Set<String> = emptySet()
    )

    /**
     * **자동 토큰(`char:N`)은 흡수하지 않는다.**
     *
     * 자동 묶음을 흡수 대상으로 삼으면 그 토큰이 그대로 새 묶음의 이름이 되고, 그러면
     * **다음 재동기화가 사용자의 묶음을 조용히 해체한다** — 자동 계획은 `char:N` 소속을
     * *"그 이미지가 캐릭터 N에 등록된 동안만 유효"*로 보므로, 수동으로 딸려 온 이미지들의
     * 토큰을 전부 벗긴다([AutoLinkPlanner] 머리말: *"자동화는 수동 그룹을 절대 건드리지
     * 않는다"*). 사용자는 자기가 만든 묶음이 다음 저장 한 번에 사라지는 것을 본다.
     *
     * 폴더 왕복 쪽은 이 예외를 이미 갖고 있었고 그 이유까지 주석에 적어 두었다 — **인앱
     * 링크만 그 예외가 없었다.** 그래서 규칙을 여기 한 자리로 올려 둘이 같은 것을 쓴다.
     *
     * 자동 토큰이 붙은 이미지 자체는 선택에 들어 있으므로 새 수동 토큰으로 함께 옮겨진다.
     * 그 뒤로는 자동 계획이 *비자동 토큰 = 손대지 않음*으로 보호한다.
     */
    fun planLink(selected: Collection<String>, metas: List<Meta>): LinkPlan {
        val byPath = metas.associateBy { it.path }
        val groups = selected.mapNotNullTo(LinkedHashSet()) { byPath[it]?.groupId }
        val manual = groups.filterTo(LinkedHashSet()) { isAbsorbable(it) }
        val auto = groups.filterNotTo(LinkedHashSet()) { isAbsorbable(it) }
        return LinkPlan(manual, manual.size >= 2, auto)
    }

    /**
     * **흡수해도 되는 토큰인가** — 자동 토큰(`char:N`)은 아니다([planLink] 머리말이 이유다).
     *
     * 규칙을 여기 한 자리에 둔 것은 같은 판정이 폴더 왕복 쪽에도 있기 때문이다(그쪽이 먼저
     * 갖고 있었고 인앱 링크만 빠져 있었다). 두 벌로 두면 다시 한쪽만 고쳐진다.
     */
    fun isAbsorbable(groupId: String?): Boolean =
        groupId != null && !AutoLinkPlanner.isAutoToken(groupId)
}
