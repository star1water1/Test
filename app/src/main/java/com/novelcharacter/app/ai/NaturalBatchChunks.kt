package com.novelcharacter.app.ai

/** Request sizing is a soft batching target, never an input limit or a sentence boundary. */
object NaturalBatchChunks {
    const val TARGET_CHARS = 6000
    const val TARGET_SEGMENTS = 12

    fun partition(input: NaturalBatchInput, ids: Set<String> = input.segments().map { it.id }.toSet(),
        targetChars: Int = TARGET_CHARS, targetSegments: Int = TARGET_SEGMENTS,
        plans: List<NaturalBatchPlan> = emptyList()): List<Set<String>> {
        require(targetChars > 0 && targetSegments > 0)
        val segments = input.segments()
        require(ids.all { id -> segments.any { it.id == id } })
        val result = mutableListOf<Set<String>>()
        var current = linkedSetOf<String>()
        var size = 0L
        val remaining = ids.toMutableSet()
        segments.filter { it.id in ids }.forEach { segment ->
            if (segment.id !in remaining) return@forEach
            val group = if (plans.isEmpty()) setOf(segment.id) else NaturalBatchPlans.retryScope(input, plans, setOf(segment.id))
            require(group.all { it in ids }) { "Retry must include the entire cited range" }
            val length = segments.filter { it.id in group }.sumOf { it.text.length.toLong() }
            if (current.isNotEmpty() && (size + length > targetChars || current.size + group.size > targetSegments)) {
                result += current; current = linkedSetOf(); size = 0
            }
            current += group; size += length; remaining -= group
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** Preserve app-issued refs and ambiguous candidates across every request and retry. */
    fun context(input: NaturalBatchInput, ids: Set<String>, all: NaturalBatchContext): NaturalBatchContext {
        val text = input.segments().filter { it.id in ids }.joinToString("\n\n") { it.text }
        val characters = all.characters.filterValues { character ->
            (listOf(character.name, character.code) + character.aliases).any {
                it.isNotBlank() && text.contains(it, ignoreCase = true)
            }
        }
        val characterIds = characters.values.map { it.id }.toSet()
        val memberships = all.memberships?.filter { it.characterId in characterIds }
        val factions = all.factions.filterValues { faction ->
            listOf(faction.name, faction.code).any { it.isNotBlank() && text.contains(it, ignoreCase = true) } ||
                (text.contains("무소속") && memberships.orEmpty().any { it.factionId == faction.id })
        }
        return all.copy(characters = characters, factions = factions,
            relationships = all.relationships.filterValues { it.firstId in characterIds && it.secondId in characterIds },
            values = all.values.filterKeys { refs -> run {
                val field = all.fields[refs.second]
                refs.first in characters && field != null && listOf(field.name, field.key).any {
                    it.isNotBlank() && text.contains(it, ignoreCase = true)
                }
            } },
            memberships = memberships?.filter { row -> factions.values.any { it.id == row.factionId } })
    }
}

/** Persisted after every request, including failures and requests not started before interruption. */
data class NaturalBatchChunkState(
    val plans: List<NaturalBatchPlan>, val failures: Map<String, String>, val nextSequence: Long
) {
    fun merge(input: NaturalBatchInput) = NaturalBatchPlans.merge(input, plans, failures.keys)

    fun reserve(input: NaturalBatchInput, ids: Set<String>): Pair<NaturalBatchChunkState, Long> {
        val sequence = nextSequence
        require(sequence >= 0 && sequence < Long.MAX_VALUE)
        // A placeholder owns these segments before sending: process death cannot resurrect old proposals.
        val placeholder = NaturalBatchPlan(input.sessionId, input.scopeRevision, input.inputRevision,
            emptyList(), emptyList(), emptyList(), emptyList(), ids.map {
                NaturalBatchPlan.SegmentStatus(it, NaturalBatchPlan.Coverage.UNPROCESSED, null)
            }, ids, sequence)
        return copy(plans = compact(plans + placeholder),
            failures = failures + ids.associateWith { "요청이 완료되지 않았습니다. 자동으로 다시 보내지 않습니다." },
            nextSequence = sequence + 1) to sequence
    }

    fun finish(sequence: Long, plan: NaturalBatchPlan?, reason: String? = null): NaturalBatchChunkState {
        val requested = plans.single { it.requestSequence == sequence }.requestedSegments
        return if (plan != null) {
            require(plan.requestSequence == sequence && plan.requestedSegments == requested)
            copy(plans = plans.map { if (it.requestSequence == sequence) plan else it }, failures = failures - requested)
        } else {
            copy(failures = failures + requested.associateWith { reason ?: "분석 요청이 실패했습니다." })
        }
    }

    companion object {
        fun create(input: NaturalBatchInput) = NaturalBatchChunkState(emptyList(),
            input.segments().associate { it.id to "아직 분석 요청을 보내지 않은 문단입니다." }, 0)

        private fun compact(plans: List<NaturalBatchPlan>): List<NaturalBatchPlan> {
            val owners = mutableMapOf<String, Long>()
            plans.sortedBy { it.requestSequence }.forEach { plan ->
                plan.requestedSegments.forEach { owners[it] = plan.requestSequence }
            }
            return plans.filter { plan -> plan.requestedSegments.any { owners[it] == plan.requestSequence } }
        }
    }
}
