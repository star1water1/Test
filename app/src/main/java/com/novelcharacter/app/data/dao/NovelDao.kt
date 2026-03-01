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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(novel: Novel): Long

    @Update
    suspend fun update(novel: Novel)

    @Delete
    suspend fun delete(novel: Novel)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: Long)
}
