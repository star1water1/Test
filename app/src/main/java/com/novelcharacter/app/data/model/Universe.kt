package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "universes",
    indices = [Index("name"), Index("createdAt"), Index(value = ["code"], unique = true)]
)
data class Universe(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val code: String = generateEntityCode(),
    val displayOrder: Long = 0,
    val borderColor: String = "",
    val borderWidthDp: Float = 1.5f,
    val imagePath: String = "",       // 직접 등록한 이미지 경로
    val imageMode: String = "none"    // none, custom, random_character
) {
    companion object {
        const val IMAGE_MODE_NONE = "none"
        const val IMAGE_MODE_CUSTOM = "custom"
        const val IMAGE_MODE_RANDOM_CHARACTER = "random_character"
    }
}
