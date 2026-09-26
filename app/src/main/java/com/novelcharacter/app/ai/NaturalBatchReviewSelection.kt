package com.novelcharacter.app.ai

/** Relationship edits require individual review of both endpoints and direction. */
object NaturalBatchReviewSelection {
    val fieldKinds = setOf(NaturalBatchPlan.Kind.SET_FIELD_VALUE,
        NaturalBatchPlan.Kind.ADD_FIELD_VALUE, NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE,
        NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE)

    val relationshipKinds = setOf(NaturalBatchPlan.Kind.ADD_RELATIONSHIP,
        NaturalBatchPlan.Kind.UPDATE_RELATIONSHIP, NaturalBatchPlan.Kind.REMOVE_RELATIONSHIP)
    val factionKinds = setOf(NaturalBatchPlan.Kind.JOIN_FACTION, NaturalBatchPlan.Kind.LEAVE_FACTION)
    val supportedKinds = fieldKinds + relationshipKinds + factionKinds

    fun canSelect(operation: NaturalBatchPlan.Operation, conflicts: Set<String>) =
        operation.kind in supportedKinds && operation.id !in conflicts

    fun canBulkSelect(operation: NaturalBatchPlan.Operation, conflicts: Set<String>) =
        operation.kind in fieldKinds && canSelect(operation, conflicts) && !operation.destructive &&
            operation.origin == NaturalBatchPlan.Origin.EXTRACTED && operation.evidence.matched
}
