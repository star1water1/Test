package com.novelcharacter.app.data.repository

/**
 * 복원에서 되살리지 못한 것의 항목별 규모 — 순수 값 (N1 → B-1).
 *
 * 미리보기(예측)와 결과(사실)가 **같은 형태**를 쓰게 해서 둘을 비교할 수 있게 한다.
 *
 * 총량 하나로 비교하면 **구성이 바뀌는 경우를 놓친다.** 예고한 필드값 유실 5건이 실제로는
 * 0건이 되고 대신 예측할 수 없던 이력 유실 3건이 생기면 총량은 줄지만(3 ≤ 5) 사용자가
 * 동의한 적 없는 유실이 발생한 것이다. 그래서 [exceeds]는 **항목별로** 비교한다.
 *
 * 항목이 늘어날 때 [total]·[exceeds]에 반영하는 것을 잊지 않도록 **[counts]를 단일 소스로**
 * 둔다 — 세 곳에 같은 목록을 적어 두면 언젠가 하나가 뒤처지고, 뒤처진 쪽은 "유실이 없다"고
 * 거짓말한다. 새 항목은 [counts]에 한 줄 추가하는 것으로 끝난다.
 */
data class RestoreLossCounts(
    // ── 캐릭터 복원 ──
    val fieldValues: Int = 0,
    val mergedFieldValues: Int = 0,
    val relationships: Int = 0,
    val relationshipChanges: Int = 0,
    val duplicateRelationshipChanges: Int = 0,
    val memberships: Int = 0,
    val events: Int = 0,
    val relationshipFactions: Int = 0,
    val changeEvents: Int = 0,
    val novelCleared: Boolean = false,

    // ── 세계관 복원 (B-1) ──
    /** 되살리지 못한 필드 정의 — 같은 자연키의 정의가 이미 있어 건너뛴 것 */
    val fieldDefinitions: Int = 0,
    /** 되살리지 못한 값 라이브러리 엔트리 */
    val fieldValueEntries: Int = 0,
    /** 삭제되지 않은 캐릭터·사건이 갖고 있던 보관 필드값 — 그 캐릭터/사건을 못 찾아 제외 */
    val orphanFieldValues: Int = 0,

    // ── 세력 복원 (B-1) ──
    /** 세력 간 관계 — 상대 세력을 찾을 수 없음 */
    val factionRelationships: Int = 0,
    /** '이 세력의 자동 관계'라는 지정을 되붙이지 못한 관계 — 관계 자체를 찾을 수 없음 */
    val detachedRelationships: Int = 0,

    // ── 사건·작품 복원 (B-1) ──
    /** 사건에 연결돼 있던 캐릭터 — 캐릭터를 찾을 수 없음 */
    val characterLinks: Int = 0,
    /** 사건↔작품 연결 — 작품을 찾을 수 없음 */
    val novelLinks: Int = 0,
    /** 사건에 다시 매달지 못한 관계 변화 이력 — 그 이력을 찾을 수 없음 */
    val changeLinks: Int = 0,
    /** 대표 이미지 연동(세계관·작품 → 캐릭터/작품)이 대상을 못 찾아 해제된 건수 */
    val imageLinks: Int = 0,
    /** 세계관을 찾을 수 없어 '세계관 없음'으로 복원된다 (사건·작품) */
    val universeCleared: Boolean = false
) {
    /**
     * 항목별 규모의 단일 소스. 이름은 비교·집계에만 쓰이며 화면 문구는 UI가 만든다.
     * 불리언 항목도 0/1로 편입해 [exceeds]가 같은 규칙으로 다룰 수 있게 한다.
     */
    val counts: List<Pair<String, Int>>
        get() = listOf(
            "fieldValues" to fieldValues,
            "mergedFieldValues" to mergedFieldValues,
            "relationships" to relationships,
            "relationshipChanges" to relationshipChanges,
            "duplicateRelationshipChanges" to duplicateRelationshipChanges,
            "memberships" to memberships,
            "events" to events,
            "relationshipFactions" to relationshipFactions,
            "changeEvents" to changeEvents,
            "novelCleared" to if (novelCleared) 1 else 0,
            "fieldDefinitions" to fieldDefinitions,
            "fieldValueEntries" to fieldValueEntries,
            "orphanFieldValues" to orphanFieldValues,
            "factionRelationships" to factionRelationships,
            "detachedRelationships" to detachedRelationships,
            "characterLinks" to characterLinks,
            "novelLinks" to novelLinks,
            "changeLinks" to changeLinks,
            "imageLinks" to imageLinks,
            "universeCleared" to if (universeCleared) 1 else 0
        )

    val total: Int get() = counts.sumOf { it.second }

    val any: Boolean get() = counts.any { it.second > 0 }

    /**
     * 어느 한 항목이라도 [predicted]보다 커졌는가 — 커졌다면 사용자가 동의하지 않은 유실이
     * 실제로 일어난 것이므로 사후에라도 알려야 한다.
     */
    fun exceeds(predicted: RestoreLossCounts): Boolean {
        val other = predicted.counts.toMap()
        return counts.any { (key, value) -> value > (other[key] ?: 0) }
    }

    /**
     * 두 집계를 더한다 — 작업 전체 복원처럼 여러 항목의 결과를 합칠 때 쓴다.
     *
     * [counts]와 달리 여기는 필드를 일일이 적어야 한다(불변 data class라 달리 방법이 없다).
     * 새 항목을 추가하고 이 함수를 잊으면 그 유실만 합계에서 사라져 **조용히 0으로 보고된다** —
     * `RestoreLossCountsTest`가 리플렉션으로 전 필드를 훑어 그 누락을 실패로 만든다.
     */
    operator fun plus(other: RestoreLossCounts): RestoreLossCounts = RestoreLossCounts(
        fieldValues = fieldValues + other.fieldValues,
        mergedFieldValues = mergedFieldValues + other.mergedFieldValues,
        relationships = relationships + other.relationships,
        relationshipChanges = relationshipChanges + other.relationshipChanges,
        duplicateRelationshipChanges = duplicateRelationshipChanges + other.duplicateRelationshipChanges,
        memberships = memberships + other.memberships,
        events = events + other.events,
        relationshipFactions = relationshipFactions + other.relationshipFactions,
        changeEvents = changeEvents + other.changeEvents,
        novelCleared = novelCleared || other.novelCleared,
        fieldDefinitions = fieldDefinitions + other.fieldDefinitions,
        fieldValueEntries = fieldValueEntries + other.fieldValueEntries,
        orphanFieldValues = orphanFieldValues + other.orphanFieldValues,
        factionRelationships = factionRelationships + other.factionRelationships,
        detachedRelationships = detachedRelationships + other.detachedRelationships,
        characterLinks = characterLinks + other.characterLinks,
        novelLinks = novelLinks + other.novelLinks,
        changeLinks = changeLinks + other.changeLinks,
        imageLinks = imageLinks + other.imageLinks,
        universeCleared = universeCleared || other.universeCleared
    )
}
