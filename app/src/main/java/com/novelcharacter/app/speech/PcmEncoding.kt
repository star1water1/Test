package com.novelcharacter.app.speech

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A disposable upload rendition. The source (including any incomplete tail) is never changed. */
object PcmEncoding {
    suspend fun prepare(store: PendingAudioStore, item: PendingAudioStore.Item): PendingAudioStore.Item = withContext(Dispatchers.IO) {
        check(store.read(item.record.id)?.revision==item.revision)
        if(item.record.captureFormat==null) return@withContext store.ready(item,PendingAudio.durationMs(store.file(item.record.id)))
        check(item.record.captureFormat==PcmCapture.FORMAT)
        check(item.record.phase in setOf(PendingAudioStore.Phase.RECORDING,PendingAudioStore.Phase.CAPTURED,PendingAudioStore.Phase.INTERRUPTED))
        val source=store.pcmFile(item.record.id)
        val bytes=PcmCapture.prefixBytes(source.length())
        check(bytes>0 && bytes>= (item.record.checkpointBytes ?: 0))
        FileOutputStream(source,true).use {it.fd.sync()}
        val temp=store.encodingFile(item.record.id)
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer?=null
        var muxStarted=false
        try {
            val writer=MediaMuxer(temp.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer=writer
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,PcmCapture.SAMPLE_RATE,1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE,SpeechProtocol.AAC_BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            source.inputStream().use {input ->
                var consumed=0L;var sentEnd=false;var done=false;var track=-1
                var progress=System.nanoTime()
                val info=MediaCodec.BufferInfo()
                val buffer=ByteArray(4096)
                while(!done) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime()-progress<30_000_000_000L) { "Encoder stalled" }
                    if(!sentEnd) {
                        val index=codec.dequeueInputBuffer(10000)
                        if(index>=0) {
                            val target=checkNotNull(codec.getInputBuffer(index));target.clear()
                            val count=minOf(bytes-consumed,buffer.size.toLong(),PcmCapture.prefixBytes(target.remaining().toLong())).toInt()
                            if(count>0) {
                                var read=0
                                while(read<count) {val n=input.read(buffer,read,count-read);check(n>0);read+=n}
                                target.put(buffer,0,count)
                            }
                            codec.queueInputBuffer(index,0,count,consumed/2*1_000_000/PcmCapture.SAMPLE_RATE,
                                if(count==0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            consumed+=count;sentEnd=count==0;progress=System.nanoTime()
                        }
                    }
                    val output=codec.dequeueOutputBuffer(info,10000)
                    if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxStarted);track=writer.addTrack(codec.outputFormat);writer.start();muxStarted=true
                    } else if(output>=0) {
                        if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                            check(muxStarted);writer.writeSampleData(track,checkNotNull(codec.getOutputBuffer(output)),info)
                        }
                        done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                        codec.releaseOutputBuffer(output,false);progress=System.nanoTime()
                    }
                }
                check(consumed==bytes && PcmCapture.prefixBytes(source.length())==bytes)
            }
            codec.stop();writer.stop();muxStarted=false
            writer.release();muxer=null
            FileOutputStream(temp,true).use {it.fd.sync()}
            val duration=PendingAudio.durationMs(temp)
            check(kotlin.math.abs(duration-PcmCapture.durationMs(bytes))<=250) { "Incomplete encoded duration" }
            currentCoroutineContext().ensureActive()
            check(store.read(item.record.id)?.revision==item.revision)
            Files.move(temp.toPath(),store.file(item.record.id).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            store.ready(item,PcmCapture.durationMs(bytes))
        } finally {
            codec.release()
            if(muxStarted) runCatching {muxer?.stop()}
            muxer?.release()
        }
    }
}
