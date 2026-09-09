package com.novelcharacter.app.ai

/** Names are display text, never identity. Retain original ordering and reject billed/uncertain IDs. */
object NarrativeResumePlan {
    fun select(pending: List<Long>, completed: Collection<Long>, uncertain: Collection<Long>,
        selected: Set<Long>): List<Long> {
        val excluded = completed.toSet() + uncertain
        return pending.distinct().filter { it in selected && it !in excluded }
    }
}
