package com.novelcharacter.app.ai

import com.novelcharacter.app.util.AiCheckState

/** Candidate indices remain stable because refinement appends rather than replaces paid drafts. */
class NarrativeReviewState {
    private val checks = AiCheckState<Long>()
    private val choices = mutableMapOf<Long, Int>()
    val edits = mutableMapOf<String, String>()
    val editing = mutableSetOf<String>()
    val expanded = mutableSetOf<Long>()
    val instructions = mutableMapOf<Long, String>()
    fun seedDefaults(ids: Collection<Long>) = checks.seedDefaults(ids)
    fun isChecked(id: Long) = checks.isChecked(id)
    fun setChecked(id: Long, value: Boolean) = checks.setChecked(id, value)
    fun choose(id: Long, index: Int) { choices[id] = index }
    fun chosen(id: Long) = choices[id] ?: 0
    fun key(id: Long, index: Int) = "$id:$index"
    fun current(id: Long, index: Int, original: String) = edits[key(id, index)] ?: original
    fun clear() {
        checks.clear(); choices.clear(); edits.clear(); editing.clear(); expanded.clear(); instructions.clear()
    }
    companion object {
        fun merge(previous: NarrativeFieldAiWriter.WriteOutcome, next: NarrativeFieldAiWriter.WriteOutcome) =
            previous.copy(
                drafts = previous.drafts + next.drafts,
                droppedCount = previous.droppedCount + next.droppedCount,
                failures = previous.failures + next.failures,
                truncationNotes = (previous.truncationNotes + next.truncationNotes).distinct(),
                truncated = previous.truncated || next.truncated,
                inputTokens = previous.inputTokens + next.inputTokens,
                outputTokens = previous.outputTokens + next.outputTokens,
                terminalFailure = next.terminalFailure
            )
        fun appliedText(live: String, chosen: String, continueWriting: Boolean): String =
            if (continueWriting && live.isNotBlank()) live.trimEnd() + "\n\n" + chosen else chosen
    }
}
