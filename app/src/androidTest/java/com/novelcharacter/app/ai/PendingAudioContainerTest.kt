package com.novelcharacter.app.ai

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.speech.PendingAudio
import com.novelcharacter.app.speech.PendingAudioStore
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingAudioContainerTest {
    @Test fun finalizedAacSurvivesFreshStoreWithReadableDurationAndIdenticalBytes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"audio-container-test-${UUID.randomUUID()}")
        try {
            val store=PendingAudioStore(root)
            val item=store.create(UUID.randomUUID().toString(),"briefing:test")
            val file=store.file(item.record.id)
            encodeOneSecond(file)
            val before=file.readBytes()
            val duration=PendingAudio.durationMs(file)
            assertTrue("duration=$duration",duration in 900..1200)
            store.ready(item,duration)
            val restored=PendingAudioStore(root).read(item.record.id)!!
            val recovered=PendingAudioStore(root).verifiedFile(restored)
            assertArrayEquals(before,recovered.readBytes())
            assertEquals(duration,PendingAudio.durationMs(recovered))
        } finally {root.deleteRecursively()}
    }
    @Test fun nonemptyUnfinalizedContainerIsNotReportedAsRecoveredAudio() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"invalid-audio-${UUID.randomUUID()}.m4a")
        try {
            file.writeBytes(byteArrayOf(0,0,0,20,102,116,121,112))
            var refused=false
            try {PendingAudio.durationMs(file)} catch(_: Exception) {refused=true}
            assertTrue(refused);assertTrue(file.exists())
        } finally {file.delete()}
    }
    /** Actual platform AAC/m4a fixture, generated from synthetic PCM without microphone access. */
    private fun encodeOneSecond(file:File) {
        val codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer=MediaMuxer(file.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started=false
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,16000,1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE,64000)
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            var samples=0;var sentEnd=false;var track=-1;var done=false
            val info=MediaCodec.BufferInfo()
            val deadline=System.nanoTime()+10_000_000_000L
            while(!done && System.nanoTime()<deadline) {
                if(!sentEnd) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val buffer=codec.getInputBuffer(index)!!;buffer.clear()
                        val count=minOf(1024,16000-samples)
                        repeat(count) {buffer.putShort(if((samples+it)%32<16) 2000 else -2000)}
                        val flags=if(count==0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index,0,count*2,samples*1_000_000L/16000,flags)
                        samples+=count;sentEnd=count==0
                    }
                }
                val output=codec.dequeueOutputBuffer(info,10000)
                if(output==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track=muxer.addTrack(codec.outputFormat);muxer.start();started=true
                } else if(output>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        check(started);muxer.writeSampleData(track,codec.getOutputBuffer(output)!!,info)
                    }
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0
                    codec.releaseOutputBuffer(output,false)
                }
            }
            check(done)
            codec.stop()
        } finally {
            codec.release()
            if(started) muxer.stop()
            muxer.release()
        }
    }
}
