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

    /**
     * 아무 캐릭터 하나 — *오늘의 캐릭터* 위젯이 생일자가 없을 때 뽑는 자리다.
     *
     * **표 전량을 올려 `random()`을 부르지 않는다.** 종전 위젯은 `getAllCharactersList()`로
     * 캐릭터를 전부 객체로 지은 뒤 하나만 쓰고 버렸다 — 이름 한 줄을 위해 표 전체를
     * CursorWindow에 담고 행마다 객체를 만드는 것이라, 저장소가 커지면 위젯이 `goAsync()`가
     * 준 시간을 넘겨 *"업데이트를 미뤘습니다"*만 보이게 된다(그 시간 상한은 2c56eeb가 걸어
     * 두었고, **줄여야 할 것은 질의 자체라고 같은 커밋이 적어 두었다**).
     *
     * `ORDER BY RANDOM()`도 표를 훑기는 하지만 **행을 객체로 짓지 않고 한 행만 돌려준다** —
     * 비용이 행 수에 붙되 상수가 훨씬 작고, 메모리는 한 행이다.
     */
    @Query("SELECT * FROM characters ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomCharacter(): Character?

    /**
     * 재색인 전용 — **표시 정렬이 아니라 저장 순서**로 읽는다.
     *
     * 위 목록 질의는 화면을 위해 `isPinned DESC`를 앞세우는데, 재색인은 읽은 차례를
     * 그대로 `displayOrder`에 굽는다. 그 질의로 읽으면 **고정 여부가 저장 순서에 새겨져**
     * 고정을 풀어도 사용자가 끌어 놓은 차례가 돌아오지 않는다(되돌릴 수 없는 쓰기다).
     * 그래서 여기에는 **고정 축이 없다** — 같은 값이면 id로 갈라 결정적으로 만든다.
     */
    @Query("SELECT * FROM characters ORDER BY displayOrder ASC, id ASC")
    suspend fun getAllCharactersByDisplayOrder(): List<Character>

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

    /**
     * 순서만 쓴다 — 재정렬은 `displayOrder` 한 칸의 일이다.
     *
     * 종전에는 화면이 들고 있던 **엔티티 사본 전체**를 `@Update`로 되썼다. 순서 편집을 여는
     * 동안 다른 화면·들이기·백그라운드 작업이 같은 행을 고치면 그 되쓰기가 **말없이 되돌렸다**
     * (개발 의도 2번 '변수 제어'). 문장 수는 `@Update` 목록과 같고(행마다 한 문장) 쓰는 열만
     * 줄어든다 — 호출부가 한 트랜잭션으로 묶는다.
     */
    @Query("UPDATE characters SET displayOrder = :order WHERE id = :id")
    suspend fun setDisplayOrder(id: Long, order: Long)

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

    @Query("SELECT c.id FROM characters c INNER JOIN novels n ON c.novelId = n.id WHERE n.universeId = :universeId")
    suspend fun getCharacterIdsByUniverse(universeId: Long): List<Long>

    /** 작품 미지정(미분류) 캐릭터 id — 엑셀 '미분류 캐릭터' 시트의 삭제 대상 산출용 */
    @Query("SELECT id FROM characters WHERE novelId IS NULL")
    suspend fun getUnclassifiedCharacterIds(): List<Long>

    /**
     * **필드 구역이 전역인 캐릭터** id — 작품이 없거나, **작품에 세계관이 없는** 캐릭터.
     *
     * [getUnclassifiedCharacterIds]와 다르다: 그쪽은 `novelId IS NULL`만 본다. 세계관 없는
     * 작품에 든 캐릭터도 세계관이 없으므로 전역 구역의 필드를 쓰는데
     * ([com.novelcharacter.app.data.repository.UniverseRepository.getFieldsForCharacterScope] ·
     * 내보내기의 '미분류 캐릭터' 시트도 `novel?.universeId == null`로 고른다),
     * 그 갈래가 좁은 질의에서 빠져 있었다.
     *
     * `LEFT JOIN`이라야 한다 — 내부 조인은 `novelId IS NULL`인 행을 통째로 떨어뜨린다(B-13).
     */
    @Query(
        "SELECT c.id FROM characters c LEFT JOIN novels n ON c.novelId = n.id " +
            "WHERE c.novelId IS NULL OR n.universeId IS NULL"
    )
    suspend fun getGlobalScopeCharacterIds(): List<Long>

    /**
     * 코드로 일괄 조회 (휴지통 복원의 참조 재해석용 — B-1).
     * 단건 조회를 참조 수만큼 반복하면 세계관 복원처럼 참조가 수백 건인 경로가 무너진다.
     * 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한).
     */
    @Query("SELECT * FROM characters WHERE code IN (:codes)")
    suspend fun getCharactersByCodes(codes: List<String>): List<Character>

    /**
     * 여러 작품에 속한 캐릭터 일괄 조회 — 월드패키지 내보내기의 범위 질의(R-54 통로 필수).
     * 종전에는 내보내기가 `getAllCharactersList()`로 전량을 올린 뒤 메모리에서 걸렀다.
     */
    @Query("SELECT * FROM characters WHERE novelId IN (:novelIds)")
    suspend fun getCharactersByNovelIds(novelIds: List<Long>): List<Character>
}
