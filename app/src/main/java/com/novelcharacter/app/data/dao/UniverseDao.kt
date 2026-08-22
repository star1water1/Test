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

    /**
     * 삭제된 캐릭터 참조 정리 — **모드는 그 캐릭터에 매달린 모드일 때만 내린다**
     * (작품 축의 [NovelDao.clearImageCharacterRef]와 같은 판정. 조건 없이 내리면
     * 직접 등록한 그림을 쓰는 세계관이 남의 삭제에 표지를 잃는다).
     */
    // SQL 쌍둥이: Universe.IMAGE_MODE_NONE
    // SQL 쌍둥이: Universe.IMAGE_MODE_SELECT_CHARACTER
    @Query(
        "UPDATE universes SET imageCharacterId = NULL, " +
            "imageMode = CASE WHEN imageMode = 'select_character' THEN 'none' ELSE imageMode END " +
            "WHERE imageCharacterId = :characterId"
    )
    suspend fun clearImageCharacterRef(characterId: Long)

    /** 여러 캐릭터의 이미지 참조 일괄 정리 (배치 삭제용) — 판정은 [clearImageCharacterRef]와 같다. */
    // SQL 쌍둥이: Universe.IMAGE_MODE_NONE
    // SQL 쌍둥이: Universe.IMAGE_MODE_SELECT_CHARACTER
    @Query(
        "UPDATE universes SET imageCharacterId = NULL, " +
            "imageMode = CASE WHEN imageMode = 'select_character' THEN 'none' ELSE imageMode END " +
            "WHERE imageCharacterId IN (:characterIds)"
    )
    suspend fun clearImageCharacterRefs(characterIds: List<Long>)

    /** 삭제된 작품 참조 정리 — 같은 결(작품에 매달린 모드일 때만 내린다). */
    // SQL 쌍둥이: Universe.IMAGE_MODE_NONE
    // SQL 쌍둥이: Universe.IMAGE_MODE_SELECT_NOVEL
    @Query(
        "UPDATE universes SET imageNovelId = NULL, " +
            "imageMode = CASE WHEN imageMode = 'select_novel' THEN 'none' ELSE imageMode END " +
            "WHERE imageNovelId = :novelId"
    )
    suspend fun clearImageNovelRef(novelId: Long)

    @Query("DELETE FROM universes")
    suspend fun deleteAll()
}
