package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "field_definitions",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("universeId")]
)
data class FieldDefinition(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val universeId: Long,
    val key: String,               // 고유 키: "mana_affinity"
    val name: String,              // 표시 이름: "마나친화"
    val type: String,              // FieldType.name: TEXT, NUMBER, SELECT, MULTI_TEXT, GRADE, CALCULATED, BODY_SIZE
    val config: String = "{}",     // JSON: 타입별 설정
    val groupName: String = "기본 정보",
    val displayOrder: Int = 0,
    val isRequired: Boolean = false
)
