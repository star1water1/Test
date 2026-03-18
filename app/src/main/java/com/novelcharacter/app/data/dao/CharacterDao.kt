package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Character

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY displayOrder ASC, name ASC")
    fun getAllCharacters(): LiveData<List<Character>>

    @Query("SELECT * FROM characters ORDER BY displayOrder ASC, name ASC")
    suspend fun getAllCharactersList(): List<Character>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY displayOrder ASC, name ASC")
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY displayOrder ASC, name ASC")
    suspend fun getCharactersByNovelList(novelId: Long): List<Character>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getCharacterById(id: Long): Character?

    @Query("SELECT * FROM characters WHERE id = :id")
    fun getCharacterByIdLive(id: Long): LiveData<Character?>

    @Query("SELECT * FROM characters WHERE name LIKE '%' || :query || '%' OR anotherName LIKE '%' || :query || '%'")
    fun searchCharacters(query: String): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE name = :name AND novelId = :novelId LIMIT 1")
    suspend fun getCharacterByNameAndNovel(name: String, novelId: Long): Character?

    @Query("SELECT * FROM characters WHERE name = :name LIMIT 1")
    suspend fun getCharacterByName(name: String): Character?

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

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM characters")
    suspend fun getNextDisplayOrder(): Long
}
