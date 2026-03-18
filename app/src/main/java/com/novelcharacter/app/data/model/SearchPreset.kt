package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "search_presets",
    indices = [Index(value = ["name"], unique = true)]
)
data class SearchPreset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val query: String = "",
    val filtersJson: String = "{}",
    val sortMode: String = SORT_RELEVANCE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDefault: Boolean = false
) {
    companion object {
        const val SORT_RELEVANCE = "relevance"
        const val SORT_NAME = "name"
        const val SORT_TAG = "tag"
        const val SORT_RECENT = "recent"
        const val MAX_PRESETS = 20
    }
}
