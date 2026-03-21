package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Novel

@Dao
interface NovelDao {
    // Pinned-first sorting: isPinned DESC ensures pinned items appear at top
    @Query("SELECT * FROM novels ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    fun getAllNovels(): LiveData<List<Novel>>

    @Query("SELECT * FROM novels ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    suspend fun getAllNovelsList(): List<Novel>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun getNovelById(id: Long): Novel?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(novel: Novel): Long

    @Update
    suspend fun update(novel: Novel)

    @Delete
    suspend fun delete(novel: Novel)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>>

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel>

    @Query("SELECT universeId, COUNT(*) as cnt FROM novels WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): List<UniverseCount>

    @Query("SELECT * FROM novels WHERE title = :title AND universeId = :universeId LIMIT 1")
    suspend fun getNovelByTitleAndUniverse(title: String, universeId: Long): Novel?

    @Query("SELECT * FROM novels WHERE title = :title AND universeId IS NULL LIMIT 1")
    suspend fun getNovelByTitleNoUniverse(title: String): Novel?

    @Query("SELECT * FROM novels WHERE code = :code LIMIT 1")
    suspend fun getNovelByCode(code: String): Novel?

    @Query("SELECT * FROM novels WHERE title LIKE '%' || :query || '%' ESCAPE '\\' OR description LIKE '%' || :query || '%' ESCAPE '\\'")
    fun searchNovels(query: String): LiveData<List<Novel>>

    @Update
    suspend fun updateAll(novels: List<Novel>)

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM novels WHERE universeId = :universeId")
    suspend fun getNextDisplayOrderInUniverse(universeId: Long): Long

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM novels WHERE universeId IS NULL")
    suspend fun getNextDisplayOrderNoUniverse(): Long

    @Query("UPDATE novels SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Long, isPinned: Boolean)

    /** 삭제된 캐릭터를 참조하는 imageCharacterId를 null로 정리 */
    @Query("UPDATE novels SET imageCharacterId = NULL, imageMode = 'none' WHERE imageCharacterId = :characterId")
    suspend fun clearImageCharacterRef(characterId: Long)
}

data class UniverseCount(val universeId: Long, val cnt: Int)
