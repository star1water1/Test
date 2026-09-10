package com.novelcharacter.app.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.speech.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PcmRecoveryTest {
    /** Real AAC encoding AND decoding, with audible markers in the start, middle and end. */
    @Test fun unfinalizedPcmWithTailRecoversAllCompleteFramesWithoutChangingSource()=runBlocking {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pcm-test-${UUID.randomUUID()}")
        try {
            val store=PendingAudioStore(root)
            val item=store.create(UUID.randomUUID().toString(),"briefing:test",pcm=true)
            val pcm=ByteBuffer.allocate(6*32000).order(ByteOrder.LITTLE_ENDIAN)
            repeat(6*16000) {sample ->
                val frequency=when(sample/32000) {0->220;1->440;else->880}
                pcm.putShort((10000*kotlin.math.sin(2*Math.PI*frequency*sample/16000)).toInt().toShort())
            }
            PcmCapture(store,item).use {
                it.append(pcm.array().copyOfRange(0,32000),32000);it.checkpoint()
                it.append(pcm.array().copyOfRange(32000,pcm.capacity()),pcm.capacity()-32000)
            } // No finish/checkpoint for the last five seconds, like interrupted capture.
            store.pcmFile(item.record.id).appendBytes(byteArrayOf(99)) // Incomplete frame is preserved.
            val original=store.pcmFile(item.record.id).readBytes()
            val ready=PcmEncoding.prepare(PendingAudioStore(root),store.read(item.record.id)!!)
            assertArrayEquals(original,store.pcmFile(item.record.id).readBytes())
            assertEquals(6000L,ready.record.durationMs)
            assertEquals(32000L,ready.record.checkpointBytes)
            val decoded=decode(store.verifiedFile(ready))
            assertTrue("Decoded ${decoded.size} samples",decoded.size in 95000..99000)
            listOf(220,440,880).forEachIndexed {index,hz ->
                val from=(index*2*16000)+8000
                val crossings=(from+1 until from+16000).count {decoded[it-1]<=0 && decoded[it]>0}
                assertTrue("section=$index expected=$hz crossings=$crossings",kotlin.math.abs(crossings-hz)<15)
            }
        } finally {root.deleteRecursively()}
    }
    @Test fun encodingFailureAndMissingCheckpointedBytesRetainOriginalAndRecord()=runBlocking {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pcm-test-${UUID.randomUUID()}")
        try {
            val store=PendingAudioStore(root)
            val item=store.create(UUID.randomUUID().toString(),"briefing:test",pcm=true)
            PcmCapture(store,item).use {it.append(ByteArray(32000),32000);it.checkpoint(true)}
            val saved=store.read(item.record.id)!!
            store.encodingFile(item.record.id).mkdir() // Codec/muxer cannot create its rendition.
            var failed=false
            try {PcmEncoding.prepare(store,saved)} catch(_:Exception) {failed=true}
            assertTrue(failed);assertEquals(saved,store.read(item.record.id));assertEquals(32000L,store.pcmFile(item.record.id).length())
            store.pcmFile(item.record.id).writeBytes(ByteArray(100))
            failed=false
            try {PcmEncoding.prepare(store,saved)} catch(_:Exception) {failed=true}
            assertTrue(failed);assertEquals(100L,store.pcmFile(item.record.id).length())
        } finally {root.deleteRecursively()}
    }
    private fun decode(file: File): ShortArray {
        val extractor=MediaExtractor();extractor.setDataSource(file.absolutePath);extractor.selectTrack(0)
        val format=extractor.getTrackFormat(0)
        val codec=MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
        val bytes=ByteArrayOutputStream()
        try {
            codec.configure(format,null,null,0);codec.start()
            var sentEnd=false;var done=false
            val info=MediaCodec.BufferInfo();val deadline=System.nanoTime()+20_000_000_000L
            while(!done && System.nanoTime()<deadline) {
                if(!sentEnd) {
                    val index=codec.dequeueInputBuffer(10000)
                    if(index>=0) {
                        val input=checkNotNull(codec.getInputBuffer(index));input.clear()
                        val count=extractor.readSampleData(input,0)
                        if(count<0) {codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);sentEnd=true}
                        else {codec.queueInputBuffer(index,0,count,extractor.sampleTime,0);extractor.advance()}
                    }
                }
                val index=codec.dequeueOutputBuffer(info,10000)
                if(index>=0) {
                    val out=checkNotNull(codec.getOutputBuffer(index));out.position(info.offset);out.limit(info.offset+info.size)
                    val chunk=ByteArray(info.size);out.get(chunk);bytes.write(chunk)
                    done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(index,false)
                }
            }
            check(done);codec.stop()
        } finally {codec.release();extractor.release()}
        val buffer=ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also {buffer.get(it)}
    }
}
