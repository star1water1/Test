package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Universe

@Dao
interface UniverseDao {
    @Query("SELECT * FROM universes ORDER BY displayOrder ASC, createdAt DESC")
    fun getAllUniverses(): LiveData<List<Universe>>

    @Query("SELECT * FROM universes ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getAllUniversesList(): List<Universe>

    @Query("SELECT * FROM universes WHERE id = :id")
    suspend fun getUniverseById(id: Long): Universe?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(universe: Universe): Long

    @Update
    suspend fun update(universe: Universe)

    @Query("SELECT * FROM universes WHERE name = :name LIMIT 1")
    suspend fun getUniverseByName(name: String): Universe?

    @Query("SELECT * FROM universes WHERE code = :code LIMIT 1")
    suspend fun getUniverseByCode(code: String): Universe?

    @Delete
    suspend fun delete(universe: Universe)

    @Update
    suspend fun updateAll(universes: List<Universe>)

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM universes")
    suspend fun getNextDisplayOrder(): Long

    @Query("UPDATE universes SET imageCharacterId = NULL, imageMode = 'none' WHERE imageCharacterId = :characterId")
    suspend fun clearImageCharacterRef(characterId: Long)

    /** 여러 캐릭터의 이미지 참조 일괄 정리 (배치 삭제용) */
    @Query("UPDATE universes SET imageCharacterId = NULL, imageMode = 'none' WHERE imageCharacterId IN (:characterIds)")
    suspend fun clearImageCharacterRefs(characterIds: List<Long>)

    @Query("UPDATE universes SET imageNovelId = NULL, imageMode = 'none' WHERE imageNovelId = :novelId")
    suspend fun clearImageNovelRef(novelId: Long)

    @Query("DELETE FROM universes")
    suspend fun deleteAll()
}
