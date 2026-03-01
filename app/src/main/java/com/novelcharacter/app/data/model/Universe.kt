package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "universes")
data class Universe(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
