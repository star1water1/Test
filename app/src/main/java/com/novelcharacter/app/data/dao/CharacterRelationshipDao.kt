package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterRelationship

@Dao
interface CharacterRelationshipDao {
    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY displayOrder ASC, createdAt DESC")
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>>

    @Query("SELECT * FROM character_relationships WHERE characterId1 = :characterId OR characterId2 = :characterId ORDER BY displayOrder ASC, createdAt DESC")
    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship>

    /** 안정 식별자 조회 — 엑셀 왕복에서 '관계 유형'이 편집돼도 같은 관계로 인식하기 위한 기준 */
    @Query("SELECT * FROM character_relationships WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): CharacterRelationship?

    @Update
    suspend fun updateAll(relationships: List<CharacterRelationship>)

    @Query("SELECT * FROM character_relationships WHERE id = :id")
    suspend fun getById(id: Long): CharacterRelationship?

    @Query("SELECT * FROM character_relationships ORDER BY createdAt DESC")
    suspend fun getAllRelationships(): List<CharacterRelationship>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relationship: CharacterRelationship): Long

    @Update
    suspend fun update(relationship: CharacterRelationship)

    @Delete
    suspend fun delete(relationship: CharacterRelationship)

    @Query("DELETE FROM character_relationships WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 특정 세력의 자동 관계 중, 지정 캐릭터가 포함된 것 삭제 */
    @Query("""
        DELETE FROM character_relationships
        WHERE factionId = :factionId
        AND (characterId1 = :characterId OR characterId2 = :characterId)
    """)
    suspend fun deleteFactionRelationshipsForCharacter(factionId: Long, characterId: Long)

    /** 특정 세력의 모든 자동 관계 삭제 */
    @Query("DELETE FROM character_relationships WHERE factionId = :factionId")
    suspend fun deleteAllByFaction(factionId: Long)

    /** 세력의 자동 관계 전부 (휴지통 세력 스냅샷용 — B-1). 삭제 전에 담아 둔다. */
    @Query("SELECT * FROM character_relationships WHERE factionId = :factionId")
    suspend fun getByFactionList(factionId: Long): List<CharacterRelationship>

    /**
     * id로 일괄 조회 (휴지통 사건 스냅샷용 — B-1).
     * 사건에 매달린 관계 변화 이력의 양 끝 캐릭터를 확인해, 그 캐릭터도 함께 지워지는지 가른다.
     * 호출부에서 900개 단위로 청크할 것 (SQLite 999-변수 상한).
     */
    @Query("SELECT * FROM character_relationships WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<CharacterRelationship>

    /** 특정 세력의 자동 관계 중 지정 캐릭터가 포함된 것 조회 */
    @Query("""
        SELECT * FROM character_relationships
        WHERE factionId = :factionId
        AND (characterId1 = :characterId OR characterId2 = :characterId)
    """)
    suspend fun getFactionRelationshipsForCharacter(factionId: Long, characterId: Long): List<CharacterRelationship>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(relationships: List<CharacterRelationship>): List<Long>

    @Query("DELETE FROM character_relationships")
    suspend fun deleteAll()

    @Query("SELECT id FROM character_relationships")
    suspend fun getAllRelationshipIds(): List<Long>

    /**
     * 일괄 삭제 영향 요약용 — 선택 캐릭터가 한쪽 끝이라도 포함된 관계 id.
     * 두 끝이 서로 다른 청크에 나뉘어도 같은 id가 반환되므로, 호출측에서 Set으로 교차청크 중복을 제거한다.
     */
    @Query("SELECT id FROM character_relationships WHERE characterId1 IN (:ids) OR characterId2 IN (:ids)")
    suspend fun getRelationshipIdsForCharacters(ids: List<Long>): List<Long>

    /**
     * 한쪽 끝(characterId1)이 목록 안인 관계 — 월드패키지 내보내기의 범위 질의(R-54 통로 필수).
     *
     * **끝마다 따로 묻는 이유는 바인드 상한이다.** `characterId1 IN (:ids) OR characterId2 IN (:ids)`는
     * Room이 목록을 두 번 펼쳐 변수를 `2 × 길이`만큼 쓰므로, 조각 상한(900)을 그대로 넘기면
     * 1,800이 되어 API 31 미만의 기본 상한(999)을 넘긴다([getRelationshipIdsForCharacters]가
     * 그 모양이다). 겹은 [com.novelcharacter.app.share.WorldPackageScope.relationshipIdsInScope]가 없앤다.
     */
    @Query("SELECT * FROM character_relationships WHERE characterId1 IN (:characterIds)")
    suspend fun getRelationshipsByEnd1(characterIds: List<Long>): List<CharacterRelationship>

    /** 다른 쪽 끝(characterId2)이 목록 안인 관계 — 짝은 [getRelationshipsByEnd1]. */
    @Query("SELECT * FROM character_relationships WHERE characterId2 IN (:characterIds)")
    suspend fun getRelationshipsByEnd2(characterIds: List<Long>): List<CharacterRelationship>
}
