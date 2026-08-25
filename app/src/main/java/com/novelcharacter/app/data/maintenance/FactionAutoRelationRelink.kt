package com.novelcharacter.app.data.maintenance

import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.util.FactionStanding

/**
 * **세력 연결을 잃은 자동 관계를 도로 잇는 계획** (2026.08.25).
 *
 * ## 무엇이 끊겨 있었나
 *
 * 실측(2026.08.25 사용자가 내보낸 파일): '캐릭터 관계' 135행 **전부** 세력 칸이 비어 있는데,
 * 그중 **87행이 명백한 세력 자동 관계**였다 —
 *
 * - 소속의 **완전 클리크와 정확히 일치**(엑실리온 12명→66 · 카노스 6명→15 · 월아 4명→6, 어긋남 0)
 * - 유형이 그 세력의 [Faction.autoRelationType]과 같다(`가문원` 81 · `월아` 6)
 * - 생성일이 소속 행과 **같은 밀리초 구간**(카노스: 소속 `…050531~537` ↔ 관계 `…050533~537`)
 *
 * 즉 [com.novelcharacter.app.data.repository.FactionRepository]의 `insertAutoRelations`가
 * 만든 행인데 `factionId`만 없다. 들이는 자리는 이미 고쳐져 있다 —
 * `ExcelImportService`가 *"세력을 관계보다 먼저 가져온다 — 뒤에 두면 factionId가 전부 유실돼
 * 수동 관계로 강등"*이라 적어 둔 그 순서다. **이미 강등된 행은 그 수리로 낫지 않는다.**
 *
 * ## 왜 스스로 낫지 않는가 — 왕복마다 굳는다
 *
 * 내보내기는 `factionId`가 없으므로 세력 칸을 비워 쓰고, 가져오기는 **빈 칸을 '연결 해제'로
 * 읽는다**(`RefIntent.CLEAR` — R-36). 소속에서 자동 관계를 채우는 `drainPendingAutoRelations`도
 * *"관계 시트가 권위"*라 시트에 이미 있는 쌍은 건너뛴다. **그래서 몇 번을 왕복해도 그대로다.**
 *
 * ## 증상 — 조용하지 않고, 하나는 거짓 안심이다
 *
 * 세력 갈래는 전부 `factionId`를 키로 돈다:
 * - `removeMember` → 순수 제거해도 그 캐릭터의 자동 관계가 남는다
 * - `departMember` → 설정상 탈퇴가 '전 <유형>'으로 바뀌지 않는다
 * - `countByFaction` → **세력을 지우기 전에 "자동 관계 0건"이라 고지하고**, 실제로는 66행이
 *   유형만 남은 고아가 된다. R-4가 막으려는 바로 그 모양이다
 *
 * ## 규칙 — **수동 관계는 절대 걸리지 않아야 한다**
 *
 * `FactionRepository`의 머리 주석이 못박아 둔 금지가 이 계획의 상한이다:
 * *"수동 관계에 factionId를 부착하는 방식은 탈퇴 시 사용자가 만든 관계가 삭제되는 조용한
 * 유실 경로가 되므로 금지."* 그래서 **`insertAutoRelations`가 실제로 만드는 모양 전부**를
 * 조건으로 건다 — 하나라도 다르면 손대지 않는다.
 *
 * | 축 | 왜 |
 * |---|---|
 * | `factionId`가 비어 있다 | 이어져 있는 것을 옮기지 않는다 |
 * | 유형 = 그 세력의 [Faction.autoRelationType] | 자동 관계의 이름표 |
 * | 두 캐릭터가 **지금** 그 세력 소속 | 판정은 [FactionStanding]이 든다 |
 * | 강도 = [Faction.autoRelationIntensity] · 양방향 · 설명 빈 칸 · 표시순서 0 | 생성자가 넣는 기본 모양 그대로 |
 * | 생성일이 **뒤에 든 소속의 생성일 ±[CREATED_AT_WINDOW_MS]** | 자동 관계는 둘째가 들어오는 그 트랜잭션에서 난다 |
 * | 그 조건을 만족하는 세력이 **정확히 하나** | 둘이면 어느 쪽인지 파일이 말해 주지 않는다 |
 *
 * 시각 축이 결정적이다 — 사람이 손으로 만든 관계가 나머지 전부를 우연히 맞추더라도
 * *소속을 넣은 그 순간*에 만들어졌을 확률은 사실상 없다. 같은 종류의 추론을
 * `FactionRepository.updateDepartedMembership`이 이미 쓴다(*"네 가지가 모두 같다면 그것은
 * 사실상 같은 사건이다"*).
 *
 * **멱등이다** — 이은 행은 `factionId`가 차서 다음 회차의 후보에서 빠진다.
 *
 * 애매하면 **잇지 않는다.** 잇지 못한 것은 사용자가 '캐릭터 관계' 시트의 세력 칸을 채워
 * 손수 이을 수 있다(안내 시트가 그 길을 적어 둔다) — 반대 방향의 잘못은 되돌릴 길이 없다.
 */
object FactionAutoRelationRelink {

    /**
     * 관계 생성일이 소속 생성일에서 이만큼 안이면 *같은 사건*으로 본다.
     *
     * 실측 간격은 **6ms**였다(둘 다 한 트랜잭션 안이다). 1분은 느린 기기에서 멤버 수십 명을
     * 한 번에 넣는 경우까지 넉넉히 덮으면서, 손으로 만든 관계와는 여전히 아득히 멀다.
     */
    const val CREATED_AT_WINDOW_MS = 60_000L

    /** 이을 것 — (관계 id, 세력 id). */
    data class Link(val relationshipId: Long, val factionId: Long)

    /**
     * @param factions 전 세력
     * @param memberships 전 소속 이력 — 탈퇴 행까지 넘긴다(*지금*의 판정은 [FactionStanding]이 든다)
     * @param orphanRelations `factionId`가 빈 관계들
     * @return 이을 것. **차례는 관계 id 오름차순**이라 실행마다 흔들리지 않는다.
     */
    fun plan(
        factions: List<Faction>,
        memberships: List<FactionMembership>,
        orphanRelations: List<CharacterRelationship>
    ): List<Link> {
        if (factions.isEmpty()) return emptyList()

        // (세력 id, 캐릭터 id) → 그 소속의 생성일. 재가입으로 행이 여럿이면 **가장 이른 것**을
        // 둔다 — 자동 관계는 처음 들어온 그 순간에 났고, 뒤에 쌓인 재가입 행은 그보다 늦다.
        val joinedAt = HashMap<Pair<Long, Long>, Long>()
        for (membership in memberships) {
            if (!FactionStanding.isCurrent(membership)) continue
            val key = membership.factionId to membership.characterId
            val known = joinedAt[key]
            if (known == null || membership.createdAt < known) joinedAt[key] = membership.createdAt
        }
        if (joinedAt.isEmpty()) return emptyList()

        val links = ArrayList<Link>()
        for (relation in orphanRelations.sortedBy { it.id }) {
            if (relation.factionId != null) continue
            if (!hasAutoRelationShape(relation)) continue

            val candidates = factions.filter { faction -> matches(faction, relation, joinedAt) }
            // 후보가 둘이면 어느 세력의 관계인지 데이터가 말해 주지 않는다 — 건드리지 않는다.
            if (candidates.size == 1) links.add(Link(relation.id, candidates.first().id))
        }
        return links
    }

    /**
     * 세력과 무관하게 *자동 관계라면 반드시 그런* 모양인가.
     *
     * `insertAutoRelations`는 [CharacterRelationship]을 기본값 그대로 만든다 — 설명 없음 ·
     * 양방향 · 표시순서 0. 사용자가 손댄 관계는 보통 이 중 하나가 다르다.
     */
    private fun hasAutoRelationShape(relation: CharacterRelationship): Boolean =
        relation.isBidirectional &&
            relation.description.isBlank() &&
            relation.displayOrder == 0 &&
            relation.characterId1 != relation.characterId2

    private fun matches(
        faction: Faction,
        relation: CharacterRelationship,
        joinedAt: Map<Pair<Long, Long>, Long>
    ): Boolean {
        val autoType = faction.autoRelationType.trim()
        if (autoType.isEmpty() || relation.relationshipType.trim() != autoType) return false
        if (relation.intensity != faction.autoRelationIntensity) return false

        val first = joinedAt[faction.id to relation.characterId1] ?: return false
        val second = joinedAt[faction.id to relation.characterId2] ?: return false
        // 자동 관계는 **뒤에 든 쪽**이 들어오는 트랜잭션에서 난다.
        val bornAt = maxOf(first, second)
        return kotlin.math.abs(relation.createdAt - bornAt) <= CREATED_AT_WINDOW_MS
    }
}
