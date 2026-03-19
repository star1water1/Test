package com.novelcharacter.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
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
    indices = [Index("novelId"), Index(value = ["code"], unique = true)]
)
data class Character(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "''")
    val anotherName: String = "",
    val novelId: Long? = null,
    @ColumnInfo(defaultValue = "'[]'")
    val imagePaths: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val memo: String = "",
    @ColumnInfo(defaultValue = "''")
    val code: String = generateEntityCode(),
    @ColumnInfo(defaultValue = "0")
    val displayOrder: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false
)
