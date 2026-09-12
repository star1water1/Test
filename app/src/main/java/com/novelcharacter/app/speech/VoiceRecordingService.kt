package com.novelcharacter.app.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novelcharacter.app.MainActivity
import java.util.concurrent.atomic.AtomicReference

/** One visible, user-started capture. No restart, transcription, or ownership by an editor. */
class VoiceRecordingService : Service() {
    private val main=Handler(Looper.getMainLooper())
    private var worker: Thread?=null
    private val stopReason=AtomicReference<String?>(null)
    private var wake: PowerManager.WakeLock?=null
    private var recordingId: String?=null
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id=intent?.getStringExtra("id")
        if(intent?.action==STOP) {
            if(id==recordingId) requestStop(id,"사용자가 녹음을 마쳤습니다.")
            if(worker==null) stopSelf()
            return START_NOT_STICKY
        }
        // An OS restart or replay cannot turn the microphone on. launch() reserves this exact ID.
        if(intent?.action!=START || id==null || state?.id!=id || state?.running!=true || worker!=null) {
            if(worker==null) stopSelf()
            return START_NOT_STICKY
        }
        recordingId=id
        stopReason.set(null)
        try {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,"음성 녹음",NotificationManager.IMPORTANCE_LOW))
            val notification=notification("녹음 준비 중 · 중지는 원본을 보관합니다.")
            if(Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION,notification)
            wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"novelcharacter:voice").apply {
                acquire(SpeechProtocol.MAX_RECORDING_MS.toLong()+60_000)
            }
            worker=Thread({capture(id)},"voice-capture").also {it.start()}
        } catch(_: Exception) {
            finish(id,0,"녹음을 시작하지 못했습니다. 마이크 권한과 알림 설정을 확인하세요. 기존 파일은 보관했습니다.")
        }
        return START_NOT_STICKY
    }
    private fun notification(message: String, ongoing: Boolean = true): Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if(ongoing) "음성 녹음" else "녹음을 보관했습니다")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing)
        if(ongoing) builder.addAction(0,"중지 · 보관",PendingIntent.getService(this,0,
            Intent(this,VoiceRecordingService::class.java).setAction(STOP).putExtra("id",recordingId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }
    private fun requestStop(id: String?, reason: String) {
        if(id!=recordingId) return
        stopReason.compareAndSet(null,reason)
        state=state?.copy(message="녹음을 마치고 원본을 보관하는 중입니다.")
    }
    private fun capture(id: String) {
        val store=PendingAudio.store(this)
        var recorder: AudioRecord?=null
        var sink: PcmCapture?=null
        var duration=0L
        var message="사용자가 녹음을 마쳤습니다."
        var callback: AudioManager.AudioRecordingCallback?=null
        try {
            val item=checkNotNull(store.read(id))
            check(item.record.phase==PendingAudioStore.Phase.RECORDING && item.record.captureFormat==PcmCapture.FORMAT)
            val minimum=AudioRecord.getMinBufferSize(PcmCapture.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            check(minimum>0)
            val rec=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(PcmCapture.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(minimum*2,PcmCapture.BYTES_PER_SECOND)).build()
            recorder=rec;check(rec.state==AudioRecord.STATE_INITIALIZED)
            if(Build.VERSION.SDK_INT>=29) {
                callback=object: AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        if(state?.id==id && state?.running==true && configs.any {it.clientAudioSessionId==rec.audioSessionId && it.isClientSilenced})
                            stopReason.compareAndSet(null,"다른 앱이나 시스템이 마이크 입력을 차단해 녹음을 중단했습니다.")
                    }
                }
                rec.registerAudioRecordingCallback(ContextCompat.getMainExecutor(this),callback)
            }
            sink=PcmCapture(store,item)
            rec.startRecording();check(rec.recordingState==AudioRecord.RECORDSTATE_RECORDING)
            val buffer=ByteArray(3200)
            var checkpoint=0L
            var lastData=android.os.SystemClock.elapsedRealtime()
            while(stopReason.get()==null) {
                val manager=getSystemService(AudioManager::class.java)
                if(manager.isMicrophoneMute || manager.mode in setOf(AudioManager.MODE_IN_CALL,AudioManager.MODE_IN_COMMUNICATION)) {
                    stopReason.compareAndSet(null,"마이크 음소거 또는 통화로 녹음을 중단했습니다.");break
                }
                val count=rec.read(buffer,0,buffer.size,AudioRecord.READ_NON_BLOCKING)
                check(count>=0 && rec.recordingState==AudioRecord.RECORDSTATE_RECORDING) { "Microphone read failed" }
                if(count>0) {sink.append(buffer,count);lastData=android.os.SystemClock.elapsedRealtime()}
                else {check(android.os.SystemClock.elapsedRealtime()-lastData<5000) { "Microphone stalled" };Thread.sleep(20)}
                duration=PcmCapture.durationMs(sink.bytes)
                if(sink.bytes-checkpoint>=PcmCapture.BYTES_PER_SECOND) {
                    sink.checkpoint();checkpoint=sink.bytes
                    val remaining=(SpeechProtocol.MAX_RECORDING_MS-duration)/1000
                    val reserve=PcmCapture.reserveBytes(duration)
                    val free=store.pcmFile(id).usableSpace
                    val info=when {
                        free<reserve+8_000_000L -> "저장 공간이 부족해지고 있습니다. 녹음을 마쳐 주세요."
                        remaining<=180 -> "전송 한도까지 약 ${remaining}초입니다. 한도에서 녹음을 마치고 보관합니다."
                        else -> "화면을 잠가도 녹음합니다. 중지하면 원본을 보관하며 자동 전사하지 않습니다."
                    }
                    state=State(id,item.record.inputKey,true,duration,info)
                    main.post {if(state?.id==id && state?.running==true) getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION,notification("저장한 음성 ${duration/1000}초 · $info"))}
                    if(free<reserve) stopReason.compareAndSet(null,"전송 파일을 만들 저장 공간을 남기고 녹음을 마쳤습니다.")
                    if(duration>=SpeechProtocol.MAX_RECORDING_MS) stopReason.compareAndSet(null,"앱의 20 MB 전송 한도에 맞춰 녹음을 마쳤습니다.")
                }
            }
            rec.stop()
            message=stopReason.get() ?: message
            sink.checkpoint(true,message.takeUnless {it=="사용자가 녹음을 마쳤습니다."})
        } catch(_: Exception) {
            message="녹음이 중단되었습니다. 파일에 기록된 음성은 보관했으며 마지막 저장 이후 구간은 복구할 때 확인합니다."
            runCatching {if(sink!=null) sink.checkpoint(true,message) else store.read(id)?.let {store.interrupted(it,message)}}
        } finally {
            if(Build.VERSION.SDK_INT>=29 && callback!=null) runCatching {recorder?.unregisterAudioRecordingCallback(callback)}
            runCatching {recorder?.release()};runCatching {sink?.close()}
            main.post {finish(id,duration,message)}
        }
    }
    private fun finish(id: String, duration: Long, message: String) {
        PendingAudioStore.end(id,holder)
        state=state?.takeIf {it.id==id}?.copy(running=false,durationMs=duration,message=message)
        if(wake?.isHeld==true) wake?.release()
        wake=null
        worker=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching {getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(message,false))}
        stopSelf()
    }
    override fun onDestroy() {
        stopReason.compareAndSet(null,"시스템이 녹음 서비스를 종료했습니다. 기록된 음성은 보관했습니다.")
        if(wake?.isHeld==true) wake?.release()
        wake=null
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL="voice-recording"
        private const val NOTIFICATION=7301
        private const val START="voice.start"
        private const val STOP="voice.stop"
        private val holder=Any()
        data class State(val id: String,val inputKey: String,val running: Boolean,val durationMs: Long,val message: String)
        @Volatile var state: State?=null; private set
        /** Only call from a visible, user-initiated action after RECORD_AUDIO is granted. */
        fun launch(context: Context, item: PendingAudioStore.Item) {
            check(state?.running!=true && PendingAudioStore.claim(item.record.id,holder))
            state=State(item.record.id,item.record.inputKey,true,0,"마이크를 준비하고 있습니다.")
            try {ContextCompat.startForegroundService(context,Intent(context,VoiceRecordingService::class.java)
                .setAction(START).putExtra("id",item.record.id))}
            catch(e: Exception) {PendingAudioStore.end(item.record.id,holder);state=null;throw e}
        }
        fun stop(context: Context, id: String) {
            if(state?.id==id && state?.running==true) context.startService(Intent(context,VoiceRecordingService::class.java)
                .setAction(STOP).putExtra("id",id))
        }
    }
}
