package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_state_changes",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("characterId"), Index("year")]
)
data class CharacterStateChange(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val fieldKey: String,          // 필드의 key 또는 특수키: __birth, __death, __alive
    val newValue: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KEY_BIRTH = "__birth"
        const val KEY_DEATH = "__death"
        const val KEY_ALIVE = "__alive"
        const val KEY_AGE = "__age"
    }
}
