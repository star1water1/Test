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
    val eventIds: List<Long> = emptyList()
)
