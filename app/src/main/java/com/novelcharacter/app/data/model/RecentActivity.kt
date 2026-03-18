package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_activities",
    indices = [Index(value = ["entityType", "entityId"], unique = true)]
)
data class RecentActivity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String,  // "character", "novel", "universe"
    val entityId: Long,
    val title: String,
    val accessedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_CHARACTER = "character"
        const val TYPE_NOVEL = "novel"
        const val TYPE_UNIVERSE = "universe"
        const val MAX_ENTRIES = 10
    }
}
