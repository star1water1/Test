package com.novelcharacter.app.data.model

import androidx.room.ColumnInfo
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
        )
    ],
    indices = [Index("universeId"), Index("createdAt"), Index(value = ["code"], unique = true)]
)
data class Novel(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    @ColumnInfo(defaultValue = "''")
    val description: String = "",
    val universeId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''")
    val code: String = generateEntityCode(),
    @ColumnInfo(defaultValue = "0")
    val displayOrder: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val borderColor: String = "",
    @ColumnInfo(defaultValue = "1.5")
    val borderWidthDp: Float = 1.5f,
    @ColumnInfo(defaultValue = "1")
    val inheritUniverseBorder: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false
)
