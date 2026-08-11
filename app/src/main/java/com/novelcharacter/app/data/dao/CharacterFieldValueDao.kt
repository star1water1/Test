package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterFieldValue

@Dao
interface CharacterFieldValueDao {
    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId")
    fun getValuesByCharacter(characterId: Long): LiveData<List<CharacterFieldValue>>

    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId")
    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue>

    @Query("SELECT * FROM character_field_values")
    suspend fun getAllValuesList(): List<CharacterFieldValue>

    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId AND fieldDefinitionId = :fieldId")
    suspend fun getValue(characterId: Long, fieldId: Long): CharacterFieldValue?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(value: CharacterFieldValue): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(values: List<CharacterFieldValue>)

    @Update
    suspend fun update(value: CharacterFieldValue)

    @Transaction
    suspend fun replaceAllByCharacter(characterId: Long, values: List<CharacterFieldValue>) {
        deleteAllByCharacter(characterId)
        insertAll(values)
    }

    @Query("DELETE FROM character_field_values WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Query("DELETE FROM character_field_values WHERE characterId = :characterId AND fieldDefinitionId = :fieldId")
    suspend fun deleteValue(characterId: Long, fieldId: Long)

    @Query("""
        SELECT cfv.* FROM character_field_values cfv
        INNER JOIN field_definitions fd ON cfv.fieldDefinitionId = fd.id
        WHERE cfv.characterId = :characterId AND fd.`key` = :fieldKey
    """)
    suspend fun getValueByFieldKey(characterId: Long, fieldKey: String): CharacterFieldValue?

    /** 같은 작품 내 모든 캐릭터의 특정 필드 값 조회 (백분위 계산용) */
    @Query("""
        SELECT cfv.value FROM character_field_values cfv
        INNER JOIN characters c ON cfv.characterId = c.id
        WHERE c.novelId = :novelId AND cfv.fieldDefinitionId = :fieldDefId AND cfv.value != ''
    """)
    suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String>

    /** 같은 세계관 내 모든 캐릭터의 특정 필드 값 조회 (백분위 계산용) */
    @Query("""
        SELECT cfv.value FROM character_field_values cfv
        INNER JOIN characters c ON cfv.characterId = c.id
        INNER JOIN novels n ON c.novelId = n.id
        WHERE n.universeId = :universeId AND cfv.fieldDefinitionId = :fieldDefId AND cfv.value != ''
    """)
    suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String>

    /** 특정 필드 정의에 대한 모든 값 조회 (타입 변경 영향 분석용) */
    @Query("SELECT * FROM character_field_values WHERE fieldDefinitionId = :fieldDefId")
    suspend fun getValuesByFieldDef(fieldDefId: Long): List<CharacterFieldValue>

    /**
     * 여러 필드 정의의 값 일괄 조회 (휴지통 세계관 스냅샷용 — B-1).
     * 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한).
     */
    @Query("SELECT * FROM character_field_values WHERE fieldDefinitionId IN (:fieldDefIds)")
    suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>): List<CharacterFieldValue>

    /**
     * 이 세계관 필드를 가리키지만 **그 세계관에 속하지 않은** 캐릭터의 값 (휴지통 세계관 스냅샷 — B-1).
     *
     * 세계관 삭제는 소속 캐릭터를 함께 지우고 그 값은 캐릭터 스냅샷이 담으므로, 세계관
     * 스냅샷이 담아야 하는 것은 **살아남는 캐릭터가 보관 중인 값**(미분류 캐릭터 — N2)뿐이다.
     * 전량을 읽어 메모리에서 거르면 캐릭터 300명·필드 50개 세계관에서 1만 5천 행을 올려
     * 거의 0행을 남긴다 — 제외를 서브쿼리로 내린다(IN 절 변수 한도도 함께 피한다).
     */
    @Query(
        """SELECT * FROM character_field_values
           WHERE fieldDefinitionId IN (:fieldDefIds)
             AND characterId NOT IN (
                 SELECT c.id FROM characters c
                 INNER JOIN novels n ON c.novelId = n.id
                 WHERE n.universeId = :universeId
             )"""
    )
    suspend fun getOrphanValuesForUniverseFields(
        fieldDefIds: List<Long>,
        universeId: Long
    ): List<CharacterFieldValue>

    /** 여러 캐릭터의 전체 필드값 일괄 조회 (백분위 배치 계산용 — 캐릭터당 개별 쿼리 N+1 방지) */
    @Query("SELECT * FROM character_field_values WHERE characterId IN (:characterIds)")
    suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue>

    /** 세계관 전체의 필드값 일괄 조회 (편집 화면 자동완성 배치 로드용) */
    @Query("""
        SELECT cfv.* FROM character_field_values cfv
        INNER JOIN characters c ON cfv.characterId = c.id
        INNER JOIN novels n ON c.novelId = n.id
        WHERE n.universeId = :universeId AND cfv.value != ''
    """)
    suspend fun getAllValuesForUniverse(universeId: Long): List<CharacterFieldValue>

    /** 특정 필드에 특정 값을 가진 캐릭터 ID 조회 (필터링용) */
    @Query("""
        SELECT DISTINCT cfv.characterId FROM character_field_values cfv
        WHERE cfv.fieldDefinitionId = :fieldDefId AND cfv.value = :value
    """)
    suspend fun getCharacterIdsByFieldValue(fieldDefId: Long, value: String): List<Long>

    /** 특정 필드에 특정 값을 포함하는 캐릭터 ID 조회 (contains 매칭) */
    @Query("""
        SELECT DISTINCT cfv.characterId FROM character_field_values cfv
        WHERE cfv.fieldDefinitionId = :fieldDefId AND cfv.value LIKE '%' || :value || '%' ESCAPE '\'
    """)
    suspend fun getCharacterIdsByFieldValueContains(fieldDefId: Long, value: String): List<Long>

    /**
     * 특정 세계관에 속하지 않는 필드값 삭제 (세계관 변경 시 고아 필드값 정리용).
     *
     * **전역 구역(universeId IS NULL — B-119 확장)의 값은 지우지 않는다.** 이 삭제의 동의는
     * *"이전 세계관의 값"*에 대한 것인데 전역 필드 값은 어느 세계관의 것도 아니다 — 소속이
     * 바뀌어도 그대로 따라가는 것이 그 필드의 성질이고, 지우면 동의 범위를 넘는 유실이다.
     */
    @Query("""
        DELETE FROM character_field_values
        WHERE characterId = :characterId
        AND fieldDefinitionId NOT IN (
            SELECT id FROM field_definitions WHERE universeId = :universeId OR universeId IS NULL
        )
    """)
    suspend fun deleteValuesNotInUniverse(characterId: Long, universeId: Long)

    /** 세계관 삭제 영향 고지용 — 해당 세계관 필드 정의에 걸린 값 총수 */
    @Query("""
        SELECT COUNT(*) FROM character_field_values
        WHERE fieldDefinitionId IN (SELECT id FROM field_definitions WHERE universeId = :universeId)
    """)
    suspend fun countValuesByUniverse(universeId: Long): Int

    // ===== 일괄 편집용 배치 메서드 =====

    /** 여러 캐릭터의 특정 필드값 일괄 삭제 */
    @Query("DELETE FROM character_field_values WHERE characterId IN (:characterIds) AND fieldDefinitionId = :fieldDefId")
    suspend fun deleteFieldValueForCharacters(characterIds: List<Long>, fieldDefId: Long)

    /** 필드값 upsert (일괄 설정용 — 기존 값이 있으면 교체, 없으면 삽입) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: CharacterFieldValue): Long

    /**
     * **이 캐릭터들의 값이 어느 세계관을 가리키는가** (B-13 — `UnassignedHistoryScope`의 입력).
     *
     * 이력(`character_state_changes`)은 문자열 `fieldKey`만 들어 세계관을 되찾을 수 없는데,
     * 값은 `fieldDefinitionId`로 정의를 직접 가리키므로 되찾을 수 있다. 미분류 캐릭터가
     * 세계관에서 나올 때 짝 없는 값은 **보관 값**으로 그 세계관의 정의를 계속 가리킨다(B-128) —
     * 그것이 *"이 캐릭터의 데이터가 어디 것인가"*의 단서다.
     *
     * **전역 구역(`universeId IS NULL`)은 뺀다** — 어느 세계관도 아니므로 귀속의 근거가
     * 되지 못한다(그 값을 세면 모든 미분류 캐릭터가 '단서 있음'으로 잘못 분류된다).
     */
    @Query("""
        SELECT DISTINCT v.characterId AS characterId, f.universeId AS universeId
        FROM character_field_values v
        JOIN field_definitions f ON f.id = v.fieldDefinitionId
        WHERE v.characterId IN (:characterIds) AND f.universeId IS NOT NULL
    """)
    suspend fun getValueUniversesForCharacters(characterIds: List<Long>): List<CharacterValueUniverse>
}

/** [CharacterFieldValueDao.getValueUniversesForCharacters]의 한 줄 — 캐릭터와 그 값이 든 세계관. */
data class CharacterValueUniverse(val characterId: Long, val universeId: Long)
