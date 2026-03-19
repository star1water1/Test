package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 사용자 정의 프리셋 템플릿.
 * 세계관 필드 구성을 저장하고 재사용할 수 있다.
 */
@Entity(
    tableName = "user_preset_templates",
    indices = [Index("name"), Index("createdAt")]
)
data class UserPresetTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val fieldsJson: String = "[]", // JSON array of FieldDefinition-like objects
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
