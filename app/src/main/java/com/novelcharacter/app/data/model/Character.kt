package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("novelId"), Index(value = ["code"], unique = true),
               Index(value = ["novelId", "isPinned", "displayOrder"]),
               Index(value = ["isPinned", "displayOrder"])]
)
data class Character(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val firstName: String = "",
    val lastName: String = "",
    val anotherName: String = "",
    val novelId: Long? = null,
    val imagePaths: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val memo: String = "",
    val code: String = generateEntityCode(),
    val displayOrder: Long = 0,
    val isPinned: Boolean = false
) {
    /** 표시용 이름: firstName + lastName이 있으면 조합, 없으면 name 사용 */
    @Ignore
    val displayName: String = if (firstName.isNotBlank() || lastName.isNotBlank()) {
        listOf(lastName, firstName).filter { it.isNotBlank() }.joinToString(" ")
    } else {
        name
    }

    /** 이명/별칭 목록 (콤마 구분 파싱) */
    @Ignore
    val aliases: List<String> = if (anotherName.isNotBlank()) {
        anotherName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        emptyList()
    }
}
