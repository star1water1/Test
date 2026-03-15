package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Character

@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY name ASC")
    fun getAllCharacters(): LiveData<List<Character>>

    @Query("SELECT * FROM characters ORDER BY name ASC")
    suspend fun getAllCharactersList(): List<Character>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY name ASC")
    fun getCharactersByNovel(novelId: Long): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE novelId = :novelId ORDER BY name ASC")
    suspend fun getCharactersByNovelList(novelId: Long): List<Character>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getCharacterById(id: Long): Character?

    @Query("SELECT * FROM characters WHERE id = :id")
    fun getCharacterByIdLive(id: Long): LiveData<Character?>

    @Query("SELECT * FROM characters WHERE name LIKE '%' || :query || '%'")
    fun searchCharacters(query: String): LiveData<List<Character>>

    @Query("SELECT * FROM characters WHERE name = :name AND novelId = :novelId LIMIT 1")
    suspend fun getCharacterByNameAndNovel(name: String, novelId: Long): Character?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(character: Character): Long

    @Update
    suspend fun update(character: Character)

    @Delete
    suspend fun delete(character: Character)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(characters: List<Character>)
}
