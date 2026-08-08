package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefNaturalKey
import com.novelcharacter.app.data.model.FieldDefRef

/**
 * 휴지통 스냅샷의 참조를 현행 DB에서 다시 찾는 해석 사다리 (N1) — 순수 로직.
 *
 * `PortableFieldFilters`의 사다리와 같은 규약(4-4)을 따른다:
 * **코드(자연키)가 대상을 정하고, DB id는 코드가 함께 일치할 때의 확인 수단일 뿐이다.**
 *
 * ```
 * 1. 스냅샷에 안정 식별자가 있다
 *    a. 옛 id가 살아 있고 그 행의 코드까지 같다  → 옛 id      (ID_CONFIRMED)
 *    b. 코드로 찾으면 있다                       → 그 id      (CODE — id만 재발급됐다)
 *    c. 둘 다 아니다                             → 없음       (MISSING)
 *       ※ id로 폴백하지 않는다. 테이블 재생성 마이그레이션은 sqlite_sequence를 되돌리므로
 *         다른 엔티티가 옛 id를 쓰고 있을 수 있고, **오배정은 생략보다 나쁘다.**
 * 2. 스냅샷에 안정 식별자가 없다(구버전 payload)
 *    → 옛 id가 살아 있으면 그것(LEGACY_ID), 아니면 없음(LEGACY_MISSING).
 *      근거가 id뿐이므로 종전과 동일하게 동작한다 — 없던 근거를 만들어낼 수는 없다.
 * ```
 *
 * 실패 사유를 [Origin]으로 구분해 돌려주는 이유는 고지 문구 때문이다. 종전 경고는 해석
 * 실패를 일괄로 "참조 대상이 삭제되어"라고 단정했는데, 재발급 시나리오에서는 대상이 멀쩡히
 * 살아 있어 **사실과 다른 경고**였다(7장: 사실과 다른 경고는 무음보다 나쁘다).
 */
object SnapshotRefResolver {

    /** 해석 결과의 근거. */
    enum class Origin {
        /** 옛 id와 코드가 모두 일치 — 동일성 완전 확인 */
        ID_CONFIRMED,

        /** id는 바뀌었고 코드로 다시 찾음 */
        CODE,

        /** 안정 식별자가 있으나 그 대상이 현재 DB에 없음 */
        MISSING,

        /** 구버전 payload — 안정 식별자가 없어 id 단독으로 찾음 */
        LEGACY_ID,

        /** 구버전 payload — 안정 식별자도 없고 옛 id도 살아 있지 않음 */
        LEGACY_MISSING
    }

    data class Resolution(val id: Long?, val origin: Origin) {
        val found: Boolean get() = id != null

        /** 근거가 id뿐이라 "같은 대상"임을 보장하지 못하는 해석인가 (고지 판단용). */
        val isLegacyGuess: Boolean get() = origin == Origin.LEGACY_ID
    }

    private val NOT_FOUND = Resolution(null, Origin.MISSING)
    private val LEGACY_NOT_FOUND = Resolution(null, Origin.LEGACY_MISSING)

    /**
     * code를 안정 식별자로 갖는 엔티티(캐릭터·세력·사건·작품)의 해석.
     *
     * @param oldId        스냅샷이 담은 삭제 시점 id
     * @param snapshotCode 스냅샷이 담은 코드. null/빈 문자열이면 구버전 payload로 본다.
     * @param codeById     현행 DB: id → code (code가 null인 레거시 행은 넣지 않는다)
     * @param idByCode     현행 DB: code → id
     * @param liveIds      현행 DB: 살아 있는 id 전체 (code가 없는 행까지 포함)
     */
    fun resolveByCode(
        oldId: Long,
        snapshotCode: String?,
        codeById: Map<Long, String>,
        idByCode: Map<String, Long>,
        liveIds: Set<Long>
    ): Resolution {
        if (snapshotCode.isNullOrBlank()) {
            return if (oldId in liveIds) Resolution(oldId, Origin.LEGACY_ID) else LEGACY_NOT_FOUND
        }
        if (codeById[oldId] == snapshotCode) return Resolution(oldId, Origin.ID_CONFIRMED)
        val byCode = idByCode[snapshotCode]
        if (byCode != null) return Resolution(byCode, Origin.CODE)
        return NOT_FOUND
    }

    /**
     * 필드 정의의 해석 — code가 없으므로 자연키 `(세계관코드, entityType, key)`를 안정 식별자로 쓴다.
     *
     * 전역 구역 필드(`universeId IS NULL` — B-119 확장)는 세계관 코드 자리가 **null인 채로**
     * 자연키가 성립한다. 그 null은 "전역"의 표기이지 유실이 아니다 — 유실이면 ref 자체가
     * 만들어지지 않는다(`TrashRepository.fieldDefRef`).
     *
     * @param naturalById 현행 DB: 필드정의 id → 자연키. **자연키를 만들 수 있는 행만** 담는다
     *   (세계관 소속인데 그 세계관 코드를 얻지 못한 행은 빠진다 — [liveIds]가 그쪽을 받는다).
     * @param idByNatural 현행 DB: 자연키 → 필드정의 id
     * @param liveIds     현행 DB: 살아 있는 필드정의 id 전체 (자연키를 못 만드는 행까지 포함).
     *   [resolveByCode]의 `liveIds`와 같은 역할이다 — **생존 판정과 동일성 판정은 다른 질문**이라
     *   한 색인이 겸할 수 없다. 겸하게 두면 자연키 없는 행을 색인에서 빼는 순간 구버전 payload가
     *   멀쩡히 살아 있는 대상을 '없음'으로 보고 값을 버린다.
     */
    fun resolveFieldDef(
        oldId: Long,
        ref: FieldDefRef?,
        naturalById: Map<Long, FieldDefNaturalKey>,
        idByNatural: Map<FieldDefNaturalKey, Long>,
        liveIds: Set<Long>
    ): Resolution {
        val wanted = if (ref == null) null else naturalKeyOf(ref)
        if (wanted == null) {
            return if (oldId in liveIds) Resolution(oldId, Origin.LEGACY_ID) else LEGACY_NOT_FOUND
        }
        if (naturalById[oldId] == wanted) return Resolution(oldId, Origin.ID_CONFIRMED)
        val byNatural = idByNatural[wanted]
        if (byNatural != null) return Resolution(byNatural, Origin.CODE)
        return NOT_FOUND
    }

    /**
     * 자연키가 성립하는 [FieldDefRef]만 조회 키로 승격한다 — **이 판정의 단일 소스다.**
     *
     * 해석([resolveFieldDef])과 색인 만들기(`TrashRepository.buildFieldDefIndex`)가 같은 질문을
     * 각자 적고 있었고, 그래서 한쪽이 `""`를 전역으로 읽는 동안 다른 쪽은 아니었다.
     * 두 벌로 적힌 한 줄은 언제든 갈라지므로 여기 하나만 둔다(B-130이 `FieldScopeCell`로
     * 내린 처방과 같다).
     *
     * entityType/key가 비어 있는 ref는 근거로 쓸 수 없으므로 null(=구버전 취급)로 떨어뜨린다.
     *
     * 세계관 코드 자리는 **null과 빈 문자열을 가른다.**
     * - `null` = 전역 구역 필드(B-119 확장). 그 자체로 대상이 하나로 좁혀지므로 자연키가 성립한다
     *   (`field_definitions`의 `universeId IS NULL` + `(entityType, key)`).
     * - `""`  = 세계관 소속인데 그 코드를 얻지 못한 것(세계관 삭제 경로는 `universe.code`를
     *   빈 값 검사 없이 그대로 싣는다). 어느 세계관인지 좁혀지지 않으므로 근거에서 뺀다 —
     *   전역으로 승격하면 남의 세계관 값이 전역 필드에 붙는다(오배정 > 생략).
     */
    fun naturalKeyOf(ref: FieldDefRef): FieldDefNaturalKey? {
        val t = ref.entityType
        val k = ref.key
        if (t.isNullOrBlank() || k.isNullOrBlank()) return null
        val u = ref.universeCode
        if (u != null && u.isBlank()) return null
        return FieldDefNaturalKey(u, t, k)
    }
}
