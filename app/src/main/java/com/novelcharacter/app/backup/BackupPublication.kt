package com.novelcharacter.app.backup

import java.io.File
import java.io.IOException
import java.util.UUID

/** Each attempt owns its final name; only a complete encrypted file is published. */
object BackupPublication {
    fun destination(directory: File, prefix: String, timestamp: String, extension: String): File =
        File(directory, "$prefix${timestamp}_${UUID.randomUUID()}$extension")

    fun publish(temporary: File, destination: File) {
        if (destination.exists() || !temporary.renameTo(destination)) {
            throw IOException("Failed to finalize backup file: ${destination.name}")
        }
    }
}
