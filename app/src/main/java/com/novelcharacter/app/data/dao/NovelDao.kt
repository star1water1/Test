package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.Novel

@Dao
interface NovelDao {
    // Pinned-first sorting: isPinned DESC ensures pinned items appear at top
    @Query("SELECT * FROM novels ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    fun getAllNovels(): LiveData<List<Novel>>

    @Query("SELECT * FROM novels ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    suspend fun getAllNovelsList(): List<Novel>

    /** 재색인 전용 — 고정 축 없이 저장 순서로 읽는다(사유는 [CharacterDao.getAllCharactersByDisplayOrder]). */
    @Query("SELECT * FROM novels ORDER BY displayOrder ASC, id ASC")
    suspend fun getAllNovelsByDisplayOrder(): List<Novel>

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun getNovelById(id: Long): Novel?

    /** 여러 작품을 한 번에 조회 (배치 세계관 해소 시 캐릭터별 getNovelById N+1 제거용). 999-변수 상한은 호출부에서 청크 처리. */
    @Query("SELECT * FROM novels WHERE id IN (:ids)")
    suspend fun getNovelsByIds(ids: List<Long>): List<Novel>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(novel: Novel): Long

    @Update
    suspend fun update(novel: Novel)

    @Delete
    suspend fun delete(novel: Novel)

    @Query("DELETE FROM novels WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    fun getNovelsByUniverse(universeId: Long): LiveData<List<Novel>>

    @Query("SELECT * FROM novels WHERE universeId = :universeId ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    suspend fun getNovelsByUniverseList(universeId: Long): List<Novel>

    /** 세계관 미배정 작품만 — 목록 화면의 "세계관 미배정만 보기" 필터(캐릭터 쪽 "작품 미배정"과 대칭). */
    @Query("SELECT * FROM novels WHERE universeId IS NULL ORDER BY isPinned DESC, displayOrder ASC, createdAt DESC")
    fun getUnassignedNovels(): LiveData<List<Novel>>

    @Query("SELECT universeId, COUNT(*) as cnt FROM novels WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getNovelCountsByUniverses(universeIds: List<Long>): List<UniverseCount>

    @Query("SELECT * FROM novels WHERE title = :title AND universeId = :universeId LIMIT 1")
    suspend fun getNovelByTitleAndUniverse(title: String, universeId: Long): Novel?

    @Query("SELECT * FROM novels WHERE title = :title AND universeId IS NULL LIMIT 1")
    suspend fun getNovelByTitleNoUniverse(title: String): Novel?

    /** 세계관 무관 동일 제목 조회 (엑셀 가져오기: 유령 작품 중복 생성 전 타 세계관 재사용 판단용) */
    @Query("SELECT * FROM novels WHERE title = :title")
    suspend fun getNovelsByTitleList(title: String): List<Novel>

    @Query("SELECT * FROM novels WHERE code = :code LIMIT 1")
    suspend fun getNovelByCode(code: String): Novel?

    /** 휴지통 복원의 참조 재해석용 일괄 조회 (B-1). 호출부에서 900개 단위로 청크할 것. */
    @Query("SELECT * FROM novels WHERE code IN (:codes)")
    suspend fun getNovelsByCodes(codes: List<String>): List<Novel>

    @Query("SELECT * FROM novels WHERE title LIKE '%' || :query || '%' ESCAPE '\\' OR description LIKE '%' || :query || '%' ESCAPE '\\'")
    fun searchNovels(query: String): LiveData<List<Novel>>

    /**
     * 순서만 쓴다 — 재정렬은 `displayOrder` 한 칸의 일이다.
     *
     * 종전에는 화면이 들고 있던 **엔티티 사본 전체**를 `@Update`로 되썼다. 순서 편집을 여는
     * 동안 다른 화면·들이기·백그라운드 작업이 같은 행을 고치면 그 되쓰기가 **말없이 되돌렸다**
     * (개발 의도 2번 '변수 제어'). 문장 수는 `@Update` 목록과 같고(행마다 한 문장) 쓰는 열만
     * 줄어든다 — 호출부가 한 트랜잭션으로 묶는다.
     */
    @Query("UPDATE novels SET displayOrder = :order WHERE id = :id")
    suspend fun setDisplayOrder(id: Long, order: Long)

    @Update
    suspend fun updateAll(novels: List<Novel>)

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM novels WHERE universeId = :universeId")
    suspend fun getNextDisplayOrderInUniverse(universeId: Long): Long

    @Query("SELECT COALESCE(MAX(displayOrder), -1) + 1 FROM novels WHERE universeId IS NULL")
    suspend fun getNextDisplayOrderNoUniverse(): Long

    @Query("UPDATE novels SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(id: Long, isPinned: Boolean)

    /**
     * 삭제된 캐릭터를 참조하는 `imageCharacterId`를 null로 정리.
     *
     * **모드는 그 캐릭터에 매달린 모드일 때만 내린다.** 종전에는 조건 없이 `'none'`으로
     * 되돌려, *직접 등록*(custom)한 그림을 쓰는 작품이 **엉뚱한 캐릭터 삭제 한 번에
     * 표지를 잃었다** — 폼이 모드와 상관없이 `imageCharacterId`를 함께 싣기 때문에
     * 그 값은 custom 작품에도 남아 있을 수 있다(그 자리도 이 판에서 함께 막았다).
     * 무작위 모드는 특정 캐릭터에 매달리지 않으므로 역시 그대로 둔다.
     */
    // SQL 쌍둥이: Novel.IMAGE_MODE_NONE
    // SQL 쌍둥이: Novel.IMAGE_MODE_SELECT_CHARACTER
    @Query(
        "UPDATE novels SET imageCharacterId = NULL, " +
            "imageMode = CASE WHEN imageMode = 'select_character' THEN 'none' ELSE imageMode END " +
            "WHERE imageCharacterId = :characterId"
    )
    suspend fun clearImageCharacterRef(characterId: Long)

    /** 여러 캐릭터의 이미지 참조 일괄 정리 (배치 삭제용) — 판정은 [clearImageCharacterRef]와 같다. */
    // SQL 쌍둥이: Novel.IMAGE_MODE_NONE
    // SQL 쌍둥이: Novel.IMAGE_MODE_SELECT_CHARACTER
    @Query(
        "UPDATE novels SET imageCharacterId = NULL, " +
            "imageMode = CASE WHEN imageMode = 'select_character' THEN 'none' ELSE imageMode END " +
            "WHERE imageCharacterId IN (:characterIds)"
    )
    suspend fun clearImageCharacterRefs(characterIds: List<Long>)

    @Query("DELETE FROM novels")
    suspend fun deleteAll()
}

data class UniverseCount(val universeId: Long, val cnt: Int)
