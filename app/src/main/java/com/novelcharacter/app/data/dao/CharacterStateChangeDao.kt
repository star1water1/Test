package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterStateChange

@Dao
interface CharacterStateChangeDao {
    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year <= :year ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND fieldKey = :fieldKey ORDER BY year ASC")
    suspend fun getChangesByField(characterId: Long, fieldKey: String): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes ORDER BY characterId ASC, year ASC, month ASC, day ASC")
    suspend fun getAllChangesList(): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE id = :id")
    suspend fun getChangeById(id: Long): CharacterStateChange?

    /**
     * 일괄 삭제 영향 요약용 — 선택 캐릭터의 사용자 의미 상태변화 수.
     * 내부 플래그(__std_year_link)는 사용자에게 보이지 않으므로 집계에서 제외한다.
     */
    @Query("SELECT COUNT(*) FROM character_state_changes WHERE characterId IN (:ids) AND fieldKey != '__std_year_link'")
    suspend fun countByCharacterIds(ids: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: CharacterStateChange): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(changes: List<CharacterStateChange>)

    @Update
    suspend fun update(change: CharacterStateChange)

    @Delete
    suspend fun delete(change: CharacterStateChange)

    @Query("DELETE FROM character_state_changes WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year = :year AND fieldKey = :fieldKey AND newValue = :newValue LIMIT 1")
    suspend fun getChangeByNaturalKey(characterId: Long, year: Int, fieldKey: String, newValue: String): CharacterStateChange?

    /**
     * 시점(month/day)까지 포함한 정밀 중복 판정 — 일괄 상태변화에서 '연도만 같고 시점이 다른 별개 기록'이
     * 자연키 4개 판정에 조용히 스킵되는 것을 막는다. `IS`는 NULL-안전 비교(NULL IS NULL = true).
     */
    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year = :year AND month IS :month AND day IS :day AND fieldKey = :fieldKey AND newValue = :newValue LIMIT 1")
    suspend fun getChangeByExactKey(characterId: Long, year: Int, month: Int?, day: Int?, fieldKey: String, newValue: String): CharacterStateChange?

    /** 엑셀 왕복 안정 식별자 매칭 — 코드 우선, 자연키는 구버전 파일 폴백 */
    @Query("SELECT * FROM character_state_changes WHERE code = :code LIMIT 1")
    suspend fun getChangeByCode(code: String): CharacterStateChange?

    /** 필드 키 변경 시 해당 세계관 캐릭터들의 상태변화 이력 fieldKey를 일괄 갱신 (무통보 이력 파손 방지) */
    @Query("""
        UPDATE character_state_changes SET fieldKey = :newKey
        WHERE fieldKey = :oldKey AND characterId IN (
            SELECT c.id FROM characters c JOIN novels n ON c.novelId = n.id WHERE n.universeId = :universeId
        )
    """)
    suspend fun migrateFieldKeyForUniverse(universeId: Long, oldKey: String, newKey: String): Int

    /** 값 라이브러리 rename/merge 전파용 — 해당 세계관 캐릭터들의 특정 필드 이력.
     *  newValue 치환은 SQL이 아니라 토크나이저(다중값 콤마 결합)로 행 단위 처리한다. */
    @Query("""
        SELECT * FROM character_state_changes
        WHERE fieldKey = :fieldKey AND characterId IN (
            SELECT c.id FROM characters c JOIN novels n ON c.novelId = n.id WHERE n.universeId = :universeId
        )
    """)
    suspend fun getChangesByFieldKeyForUniverse(universeId: Long, fieldKey: String): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month = :month AND day = :day")
    suspend fun getChangesByFieldAndDate(fieldKey: String, month: Int, day: Int): List<CharacterStateChange>

    /** 생일(month/day)이 있는 상태변경 전체 — 일회성 조회 (Worker/Widget용) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    suspend fun getChangesWithDate(fieldKey: String): List<CharacterStateChange>

    /** 생일(month/day)이 있는 상태변경 전체 — 반응형 (테이블 변경 시 자동 갱신) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    fun observeChangesWithDate(fieldKey: String): LiveData<List<CharacterStateChange>>

    @Query("DELETE FROM character_state_changes WHERE fieldKey = :fieldKey")
    suspend fun deleteChangesByFieldKey(fieldKey: String)

    @Query("""
        DELETE FROM character_state_changes
        WHERE fieldKey = :fieldKey
          AND characterId IN (
              SELECT c.id FROM characters c
              INNER JOIN novels n ON c.novelId = n.id
              WHERE n.universeId = :universeId
          )
    """)
    suspend fun deleteChangesByFieldKeyAndUniverse(fieldKey: String, universeId: Long)

    /** 세계관에 속한 모든 필드의 state change 삭제 (세계관 삭제 시 사용) */
    @Query("""
        DELETE FROM character_state_changes
        WHERE fieldKey IN (
            SELECT `key` FROM field_definitions WHERE universeId = :universeId
        )
        AND characterId IN (
            SELECT c.id FROM characters c
            INNER JOIN novels n ON c.novelId = n.id
            WHERE n.universeId = :universeId
        )
    """)
    suspend fun deleteAllChangesByUniverse(universeId: Long)

    @Query("DELETE FROM character_state_changes")
    suspend fun deleteAll()

    @Query("SELECT id FROM character_state_changes")
    suspend fun getAllChangeIds(): List<Long>

    @Query("DELETE FROM character_state_changes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
