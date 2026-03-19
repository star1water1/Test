package com.novelcharacter.app.data.model

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "''")
    val description: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val code: String = generateEntityCode(),
    @ColumnInfo(defaultValue = "0")
    val displayOrder: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val borderColor: String = "",
    @ColumnInfo(defaultValue = "1.5")
    val borderWidthDp: Float = 1.5f
)
