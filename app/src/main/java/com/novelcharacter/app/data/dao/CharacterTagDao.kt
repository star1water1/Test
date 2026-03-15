package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterTag

@Dao
interface CharacterTagDao {
    @Query("SELECT * FROM character_tags WHERE characterId = :characterId ORDER BY tag ASC")
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>>

    @Query("SELECT * FROM character_tags WHERE characterId = :characterId ORDER BY tag ASC")
    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag>

    @Query("SELECT DISTINCT tag FROM character_tags ORDER BY tag ASC")
    suspend fun getAllDistinctTags(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: CharacterTag): Long

    @Delete
    suspend fun delete(tag: CharacterTag)

    @Query("DELETE FROM character_tags WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<CharacterTag>)

    @Transaction
    suspend fun replaceAllForCharacter(characterId: Long, tags: List<CharacterTag>) {
        deleteAllByCharacter(characterId)
        insertAll(tags)
    }
}
