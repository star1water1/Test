package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Novel

@Dao
interface NovelDao {
    @Query("SELECT * FROM novels ORDER BY createdAt DESC")
    fun getAllNovels(): LiveData<List<Novel>>

    @Query("SELECT * FROM novels ORDER BY createdAt DESC")
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

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY createdAt DESC")
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>>

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY createdAt DESC")
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel>

    @Query("SELECT universeId, COUNT(*) as cnt FROM novels WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): List<UniverseCount>

    @Query("SELECT * FROM novels WHERE title = :title AND universeId = :universeId LIMIT 1")
    suspend fun getNovelByTitleAndUniverse(title: String, universeId: Long): Novel?

    @Query("SELECT * FROM novels WHERE title = :title AND universeId IS NULL LIMIT 1")
    suspend fun getNovelByTitleNoUniverse(title: String): Novel?

    @Query("SELECT * FROM novels WHERE code = :code LIMIT 1")
    suspend fun getNovelByCode(code: String): Novel?

    @Query("SELECT * FROM novels WHERE title LIKE '%' || :query || '%' OR description LIKE '%' || :query || '%'")
    fun searchNovels(query: String): LiveData<List<Novel>>
}

data class UniverseCount(val universeId: Long, val cnt: Int)
