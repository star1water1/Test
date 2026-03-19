package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.UserPresetTemplate

@Dao
interface UserPresetTemplateDao {
    @Query("SELECT * FROM user_preset_templates ORDER BY updatedAt DESC")
    fun getAllTemplates(): LiveData<List<UserPresetTemplate>>

    @Query("SELECT * FROM user_preset_templates ORDER BY updatedAt DESC")
    suspend fun getAllTemplatesList(): List<UserPresetTemplate>

    @Query("SELECT * FROM user_preset_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): UserPresetTemplate?

    @Insert
    suspend fun insert(template: UserPresetTemplate): Long

    @Update
    suspend fun update(template: UserPresetTemplate)

    @Delete
    suspend fun delete(template: UserPresetTemplate)
}
