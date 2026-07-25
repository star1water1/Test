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

        /**
         * 저장 가능한 정렬모드 전체 — 엑셀 드롭다운·가져오기 유효값 검증·사용 안내 문구가
         * 공유하는 단일 소스. 규칙을 양쪽에 따로 두면 반드시 드리프트한다.
         */
        val SORT_MODES = listOf(SORT_RELEVANCE, SORT_NAME, SORT_TAG, SORT_RECENT)
    }
}
