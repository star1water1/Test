package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.NameBankEntry

@Dao
interface NameBankDao {
    @Query("SELECT * FROM name_bank ORDER BY createdAt DESC")
    fun getAllNames(): LiveData<List<NameBankEntry>>

    @Query("SELECT * FROM name_bank ORDER BY createdAt DESC")
    suspend fun getAllNamesList(): List<NameBankEntry>

    @Query("SELECT * FROM name_bank WHERE isUsed = 0 ORDER BY createdAt DESC")
    fun getAvailableNames(): LiveData<List<NameBankEntry>>

    @Query("SELECT * FROM name_bank WHERE isUsed = 0 ORDER BY createdAt DESC")
    suspend fun getAvailableNamesList(): List<NameBankEntry>

    @Query("SELECT * FROM name_bank WHERE name LIKE '%' || :query || '%' ESCAPE '\\' OR notes LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY createdAt DESC")
    fun searchNames(query: String): LiveData<List<NameBankEntry>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: NameBankEntry): Long

    @Update
    suspend fun update(entry: NameBankEntry)

    @Delete
    suspend fun delete(entry: NameBankEntry)

    @Query("UPDATE name_bank SET isUsed = 1, usedByCharacterId = :characterId WHERE id = :id")
    suspend fun markAsUsed(id: Long, characterId: Long)

    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE id = :id")
    suspend fun markAsAvailable(id: Long)

    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE usedByCharacterId = :characterId")
    suspend fun resetUsageByCharacter(characterId: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<NameBankEntry>)

    /** 여러 캐릭터의 이름뱅크 사용 상태 일괄 해제 (배치 삭제용) */
    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE usedByCharacterId IN (:characterIds)")
    suspend fun resetUsageByCharacterIds(characterIds: List<Long>)

    @Query("DELETE FROM name_bank")
    suspend fun deleteAll()

    @Query("SELECT id FROM name_bank")
    suspend fun getAllEntryIds(): List<Long>

    @Query("DELETE FROM name_bank WHERE id = :id")
    suspend fun deleteById(id: Long)
}
