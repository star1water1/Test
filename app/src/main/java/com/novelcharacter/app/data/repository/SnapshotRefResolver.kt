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
     * @param naturalById 현행 DB: 필드정의 id → 자연키
     * @param idByNatural 현행 DB: 자연키 → 필드정의 id
     */
    fun resolveFieldDef(
        oldId: Long,
        ref: FieldDefRef?,
        naturalById: Map<Long, FieldDefNaturalKey>,
        idByNatural: Map<FieldDefNaturalKey, Long>
    ): Resolution {
        val wanted = ref?.toNaturalKey()
        if (wanted == null) {
            return if (naturalById.containsKey(oldId)) Resolution(oldId, Origin.LEGACY_ID)
            else LEGACY_NOT_FOUND
        }
        if (naturalById[oldId] == wanted) return Resolution(oldId, Origin.ID_CONFIRMED)
        val byNatural = idByNatural[wanted]
        if (byNatural != null) return Resolution(byNatural, Origin.CODE)
        return NOT_FOUND
    }

    /**
     * 자연키가 성립하는 [FieldDefRef]만 조회 키로 승격한다.
     * entityType/key가 비어 있는 ref는 근거로 쓸 수 없으므로 null(=구버전 취급)로 떨어뜨린다.
     * (세계관 없는 필드 정의는 존재하지 않지만, universeCode가 비어도 key 쌍만으로는 좁히지 않는다)
     */
    private fun FieldDefRef.toNaturalKey(): FieldDefNaturalKey? {
        val t = entityType
        val k = key
        if (t.isNullOrBlank() || k.isNullOrBlank()) return null
        if (universeCode.isNullOrBlank()) return null
        return FieldDefNaturalKey(universeCode, t, k)
    }
}
