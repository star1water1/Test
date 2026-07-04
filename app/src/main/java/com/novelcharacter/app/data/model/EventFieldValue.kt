package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 사건 커스텀 필드 값 (B-10). CharacterFieldValue와 동형 —
 * entityType="event"인 FieldDefinition의 값을 사건별로 저장한다.
 */
@Entity(
    tableName = "event_field_values",
    foreignKeys = [
        ForeignKey(
            entity = TimelineEvent::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
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
        Index("eventId"), Index("fieldDefinitionId"),
        Index(value = ["eventId", "fieldDefinitionId"], unique = true)
    ]
)
data class EventFieldValue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventId: Long,
    val fieldDefinitionId: Long,
    val value: String
)
