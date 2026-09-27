package com.novelcharacter.app.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RecoverableFileDeleteTest {
    private fun fixture(block: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("image-delete").toFile()
        try {
            block(File(directory, "original.jpg").apply { writeText("image bytes") }, File(directory, "journal"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun transactionFailureRestoresOriginalBytes() = fixture { image, root ->
        val attempt = RecoverableFileDelete.stage(image, root)!!
        assertFalse(image.exists())
        assertTrue(attempt.rollback())
        assertEquals("image bytes", image.readText())
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun successfulCommitRemovesImageAndJournal() = fixture { image, root ->
        assertTrue(RecoverableFileDelete.stage(image, root)!!.commit())
        assertFalse(image.exists())
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun processDeathBeforeCommitReceiptRestoresBytesConservatively() = fixture { image, root ->
        RecoverableFileDelete.stage(image, root)
        assertEquals(0, RecoverableFileDelete.recover(root))
        assertEquals("image bytes", image.readText())
    }
    @Test fun recoveryFinishesConfirmedDeletion() = fixture { image, root ->
        RecoverableFileDelete.stage(image, root)
        File(root.listFiles()!!.single(), "committed").writeText("1")
        assertEquals(0, RecoverableFileDelete.recover(root))
        assertFalse(image.exists())
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun rollbackDoesNotOverwriteANewerOriginal() = fixture { image, root ->
        val attempt = RecoverableFileDelete.stage(image, root)!!
        image.writeText("new image")
        assertFalse(attempt.rollback())
        assertEquals("new image", image.readText())
        assertEquals("image bytes", File(root.listFiles()!!.single(), "image").readText())
        assertEquals(1, RecoverableFileDelete.recover(root))
    }
}
