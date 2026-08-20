package com.novelcharacter.app.data.model

/**
 * 휴지통용 캐릭터 스냅샷 (B-7) — 캐릭터와 모든 연관 데이터를 통째로 직렬화한다.
 * WorldPackageExporter가 수집하는 연관 집합과 동형이며, Gson으로 JSON 왕복된다.
 */
data class CharacterSnapshot(
    val character: Character,
    /**
     * 아래 여덟 목록은 **전부 nullable이다** — 앱이 만드는 payload에는 늘 키가 있지만,
     * Gson은 키가 통째로 없으면 non-null 선언에도 null을 주입한다(R-2). 그래서 손으로 편집한
     * payload가 하나 들어오면 non-null 선언은 **복원 순간 NPE로 터진다**(B-19).
     * 실제로 겪은 사고는 아니고 — 터져도 "복원 실패"로 떨어져 무통보 유실은 아니다 —
     * **`refs`·`nameBankCodes`·`revertScope`는 이미 nullable이라 캐릭터 payload만 규약을
     * 어긴 상태였다.** 규약을 어긴 채로 두면 다음에 목록을 늘리는 사람이 그 모양을 따라 적는다.
     *
     * **원소 타입은 절대 바꾸지 말 것** — 구버전 payload의 역직렬화가 깨진다. 널 허용은
     * 읽는 쪽 `.orEmpty()`와 짝이고, **그 짝을 강제하는 것은 시험이 아니라 컴파일러다**
     * (선언을 non-null로 되돌리면 읽는 쪽은 그대로 컴파일되고 시험도 그대로 초록이다).
     */
    val fieldValues: List<CharacterFieldValue>? = null,
    val stateChanges: List<CharacterStateChange>? = null,
    val tags: List<CharacterTag>? = null,
    /** 명대사 (사용자 요청 2026.08.20). 위 규약 그대로 nullable이다 — 옛 payload에는 이 키가 없다. */
    val quotes: List<CharacterQuote>? = null,
    val relationships: List<CharacterRelationship>? = null,
    val relationshipChanges: List<CharacterRelationshipChange>? = null,
    val factionMemberships: List<FactionMembership>? = null,
    val eventIds: List<Long>? = null,
    /**
     * 위 목록들이 담은 DB id의 안정 식별자 (N1). 구버전 payload에는 이 키가 없어 Gson이
     * null을 주입하며, 그때는 종전대로 id 단독 해석으로 폴백한다. 상세는 [SnapshotRefs].
     *
     * 기존 필드의 타입은 절대 바꾸지 말 것 — 구버전 payload의 역직렬화가 깨진다.
     * (`eventIds: List<Long>`에 코드를 담을 자리가 없어 refs로 **병기**하는 이유가 이것이다)
     */
    val refs: SnapshotRefs? = null,
    /**
     * 이 캐릭터가 이름 은행에서 가져다 쓰고 있던 엔트리의 code (B-3).
     *
     * 캐릭터를 지우면 `resetUsageByCharacter`가 사용 표시를 풀어 주는데, 종전에는 복원해도
     * 되돌아오지 않았고 집계도 경고도 없었다. **id가 아니라 code로 담는다** — 엔트리는 휴지통
     * 스냅샷 타입이 없어 같은 작업으로 함께 복원될 수 없으므로, code 단독 조회가 곧 R-1이다.
     * (R-11의 답: 이 참조 종류는 **보류 키가 필요 없다.** 같은 작업이 되살릴 대상이 아니다.)
     *
     * 구버전 payload에는 이 키가 없어 Gson이 null을 주입한다 — 읽는 쪽은 `.orEmpty()`로 받는다(R-2).
     */
    val nameBankCodes: List<String>? = null,
    /**
     * **편집 직전 백업 전용** — 그 편집이 실제로 파괴한 데이터 갈래 ([RestoreModes]의 SCOPE_*).
     *
     * 되돌리기는 살아 있는 캐릭터에 쓰는 동작이라 교체 범위가 곧 파괴 범위다. 경로마다 파괴하는
     * 것이 다른데(작품 이동은 필드값·세력소속, 값 라이브러리 삭제는 필드값·상태변화, 어느 경로도
     * 태그는 건드리지 않는다) 합집합으로 되돌리면 **원래 편집이 손대지도 않은 데이터를 지운다.**
     * 그래서 무엇을 파괴했는지를 스냅샷이 함께 기록한다.
     *
     * 구버전 편집 백업에는 이 키가 없다 — 그때는 모든 편집 경로가 공통으로 파괴하는
     * [RestoreModes.LEGACY_REVERT_SCOPE](필드값)만 되돌린다(덜 되돌릴지언정 더 지우지 않는다).
     */
    val revertScope: List<String>? = null
)
