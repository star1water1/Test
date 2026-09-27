package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.data.model.*
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterFormSaveTest {
    private lateinit var db: AppDatabase
    private lateinit var saver: CharacterFormSaver
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java).build()
        val characters = CharacterRepository(db, db.characterDao(), db.characterFieldValueDao(),
            db.characterStateChangeDao(), db.characterTagDao(), db.characterRelationshipDao(), db.nameBankDao())
        saver = CharacterFormSaver(db, characters,
            UniverseRepository(db, db.universeDao(), db.fieldDefinitionDao(), db.novelDao()),
            NovelRepository(db, db.novelDao()))
        db.universeDao().insert(Universe(id = 1, name = "World", code = "world"))
        db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1, code = "book"))
        db.fieldDefinitionDao().insert(FieldDefinition(id = 20, universeId = 1, key = "visible", name = "Visible", type = FieldType.TEXT.name))
        db.fieldDefinitionDao().insert(FieldDefinition(id = 21, universeId = 1, key = "hidden", name = "Hidden", type = FieldType.TEXT.name))
        Unit
    }
    @After fun close() { db.close() }

    @Test fun failedFieldInsertRollsBackNewCharacterAndAllowsSingleRetry() = runBlocking {
        val character = Character(name = "Alice", novelId = 10, code = "alice")
        try {
            saver.save(character, listOf(CharacterFieldValue(characterId = -1, fieldDefinitionId = 999, value = "bad")),
                listOf("tag"), setOf(20), false, null, false)
            fail("Missing field must reject save")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertTrue(db.characterDao().getAllCharactersList().isEmpty())
        assertTrue(db.characterTagDao().getAllTagsList().isEmpty())
        val saved = saver.save(character, listOf(CharacterFieldValue(characterId = -1, fieldDefinitionId = 20, value = "good")),
            listOf("tag"), setOf(20), false, null, false)
        assertEquals(1, db.characterDao().getAllCharactersList().size)
        assertEquals("good", db.characterFieldValueDao().getValuesByCharacterList(saved.id).single().value)
        assertEquals("tag", db.characterTagDao().getAllTagsList().single().tag)
    }

    @Test fun failedTagWriteRollsBackNameAndFields() = runBlocking {
        val id = db.characterDao().insert(Character(name = "Before", novelId = 10, code = "alice"))
        db.characterFieldValueDao().insert(CharacterFieldValue(characterId = id, fieldDefinitionId = 20, value = "before"))
        db.characterTagDao().insert(CharacterTag(characterId = id, tag = "before"))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_tag BEFORE INSERT ON character_tags WHEN NEW.tag = 'reject' BEGIN SELECT RAISE(ABORT, 'tag failure'); END")
        try {
            saver.save(db.characterDao().getCharacterById(id)!!.copy(name = "After"),
                listOf(CharacterFieldValue(characterId = id, fieldDefinitionId = 20, value = "after")),
                listOf("reject"), setOf(20), true, null, false)
            fail("Trigger must reject save")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals("Before", db.characterDao().getCharacterById(id)!!.name)
        assertEquals("before", db.characterFieldValueDao().getValuesByCharacterList(id).single().value)
        assertEquals("before", db.characterTagDao().getAllTagsList().single().tag)
    }

    @Test fun editingCoveredFieldsPreservesUnrenderedValuesAndStableCode() = runBlocking {
        val id = db.characterDao().insert(Character(name = "Before", novelId = 10, code = "alice"))
        db.characterFieldValueDao().insert(CharacterFieldValue(characterId = id, fieldDefinitionId = 20, value = "before"))
        db.characterFieldValueDao().insert(CharacterFieldValue(characterId = id, fieldDefinitionId = 21, value = "hidden value"))
        val saved = saver.save(db.characterDao().getCharacterById(id)!!.copy(name = "After"),
            listOf(CharacterFieldValue(characterId = id, fieldDefinitionId = 20, value = "after")),
            listOf("new tag"), setOf(20), true, null, false)
        assertEquals(1, saved.preserved)
        assertEquals("alice", db.characterDao().getCharacterById(id)!!.code)
        assertEquals(mapOf(20L to "after", 21L to "hidden value"),
            db.characterFieldValueDao().getValuesByCharacterList(id).associate { it.fieldDefinitionId to it.value })
    }
}
