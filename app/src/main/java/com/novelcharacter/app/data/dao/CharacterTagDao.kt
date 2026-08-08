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

    /** 주어진 태그 중 하나라도 가진 캐릭터 id(OR). 태그 필터 전용 — getAllTagsList 전체 스캔 대체. */
    @Query("SELECT DISTINCT characterId FROM character_tags WHERE tag IN (:tags)")
    suspend fun getCharacterIdsByTags(tags: List<String>): List<Long>

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

    /**
     * 캐릭터별 태그를 **한 번에** 읽는다 — 누가 어느 태그를 들었는지까지 든다 (B-167).
     *
     * 위 [getDistinctTagsForCharacters]와 갈리는 자리가 그것이다: 그쪽은 *"이 무리에 어떤
     * 태그들이 있는가"*(일괄 편집의 후보 목록)를 묻고, 이쪽은 *"각자 무엇을 들었는가"*를
     * 묻는다. 대결 카드가 참가자마다 자기 태그를 그려야 해서 한 줄로 뭉뚱그릴 수 없다.
     *
     * 호출측이 `characterIds`를 청크로 나눠 넣는다 — SQLite 변수 상한(999) 때문이다.
     */
    @Query("SELECT * FROM character_tags WHERE characterId IN (:characterIds) ORDER BY tag ASC")
    suspend fun getTagsForCharacters(characterIds: List<Long>): List<CharacterTag>

    @Query("DELETE FROM character_tags WHERE characterId IN (:characterIds) AND tag IN (:tags)")
    suspend fun deleteTagsFromCharacters(characterIds: List<Long>, tags: List<String>)
}
