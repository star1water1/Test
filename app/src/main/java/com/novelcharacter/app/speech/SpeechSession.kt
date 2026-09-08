package com.novelcharacter.app.speech

/** Pure state machine; Android recorder/transport live outside it. */
class SpeechSession {
    enum class Phase { IDLE, RECORDING, READY, TRANSCRIBING, REVIEW, ERROR }
    var phase=Phase.IDLE; private set
    var original=""; private set
    var draft=""
    var error: SpeechError?=null; private set
    private var revision=0
    fun startRecording(permission: Boolean): Boolean {
        if(phase==Phase.RECORDING || phase==Phase.TRANSCRIBING) return false
        if(!permission) { fail(SpeechError.PERMISSION); return false }
        phase=Phase.RECORDING; error=null
        return true
    }
    fun audioReady() { phase=Phase.READY; error=null }
    fun beginTranscription(): Int? {
        if(phase!=Phase.READY && phase!=Phase.ERROR) return null
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
    fun fail(reason: SpeechError) { error=reason;phase=Phase.ERROR }
    fun reset() { revision++;phase=Phase.IDLE;original="";draft="";error=null }
}
