package com.novelcharacter.app.ui.common

import android.app.Application
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.speech.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Scoped to the host, so closing/recreating the voice sheet cannot discard an in-flight paid result. */
class VoiceInputViewModel(application: Application): AndroidViewModel(application) {
    val session=SpeechSession()
    private val slot=ReviewSlot(application,SpeechSession.Snapshot::class.java)
    private var boundKey: String?=null
    fun bind(key: String) {
        if(boundKey==key) return
        check(boundKey==null)
        val saved=slot.read("voice:$key")
        boundKey=key
        if(saved!=null) {
            session.restore(saved)
            // Legacy sessions acquire an ID before an input can acknowledge their delivery.
            saveTranscript()
            notice=if(saved.awaitingResponse) "이전 전사가 중단되었습니다. 응답을 받지 못한 요청도 과금되었을 수 있습니다. 임시 녹음은 삭제되었으며 자동으로 재요청하지 않습니다."
                else "보관한 전사 원문과 수정 내용을 복구했습니다. 새 요청은 보내지 않았습니다."
        }
    }
    fun editDraft(text: String) { session.draft=text; saveTranscript() }
    fun acceptInto(input: NaturalLanguageInputModel): Boolean {
        val key=boundKey ?: return false
        if(session.phase!=SpeechSession.Phase.REVIEW || session.draft.isBlank() || !slot.beginRequest()) return false
        try {
            if(!saveTranscript()) return false
            if(!input.accept(com.novelcharacter.app.ai.CreativeInputState.Delivery(
                    key,session.sessionId,session.draft,session.original))) return false
            discard() // Receipt is durable; even failed cleanup cannot append this session twice.
            return true
        } finally { slot.endRequest() }
    }
    private fun saveTranscript(): Boolean = if(boundKey!=null &&
        (session.original.isNotBlank() || session.phase==SpeechSession.Phase.TRANSCRIBING)) slot.save(session.snapshot()) else true
    val updates=MutableLiveData(0)
    var notice="";private set
    var seconds=0L;private set
    private var recorder: MediaRecorder?=null
    private var recognizer: SpeechRecognizer?=null
    private var deviceGeneration=0
    private var audio: File?=null
    private var started=0L
    private val audioRoot=File(application.cacheDir,"voice-input").apply {mkdirs()}
    var terms=emptyList<String>()
    var omitted=0

    fun refresh() { saveTranscript(); updates.value=(updates.value ?: 0)+1 }
    fun start(permission: Boolean) {
        if(slot.failed) return
        val config=SpeechSettings(getApplication()).read()
        if(config.mode==SpeechMode.CLOUD && config.providerId.isBlank()) {session.fail(SpeechError.NO_PROVIDER);refresh();return}
        if(!session.startRecording(permission)) {refresh();return}
        notice="";seconds=0;started=SystemClock.elapsedRealtime()
        if(config.mode==SpeechMode.ON_DEVICE) startDevice(config) else startFile()
        refresh()
        viewModelScope.launch {
            while(session.phase==SpeechSession.Phase.RECORDING) {
                seconds=(SystemClock.elapsedRealtime()-started)/1000
                refresh();delay(1000)
            }
        }
    }
    private fun startFile() {
        deleteAudio()
        try {
            audio=File.createTempFile("voice-",".m4a",audioRoot)
            @Suppress("DEPRECATION")
            val rec=if(Build.VERSION.SDK_INT>=31) MediaRecorder(getApplication()) else MediaRecorder()
            recorder=rec
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000);rec.setAudioEncodingBitRate(64000)
            rec.setOutputFile(audio!!.absolutePath)
            rec.setMaxDuration(SpeechProtocol.MAX_RECORDING_MS)
            rec.setMaxFileSize(SpeechProtocol.MAX_AUDIO_BYTES)
            rec.setOnInfoListener { _,what,_->
                if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    notice="녹음 한도에 도달해 녹음을 마쳤습니다. 현재 녹음을 전사한 뒤 이어서 입력할 수 있습니다."
                    stop(false)
                }
            }
            rec.setOnErrorListener { _,_,_->stop(false);session.fail(SpeechError.RECORDING);refresh()}
            rec.prepare();rec.start()
        } catch(_: Exception) {
            recorder?.release();recorder=null;deleteAudio();session.fail(SpeechError.RECORDING)
        }
    }
    private fun startDevice(config: SpeechConfig) {
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(getApplication())) {
            session.fail(SpeechError.DEVICE_UNAVAILABLE);return
        }
        try {
            recognizer?.destroy()
            val generation=++deviceGeneration
            recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(getApplication())
            recognizer!!.setRecognitionListener(object: RecognitionListener {
                override fun onReadyForSpeech(params:Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB:Float) {}
                override fun onBufferReceived(buffer:ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error:Int) {
                    if(generation!=deviceGeneration) return
                    session.fail(if(error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) SpeechError.PERMISSION
                        else if(error==SpeechRecognizer.ERROR_NO_MATCH || error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT) SpeechError.EMPTY
                        else SpeechError.DEVICE_UNAVAILABLE)
                    recognizer?.destroy();recognizer=null;refresh()
                }
                override fun onResults(results:Bundle?) {
                    if(generation!=deviceGeneration) return
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if(text.isNullOrBlank()) session.fail(SpeechError.EMPTY) else session.deviceResult(text)
                    recognizer?.destroy();recognizer=null;refresh()
                }
                override fun onPartialResults(partialResults:Bundle?) {}
                override fun onEvent(eventType:Int,params:Bundle?) {}
            })
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if(config.language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE,config.language)
                if(Build.VERSION.SDK_INT>=33 && config.sendHints) putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS,ArrayList(terms))
            })
            if(Build.VERSION.SDK_INT<33 && config.sendHints && terms.isNotEmpty())
                notice="이 Android 버전은 온디바이스 고유명사 힌트를 지원하지 않습니다. 전사 뒤 용어를 확인하세요."
        } catch(_: Exception) {session.fail(SpeechError.DEVICE_UNAVAILABLE);recognizer?.destroy();recognizer=null}
    }
    fun stop(transcribe: Boolean) {
        if(session.phase!=SpeechSession.Phase.RECORDING) return
        if(recognizer!=null) {recognizer?.stopListening();return}
        try {
            recorder?.stop();recorder?.release();recorder=null
            seconds=(SystemClock.elapsedRealtime()-started)/1000
            session.audioReady()
            if(transcribe) transcribe()
        } catch(_: Exception) {
            recorder?.release();recorder=null;deleteAudio();session.fail(SpeechError.RECORDING)
        }
        refresh()
    }
    fun transcribe() {
        val file=audio ?: run {session.fail(SpeechError.FILE);refresh();return}
        if(!slot.beginRequest()) return
        val id=session.beginTranscription() ?: run {slot.endRequest();return}
        val config=SpeechSettings(getApplication()).read()
        if(!saveTranscript()) {slot.endRequest();session.fail(SpeechError.FILE);updates.value=(updates.value ?: 0)+1;return}
        refresh()
        viewModelScope.launch {
            try {
            val result=try {SpeechTranscriber(getApplication()).transcribe(config,file,audioRoot,terms,seconds)}
                catch(e: CancellationException) {throw e}
                catch(_: Exception) {SpeechResult.Failure(SpeechError.NETWORK)}
            session.complete(id,result)
            if(result is SpeechResult.Success && saveTranscript()) deleteAudio()
            refresh()
            } finally { slot.endRequest() }
        }
    }
    fun hasAudio()=audio?.isFile==true
    fun discard() {
        if(session.phase==SpeechSession.Phase.TRANSCRIBING) return
        if(!slot.clear()) return
        notice=""
        deviceGeneration++
        if(session.phase==SpeechSession.Phase.RECORDING) stop(false)
        recognizer?.cancel();recognizer?.destroy();recognizer=null
        deleteAudio();session.reset();refresh()
    }
    private fun deleteAudio() {
        audio?.let {if(it.exists() && !it.delete()) notice="임시 녹음을 바로 지우지 못했습니다. 앱 임시 저장소에 남아 있습니다."}
        audio=null
    }
    override fun onCleared() {
        try {recorder?.stop()} catch(_: Exception) {}
        recorder?.release();recognizer?.destroy();deleteAudio()
        super.onCleared()
    }
}
