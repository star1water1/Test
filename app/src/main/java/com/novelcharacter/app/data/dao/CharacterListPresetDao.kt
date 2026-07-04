package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterListPreset

@Dao
interface CharacterListPresetDao {
    @Query("SELECT * FROM character_list_presets ORDER BY isDefault DESC, updatedAt DESC")
    fun getAllPresets(): LiveData<List<CharacterListPreset>>

    @Query("SELECT * FROM character_list_presets ORDER BY isDefault DESC, updatedAt DESC")
    suspend fun getAllPresetsList(): List<CharacterListPreset>

    @Query("SELECT * FROM character_list_presets WHERE id = :id")
    suspend fun getPresetById(id: Long): CharacterListPreset?

    @Query("SELECT COUNT(*) FROM character_list_presets")
    suspend fun getPresetCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: CharacterListPreset): Long

    @Update
    suspend fun update(preset: CharacterListPreset)

    @Query("DELETE FROM character_list_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM character_list_presets")
    suspend fun deleteAll()
}
