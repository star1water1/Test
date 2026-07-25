package com.novelcharacter.app.data.model

/**
 * 휴지통용 캐릭터 스냅샷 (B-7) — 캐릭터와 모든 연관 데이터를 통째로 직렬화한다.
 * WorldPackageExporter가 수집하는 연관 집합과 동형이며, Gson으로 JSON 왕복된다.
 */
data class CharacterSnapshot(
    val character: Character,
    val fieldValues: List<CharacterFieldValue> = emptyList(),
    val stateChanges: List<CharacterStateChange> = emptyList(),
    val tags: List<CharacterTag> = emptyList(),
    val relationships: List<CharacterRelationship> = emptyList(),
    val relationshipChanges: List<CharacterRelationshipChange> = emptyList(),
    val factionMemberships: List<FactionMembership> = emptyList(),
    val eventIds: List<Long> = emptyList(),
    /**
     * 위 목록들이 담은 DB id의 안정 식별자 (N1). 구버전 payload에는 이 키가 없어 Gson이
     * null을 주입하며, 그때는 종전대로 id 단독 해석으로 폴백한다. 상세는 [SnapshotRefs].
     *
     * 기존 필드의 타입은 절대 바꾸지 말 것 — 구버전 payload의 역직렬화가 깨진다.
     * (`eventIds: List<Long>`에 코드를 담을 자리가 없어 refs로 **병기**하는 이유가 이것이다)
     */
    val refs: SnapshotRefs? = null
)
