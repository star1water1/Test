package com.novelcharacter.app.ai

/** M4 exposes field edits only; bulk selection excludes creative and destructive proposals. */
object NaturalBatchReviewSelection {
    val supportedKinds = setOf(NaturalBatchPlan.Kind.SET_FIELD_VALUE,
        NaturalBatchPlan.Kind.ADD_FIELD_VALUE, NaturalBatchPlan.Kind.REMOVE_FIELD_VALUE,
        NaturalBatchPlan.Kind.CLEAR_FIELD_VALUE)

    fun canSelect(operation: NaturalBatchPlan.Operation, conflicts: Set<String>) =
        operation.kind in supportedKinds && operation.id !in conflicts

    fun canBulkSelect(operation: NaturalBatchPlan.Operation, conflicts: Set<String>) =
        canSelect(operation, conflicts) && !operation.destructive &&
            operation.origin == NaturalBatchPlan.Origin.EXTRACTED && operation.evidence.matched
}
