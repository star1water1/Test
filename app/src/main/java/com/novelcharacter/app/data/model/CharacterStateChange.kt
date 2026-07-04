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
    indices = [Index("characterId"), Index("year"), Index("fieldKey"), Index(value = ["code"], unique = true)]
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
    val createdAt: Long = System.currentTimeMillis(),
    // 엑셀 왕복 안정 식별자 — 자연키(캐릭터+연도+필드키+새 값) 편집이 중복 생성으로 이어지지 않게 하는 기준.
    // nullable인 이유: v35 이전 Gson 스냅샷(휴지통) 역직렬화 시 null이 주입될 수 있다 (레거시 수용).
    val code: String? = generateEntityCode()
) {
    companion object {
        const val KEY_BIRTH = "__birth"
        const val KEY_DEATH = "__death"
        const val KEY_ALIVE = "__alive"
        const val KEY_AGE = "__age"
    }
}
