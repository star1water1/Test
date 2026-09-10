package com.novelcharacter.app.speech

/** Pure state machine; Android recorder/transport live outside it. */
class SpeechSession {
    enum class Phase { IDLE, RECORDING, READY, TRANSCRIBING, REVIEW, ERROR }
    var phase=Phase.IDLE; private set
    var original=""; private set
    var draft=""
    var error: SpeechError?=null; private set
    var sessionId: String = java.util.UUID.randomUUID().toString(); private set
    private var revision=0
    fun startRecording(permission: Boolean): Boolean {
        if(phase==Phase.RECORDING || phase==Phase.TRANSCRIBING) return false
        if(!permission) { fail(SpeechError.PERMISSION); return false }
        sessionId=java.util.UUID.randomUUID().toString()
        phase=Phase.RECORDING; error=null
        return true
    }
    fun audioReady() { phase=Phase.READY; error=null }
    /** Attach only to a verified live service, never start a microphone during restore. */
    fun attachRecording(id: String) {check(sessionId==id);phase=Phase.RECORDING;error=null}
    fun beginTranscription(): Int? {
        if(phase!=Phase.READY && phase!=Phase.ERROR && phase!=Phase.REVIEW) return null
        phase=Phase.TRANSCRIBING; error=null
        return ++revision
    }
    fun complete(requestId: Int, result: SpeechResult) {
        if(requestId!=revision || phase!=Phase.TRANSCRIBING) return
        when(result) {
            is SpeechResult.Success->{original=result.text;draft=result.text;phase=Phase.REVIEW;error=null}
            is SpeechResult.Failure->fail(result.kind)
        }
    }
    fun deviceResult(text: String) {
        original=text;draft=text;phase=Phase.REVIEW;error=null
    }
    fun transcriptionInterrupted() {
        revision++;phase=if(original.isNotBlank()) Phase.REVIEW else Phase.READY;error=null
    }
    data class Snapshot(val original: String, val draft: String, val awaitingResponse: Boolean,
        val sessionId: String? = null)
    fun snapshot() = Snapshot(original,draft,phase==Phase.TRANSCRIBING,sessionId)
    /** A restore never starts recording or repeats a paid request. The audio store restores files. */
    fun restore(value: Snapshot) {
        revision++
        sessionId=value.sessionId?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        original=value.original; draft=value.draft
        phase=if(original.isNotBlank()) Phase.REVIEW else if(value.awaitingResponse) Phase.ERROR else Phase.IDLE
        error=if(value.awaitingResponse && original.isBlank()) SpeechError.FILE else null
    }
    fun fail(reason: SpeechError) { error=reason;phase=Phase.ERROR }
    fun reset() {
        revision++;sessionId=java.util.UUID.randomUUID().toString()
        phase=Phase.IDLE;original="";draft="";error=null
    }
}
