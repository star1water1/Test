package com.novelcharacter.app.data.model

/**
 * 캐릭터 외 엔티티(세계관·작품·세력·사건)의 휴지통 payload (B-1).
 *
 * ## 왜 캐릭터 스냅샷을 흉내내지 않고 따로 두는가
 * 세계관 하나를 지우면 캐릭터 수백 명이 함께 사라지는데, 그 캐릭터는 이미
 * [CharacterSnapshot]으로 개별 보관된다. 세계관 스냅샷이 캐릭터까지 품으면 같은 데이터가
 * 두 벌 남고 복원 시 어느 쪽이 진짜인지 알 수 없다. 그래서 **스냅샷은 겹치지 않고 이어붙는다** —
 * 각 스냅샷은 "그 엔티티가 죽을 때 함께 죽지만 **다른 스냅샷이 담지 않는 것**"만 담고,
 * 같은 삭제 작업(`TrashSnapshot.operationId`)으로 묶여 순서대로 복원된다.
 * 상위를 먼저 되살리면 코드가 되살아나므로 하위 스냅샷의 참조가 R-1 사다리로 다시 이어진다.
 *
 * ## R-2 (Gson 하위호환)
 * `TrashRepository`는 순수 `Gson()`을 쓴다. Gson은 Kotlin 생성자 기본값을 실행하지 않고
 * Unsafe로 객체를 할당하므로 **payload에 없는 필드는 선언이 non-null이어도 런타임에 null이
 * 주입된다.** 그래서 이 파일의 모든 컬렉션·참조 필드는 nullable이고, 읽는 쪽이
 * `?: emptyList()`로 받는다. 앞으로 필드를 추가할 때도 반드시 nullable + 폴백을 지킬 것이며
 * **기존 필드의 타입은 절대 바꾸지 말 것**(구버전 payload의 역직렬화가 깨진다).
 * 계약은 `EntitySnapshotPayloadTest`가 고정한다.
 */

/**
 * 캐릭터 외 스냅샷이 공유하는 안정 식별자 묶음 (R-1).
 *
 * [SnapshotRefs]와 같은 규약이다 — **코드(자연키)가 대상을 정하고 DB id는 확인 수단**이며,
 * 해석은 같은 사다리([com.novelcharacter.app.data.repository.SnapshotRefResolver])를 쓴다.
 * 캐릭터용과 클래스를 나눈 이유는 담는 참조 종류가 달라서다(캐릭터는 novelCode 하나지만
 * 여기서는 작품·사건·관계를 여러 건 담는다). 형태는 일부러 동일하게 맞춰 두었다.
 */
data class EntityRefs(
    /** payload 형식 버전. 구버전 payload에는 이 키가 없어 null이 들어온다. */
    val version: Int? = SnapshotRefs.VERSION,
    /** 이 엔티티가 속한 세계관의 코드 (세계관 스냅샷 자신은 universe.code가 곧 그것이라 null) */
    val universeCode: String? = null,
    /** novelId.toString() → novel.code */
    val novels: Map<String, String>? = null,
    /** characterId.toString() → character.code */
    val characters: Map<String, String>? = null,
    /** factionId.toString() → faction.code */
    val factions: Map<String, String>? = null,
    /** eventId.toString() → event.code */
    val events: Map<String, String>? = null,
    /** fieldDefinitionId.toString() → 필드 정의 자연키 (FieldDefinition에는 code가 없다) */
    val fieldDefs: Map<String, FieldDefRef>? = null
)

/**
 * 세계관 스냅샷.
 *
 * 함께 담는 것: 필드 정의(FK CASCADE), 값 라이브러리 엔트리(필드 정의 CASCADE),
 * 그리고 **삭제되지 않는 캐릭터·사건이 갖고 있던 이 세계관 필드의 값**.
 * 마지막 항목이 핵심이다 — 미분류 캐릭터가 보관 중인 값(N2)은 어떤 캐릭터 스냅샷에도
 * 담기지 않으면서 필드 정의 CASCADE로 함께 소멸하므로, 여기서 담지 않으면 조용히 사라진다.
 *
 * 담지 않는 것: 하위 작품·세력·사건·캐릭터 — 각자 자기 스냅샷을 갖고 같은 작업으로 묶인다.
 */
data class UniverseSnapshot(
    val universe: Universe,
    val fieldDefinitions: List<FieldDefinition>? = null,
    val fieldValueEntries: List<FieldValueEntry>? = null,
    /** 이 세계관 필드를 가리키지만 **삭제되지 않는** 캐릭터가 가진 값 */
    val orphanCharacterFieldValues: List<CharacterFieldValue>? = null,
    /** 동상 — 삭제되지 않는 사건이 가진 이 세계관 사건 필드의 값 */
    val orphanEventFieldValues: List<EventFieldValue>? = null,
    val refs: EntityRefs? = null
)

/**
 * 작품 스냅샷.
 *
 * 소속 캐릭터는 각자 캐릭터 스냅샷으로 남는다. 작품이 지워질 때 **사건은 살아남고 연결만**
 * 끊기므로(cross ref CASCADE) 그 연결을 코드로 담아 복원 시 되붙인다.
 * 이 작품을 대표 이미지로 쓰던 세계관의 참조(imageNovelId)도 끊기지만, 세계관 쪽 데이터라
 * 여기서 되살리지 않는다 — 사용자가 다시 지정하면 되고, 잘못 되살리면 오배정이 된다.
 */
data class NovelSnapshot(
    val novel: Novel,
    /** 삭제 시점 이 작품에 연결돼 있던 사건 id (코드는 refs.events에 병기) */
    val linkedEventIds: List<Long>? = null,
    val refs: EntityRefs? = null
)

/**
 * 세력 스냅샷.
 *
 * 세력이 죽으면 소속(FK CASCADE)·세력 간 관계(FK CASCADE)가 함께 죽고,
 * 캐릭터 자동 관계는 삭제 옵션에 따라 **지워지거나(deleteRelationships=true)
 * factionId만 null이 된다(SET_NULL)**. 두 경우는 복원 방법이 다르므로 따로 담는다.
 */
data class FactionSnapshot(
    val faction: Faction,
    val memberships: List<FactionMembership>? = null,
    val factionRelationships: List<FactionRelationship>? = null,
    /** 삭제 옵션이 '자동 관계도 삭제'였을 때 함께 지워진 캐릭터 관계 */
    val autoRelationships: List<CharacterRelationship>? = null,
    /** 위 관계에 매달려 있던 변화 이력 (탈퇴 이력이 여기 있다) */
    val autoRelationshipChanges: List<CharacterRelationshipChange>? = null,
    /**
     * 삭제 옵션이 '관계는 유지'였을 때 factionId만 null이 된 관계의 code.
     * 관계 자체는 살아 있으므로 되살릴 것은 '이 세력의 자동 관계'라는 지정뿐이다.
     */
    val detachedRelationshipCodes: List<String>? = null,
    val refs: EntityRefs? = null
)

/**
 * 사건 스냅샷.
 *
 * 사건이 죽으면 사건 필드값·캐릭터 연결·작품 연결이 CASCADE로 죽고,
 * **관계 변화 이력의 사건 연결은 SET_NULL로 조용히 끊긴다** — 이력 자체는 살아남기 때문에
 * 어떤 캐릭터 스냅샷도 그 손실을 담지 못한다. 그래서 끊긴 이력의 code를 담아 되붙인다.
 */
data class EventSnapshot(
    val event: TimelineEvent,
    val fieldValues: List<EventFieldValue>? = null,
    /** 이 사건에 연결돼 있던 캐릭터 id (코드는 refs.characters에 병기) */
    val characterIds: List<Long>? = null,
    /** 이 사건에 연결돼 있던 작품 id (코드는 refs.novels에 병기) */
    val novelIds: List<Long>? = null,
    /** eventId가 SET_NULL로 끊긴 관계 변화 이력의 code */
    val relationshipChangeCodes: List<String>? = null,
    /**
     * 출생/사망 사건 삭제가 **캐릭터 쪽에서** 함께 지우는 상태변화 이력(`__birth`/`__death`).
     *
     * 이 이력은 사건에 FK로 매달려 있지 않고 시맨틱 동기화의 역방향 처리가 지운다
     * (`SemanticFieldSyncHelper.onBirthEventDeleted`). 사건 삭제만 되돌리면 캐릭터의 출생·사망
     * 기록은 돌아오지 않는데, 그 상태로 "복구할 수 있습니다"라고 안내하면 사실과 다른 약속이 된다.
     * 파생 필드값(생년·나이·생존 여부)은 담지 않는다 — 그 사이 사용자가 고쳤을 수 있어
     * 되돌리면 덮어쓰기가 되므로, 되돌리지 않았다는 사실을 복원 결과로 알린다.
     */
    val linkedStateChanges: List<CharacterStateChange>? = null,
    val refs: EntityRefs? = null
)
