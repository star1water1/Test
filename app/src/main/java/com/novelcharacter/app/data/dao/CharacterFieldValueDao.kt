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

    /** 특정 세계관에 속하지 않는 필드값 삭제 (세계관 변경 시 고아 필드값 정리용) */
    @Query("""
        DELETE FROM character_field_values
        WHERE characterId = :characterId
        AND fieldDefinitionId NOT IN (
            SELECT id FROM field_definitions WHERE universeId = :universeId
        )
    """)
    suspend fun deleteValuesNotInUniverse(characterId: Long, universeId: Long)
}
