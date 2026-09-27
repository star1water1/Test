package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.ImageMeta
import com.novelcharacter.app.util.ImageDeletionService
import com.novelcharacter.app.util.RecoverableFileDelete
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageDeletionTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var directory: File
    private lateinit var image: File
    private var id = 0L
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        directory = File(context.cacheDir, "image-delete-test-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        image = File(directory, "image.jpg").apply { writeText("original image bytes") }
        id = db.characterDao().insert(Character(name = "Owner", code = "owner",
            imagePaths = Gson().toJson(listOf(image.absolutePath)), representativeImagePath = image.absolutePath))
        db.imageMetaDao().insert(ImageMeta(path = image.absolutePath))
        Unit
    }
    @After fun close() { db.close(); directory.deleteRecursively() }
    private suspend fun delete() = ImageDeletionService.delete(db, image.absolutePath,
        ImageDeletionService.Owners(characterIds = listOf(id)), null,
        recoveryDir = File(directory, RecoverableFileDelete.DIRECTORY))

    @Test fun successRemovesReferencesRepresentativeMetadataAndFile() = runBlocking {
        assertEquals(image.length(), delete())
        val character = db.characterDao().getCharacterById(id)!!
        assertEquals("[]", character.imagePaths)
        assertEquals("", character.representativeImagePath)
        assertNull(db.imageMetaDao().getByPath(image.absolutePath))
        assertFalse(image.exists())
    }
    @Test fun deferredConstraintFailureRestoresDatabaseAndOriginalBytes() = runBlocking {
        // This violation would fail at COMMIT. Reject it after staging, before Room ends the transaction.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_commit AFTER DELETE ON image_meta BEGIN INSERT INTO character_tags(characterId, tag) VALUES (999999, 'invalid owner'); END")
        db.openHelper.writableDatabase.execSQL("PRAGMA defer_foreign_keys = ON")
        db.openHelper.writableDatabase.query("PRAGMA defer_foreign_keys").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        assertNull(delete())
        assertEquals("original image bytes", image.readText())
        assertEquals(Gson().toJson(listOf(image.absolutePath)), db.characterDao().getCharacterById(id)!!.imagePaths)
        assertNotNull(db.imageMetaDao().getByPath(image.absolutePath))
    }
}
