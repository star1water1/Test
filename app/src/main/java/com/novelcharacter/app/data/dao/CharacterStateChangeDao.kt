package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.CharacterStateChange

@Dao
interface CharacterStateChangeDao {
    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year <= :year ORDER BY year ASC, month ASC, day ASC")
    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND fieldKey = :fieldKey ORDER BY year ASC")
    suspend fun getChangesByField(characterId: Long, fieldKey: String): List<CharacterStateChange>

    /**
     * [getChangesByField]의 **일괄 짝** — 캐릭터마다 한 질의를 치던 자리를 접는다 (R-54 통로 필수).
     *
     * `ORDER BY`가 단건 짝과 글자 그대로 같지만 **조각 경계에서 어긋나므로**, 캐릭터별
     * 묶음의 순서까지 종전과 같아야 하는 호출부는 받아서 다시 세운다
     * (`TrashRepository.snapshotEvent`가 그렇게 쓴다 — 스냅샷 내용의 순서가 걸린 자리다).
     *
     * `fieldKey`가 목록 밖 변수 하나를 더 쓰므로 통로에 예약분 1을 밝힌다.
     */
    /**
     * 여러 캐릭터의 이력을 **한 번에** — 캐릭터마다 [getChangesByCharacterList]를 치던 자리의 통로.
     *
     * 표준연도를 한 번 고치면 그 작품의 캐스트 전원이 재동기화되는데, 종전에는 그 루프가
     * 캐릭터마다 이 표를 두 번(연동 판정 · 이력 갱신) 물었다 — 질의 수가 인원에 비례한다.
     * 999-변수 상한은 [com.novelcharacter.app.util.SqlInChunks]가 든다.
     */
    @Query("SELECT * FROM character_state_changes WHERE characterId IN (:characterIds) ORDER BY year ASC")
    suspend fun getChangesForCharacters(characterIds: List<Long>): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE characterId IN (:characterIds) AND fieldKey = :fieldKey ORDER BY year ASC")
    suspend fun getChangesByCharacterIdsAndField(
        characterIds: List<Long>,
        fieldKey: String
    ): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes ORDER BY characterId ASC, year ASC, month ASC, day ASC")
    suspend fun getAllChangesList(): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE id = :id")
    suspend fun getChangeById(id: Long): CharacterStateChange?

    /**
     * 일괄 삭제 영향 요약용 — 선택 캐릭터의 사용자 의미 상태변화 수.
     * 내부 플래그(__std_year_link)는 사용자에게 보이지 않으므로 집계에서 제외한다.
     */
    // SQL 쌍둥이: StandardYearLink.KEY
    @Query("SELECT COUNT(*) FROM character_state_changes WHERE characterId IN (:ids) AND fieldKey != '__std_year_link'")
    suspend fun countByCharacterIds(ids: List<Long>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: CharacterStateChange): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(changes: List<CharacterStateChange>)

    @Update
    suspend fun update(change: CharacterStateChange)

    /** 일괄 갱신 — 사건 연쇄 이동이 캐릭터마다 한 번씩 치던 자리(R-54의 결). */
    @Update
    suspend fun updateAll(changes: List<CharacterStateChange>)

    @Delete
    suspend fun delete(change: CharacterStateChange)

    @Query("DELETE FROM character_state_changes WHERE characterId = :characterId")
    suspend fun deleteAllByCharacter(characterId: Long)

    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year = :year AND fieldKey = :fieldKey AND newValue = :newValue LIMIT 1")
    suspend fun getChangeByNaturalKey(characterId: Long, year: Int, fieldKey: String, newValue: String): CharacterStateChange?

    /**
     * 시점(month/day)까지 포함한 정밀 중복 판정 — 일괄 상태변화에서 '연도만 같고 시점이 다른 별개 기록'이
     * 자연키 4개 판정에 조용히 스킵되는 것을 막는다. `IS`는 NULL-안전 비교(NULL IS NULL = true).
     */
    @Query("SELECT * FROM character_state_changes WHERE characterId = :characterId AND year = :year AND month IS :month AND day IS :day AND fieldKey = :fieldKey AND newValue = :newValue LIMIT 1")
    suspend fun getChangeByExactKey(characterId: Long, year: Int, month: Int?, day: Int?, fieldKey: String, newValue: String): CharacterStateChange?

    /** 엑셀 왕복 안정 식별자 매칭 — 코드 우선, 자연키는 구버전 파일 폴백 */
    @Query("SELECT * FROM character_state_changes WHERE code = :code LIMIT 1")
    suspend fun getChangeByCode(code: String): CharacterStateChange?

    /**
     * 이미 쓰이고 있는 code — 여러 행을 한 번에 되살릴 때 **재발급 대상**을 가려낸다
     * (`DuelMatchDao.getExistingCodes`와 같은 규약). code는 유니크라, 하나만 겹쳐도
     * `insertAll`이 통째로 엎어져 그 조각이 전부 안 살아난다.
     */
    @Query("SELECT code FROM character_state_changes WHERE code IN (:codes)")
    suspend fun getExistingCodes(codes: List<String>): List<String>

    /** 필드 키 변경 시 해당 세계관 캐릭터들의 상태변화 이력 fieldKey를 일괄 갱신 (무통보 이력 파손 방지) */
    @Query("""
        UPDATE character_state_changes SET fieldKey = :newKey
        WHERE fieldKey = :oldKey AND characterId IN (
            SELECT c.id FROM characters c JOIN novels n ON c.novelId = n.id WHERE n.universeId = :universeId
        )
    """)
    suspend fun migrateFieldKeyForUniverse(universeId: Long, oldKey: String, newKey: String): Int

    /**
     * 위 조인이 **원리적으로 빠뜨리는 캐릭터** — `novelId IS NULL`(미분류)이면서 옛 키의
     * 이력을 실제로 든 것들 (B-13).
     *
     * NULL 외래키는 내부 조인에서 떨어지므로 [migrateFieldKeyForUniverse]는 이 캐릭터들을
     * 절대 만나지 못한다. 처분은 `UnassignedHistoryScope`가 정한다 — 여기서 통째로 옮기면
     * 다른 세계관의 같은 키 이력까지 갈아엎는다.
     *
     * **이력이 있는 캐릭터만 돌려준다** — 미분류 전체를 돌려주면 아무 일도 없던 캐릭터가
     * '가리지 못해 남긴 것'으로 세어져 고지가 거짓이 된다.
     */
    @Query("""
        SELECT DISTINCT c.id FROM characters c
        JOIN character_state_changes s ON s.characterId = c.id
        WHERE c.novelId IS NULL AND s.fieldKey = :oldKey
    """)
    suspend fun getUnassignedCharactersWithFieldKey(oldKey: String): List<Long>

    /** 가려낸 캐릭터만 이관한다 (B-13) — 대상은 `UnassignedHistoryScope`가 고른다. */
    @Query("""
        UPDATE character_state_changes SET fieldKey = :newKey
        WHERE fieldKey = :oldKey AND characterId IN (:characterIds)
    """)
    suspend fun migrateFieldKeyForCharacters(characterIds: List<Long>, oldKey: String, newKey: String): Int

    /** 옛 키로 남는 이력 건수 — 고지가 캐릭터 수가 아니라 **실제 건수**를 말하게 한다 (B-13). */
    @Query("""
        SELECT COUNT(*) FROM character_state_changes
        WHERE fieldKey = :oldKey AND characterId IN (:characterIds)
    """)
    suspend fun countByFieldKeyForCharacters(characterIds: List<Long>, oldKey: String): Int

    /** 값 라이브러리 rename/merge 전파용 — 해당 세계관 캐릭터들의 특정 필드 이력.
     *  newValue 치환은 SQL이 아니라 토크나이저(다중값 콤마 결합)로 행 단위 처리한다. */
    @Query("""
        SELECT * FROM character_state_changes
        WHERE fieldKey = :fieldKey AND characterId IN (
            SELECT c.id FROM characters c JOIN novels n ON c.novelId = n.id WHERE n.universeId = :universeId
        )
    """)
    suspend fun getChangesByFieldKeyForUniverse(universeId: Long, fieldKey: String): List<CharacterStateChange>

    /**
     * 같은 용도의 **캐릭터 지정판** — 위 질의가 `JOIN novels` 때문에 원리적으로 못 만나는
     * 미분류 캐릭터(B-13)를 [migrateFieldKeyForCharacters]와 **같은 방식으로** 집는다.
     *
     * 대상은 `UnassignedHistoryScope`가 가린다 — 키는 세계관 안에서만 유일해서 이력 행
     * 하나만으로는 그것이 어느 세계관의 것인지 알 수 없기 때문이다.
     */
    @Query("""
        SELECT * FROM character_state_changes
        WHERE fieldKey = :fieldKey AND characterId IN (:characterIds)
    """)
    suspend fun getChangesByFieldKeyForCharacters(characterIds: List<Long>, fieldKey: String): List<CharacterStateChange>

    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month = :month AND day = :day")
    suspend fun getChangesByFieldAndDate(fieldKey: String, month: Int, day: Int): List<CharacterStateChange>

    /** 생일(month/day)이 있는 상태변경 전체 — 일회성 조회 (Worker/Widget용) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    suspend fun getChangesWithDate(fieldKey: String): List<CharacterStateChange>

    /** 생일(month/day)이 있는 상태변경 전체 — 반응형 (테이블 변경 시 자동 갱신) */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey AND month IS NOT NULL AND day IS NOT NULL")
    fun observeChangesWithDate(fieldKey: String): LiveData<List<CharacterStateChange>>

    /**
     * 그 키의 이력 전량 — 필드 정의 삭제가 **지우기 전에 스냅샷**을 뜨는 자리다.
     * 지우는 짝([deleteChangesByFieldKey])과 범위가 글자 그대로 같아야 한다.
     */
    @Query("SELECT * FROM character_state_changes WHERE fieldKey = :fieldKey")
    suspend fun getChangesByFieldKey(fieldKey: String): List<CharacterStateChange>

    @Query("DELETE FROM character_state_changes WHERE fieldKey = :fieldKey")
    suspend fun deleteChangesByFieldKey(fieldKey: String)

    /** 위 삭제의 **읽기 쌍둥이** — 범위가 글자 그대로 같아야 스냅샷이 지워질 것을 전부 담는다. */
    @Query("""
        SELECT * FROM character_state_changes
        WHERE fieldKey = :fieldKey
          AND characterId IN (
              SELECT c.id FROM characters c
              INNER JOIN novels n ON c.novelId = n.id
              WHERE n.universeId = :universeId
          )
    """)
    suspend fun getChangesByFieldKeyAndUniverse(fieldKey: String, universeId: Long): List<CharacterStateChange>

    @Query("""
        DELETE FROM character_state_changes
        WHERE fieldKey = :fieldKey
          AND characterId IN (
              SELECT c.id FROM characters c
              INNER JOIN novels n ON c.novelId = n.id
              WHERE n.universeId = :universeId
          )
    """)
    suspend fun deleteChangesByFieldKeyAndUniverse(fieldKey: String, universeId: Long)

    /** 세계관에 속한 모든 필드의 state change 삭제 (세계관 삭제 시 사용) */
    // `deleteAllChangesByUniverse`는 없앴다(R-24 — 2026.08.23). **언제나 0행을 지웠다.**
    //
    // 그 질의의 두 조건 중 뒤엣것이 `characterId IN (이 세계관 캐릭터)`였는데, 세계관 삭제는
    // **그 캐릭터들을 먼저 지운다**(`deleteCharactersCascade`, 같은 트랜잭션의 1단계)이고
    // 이 표는 `characterId`에 `onDelete = CASCADE`가 걸려 있어 그 시점에 이미 함께 사라진다.
    // 즉 4단계에 닿았을 때 후보 집합이 비어 있다. 붙어 있던 주석은 *"fieldKey가 문자열
    // 참조라 CASCADE 대상이 아니므로 명시적 삭제"*라고 적었는데, 지우지 못하는 몫은
    // **미분류 캐릭터(`novelId IS NULL`)의 이력**이고 그 질의의 `INNER JOIN novels`는
    // 애초에 그들을 만나지 못했다(B-13).
    //
    // **그 몫을 여기서 지우면 안 된다**: 이력 행에는 세계관이 없고 `fieldKey`는
    // `(universeId, entityType, key)`에서만 유일하므로, 키만 보고 지우면 **다른 세계관의
    // 이력이 함께 사라진다**(`UnassignedHistoryScope`의 KDoc이 같은 이유로 일괄 UPDATE를
    // 금지한다). 가리는 법이 필요하면 그 순수 판정을 지날 것.

    @Query("DELETE FROM character_state_changes")
    suspend fun deleteAll()

    @Query("SELECT id FROM character_state_changes")
    suspend fun getAllChangeIds(): List<Long>

    /**
     * id로 여러 행을 한 번에 읽는다 — 엑셀 '없는 항목 삭제'가 **지우기 전에 스냅샷**을
     * 뜨려면 행 자체가 필요한데, 종전에는 id만 읽는 통로뿐이라 그 갈래가 휴지통을
     * 우회했다. 999-변수 상한은 `SqlInChunks`가 든다(R-54).
     */
    @Query("SELECT * FROM character_state_changes WHERE id IN (:ids)")
    suspend fun getChangesByIds(ids: List<Long>): List<CharacterStateChange>

    @Query("DELETE FROM character_state_changes WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 여러 캐릭터의 상태변화 일괄 조회 — 월드패키지 내보내기의 범위 질의(R-54 통로 필수).
     * 정렬은 호출부가 [com.novelcharacter.app.share.WorldPackageScope]의 계약으로 세운다 —
     * 청크를 이어 붙이면 질의의 `ORDER BY`가 조각 경계에서 어긋나기 때문이다.
     */
    @Query("SELECT * FROM character_state_changes WHERE characterId IN (:characterIds)")
    suspend fun getChangesByCharacterIds(characterIds: List<Long>): List<CharacterStateChange>
}
