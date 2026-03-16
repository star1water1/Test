package com.novelcharacter.app.data.model

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
    val description: String = "",
    val universeId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val code: String = generateEntityCode()
)
