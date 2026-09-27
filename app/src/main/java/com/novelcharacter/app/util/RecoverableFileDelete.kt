package com.novelcharacter.app.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Keep the bytes until the database commits. Uncertain interrupted attempts restore them. */
class RecoverableFileDelete private constructor(private val directory: File, private val original: File) {
    private val payload = File(directory, "image")
    private val committed = File(directory, "committed")

    fun rollback(): Boolean {
        if (payload.exists()) {
            // Never replace a newer file that appeared at the original path.
            if (original.exists() || !payload.renameTo(original)) return false
        }
        return cleanup()
    }

    fun commit(): Boolean {
        // A crash before this marker leaves an extra preserved file, never missing image bytes.
        FileOutputStream(committed).use { it.write(1); it.fd.sync() }
        return (!payload.exists() || payload.delete()) && cleanup()
    }

    private fun cleanup(): Boolean {
        File(directory, "path").delete()
        committed.delete()
        return directory.delete()
    }

    companion object {
        const val DIRECTORY = "pending_image_deletes"

        fun stage(original: File, root: File): RecoverableFileDelete? {
            if (!original.exists()) return null
            val directory = File(root, UUID.randomUUID().toString())
            if (!directory.mkdirs()) throw IOException("Cannot stage image deletion")
            val deletion = RecoverableFileDelete(directory, original)
            try {
                FileOutputStream(File(directory, "path")).use {
                    it.write(original.absolutePath.toByteArray(Charsets.UTF_8))
                    it.fd.sync()
                }
                if (!original.renameTo(deletion.payload)) throw IOException("Cannot stage image file")
                return deletion
            } catch (e: Exception) {
                deletion.rollback()
                throw e
            }
        }

        /** Returns unresolved attempts; callers retain them and can retry on the next launch. */
        fun recover(root: File): Int {
            var unresolved = 0
            for (directory in root.listFiles().orEmpty()) {
                if (!directory.isDirectory) continue
                try {
                    val path = File(directory, "path")
                    if (!path.exists()) {
                        // Cleanup may have been interrupted after deleting the path receipt.
                        if (!File(directory, "image").exists()) File(directory, "committed").delete()
                        if (!directory.delete()) unresolved++
                        continue
                    }
                    val deletion = RecoverableFileDelete(directory, File(path.readText(Charsets.UTF_8)))
                    val done = if (deletion.committed.exists()) deletion.commit() else deletion.rollback()
                    if (!done) unresolved++
                } catch (_: Exception) {
                    unresolved++
                }
            }
            return unresolved
        }
    }
}
