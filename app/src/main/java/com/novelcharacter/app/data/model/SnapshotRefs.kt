package com.novelcharacter.app.data.model

/**
 * 휴지통 스냅샷이 DB id와 **함께** 담는 안정 식별자 (N1).
 *
 * 스냅샷 payload는 원래 참조를 DB id로만 담았다. 그런데 덮어쓰기 임포트·세계관 삭제처럼
 * 상위 엔티티가 지워졌다가 다시 들어오는 경로 뒤에는 그 id가 재발급되어, 같은 대상이 멀쩡히
 * 살아 있는데도 복원이 전부 생략됐다. 반대로 테이블 재생성 마이그레이션(DROP+RENAME)은
 * sqlite_sequence를 생존 최대 id로 되돌리므로 **다른 엔티티가 옛 id를 물려받아 오배정**될 수도 있다.
 * 즉 `getById(...) != null`은 "같은 대상인가"를 원리적으로 판별하지 못한다.
 *
 * 규약 4-4(DB id는 어떤 단계에서도 단독 근거가 아니다)를 스냅샷에도 적용한다 —
 * **코드(자연키)가 대상을 정하고, id는 코드가 함께 일치할 때의 확인 수단**이다.
 *
 * ## Gson 하위호환
 * `TrashRepository`는 순수 `Gson()`을 쓴다. Gson은 Kotlin 생성자 기본값을 실행하지 않고
 * Unsafe로 객체를 할당하므로 **구버전 payload에 없는 필드는 선언이 non-null이어도 런타임에
 * null이 주입된다**(같은 이유로 `CharacterStateChange.code` 등이 nullable이다).
 * 따라서 이 클래스의 모든 컬렉션 필드는 nullable이며, 읽는 쪽이 `?: emptyMap()`으로 받는다.
 * 앞으로 필드를 추가할 때도 반드시 nullable + 폴백을 지킬 것.
 */
data class SnapshotRefs(
    /** payload 형식 버전. 구버전 payload에는 이 키가 없어 null이 들어오며 그때는 v0(=근거 없음)으로 읽는다. */
    val version: Int? = VERSION,
    /** 삭제 시점 캐릭터가 속했던 작품의 코드 */
    val novelCode: String? = null,
    /** 삭제 시점 캐릭터가 속했던 세계관의 코드 (미분류 캐릭터는 null) */
    val universeCode: String? = null,
    /** fieldDefinitionId.toString() → 필드 정의의 자연키 (FieldDefinition에는 code가 없다) */
    val fieldDefs: Map<String, FieldDefRef>? = null,
    /** characterId.toString() → character.code (관계 상대) */
    val characters: Map<String, String>? = null,
    /** factionId.toString() → faction.code (세력 소속·관계의 세력 열) */
    val factions: Map<String, String>? = null,
    /** eventId.toString() → event.code (참가 사건·관계 변화가 가리키는 사건) */
    val events: Map<String, String>? = null
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * 필드 정의의 안정 식별자.
 *
 * `FieldDefinition`에는 code가 없고 유니크 제약이 `(universeId, entityType, key)`다.
 * universeId 자체가 재발급되는 DB id이므로, 기기 이전·덮어쓰기에서도 성립하려면
 * 세계관을 **코드**로 승격한 `(universeCode, entityType, key)`가 자연키다.
 * (7장 규약: "코드 열이 없는 시트는 왕복 불변 속성을 안정 식별자로 승격한다")
 */
data class FieldDefRef(
    val universeCode: String? = null,
    val entityType: String? = null,
    val key: String? = null
)

/** 현행 DB 쪽 자연키 인덱스 — [FieldDefRef]와 같은 3요소를 조회 키로 쓰는 형태. */
data class FieldDefNaturalKey(
    val universeCode: String?,
    val entityType: String,
    val key: String
)
