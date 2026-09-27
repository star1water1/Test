package com.novelcharacter.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackupPublicationTest {
    @Test fun simultaneousAttemptsHaveDistinctNamesAndFailureRetainsCompletedBackup() {
        val dir = Files.createTempDirectory("backup-publish").toFile()
        try {
            val first = BackupPublication.destination(dir, "NovelCharacter_AutoBackup_", "20260927_000000", ".enc")
            val second = BackupPublication.destination(dir, "NovelCharacter_AutoBackup_", "20260927_000000", ".enc")
            assertNotEquals(first, second)
            val temporary = File(dir, "encrypted.part").apply { writeText("complete") }
            BackupPublication.publish(temporary, first)
            // A failed attempt's cleanup touches its own temporary file only.
            val failed = File(dir, "failed.part").apply { writeText("partial") }
            failed.delete()
            assertEquals("complete", first.readText())
            assertFalse(second.exists())
            assertFalse(temporary.exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun publishingCannotReplaceAnExistingDestination() {
        val dir = Files.createTempDirectory("backup-collision").toFile()
        try {
            val target = File(dir, "backup.enc").apply { writeText("old") }
            val partial = File(dir, "part").apply { writeText("new") }
            assertThrows(java.io.IOException::class.java) { BackupPublication.publish(partial, target) }
            assertEquals("old", target.readText())
            assertEquals("new", partial.readText())
        } finally { dir.deleteRecursively() }
    }
}
