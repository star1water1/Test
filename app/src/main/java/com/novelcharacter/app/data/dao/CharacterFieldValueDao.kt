package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterFieldValue

@Dao
abstract class CharacterFieldValueDao {
    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId")
    abstract fun getValuesByCharacter(characterId: Long): LiveData<List<CharacterFieldValue>>

    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId")
    abstract suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue>

    @Query("SELECT * FROM character_field_values WHERE characterId = :characterId AND fieldDefinitionId = :fieldId")
    abstract suspend fun getValue(characterId: Long, fieldId: Long): CharacterFieldValue?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(value: CharacterFieldValue): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(values: List<CharacterFieldValue>)

    @Update
    abstract suspend fun update(value: CharacterFieldValue)

    @Query("DELETE FROM character_field_values WHERE characterId = :characterId")
    abstract suspend fun deleteAllByCharacter(characterId: Long)

    @Query("DELETE FROM character_field_values WHERE characterId = :characterId AND fieldDefinitionId = :fieldId")
    abstract suspend fun deleteValue(characterId: Long, fieldId: Long)

    @Query("""
        SELECT cfv.* FROM character_field_values cfv
        INNER JOIN field_definitions fd ON cfv.fieldDefinitionId = fd.id
        WHERE cfv.characterId = :characterId AND fd.`key` = :fieldKey
    """)
    abstract suspend fun getValueByFieldKey(characterId: Long, fieldKey: String): CharacterFieldValue?

    @Transaction
    open suspend fun replaceAllByCharacter(characterId: Long, values: List<CharacterFieldValue>) {
        deleteAllByCharacter(characterId)
        insertAll(values)
    }
}
