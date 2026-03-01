package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("novelId"), Index("year"), Index("universeId")]
)
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val calendarType: String = "천개력",
    val description: String,
    val universeId: Long? = null,
    val novelId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getFormattedDate(): String {
        val yearStr = if (year < 0) "BC ${-year}" else "$year"
        val monthStr = month?.let { "${it}월" } ?: ""
        val dayStr = day?.let { "${it}일" } ?: ""
        return "$calendarType $yearStr $monthStr $dayStr".trim()
    }
}
