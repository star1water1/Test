package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.repository.TrashRepository
import com.novelcharacter.app.data.repository.UniverseRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 묶음 복원은 한 트랜잭션이다 (B-18) — 전부 되살아나거나, 아무것도 바뀌지 않는다. */
@RunWith(AndroidJUnit4::class)
class TrashOperationRestoreTest {
    private lateinit var db: AppDatabase
    private lateinit var operation: String

    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val universeId = db.universeDao().insert(Universe(name = "가람"))
        val novelId = db.novelDao().insert(Novel(title = "첫 권", universeId = universeId))
        listOf("하나", "둘", "셋").forEach { db.characterDao().insert(Character(name = it, novelId = novelId)) }
        UniverseRepository(db, db.universeDao(), db.fieldDefinitionDao(), db.novelDao())
            .deleteUniverse(db.universeDao().getUniverseById(universeId)!!)
        assertTrue(db.universeDao().getAllUniversesList().isEmpty())
        db.openHelper.readableDatabase.query("SELECT DISTINCT operationId FROM trash_snapshots").use {
            assertEquals(1, it.count); it.moveToFirst(); operation = it.getString(0)
        }
    }

    @After fun close() = db.close()

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun everythingComesBackAndProgressReachesTheTotal() = runBlocking {
        val total = count("trash_snapshots")
        val seen = ArrayList<Pair<Int, Int>>()
        val result = TrashRepository(db).restoreOperation(operation) { done, all -> seen += done to all }
        assertTrue(result.failed.isEmpty())
        assertEquals(total, result.restored.size)
        assertEquals((1..total).map { it to total }, seen)
        assertEquals(0, count("trash_snapshots"))
        assertEquals(1, count("universes")); assertEquals(1, count("novels")); assertEquals(3, count("characters"))
        assertNotNull(db.characterDao().getAllCharactersList().single { it.name == "셋" }.novelId)
    }

    /** 마지막 캐릭터에서 실패한다 — 그 앞에 되살린 세계관·작품·캐릭터까지 전부 되돌려져야 한다. */
    @Test fun aFailureInTheMiddleLeavesNothingRestoredAndEverySnapshotInTheTrash() = runBlocking {
        val total = count("trash_snapshots")
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_third BEFORE INSERT ON characters WHEN NEW.name = '셋' " +
                "BEGIN SELECT RAISE(ABORT, 'restore test failure'); END")
        try {
            TrashRepository(db).restoreOperation(operation)
            fail("A failed item must abort the whole operation")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("restore test failure"))
        }
        assertEquals(total, count("trash_snapshots"))
        assertEquals(0, count("universes")); assertEquals(0, count("novels")); assertEquals(0, count("characters"))
        assertNull(db.universeDao().getAllUniversesList().firstOrNull())

        // 원인이 사라지면 같은 묶음이 그대로 복원된다 — 실패가 스냅샷을 상하게 하지 않았다.
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_third")
        assertEquals(total, TrashRepository(db).restoreOperation(operation).restored.size)
        assertEquals(3, count("characters"))
    }
}
