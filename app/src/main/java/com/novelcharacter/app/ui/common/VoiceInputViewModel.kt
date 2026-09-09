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
    private val audioStore=PendingAudio.store(application)
    private var pendingAudio: PendingAudioStore.Item?=null
    private var transcription: kotlinx.coroutines.Job?=null
    internal var transcribeFile: suspend (SpeechConfig,File,File,List<String>,Long)->SpeechResult = { config,file,root,hints,duration ->
        SpeechTranscriber(application).transcribe(config,file,root,hints,duration)
    }
    private var blocked=false
    private var released=false
    private inline fun preserve(action: ()->Unit): Boolean = try { action(); true } catch(_: Exception) {
        notice="녹음을 보관하거나 복구하지 못했습니다. 기존 파일은 지우지 않았습니다. 저장 공간과 보관한 녹음 목록을 확인하세요."
        false
    }
    fun bind(key: String, audioId: String?=null) {
        if(boundKey==key) return
        check(boundKey==null)
        val saved=slot.read("voice:$key")
        boundKey=key
        if(saved!=null) {
            session.restore(saved)
            // Legacy sessions acquire an ID before an input can acknowledge their delivery.
            saveTranscript()
            notice=if(saved.awaitingResponse) "이전 전사가 중단되었습니다. 응답을 받지 못한 요청도 과금되었을 수 있습니다. 자동으로 재요청하지 않습니다."
                else "보관한 전사 원문과 수정 내용을 복구했습니다. 새 요청은 보내지 않았습니다."
        }
        blocked=!preserve {
            val candidates=audioStore.catalog().items.filter { it.record.inputKey==key }
            val item=if(audioId!=null) candidates.single { it.record.id==audioId }
                else candidates.singleOrNull().also { check(candidates.size<=1) }
            if(item!=null) {
                check(saved==null || saved.sessionId==item.record.id || saved.original.isBlank())
                check(!PendingAudioStore.isActive(item.record.id))
                pendingAudio=item
                released=item.record.phase==PendingAudioStore.Phase.RELEASED
                audio=audioStore.file(item.record.id)
                seconds=item.record.durationMs/1000
                if(!released) {
                    val snapshot=if(saved?.sessionId==item.record.id) saved else item.record.transcript
                    session.restore(snapshot ?: SpeechSession.Snapshot("","",false,item.record.id))
                    if(item.record.phase in setOf(PendingAudioStore.Phase.RECORDING,PendingAudioStore.Phase.INTERRUPTED)) {
                        notice="녹음이 예기치 않게 중단되었습니다. 원본은 보관했습니다. 재전사 전에 읽을 수 있는 음성 구간을 확인합니다."
                    } else if(item.record.phase==PendingAudioStore.Phase.TRANSCRIBING) {
                        notice="이전 전사의 완료 여부를 확인할 수 없습니다. 녹음은 보관했으며 자동 재요청하지 않습니다. 재시도 전 제공자의 과금 내역을 확인하세요."
                    } else notice="보관한 녹음을 복구했습니다. 확인한 전사를 입력에 추가하거나 직접 버릴 때까지 원본을 보관합니다."
                    if(session.original.isBlank()) session.audioReady()
                    check(saveTranscript())
                } else notice="이미 처리한 녹음 파일이 남아 있습니다. 남은 파일 정리만 실행할 수 있습니다."
            }
        }
    }
    fun editDraft(text: String) { session.draft=text; saveTranscript() }
    fun acceptInto(input: NaturalLanguageInputModel): Boolean {
        val key=boundKey ?: return false
        if(blocked || released || session.phase !in setOf(SpeechSession.Phase.REVIEW,SpeechSession.Phase.ERROR) ||
            session.draft.isBlank() || !slot.beginRequest()) return false
        try {
            if(!saveTranscript()) return false
            if(!input.accept(com.novelcharacter.app.ai.CreativeInputState.Delivery(
                    key,session.sessionId,session.draft,session.original))) return false
            discardHeld() // Receipt is durable; reuse this lease through source cleanup.
            return true
        } finally { slot.endRequest() }
    }
    private fun saveTranscript(): Boolean = if(boundKey!=null &&
        (pendingAudio!=null || session.original.isNotBlank() || session.phase==SpeechSession.Phase.TRANSCRIBING)) slot.save(session.snapshot()) else true
    val updates=MutableLiveData(0)
    var notice="";private set
    var seconds=0L;private set
    private var recorder: MediaRecorder?=null
    private var recognizer: SpeechRecognizer?=null
    private var deviceGeneration=0
    private var audio: File?=null
    private var started=0L
    private val audioRoot=File(audioStore.root,"audio")
    var terms=emptyList<String>()
    var omitted=0

    fun refresh() { saveTranscript(); updates.value=(updates.value ?: 0)+1 }
    fun start(permission: Boolean) {
        if(slot.failed || blocked || pendingAudio!=null || session.original.isNotBlank()) return
        val config=SpeechSettings(getApplication()).read()
        if(config.mode==SpeechMode.CLOUD && config.providerId.isBlank()) {session.fail(SpeechError.NO_PROVIDER);refresh();return}
        if(!permission) {session.startRecording(false);refresh();return}
        if(!slot.beginRequest()) return
        if(!session.startRecording(true)) {slot.endRequest();refresh();return}
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
        try {
            pendingAudio=audioStore.create(session.sessionId,checkNotNull(boundKey))
            check(PendingAudioStore.claim(session.sessionId,this))
            audio=audioStore.file(session.sessionId)
            check(saveTranscript())
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
            recorder?.release();recorder=null;session.fail(SpeechError.RECORDING)
            endAudioUse();slot.endRequest()
            notice="녹음을 시작하지 못했습니다. 생성된 파일이 있다면 보관한 녹음 목록에 남겨 두었습니다."
        }
    }
    private fun startDevice(config: SpeechConfig) {
        if(Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(getApplication())) {
            session.fail(SpeechError.DEVICE_UNAVAILABLE);slot.endRequest();return
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
                    recognizer?.destroy();recognizer=null;slot.endRequest();refresh()
                }
                override fun onResults(results:Bundle?) {
                    if(generation!=deviceGeneration) return
                    val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if(text.isNullOrBlank()) session.fail(SpeechError.EMPTY) else session.deviceResult(text)
                    recognizer?.destroy();recognizer=null;slot.endRequest();refresh()
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
        } catch(_: Exception) {session.fail(SpeechError.DEVICE_UNAVAILABLE);recognizer?.destroy();recognizer=null;slot.endRequest()}
    }
    fun stop(transcribe: Boolean) {
        if(session.phase!=SpeechSession.Phase.RECORDING) return
        if(recognizer!=null) {recognizer?.stopListening();return}
        try {
            recorder?.stop();recorder?.release();recorder=null
            val item=checkNotNull(pendingAudio)
            pendingAudio=audioStore.ready(item,PendingAudio.durationMs(checkNotNull(audio)))
            seconds=pendingAudio!!.record.durationMs/1000
            session.audioReady()
        } catch(_: Exception) {
            recorder?.release();recorder=null;session.fail(SpeechError.RECORDING)
            preserve { pendingAudio=pendingAudio?.let { audioStore.interrupted(it) } }
            notice="녹음 종료를 확인하지 못했습니다. 원본 파일은 남겨 두었습니다. 재전사 전에 읽을 수 있는 음성 구간을 확인합니다."
        } finally {
            endAudioUse();slot.endRequest()
        }
        refresh()
        if(transcribe && session.phase==SpeechSession.Phase.READY) transcribe()
    }
    fun transcribe() {
        if(blocked || released || transcription?.isActive==true) return
        val item=pendingAudio ?: return
        if(!slot.beginRequest()) return
        val config=SpeechSettings(getApplication()).read()
        if(config.mode!=SpeechMode.CLOUD) {notice="보관한 파일을 전사하려면 외부 전사 설정을 선택하세요. 제공자를 자동 전환하지 않습니다.";slot.endRequest();refresh();return}
        if(!PendingAudioStore.claim(item.record.id,this)) {slot.endRequest();return}
        if(!preserve {
            // Re-read revision before allowing a network request from an old recovery window.
            check(audioStore.read(item.record.id)?.revision==item.revision)
            if(item.record.phase in setOf(PendingAudioStore.Phase.RECORDING,PendingAudioStore.Phase.INTERRUPTED))
                pendingAudio=audioStore.ready(item,PendingAudio.durationMs(audioStore.file(item.record.id)))
            pendingAudio=audioStore.beginAttempt(checkNotNull(pendingAudio),config.providerId,config.model)
            seconds=checkNotNull(pendingAudio).record.durationMs/1000
        }) {endAudioUse();slot.endRequest();refresh();return}
        val id=session.beginTranscription() ?: run {endAudioUse();slot.endRequest();return}
        if(!saveTranscript()) {endAudioUse();slot.endRequest();session.transcriptionInterrupted();refresh();return}
        notice="녹음 원본은 전사 확인 전까지 보관합니다. 재시도도 추가 과금될 수 있습니다."
        refresh()
        transcription=viewModelScope.launch {
            try {
            val result=try {transcribeFile(config,
                audioStore.verifiedFile(checkNotNull(pendingAudio)),audioRoot,terms,seconds)}
                catch(e: CancellationException) {throw e}
                catch(_: Exception) {SpeechResult.Failure(SpeechError.NETWORK)}
            session.complete(id,result)
            // Both records keep audio alive. Even a successful response never deletes it here.
            if(saveTranscript()) preserve {
                pendingAudio=audioStore.finishAttempt(checkNotNull(pendingAudio),
                    if(result is SpeechResult.Success) session.snapshot() else null)
            }
            refresh()
            } catch(e: CancellationException) {
                session.transcriptionInterrupted()
                preserve { pendingAudio=audioStore.finishAttempt(checkNotNull(pendingAudio),null) }
                notice="전사를 중단했습니다. 녹음과 이전 전사는 보관했습니다. 서버의 처리·과금 여부는 확인이 필요합니다."
                refresh()
                throw e
            } finally { endAudioUse();slot.endRequest() }
        }
    }
    fun cancelTranscription() { transcription?.cancel() }
    fun hasAudio()=pendingAudio!=null && !released
    fun needsCleanup()=released
    private fun endAudioUse() {pendingAudio?.let {PendingAudioStore.end(it.record.id,this)}}
    fun discard() {
        if(session.phase==SpeechSession.Phase.TRANSCRIBING) return
        if(session.phase==SpeechSession.Phase.RECORDING) stop(false)
        if(!slot.beginRequest()) return
        try {discardHeld()} finally {slot.endRequest()}
    }
    /** Caller already holds the voice lease. ReviewRequestLease is deliberately non-reentrant. */
    private fun discardHeld() {
        if(!preserve {
            pendingAudio?.let {
                pendingAudio=audioStore.release(it);released=true
                audioStore.cleanup(checkNotNull(pendingAudio));pendingAudio=null;audio=null
            }
        }) {refresh();return}
        if(!slot.clear()) return
        released=false;blocked=false;notice=""
        deviceGeneration++
        recognizer?.cancel();recognizer?.destroy();recognizer=null
        session.reset();refresh()
    }
    override fun onCleared() {
        if(recorder!=null) stop(false)
        recorder?.release();recognizer?.destroy()
        if(transcription?.isActive!=true) {endAudioUse();slot.endRequest()}
        super.onCleared()
    }
}
