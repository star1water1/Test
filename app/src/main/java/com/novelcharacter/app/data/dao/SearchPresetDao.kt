package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.SearchPreset

@Dao
interface SearchPresetDao {
    @Query("SELECT * FROM search_presets ORDER BY isDefault DESC, updatedAt DESC")
    fun getAllPresets(): LiveData<List<SearchPreset>>

    @Query("SELECT * FROM search_presets ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAllPresetsList(): List<SearchPreset>

    @Query("SELECT * FROM search_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): SearchPreset?

    @Query("SELECT COUNT(*) FROM search_presets")
    suspend fun getPresetCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: SearchPreset): Long

    @Update
    suspend fun update(preset: SearchPreset)

    @Query("DELETE FROM search_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM search_presets WHERE isDefault = 0")
    suspend fun deleteAllUserPresets()
}
