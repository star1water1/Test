package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 휴지통 스냅샷 (B-7).
 *
 * 삭제 시점에 엔티티와 그 연관 데이터 전체를 JSON으로 직렬화해 보관한다.
 * FK가 없는 독립 테이블이므로 원본 삭제(CASCADE)와 무관하게 살아남고,
 * 소프트 삭제(deletedAt 컬럼)와 달리 기존 조회 쿼리에 영향을 주지 않는다.
 *
 * 이미지 파일은 스냅샷이 살아 있는 동안 디스크에 유지되며,
 * 스냅샷 영구 삭제/정리 시점에 함께 삭제된다.
 */
@Entity(
    tableName = "trash_snapshots",
    indices = [Index("deletedAt")]
)
data class TrashSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val entityType: String,          // 현재 "character"만 사용 — 확장 대비 문자열
    val entityName: String,          // 목록 표시용 이름
    val payload: String,             // 엔티티+연관 데이터 JSON (CharacterSnapshot 등)
    val imagePaths: String = "[]",   // 보류 중인 이미지 파일 경로 JSON 배열
    val deletedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_CHARACTER = "character"

        /** 휴지통 최대 보관 개수 — 초과 시 오래된 것부터 영구 삭제 */
        const val MAX_ITEMS = 30

        /** 보관 기한 (30일) — 초과 시 자동 영구 삭제 */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
