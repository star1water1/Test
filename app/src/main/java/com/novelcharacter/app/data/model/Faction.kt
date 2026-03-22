package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "factions",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("universeId"),
        Index(value = ["code"], unique = true)
    ]
)
data class Faction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val universeId: Long,
    val name: String,
    val description: String = "",
    val color: String = "#2196F3",
    val autoRelationType: String,        // 자동 생성 관계의 유형명 (사용자 지정)
    val autoRelationIntensity: Int = 5,  // 자동 관계 강도 (1-10)
    val code: String = generateEntityCode(),
    val displayOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
