package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Character

@Dao
interface CharacterDao {
    // Pinned-first sorting: isPinned DESC ensures pinned items appear at top
    @Query("SELECT * FROM characters ORDER BY isPinned DESC, displayOrder ASC, name ASC")
    fun getAllCharacters(): LiveData<List<Character>>

    @Query("SELECT * FROM characters ORDER BY isPinned DESC, displayOrder ASC, name ASC")
    suspend fun getAllCharactersList(): List<Character>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY isPinned DESC, displayOrder ASC, name ASC")
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY isPinned DESC, displayOrder ASC, name ASC")
    suspend fun getCharactersByNovelList(novelId: Long): List<Character>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getCharacterById(id: Long): Character?

    @Query("SELECT * FROM characters WHERE id IN (:ids)")
    suspend fun getCharactersByIds(ids: List<Long>): List<Character>

    @Query("SELECT * FROM characters WHERE id = :id")
    fun getCharacterByIdLive(id: Long): LiveData<Character?>

    @Query("SELECT * FROM characters WHERE name LIKE '%' || :query || '%' ESCAPE '\\' OR anotherName LIKE '%' || :query || '%' ESCAPE '\\' OR firstName LIKE '%' || :query || '%' ESCAPE '\\' OR lastName LIKE '%' || :query || '%' ESCAPE '\\'")
    fun searchCharacters(query: String): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE name = :name AND (novelId = :novelId OR (:novelId IS NULL AND novelId IS NULL)) LIMIT 1")
    suspend fun getCharacterByNameAndNovel(name: String, novelId: Long?): Character?

    @Query("SELECT * FROM characters WHERE name = :name LIMIT 1")
    suspend fun getCharacterByName(name: String): Character?

    @Query("SELECT * FROM characters WHERE name = :name")
    suspend fun getAllCharactersByName(name: String): List<Character>

    @Query("SELECT * FROM characters WHERE code = :code LIMIT 1")
    suspend fun getCharacterByCode(code: String): Character?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(character: Character): Long

    @Update
    suspend fun update(character: Character)

    @Delete
    suspend fun delete(character: Character)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(characters: List<Character>)

    @Update
    suspend fun updateAll(characters: List<Character>)

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM characters WHERE novelId = :novelId")
    suspend fun getNextDisplayOrderInNovel(novelId: Long): Long

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM characters WHERE novelId IS NULL")
    suspend fun getNextDisplayOrderNoNovel(): Long

    @Query("UPDATE characters SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Long, isPinned: Boolean)

    /** 세계관에 속한 모든 캐릭터 조회 (JOIN) — N+1 회피용 */
    @Query("""
        SELECT c.* FROM characters c
        INNER JOIN novels n ON c.novelId = n.id
        WHERE n.universeId = :universeId
    """)
    suspend fun getCharactersByUniverseList(universeId: Long): List<Character>

    @Query("DELETE FROM characters")
    suspend fun deleteAll()

    // ===== 일괄 편집용 배치 메서드 =====

    @Query("UPDATE characters SET novelId = :novelId, updatedAt = :now WHERE id IN (:ids)")
    suspend fun updateNovelIdForIds(ids: List<Long>, novelId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE characters SET isPinned = :isPinned WHERE id IN (:ids)")
    suspend fun setPinnedForIds(ids: List<Long>, isPinned: Boolean)

    @Query("DELETE FROM characters WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
