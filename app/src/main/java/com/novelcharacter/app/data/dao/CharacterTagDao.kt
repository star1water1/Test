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

    @Query("SELECT * FROM character_tags ORDER BY tag ASC")
    suspend fun getAllTagsList(): List<CharacterTag>

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

    // ===== 일괄 편집용 배치 메서드 =====

    @Query("SELECT DISTINCT tag FROM character_tags WHERE characterId IN (:characterIds) ORDER BY tag ASC")
    suspend fun getDistinctTagsForCharacters(characterIds: List<Long>): List<String>

    @Query("DELETE FROM character_tags WHERE characterId IN (:characterIds) AND tag IN (:tags)")
    suspend fun deleteTagsFromCharacters(characterIds: List<Long>, tags: List<String>)
}
