package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "novels",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["imageCharacterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("universeId"), Index("createdAt"), Index(value = ["code"], unique = true), Index("imageCharacterId")]
)
data class Novel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val universeId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val code: String = generateEntityCode(),
    val displayOrder: Long = 0,
    val borderColor: String = "",
    val borderWidthDp: Float = 1.5f,
    val inheritUniverseBorder: Boolean = true,
    val isPinned: Boolean = false,
    val imagePath: String = "",           // 직접 등록한 이미지 경로
    val imageMode: String = "none",       // none, custom, random_character, select_character
    val imageCharacterId: Long? = null    // select_character 모드에서 선택된 캐릭터 ID
) {
    companion object {
        const val IMAGE_MODE_NONE = "none"
        const val IMAGE_MODE_CUSTOM = "custom"
        const val IMAGE_MODE_RANDOM_CHARACTER = "random_character"
        const val IMAGE_MODE_SELECT_CHARACTER = "select_character"
    }
}
