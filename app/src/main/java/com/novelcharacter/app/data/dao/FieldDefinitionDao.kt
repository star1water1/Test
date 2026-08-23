package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 필드 정의 DAO. entityType 파라미터는 기본값 "character"로,
 * 기존 캐릭터 경로 호출부(통계·편집·엑셀 등)는 시그니처 변경 없이 캐릭터 필드만 받는다.
 * 사건 필드(B-10)는 entityType = "event"를 명시해 조회한다.
 */
@Dao
interface FieldDefinitionDao {
    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND entityType = :entityType ORDER BY displayOrder ASC")
    fun getFieldsByUniverse(universeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): LiveData<List<FieldDefinition>>

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND entityType = :entityType ORDER BY displayOrder ASC")
    suspend fun getFieldsByUniverseList(universeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    // ── 전역 구역 (universeId IS NULL — B-119 확장) ──
    // `= :universeId`에 null을 넘기면 SQLite가 아무것도 못 찾으므로(NULL은 =로 비교되지 않는다)
    // IS NULL 질의를 따로 둔다. 이 구역의 행은 템플릿의 그림자다(FieldDefinition KDoc).

    @Query("SELECT * FROM field_definitions WHERE universeId IS NULL AND entityType = :entityType ORDER BY displayOrder ASC")
    suspend fun getGlobalFieldsList(entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    @Query("SELECT * FROM field_definitions WHERE universeId IS NULL ORDER BY entityType ASC, displayOrder ASC")
    suspend fun getGlobalFieldsAllTypes(): List<FieldDefinition>

    @Query("DELETE FROM field_definitions WHERE universeId IS NULL AND id IN (:ids)")
    suspend fun deleteGlobalByIds(ids: List<Long>)

    @Query("SELECT * FROM field_definitions WHERE universeId IS NULL AND `key` = :key AND entityType = :entityType")
    suspend fun getGlobalFieldByKey(key: String, entityType: String = FieldDefinition.ENTITY_CHARACTER): FieldDefinition?

    @Query("SELECT * FROM field_definitions WHERE id = :id")
    suspend fun getFieldById(id: Long): FieldDefinition?

    /**
     * id 목록으로 조회 — 세계관/entityType을 가리지 않는다.
     * 미분류(작품 미배정) 캐릭터가 보관 중인 필드값처럼 **현재 세계관 밖 정의를 가리키는**
     * 값을 화면에 드러낼 때 쓴다 (N2 — 존재를 알 수 없는 데이터를 남기지 않는다).
     */
    @Query("SELECT * FROM field_definitions WHERE id IN (:ids) ORDER BY universeId ASC, displayOrder ASC")
    suspend fun getFieldsByIds(ids: List<Long>): List<FieldDefinition>

    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId AND `key` = :key AND entityType = :entityType")
    suspend fun getFieldByKey(universeId: Long, key: String, entityType: String = FieldDefinition.ENTITY_CHARACTER): FieldDefinition?

    /**
     * 세계관을 가리지 않고 **같은 키를 든 필드 전부** (B-11 — 크로스-세계관 필드 필터).
     *
     * 키는 세계관 안에서만 유니크하므로 여기 여러 건이 나오는 것이 정상이다 — 세계관 A의
     * '성별'과 세계관 B의 '성별'은 **다른 id에 같은 키**다. 전역 뷰(통합 검색·캐릭터 탭)의
     * 정렬이 이미 키로 병합되므로(`CharacterListPreset.sortFieldKey`) 필터도 같은 잣대를
     * 써야 한 화면 안에서 두 규칙이 갈리지 않는다.
     *
     * **무소속(전역) 필드도 함께 나온다** — `universeId IS NULL`을 거르지 않는 것은
     * 그쪽도 같은 키의 같은 뜻이기 때문이다(B-119 확장이 연 구역).
     */
    @Query("SELECT * FROM field_definitions WHERE `key` = :key AND entityType = :entityType")
    suspend fun getFieldsByKey(key: String, entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(field: FieldDefinition): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(fields: List<FieldDefinition>)

    @Update
    suspend fun update(field: FieldDefinition)

    @Update
    suspend fun updateAll(fields: List<FieldDefinition>)

    @Delete
    suspend fun delete(field: FieldDefinition)

    @Query("SELECT * FROM field_definitions WHERE entityType = :entityType ORDER BY universeId ASC, displayOrder ASC")
    suspend fun getAllFieldsList(entityType: String = FieldDefinition.ENTITY_CHARACTER): List<FieldDefinition>

    /** 캐릭터·사건 구분 없이 전체 필드 (값 라이브러리 수확/시드용) */
    @Query("SELECT * FROM field_definitions ORDER BY universeId ASC, entityType ASC, displayOrder ASC")
    suspend fun getAllFieldsAllTypes(): List<FieldDefinition>

    /**
     * 한 세계관의 필드를 entityType 구분 없이 전부 (휴지통 세계관 스냅샷용 — B-1).
     * 세계관 삭제는 FK CASCADE로 **모든** entityType의 정의를 함께 지우므로,
     * 캐릭터 필드만 담은 스냅샷은 사건 필드를 조용히 잃는다.
     */
    @Query("SELECT * FROM field_definitions WHERE universeId = :universeId ORDER BY entityType ASC, displayOrder ASC")
    suspend fun getFieldsByUniverseAllTypes(universeId: Long): List<FieldDefinition>

    @Query("DELETE FROM field_definitions WHERE universeId = :universeId")
    suspend fun deleteAllByUniverse(universeId: Long)

    @Query("SELECT universeId, COUNT(*) as cnt FROM field_definitions WHERE universeId IN (:universeIds) GROUP BY universeId")
    suspend fun getFieldCountsByUniverses(universeIds: List<Long>): List<UniverseFieldCount>

    /** 특정 필드를 제외하고 같은 key를 가진 필드가 존재하는지 확인 (같은 entityType 내) */
    @Query("SELECT COUNT(*) FROM field_definitions WHERE `key` = :key AND id != :excludeId AND entityType = :entityType")
    suspend fun countFieldsByKeyExcluding(key: String, excludeId: Long, entityType: String = FieldDefinition.ENTITY_CHARACTER): Int

    @Query("DELETE FROM field_definitions")
    suspend fun deleteAll()
}

data class UniverseFieldCount(val universeId: Long, val cnt: Int)
