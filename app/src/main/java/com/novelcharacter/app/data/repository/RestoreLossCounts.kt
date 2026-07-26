package com.novelcharacter.app.data.repository

/**
 * 복원에서 되살리지 못한 것의 항목별 규모 — 순수 값 (N1).
 *
 * 미리보기(예측)와 결과(사실)가 **같은 형태**를 쓰게 해서 둘을 비교할 수 있게 한다.
 *
 * 총량 하나로 비교하면 **구성이 바뀌는 경우를 놓친다.** 예고한 필드값 유실 5건이 실제로는
 * 0건이 되고 대신 예측할 수 없던 이력 유실 3건이 생기면 총량은 줄지만(3 ≤ 5) 사용자가
 * 동의한 적 없는 유실이 발생한 것이다. 그래서 [exceeds]는 **항목별로** 비교한다.
 */
data class RestoreLossCounts(
    val fieldValues: Int = 0,
    val mergedFieldValues: Int = 0,
    val relationships: Int = 0,
    val relationshipChanges: Int = 0,
    val duplicateRelationshipChanges: Int = 0,
    val memberships: Int = 0,
    val events: Int = 0,
    val relationshipFactions: Int = 0,
    val changeEvents: Int = 0,
    val novelCleared: Boolean = false
) {
    val total: Int
        get() = fieldValues + mergedFieldValues + relationships + relationshipChanges +
            duplicateRelationshipChanges + memberships + events + relationshipFactions +
            changeEvents + (if (novelCleared) 1 else 0)

    val any: Boolean get() = total > 0

    /**
     * 어느 한 항목이라도 [predicted]보다 커졌는가 — 커졌다면 사용자가 동의하지 않은 유실이
     * 실제로 일어난 것이므로 사후에라도 알려야 한다.
     */
    fun exceeds(predicted: RestoreLossCounts): Boolean =
        fieldValues > predicted.fieldValues ||
            mergedFieldValues > predicted.mergedFieldValues ||
            relationships > predicted.relationships ||
            relationshipChanges > predicted.relationshipChanges ||
            duplicateRelationshipChanges > predicted.duplicateRelationshipChanges ||
            memberships > predicted.memberships ||
            events > predicted.events ||
            relationshipFactions > predicted.relationshipFactions ||
            changeEvents > predicted.changeEvents ||
            (novelCleared && !predicted.novelCleared)
}
