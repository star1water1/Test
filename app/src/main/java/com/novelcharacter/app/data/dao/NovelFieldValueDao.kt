package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.novelcharacter.app.data.model.NovelFieldValue

/**
 * 작품 커스텀 필드 값 DAO (확-3). [EventFieldValueDao]와 같은 규약을 따른다 —
 * 부분 저장은 **커버 집합**([replaceForFields])으로만 하고, 폼이 렌더하지 못한 값은 건드리지 않는다(R-5).
 */
@Dao
interface NovelFieldValueDao {

    @Query("SELECT * FROM novel_field_values WHERE novelId = :novelId")
    suspend fun getValuesByNovelList(novelId: Long): List<NovelFieldValue>

    @Query("SELECT * FROM novel_field_values")
    suspend fun getAllValuesList(): List<NovelFieldValue>

    /**
     * 여러 작품의 필드값 일괄 조회. 호출부에서 900개 단위로 청크할 것(SQLite 999-변수 상한) —
     * 전량 조회를 쓰면 화면에 보이는 작품 수와 무관하게 테이블 전체를 읽는다.
     */
    @Query("SELECT * FROM novel_field_values WHERE novelId IN (:novelIds)")
    suspend fun getValuesByNovels(novelIds: List<Long>): List<NovelFieldValue>

    /** 필드별 전체 값 (값 라이브러리 usageCount 재계산·전파용) */
    @Query("SELECT * FROM novel_field_values WHERE fieldDefinitionId = :fieldDefId")
    suspend fun getValuesByFieldDef(fieldDefId: Long): List<NovelFieldValue>

    @Query("SELECT * FROM novel_field_values WHERE fieldDefinitionId IN (:fieldDefIds)")
    suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>): List<NovelFieldValue>

    @Query("SELECT * FROM novel_field_values WHERE novelId = :novelId AND fieldDefinitionId = :fieldDefId")
    suspend fun getValue(novelId: Long, fieldDefId: Long): NovelFieldValue?

    /**
     * 이 세계관 필드를 참조하지만 **이 세계관 소속이 아닌 작품**의 값 — 세계관 스냅샷용.
     * 사유는 `CharacterFieldValueDao.getOrphanValuesForUniverseFields`와 같다: 함께 삭제되는
     * 작품의 값은 각자의 작품 스냅샷이 담으므로 여기서 빼고, 살아남는 작품이 보관 중인 값만 담는다.
     * 그 값은 어떤 작품 스냅샷에도 실리지 않으면서 필드 정의 CASCADE로 함께 소멸한다.
     */
    @Query(
        """SELECT * FROM novel_field_values
           WHERE fieldDefinitionId IN (:fieldDefIds)
             AND novelId NOT IN (SELECT id FROM novels WHERE universeId = :universeId)"""
    )
    suspend fun getOrphanValuesForUniverseFields(
        fieldDefIds: List<Long>,
        universeId: Long
    ): List<NovelFieldValue>

    /**
     * 세계관 삭제 영향 고지용 — 해당 세계관 필드 정의에 걸린 작품 필드값 총수.
     * 캐릭터·사건판과 짝이다(R-29: 종류가 갈리는 집계는 전부 같이 세야 한다).
     */
    @Query("""
        SELECT COUNT(*) FROM novel_field_values
        WHERE fieldDefinitionId IN (SELECT id FROM field_definitions WHERE universeId = :universeId)
    """)
    suspend fun countValuesByUniverse(universeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(values: List<NovelFieldValue>)

    @Update
    suspend fun update(value: NovelFieldValue)

    @Delete
    suspend fun delete(value: NovelFieldValue)

    @Query("DELETE FROM novel_field_values WHERE novelId = :novelId")
    suspend fun deleteByNovel(novelId: Long)

    @Query("DELETE FROM novel_field_values WHERE novelId = :novelId AND fieldDefinitionId IN (:fieldIds)")
    suspend fun deleteByNovelAndFields(novelId: Long, fieldIds: List<Long>)

    @Query("DELETE FROM novel_field_values")
    suspend fun deleteAll()

    /**
     * **커버 집합만** 갈아끼운다 (R-5). 폼이 렌더한 필드([fieldIds])의 기존 값을 지우고 새 값을 넣되,
     * 렌더하지 못한 필드의 값은 손대지 않는다 — 폼을 '전체 진실'로 다루면 화면에 못 그린 값이
     * 저장 한 번에 전멸한다(사건판이 S-6에서 겪은 결함).
     *
     * SQLite 999-변수 상한 때문에 삭제를 900개씩 청크한다.
     */
    @Transaction
    suspend fun replaceForFields(novelId: Long, fieldIds: List<Long>, values: List<NovelFieldValue>) {
        if (fieldIds.isNotEmpty()) {
            fieldIds.chunked(900).forEach { deleteByNovelAndFields(novelId, it) }
        }
        if (values.isNotEmpty()) insertAll(values)
    }
}
