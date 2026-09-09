package com.novelcharacter.app.speech

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

object PendingAudio {
    fun store(context: Context) = PendingAudioStore(File(context.noBackupFilesDir, "pending-voice"),
        File(context.cacheDir, "voice-input"))

    /** Validate a readable audio track, not merely a nonempty container. No upload on failure. */
    fun durationMs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    extractor.selectTrack(index)
                    check(extractor.sampleTime >= 0)
                    val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    check(duration > 0)
                    return duration
                }
            }
            error("No readable audio track")
        } finally { extractor.release() }
    }
}
