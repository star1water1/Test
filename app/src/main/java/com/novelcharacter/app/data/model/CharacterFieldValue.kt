package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_field_values",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FieldDefinition::class,
            parentColumns = ["id"],
            childColumns = ["fieldDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("characterId"),
        Index("fieldDefinitionId"),
        Index(value = ["characterId", "fieldDefinitionId"], unique = true)
    ]
)
data class CharacterFieldValue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val fieldDefinitionId: Long,
    val value: String = ""
)
