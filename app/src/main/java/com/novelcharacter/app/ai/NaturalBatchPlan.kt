package com.novelcharacter.app.ai

import java.util.UUID

/** A draft belongs to one scope and one exact version of the user's words. */
data class NaturalBatchInput(
    val sessionId: String,
    val scope: Scope,
    val scopeRevision: Long,
    val inputRevision: Long,
    val text: String
) {
    enum class ScopeKind { UNIVERSE, WORK }
    data class Scope(val kind: ScopeKind, val id: Long)
    data class Segment(val id: String, val start: Int, val end: Int, val text: String)

    init {
        require(sessionId.isNotBlank() && scope.id > 0 && scopeRevision >= 0 && inputRevision >= 0)
    }

    fun edit(next: String): NaturalBatchInput =
        if (next == text) this else copy(text = next, inputRevision = inputRevision + 1)

    fun changeScope(next: Scope): NaturalBatchInput =
        if (next == scope) this else copy(scope = next, scopeRevision = scopeRevision + 1)

    /** Blank lines are the only automatic boundary; punctuation cannot split a correction or negation. */
    fun segments(): List<Segment> {
        val boundaries = Regex("(?:\\r?\\n[ \\t]*){2,}")
        val result = mutableListOf<Segment>()
        var start = 0
        fun add(end: Int) {
            if (text.substring(start, end).isNotBlank()) {
                result += Segment("s${result.size}:$start:$end", start, end, text.substring(start, end))
            }
        }
        boundaries.findAll(text).forEach { match ->
            add(match.range.first)
            start = match.range.last + 1
        }
        add(text.length)
        return result
    }

    companion object {
        fun create(scope: Scope, text: String = "") =
            NaturalBatchInput(UUID.randomUUID().toString(), scope, 0, 0, text)
    }
}

/** Parsed suggestions are inert. References can only be resolved by the caller's request map. */
data class NaturalBatchPlan(
    val sessionId: String,
    val scopeRevision: Long,
    val inputRevision: Long,
    val operations: List<Operation>,
    val constraints: List<Constraint>,
    val unresolved: List<Unresolved>,
    val notes: List<Note>,
    val segmentStatus: List<SegmentStatus>,
    val requestedSegments: Set<String>,
    /** App-issued request order, never supplied by the model. */
    val requestSequence: Long
) {
    enum class Origin { EXTRACTED, DERIVED, CREATIVE }
    enum class Kind {
        SET_FIELD_VALUE, ADD_FIELD_VALUE, REMOVE_FIELD_VALUE, CLEAR_FIELD_VALUE,
        ADD_RELATIONSHIP, UPDATE_RELATIONSHIP, REMOVE_RELATIONSHIP,
        JOIN_FACTION, LEAVE_FACTION
    }
    enum class Coverage { PROCESSED, IGNORED, UNPROCESSED }
    enum class LeaveMode { REMOVE, DEPART }
    enum class Comparison { GREATER_THAN, LESS_THAN, EQUAL_TO }
    enum class ReviewStatus { NEEDS_REVIEW, CONFLICT }

    data class Evidence(val segmentIds: List<String>, val quote: String, val matched: Boolean)
    data class Operation(
        val id: String,
        val kind: Kind,
        val targetRef: String,
        val fieldRef: String?,
        val relatedRef: String?,
        val factionRef: String?,
        val relationshipRef: String?,
        val value: String?,
        val relationshipType: String?,
        val relationshipDescription: String?,
        val intensity: Int?,
        val bidirectional: Boolean?,
        val joinYear: Int?,
        val leaveYear: Int?,
        val leaveMode: LeaveMode?,
        val origin: Origin,
        val evidence: Evidence
    ) {
        val destructive: Boolean get() = kind in setOf(
            Kind.REMOVE_FIELD_VALUE, Kind.CLEAR_FIELD_VALUE,
            Kind.REMOVE_RELATIONSHIP, Kind.LEAVE_FACTION
        )

        /** Same cell/link/membership must be reconciled before any executor sees the plan. */
        fun collisionKey(): String = when (kind) {
            Kind.SET_FIELD_VALUE, Kind.ADD_FIELD_VALUE, Kind.REMOVE_FIELD_VALUE,
            Kind.CLEAR_FIELD_VALUE -> "field:$targetRef:$fieldRef"
            Kind.ADD_RELATIONSHIP, Kind.UPDATE_RELATIONSHIP, Kind.REMOVE_RELATIONSHIP ->
                relationshipRef?.let { "relationship:$it" }
                    ?: "relationship:${listOf(targetRef, relatedRef.orEmpty()).sorted().joinToString(":")}:$relationshipType"
            Kind.JOIN_FACTION, Kind.LEAVE_FACTION -> "faction:$targetRef:$factionRef"
        }
    }
    data class Constraint(val id: String, val segmentIds: List<String>,
        val leftTargetRef: String, val rightTargetRef: String, val fieldRef: String,
        val comparison: Comparison, val description: String)
    data class Unresolved(val id: String, val segmentIds: List<String>, val text: String, val reason: String)
    data class Note(val id: String, val segmentIds: List<String>, val text: String)
    data class SegmentStatus(val segmentId: String, val coverage: Coverage, val reason: String?)
}

/** A failed chunk is represented separately so its source text remains available for retry. */
data class NaturalBatchMerge(
    val sessionId: String,
    val scopeRevision: Long,
    val inputRevision: Long,
    val operations: List<NaturalBatchPlan.Operation>,
    val constraints: List<NaturalBatchPlan.Constraint>,
    val unresolved: List<NaturalBatchPlan.Unresolved>,
    val notes: List<NaturalBatchPlan.Note>,
    val conflicts: Set<String>,
    val incompleteSegments: Set<String>,
    val failedSegments: Set<String>,
    val complete: Boolean
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
    fun status(operation: NaturalBatchPlan.Operation): NaturalBatchPlan.ReviewStatus =
        if (operation.id in conflicts) NaturalBatchPlan.ReviewStatus.CONFLICT
        else NaturalBatchPlan.ReviewStatus.NEEDS_REVIEW
}

object NaturalBatchPlans {
    /** Keep enough original context when a cited item crosses the requested retry boundary. */
    fun retryScope(input: NaturalBatchInput, chunks: List<NaturalBatchPlan>, seeds: Set<String>): Set<String> {
        val valid = input.segments().map { it.id }.toSet()
        require(seeds.isNotEmpty() && seeds.all { it in valid })
        require(chunks.all { it.sessionId == input.sessionId &&
            it.scopeRevision == input.scopeRevision && it.inputRevision == input.inputRevision &&
            it.requestedSegments.all { id -> id in valid } })
        val ordered = chunks.sortedBy { it.requestSequence }
        val latestOwner = mutableMapOf<String, Int>()
        ordered.forEachIndexed { index, chunk ->
            chunk.requestedSegments.forEach { latestOwner[it] = index }
        }
        val groups = ordered.flatMapIndexed { index, chunk ->
            (chunk.operations.map { it.evidence.segmentIds } +
                chunk.constraints.map { it.segmentIds } +
                chunk.unresolved.map { it.segmentIds } + chunk.notes.map { it.segmentIds })
                .filter { ids -> ids.all { latestOwner[it] == index } }
        }
        val scope = seeds.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            groups.forEach { ids ->
                if (ids.any { it in scope } && scope.addAll(ids)) changed = true
            }
        } while (changed)
        return scope
    }

    fun merge(
        input: NaturalBatchInput,
        chunks: List<NaturalBatchPlan>,
        failedSegments: Set<String> = emptySet()
    ): NaturalBatchMerge {
        val validSegments = input.segments().map { it.id }.toSet()
        require(failedSegments.all { it in validSegments })
        require(chunks.all {
            it.sessionId == input.sessionId && it.scopeRevision == input.scopeRevision &&
                it.inputRevision == input.inputRevision && it.requestSequence >= 0 &&
                it.requestedSegments.isNotEmpty() &&
                it.requestedSegments.all { id -> id in validSegments }
        })
        val ordered = chunks.sortedBy { it.requestSequence }
        val latestOwner = mutableMapOf<String, Int>()
        val lastSequence = mutableMapOf<String, Long>()
        ordered.forEachIndexed { index, chunk ->
            chunk.requestedSegments.forEach { id ->
                require(lastSequence[id] != chunk.requestSequence) {
                    "Two responses for one segment have the same request sequence"
                }
                lastSequence[id] = chunk.requestSequence
                latestOwner[id] = index
            }
        }
        fun retained(index: Int, ids: List<String>): Boolean {
            require(ids.isNotEmpty() && ids.all { it in ordered[index].requestedSegments })
            val ownership = ids.map { latestOwner[it] == index }
            require(ownership.all { it } || ownership.none { it }) {
                "Retry splits a cited item; expand the retry with retryScope"
            }
            return ownership.all { it }
        }
        fun identity(chunk: NaturalBatchPlan, id: String) =
            chunk.requestSequence.toString() + ":" +
                chunk.requestedSegments.sorted().joinToString("+") + "|" + id
        val operations = ordered.flatMapIndexed { index, chunk ->
            chunk.operations.filter { retained(index, it.evidence.segmentIds) }
                .map { it.copy(id = identity(chunk, it.id)) }
        }
        val constraints = ordered.flatMapIndexed { index, chunk ->
            chunk.constraints.filter { retained(index, it.segmentIds) }
                .map { it.copy(id = identity(chunk, it.id)) }
        }
        val unresolved = ordered.flatMapIndexed { index, chunk ->
            chunk.unresolved.filter { retained(index, it.segmentIds) }
                .map { it.copy(id = identity(chunk, it.id)) }
        }
        val notes = ordered.flatMapIndexed { index, chunk ->
            chunk.notes.filter { retained(index, it.segmentIds) }
                .map { it.copy(id = identity(chunk, it.id)) }
        }
        val allIds = operations.map { it.id } + constraints.map { it.id } +
            unresolved.map { it.id } + notes.map { it.id }
        val duplicatedIds = allIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val collisions = operations.groupBy { it.collisionKey() }.values
            .filter { it.size > 1 }.flatten().map { it.id }.toSet()
        val conflicts = collisions + duplicatedIds
        val status = ordered.flatMapIndexed { index, chunk ->
            chunk.segmentStatus.filter { latestOwner[it.segmentId] == index }
        }.groupBy { it.segmentId }
        val covered = (operations.flatMap { it.evidence.segmentIds } +
            constraints.flatMap { it.segmentIds } + unresolved.flatMap { it.segmentIds } +
            notes.flatMap { it.segmentIds }).toSet()
        val incomplete = validSegments.filterTo(mutableSetOf()) { id ->
            val entries = status[id].orEmpty()
            val one = entries.singleOrNull()
            entries.size != 1 || one?.coverage == NaturalBatchPlan.Coverage.UNPROCESSED ||
                (one?.coverage == NaturalBatchPlan.Coverage.PROCESSED && id !in covered) ||
                (one?.coverage == NaturalBatchPlan.Coverage.IGNORED && id in covered)
        }
        return NaturalBatchMerge(input.sessionId, input.scopeRevision, input.inputRevision,
            operations, constraints, unresolved, notes, conflicts, incomplete + failedSegments,
            failedSegments, validSegments.isNotEmpty() && incomplete.isEmpty() && failedSegments.isEmpty())
    }
}
