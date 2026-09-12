package com.novelcharacter.app.ai

/** The text and delivery receipts are one journal payload, including across process death. */
class CreativeInputState(val key: String, saved: Snapshot?, initial: String = "") {
    data class Snapshot(
        val text: String,
        val pending: Boolean,
        // Nullable for pre-session journal payloads: Gson does not run Kotlin defaults.
        val inputKey: String? = null,
        val acceptedSpeechSessions: Map<String, AcceptedTranscript>? = null
    )

    data class AcceptedTranscript(val original: String?, val finalText: String)
    data class Delivery(val inputKey: String, val sessionId: String, val text: String,
        val original: String? = null)

    var snapshot: Snapshot = (saved ?: Snapshot(initial, false)).let {
        require(it.inputKey == null || it.inputKey == key) { "Input owner mismatch" }
        it.copy(inputKey = key, acceptedSpeechSessions = it.acceptedSpeechSessions.orEmpty())
    }; private set

    fun edit(text: String, persist: (Snapshot) -> Boolean): Boolean =
        commit(snapshot.copy(text = text, pending = false), persist)

    fun accept(delivery: Delivery, persist: (Snapshot) -> Boolean): Boolean {
        if (delivery.inputKey != key || delivery.sessionId.isBlank() || delivery.text.isBlank()) return false
        // Check even on duplicate delivery: a stale editor must not acknowledge newer input.
        if (delivery.sessionId in snapshot.acceptedSpeechSessions.orEmpty()) return persist(snapshot)
        val text = if (snapshot.text.isEmpty()) delivery.text else snapshot.text + "\n" + delivery.text
        return commit(snapshot.copy(text = text, pending = true,
            acceptedSpeechSessions = snapshot.acceptedSpeechSessions.orEmpty() +
                (delivery.sessionId to AcceptedTranscript(delivery.original, delivery.text))), persist)
    }

    private fun commit(next: Snapshot, persist: (Snapshot) -> Boolean): Boolean {
        if (!persist(next)) return false
        snapshot = next
        return true
    }
}
