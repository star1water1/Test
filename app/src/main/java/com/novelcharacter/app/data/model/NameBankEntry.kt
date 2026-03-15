package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "name_bank",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["usedByCharacterId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("isUsed"), Index("usedByCharacterId"), Index("createdAt")]
)
data class NameBankEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val gender: String = "",
    val origin: String = "",
    val notes: String = "",
    val isUsed: Boolean = false,
    val usedByCharacterId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
