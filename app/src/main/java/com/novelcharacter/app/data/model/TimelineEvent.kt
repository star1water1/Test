package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("year"), Index("universeId"), Index(value = ["code"], unique = true)]
)
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val calendarType: String = "천개력",
    val description: String,
    val eventType: String = "",       // "", "birth", "death" — 시맨틱 사건 유형
    val universeId: Long? = null,
    // novelId 제거됨 — timeline_event_novel_cross_ref 다대다 테이블로 이동
    val displayOrder: Int = 0,
    val isTemporary: Boolean = false,  // 간편 모드에서 임시 배치된 사건
    val createdAt: Long = System.currentTimeMillis(),
    // 엑셀 왕복 안정 식별자 — 자연키(연도+설명) 편집이 중복 생성으로 이어지지 않게 하는 기준.
    // nullable인 이유: v35 이전 Gson 스냅샷/월드패키지 역직렬화 시 null이 주입될 수 있다 (레거시 수용).
    val code: String? = generateEntityCode()
) {
    companion object {
        const val TYPE_NONE = ""
        const val TYPE_BIRTH = "birth"
        const val TYPE_DEATH = "death"
    }

    fun getFormattedDate(): String {
        val yearStr = if (year < 0) "BC ${-year}" else "$year"
        return listOfNotNull(
            calendarType,
            yearStr,
            month?.let { "${it}월" },
            day?.let { "${it}일" }
        ).joinToString(" ")
    }
}
