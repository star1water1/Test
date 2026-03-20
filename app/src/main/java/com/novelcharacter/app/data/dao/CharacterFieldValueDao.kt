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
}
