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
    val displayOrder: Long = 0
)
