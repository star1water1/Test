package com.novelcharacter.app.speech

import java.io.Closeable
import java.io.FileOutputStream

/** Headerless signed PCM16 LE, mono 16 kHz. Every complete frame survives without container finalization. */
class PcmCapture(private val store: PendingAudioStore, initial: PendingAudioStore.Item) : Closeable {
    var item = initial; private set
    var bytes = 0L; private set
    private val output: FileOutputStream
    init {
        check(initial.record.captureFormat==FORMAT && initial.record.phase==PendingAudioStore.Phase.RECORDING)
        val file=store.pcmFile(initial.record.id)
        check(file.createNewFile()) { "Never overwrite a previous capture" }
        output=FileOutputStream(file)
    }
    fun append(buffer: ByteArray, count: Int) {
        require(count in 0..buffer.size && count%2==0)
        output.write(buffer,0,count);bytes+=count
    }
    fun checkpoint(finished: Boolean = false, reason: String? = null): PendingAudioStore.Item {
        output.fd.sync()
        item=store.checkpoint(item,bytes,finished,reason)
        return item
    }
    override fun close() { output.close() } // Closing alone deliberately does not imply normal stop.
    companion object {
        const val FORMAT="pcm16le-mono-16000-v1"
        const val SAMPLE_RATE=16000
        const val BYTES_PER_SECOND=32000
        fun durationMs(bytes: Long)=bytes/2*1000/SAMPLE_RATE
        fun prefixBytes(length: Long)=length-length%2
        fun reserveBytes(durationMs: Long)=durationMs/1000*(SpeechProtocol.AAC_BIT_RATE/8)+4_000_000L
    }
}
