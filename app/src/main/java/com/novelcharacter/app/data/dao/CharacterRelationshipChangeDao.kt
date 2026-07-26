package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterRelationshipChange

@Dao
interface CharacterRelationshipChangeDao {
    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId ORDER BY year ASC, month ASC, day ASC")
    fun getChangesForRelationship(relationshipId: Long): LiveData<List<CharacterRelationshipChange>>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesForRelationshipList(relationshipId: Long): List<CharacterRelationshipChange>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId AND year <= :year ORDER BY year DESC, month DESC, day DESC LIMIT 1")
    suspend fun getChangeAtYear(relationshipId: Long, year: Int): CharacterRelationshipChange?

    @Query("SELECT * FROM character_relationship_changes ORDER BY year ASC")
    suspend fun getAllChanges(): List<CharacterRelationshipChange>

    @Query("SELECT * FROM character_relationship_changes WHERE relationshipId = :relationshipId AND year = :year AND (month = :month OR (month IS NULL AND :month IS NULL)) AND (day = :day OR (day IS NULL AND :day IS NULL)) LIMIT 1")
    suspend fun getChangeByNaturalKey(relationshipId: Long, year: Int, month: Int?, day: Int?): CharacterRelationshipChange?

    /** 엑셀 왕복 안정 식별자 매칭 — 코드 우선, 자연키는 구버전 파일 폴백 */
    @Query("SELECT * FROM character_relationship_changes WHERE code = :code LIMIT 1")
    suspend fun getChangeByCode(code: String): CharacterRelationshipChange?

    /**
     * 특정 사건에 연결된 관계 변화 이력 (휴지통 사건 스냅샷용 — B-1).
     * 사건이 지워지면 이 연결은 FK SET_NULL로 **조용히** 끊긴다 — 이력 자체는 살아남으므로
     * 어떤 캐릭터 스냅샷도 그 손실을 담지 못한다. 사건 스냅샷이 code로 담아 되붙인다.
     */
    @Query("SELECT * FROM character_relationship_changes WHERE eventId = :eventId")
    suspend fun getChangesByEventList(eventId: Long): List<CharacterRelationshipChange>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: CharacterRelationshipChange): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(changes: List<CharacterRelationshipChange>)

    @Update
    suspend fun update(change: CharacterRelationshipChange)

    @Delete
    suspend fun delete(change: CharacterRelationshipChange)

    @Query("DELETE FROM character_relationship_changes WHERE relationshipId = :relationshipId")
    suspend fun deleteAllByRelationship(relationshipId: Long)

    @Query("DELETE FROM character_relationship_changes")
    suspend fun deleteAll()

    @Query("SELECT id FROM character_relationship_changes")
    suspend fun getAllChangeIds(): List<Long>

    @Query("DELETE FROM character_relationship_changes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
