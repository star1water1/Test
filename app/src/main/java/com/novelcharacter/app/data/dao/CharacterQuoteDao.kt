package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.CharacterQuote

/**
 * 명대사 표 (사용자 요청 2026.08.20).
 *
 * 차례는 **어디서 읽든 같다** — `sortOrder ASC, id ASC`. 사용자가 드래그로 정한 순서라
 * 화면마다 다르게 정렬하면 *"맨 위 것"*이 자리마다 달라진다(원칙 05).
 */
@Dao
interface CharacterQuoteDao {

    @Query("SELECT * FROM character_quotes WHERE characterId = :characterId ORDER BY sortOrder ASC, id ASC")
    fun getQuotesByCharacter(characterId: Long): LiveData<List<CharacterQuote>>

    @Query("SELECT * FROM character_quotes WHERE characterId = :characterId ORDER BY sortOrder ASC, id ASC")
    suspend fun getQuotesByCharacterList(characterId: Long): List<CharacterQuote>

    /**
     * 여러 캐릭터의 명대사를 **한 번에** — 대결 카드와 생일 모달이 참가자마다 자기 대사를
     * 그려야 해서 한 줄로 뭉뚱그릴 수 없다(태그의 `getTagsForCharacters`와 같은 자리).
     *
     * 호출측이 `characterIds`를 `util/SqlInChunks`로 나눠 넣는다 — SQLite 변수 상한(999).
     */
    @Query("SELECT * FROM character_quotes WHERE characterId IN (:characterIds) ORDER BY sortOrder ASC, id ASC")
    suspend fun getQuotesForCharacters(characterIds: List<Long>): List<CharacterQuote>

    @Query("SELECT * FROM character_quotes ORDER BY characterId ASC, sortOrder ASC, id ASC")
    suspend fun getAllQuotesList(): List<CharacterQuote>

    @Query("SELECT * FROM character_quotes WHERE code = :code LIMIT 1")
    suspend fun getQuoteByCode(code: String): CharacterQuote?

    /**
     * 여러 캐릭터의 대사 수 — 일괄 삭제 고지가 쓴다(R-4). 호출부가 `SqlInChunks.each`로
     * 나눠 넣고 `+=`로 더한다 — 나누기가 계수를 바꾸지 않는다(R-54).
     */
    @Query("SELECT COUNT(*) FROM character_quotes WHERE characterId IN (:characterIds)")
    suspend fun countByCharacterIds(characterIds: List<Long>): Int

    /** 다음 차례 값 — 새 대사는 맨 끝에 붙는다. 비어 있으면 0이다. */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM character_quotes WHERE characterId = :characterId")
    suspend fun nextSortOrder(characterId: Long): Int

    /**
     * **덮어쓰기가 아니라 IGNORE다** (R-60의 근거와 같은 축).
     * `code`가 유니크라 REPLACE로 두면 같은 코드가 들어올 때 **행이 지워졌다 새로 생겨
     * id가 갈리고**, 그 id를 들고 있던 자리(드래그 중인 목록·휴지통 스냅샷)가 어긋난다.
     * 고치는 것은 언제나 [update]이고, 겹치는 코드는 부르는 쪽이 먼저 묻는다.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(quote: CharacterQuote): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(quotes: List<CharacterQuote>)

    @Update
    suspend fun update(quote: CharacterQuote)

    @Update
    suspend fun updateAll(quotes: List<CharacterQuote>)

    @Delete
    suspend fun delete(quote: CharacterQuote)

    @Query("DELETE FROM character_quotes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM character_quotes WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Query("DELETE FROM character_quotes")
    suspend fun deleteAll()                                       // 초기화(ResetPlan)
}
