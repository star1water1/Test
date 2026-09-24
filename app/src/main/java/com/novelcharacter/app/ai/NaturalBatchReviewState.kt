package com.novelcharacter.app.ai

/** Pure review decisions. Persistence and domain validation are added at their own milestones. */
class NaturalBatchReviewState(initial: NaturalBatchInput) {
    data class Request(val sessionId: String, val scopeRevision: Long,
        val inputRevision: Long, val generation: Long)
    data class Snapshot(val input: NaturalBatchInput, val plan: NaturalBatchMerge?,
        val selected: Set<String>, val confirmed: Set<String>, val edits: Map<String, String>,
        val generation: Long)

    var input: NaturalBatchInput = initial; private set
    var plan: NaturalBatchMerge? = null; private set
    private val selected = mutableSetOf<String>()
    private val confirmed = mutableSetOf<String>()
    private val edits = mutableMapOf<String, String>()
    private var generation = 0L
    private var acceptedGeneration: Long? = null

    fun snapshot() = Snapshot(input, plan, selected.toSet(), confirmed.toSet(), edits.toMap(), generation)

    fun restore(saved: Snapshot) {
        input = saved.input
        plan = saved.plan?.takeIf { it.sessionId == input.sessionId &&
            it.scopeRevision == input.scopeRevision && it.inputRevision == input.inputRevision }
        val ids = plan?.operations?.map { it.id }?.toSet().orEmpty() - plan?.conflicts.orEmpty()
        selected.clear(); selected.addAll(saved.selected intersect saved.confirmed intersect ids)
        confirmed.clear(); confirmed.addAll(saved.confirmed intersect ids)
        edits.clear(); edits.putAll(saved.edits.filterKeys { it in ids })
        generation = saved.generation + 1 // Callbacks from before process restoration are stale.
        acceptedGeneration = null
    }

    fun editInput(text: String) {
        val next = input.edit(text)
        if (next != input) { input = next; invalidate() }
    }

    fun changeScope(scope: NaturalBatchInput.Scope) {
        val next = input.changeScope(scope)
        if (next != input) { input = next; invalidate() }
    }

    private fun invalidate() {
        generation++
        acceptedGeneration = null
        plan = null
        selected.clear(); confirmed.clear(); edits.clear()
    }

    fun beginAnalysis(): Request {
        generation++
        acceptedGeneration = null
        return Request(input.sessionId, input.scopeRevision, input.inputRevision, generation)
    }

    fun accept(request: Request, response: NaturalBatchMerge): Boolean {
        if (request != Request(input.sessionId, input.scopeRevision, input.inputRevision, generation) ||
            acceptedGeneration == request.generation ||
            response.sessionId != input.sessionId || response.scopeRevision != input.scopeRevision ||
            response.inputRevision != input.inputRevision) return false
        plan = response
        acceptedGeneration = request.generation
        selected.clear(); confirmed.clear(); edits.clear()
        return true
    }

    fun confirm(id: String) {
        require(plan?.operations?.any { it.id == id } == true)
        require(id !in plan!!.conflicts) { "Conflicting proposal" }
        confirmed += id
    }

    fun setSelected(id: String, on: Boolean) {
        require(plan?.operations?.any { it.id == id } == true)
        if (on) {
            require(id in confirmed) { "Review confirmation required" }
            selected += id
        } else selected -= id
    }

    /** A direct edit only invalidates the edited row's confirmation; it never edits the AI payload. */
    fun editProposal(id: String, value: String) {
        require(plan?.operations?.any { it.id == id } == true)
        edits[id] = value
        confirmed -= id
        selected -= id
    }

    fun isSelected(id: String) = id in selected
    fun editedValue(id: String) = edits[id]
}
