package com.novelcharacter.app.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 최근 활동의 제목은 원본의 지금 이름을 따른다 — 이름을 바꾼 뒤 홈이 옛 이름을 보이지 않는다. */
@RunWith(AndroidJUnit4::class)
class RecentActivityLiveTitleTest {
    private lateinit var db: AppDatabase

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java
        ).build()
        db.universeDao().insert(Universe(id = 1, name = "World", code = "world"))
        db.novelDao().insert(Novel(id = 10, title = "Book", universeId = 1, code = "book"))
        db.characterDao().insert(Character(id = 100, name = "Alice", novelId = 10, code = "alice"))
        val dao = db.recentActivityDao()
        dao.upsert(RecentActivity(entityType = RecentActivity.TYPE_UNIVERSE, entityId = 1, title = "World", accessedAt = 1))
        dao.upsert(RecentActivity(entityType = RecentActivity.TYPE_NOVEL, entityId = 10, title = "Book", accessedAt = 2))
        dao.upsert(RecentActivity(entityType = RecentActivity.TYPE_CHARACTER, entityId = 100, title = "Alice", accessedAt = 3))
        Unit
    }

    @After fun close() { db.close() }

    private fun titles() = runBlocking {
        db.recentActivityDao().getRecentActivitiesList().associate { it.entityType to it.title }
    }

    @Test fun renamedEntitiesShowCurrentNames() = runBlocking {
        db.characterDao().update(db.characterDao().getCharacterById(100)!!.copy(name = "Alicia"))
        db.novelDao().update(db.novelDao().getNovelById(10)!!.copy(title = "Book II"))
        db.universeDao().update(db.universeDao().getUniverseById(1)!!.copy(name = "World Prime"))

        val t = titles()
        assertEquals("Alicia", t[RecentActivity.TYPE_CHARACTER])
        assertEquals("Book II", t[RecentActivity.TYPE_NOVEL])
        assertEquals("World Prime", t[RecentActivity.TYPE_UNIVERSE])
    }

    @Test fun orderAndRecordedTitleFallbackArePreserved() = runBlocking {
        db.recentActivityDao().upsert(
            RecentActivity(entityType = RecentActivity.TYPE_CHARACTER, entityId = 999, title = "Gone", accessedAt = 4)
        )
        val rows = db.recentActivityDao().getRecentActivitiesList()
        assertEquals(listOf(999L, 100L, 10L, 1L), rows.map { it.entityId })
        assertEquals("Gone", rows.first().title)
        assertEquals(4L, rows.first().accessedAt)
    }
}
