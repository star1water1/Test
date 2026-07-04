package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 데이터 처리 작업 이력 (변수 제어 — 결과를 나중에 다시 확인할 수 있게).
 *
 * OpResult를 시간순으로 보관한다. RecentActivity(최근 열람)와 목적이 다른 별도 테이블이며,
 * 상한(MAX_ENTRIES)을 두고 insert 시 오래된 항목을 정리한다.
 */
@Entity(
    tableName = "operation_logs",
    indices = [Index("createdAt")]
)
data class OperationLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String,      // OpResult.CAT_* — 분류/아이콘용
    val summary: String,       // 한 줄 요약
    val detail: String? = null,// 상세 내역(경고/오류 목록 등)
    val success: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** 이력 최대 보관 개수 — 초과 시 오래된 것부터 정리 */
        const val MAX_ENTRIES = 200
    }
}
