package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.FieldValueEntry

@Dao
interface FieldValueEntryDao {

    @Query("SELECT * FROM field_value_entries WHERE fieldDefinitionId = :fieldDefId ORDER BY usageCount DESC, value")
    suspend fun getByField(fieldDefId: Long): List<FieldValueEntry>

    // field_definitions JOIN: FK 캐스케이드 삭제는 recursive_triggers=OFF라 자식 테이블 무효화가
    // 안 울린다 — 부모 테이블을 관측 의존성에 포함시켜 필드/세계관 삭제 시에도 화면이 갱신되게 한다.
    @Query(
        """SELECT fve.* FROM field_value_entries fve
           INNER JOIN field_definitions fd ON fve.fieldDefinitionId = fd.id
           WHERE fve.fieldDefinitionId = :fieldDefId
           ORDER BY fve.usageCount DESC, fve.value"""
    )
    fun getByFieldLive(fieldDefId: Long): LiveData<List<FieldValueEntry>>

    /** 폼 자동완성 1쿼리 배치 로드 (숨김 제외) */
    @Query(
        """SELECT fve.* FROM field_value_entries fve
           INNER JOIN field_definitions fd ON fve.fieldDefinitionId = fd.id
           WHERE fd.universeId = :universeId AND fd.entityType = :entityType AND fve.isHidden = 0
           ORDER BY fve.usageCount DESC, fve.value"""
    )
    suspend fun getForUniverse(universeId: Long, entityType: String): List<FieldValueEntry>

    /** IN 청크(999)는 레포지토리에서 처리 */
    @Query("SELECT * FROM field_value_entries WHERE fieldDefinitionId IN (:fieldDefIds)")
    suspend fun getForFields(fieldDefIds: List<Long>): List<FieldValueEntry>

    @Query("SELECT * FROM field_value_entries")
    suspend fun getAllList(): List<FieldValueEntry>

    /** 라이브러리 홈 요약 — 값 자체는 로드하지 않는다 */
    @Query(
        """SELECT fieldDefinitionId,
                  COUNT(*) AS entryCount,
                  SUM(CASE WHEN category = '' THEN 1 ELSE 0 END) AS uncategorizedCount,
                  SUM(CASE WHEN usageCount = 0 THEN 1 ELSE 0 END) AS unusedCount
           FROM field_value_entries GROUP BY fieldDefinitionId"""
    )
    suspend fun countByField(): List<FieldEntryCount>

    @Query("SELECT * FROM field_value_entries WHERE fieldDefinitionId = :fieldDefId AND value = :value")
    suspend fun getByFieldAndValue(fieldDefId: Long, value: String): FieldValueEntry?

    @Query("SELECT * FROM field_value_entries WHERE code = :code")
    suspend fun getByCode(code: String): FieldValueEntry?

    /** 수확용 — 이미 있는 (필드, 값)은 무시. 반환값의 -1이 충돌 행. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entries: List<FieldValueEntry>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: FieldValueEntry): Long

    @Update
    suspend fun update(entry: FieldValueEntry)

    @Update
    suspend fun updateAll(entries: List<FieldValueEntry>)

    @Delete
    suspend fun delete(entry: FieldValueEntry)

    /** 유지보수 잡용 — 큐레이션 흔적이 전무한 미사용 AUTO 엔트리만 정리 (확인 후 실행) */
    @Query(
        """DELETE FROM field_value_entries
           WHERE fieldDefinitionId = :fieldDefId AND source = 'AUTO' AND usageCount = 0
             AND displayLabel = '' AND category = '' AND description = '' AND aliasesJson = '[]'
             AND isHidden = 0"""
    )
    suspend fun pruneUncuratedUnused(fieldDefId: Long): Int

    @Query(
        """SELECT COUNT(*) FROM field_value_entries
           WHERE fieldDefinitionId = :fieldDefId AND source = 'AUTO' AND usageCount = 0
             AND displayLabel = '' AND category = '' AND description = '' AND aliasesJson = '[]'
             AND isHidden = 0"""
    )
    suspend fun countUncuratedUnused(fieldDefId: Long): Int
}

/** countByField 결과 행 */
data class FieldEntryCount(
    val fieldDefinitionId: Long,
    val entryCount: Int,
    val uncategorizedCount: Int,
    val unusedCount: Int
)
