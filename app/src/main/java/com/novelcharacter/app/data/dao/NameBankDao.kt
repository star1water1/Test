package com.novelcharacter.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.novelcharacter.app.data.model.NameBankEntry

@Dao
interface NameBankDao {
    @Query("SELECT * FROM name_bank ORDER BY createdAt DESC")
    fun getAllNames(): LiveData<List<NameBankEntry>>

    @Query("SELECT * FROM name_bank ORDER BY createdAt DESC")
    suspend fun getAllNamesList(): List<NameBankEntry>

    @Query("SELECT * FROM name_bank WHERE isUsed = 0 ORDER BY createdAt DESC")
    fun getAvailableNames(): LiveData<List<NameBankEntry>>

    @Query("SELECT * FROM name_bank WHERE isUsed = 0 ORDER BY createdAt DESC")
    suspend fun getAvailableNamesList(): List<NameBankEntry>

    /**
     * 이름이 같은 엔트리 — 저장 시 자동 대조의 후보 조회 (B-124 ⓑ).
     *
     * **조건이 [com.novelcharacter.app.util.NameBankMatch.normalize]와 한 벌이다:**
     * 앞뒤 공백을 걷고(`TRIM`) ASCII 대소문자를 접는다(`COLLATE NOCASE`). 순수 규칙을
     * 유니코드 전체로 넓히면 이 조회가 후보를 주지 않는 자리에서 판정만 일치해 어긋난다 —
     * 그래서 좁은 쪽(SQL)에 맞춰 두었고, 그 사실을 양쪽 KDoc과 하네스가 함께 잠근다.
     *
     * 최종 판정은 여기가 아니라 `NameBankMatch.planOnSave`다 — 이 조회는 **후보를 좁히는
     * 것까지만** 한다(넓게 줘도 무해하도록 그쪽이 다시 거른다).
     *
     * `name`에는 색인이 없어 작은 표 전수 훑기다. 저장마다 1회이고 목표 규모(×30)에서
     * 1,230행이라 새 비용 축이 아니다 — 색인을 더하려면 마이그레이션이 따라오므로
     * 실사용이 요구할 때 연다(설계 8-4의 *"색인 있는 작은 표"*는 실제 스키마와 달랐다).
     */
    @Query("SELECT * FROM name_bank WHERE TRIM(name) = :name COLLATE NOCASE")
    suspend fun findByName(name: String): List<NameBankEntry>

    /** 엑셀 가져오기 왕복 매칭용 코드 조회 (F3-D) */
    @Query("SELECT * FROM name_bank WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): NameBankEntry?

    /**
     * 이 캐릭터가 쓰고 있는 이름 은행 엔트리 (B-3, 휴지통 스냅샷용).
     *
     * **사용 표시를 푸는 `resetUsageByCharacter`보다 먼저** 불러야 한다 — 삭제 경로는
     * 스냅샷을 남긴 뒤에 사용 표시를 풀므로 그 순서가 이미 지켜져 있다.
     */
    @Query("SELECT * FROM name_bank WHERE usedByCharacterId = :characterId")
    suspend fun getEntriesUsedByCharacter(characterId: Long): List<NameBankEntry>

    /** 일괄 캐릭터 등록의 선택 엔트리 조회. 호출부에서 900개 단위로 청크할 것. */
    @Query("SELECT * FROM name_bank WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<NameBankEntry>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: NameBankEntry): Long

    @Update
    suspend fun update(entry: NameBankEntry)

    @Delete
    suspend fun delete(entry: NameBankEntry)

    @Query("UPDATE name_bank SET isUsed = 1, usedByCharacterId = :characterId WHERE id = :id")
    suspend fun markAsUsed(id: Long, characterId: Long)

    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE id = :id")
    suspend fun markAsAvailable(id: Long)

    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE usedByCharacterId = :characterId")
    suspend fun resetUsageByCharacter(characterId: Long)

    /** 여러 캐릭터의 이름뱅크 사용 상태 일괄 해제 (배치 삭제용) */
    @Query("UPDATE name_bank SET isUsed = 0, usedByCharacterId = NULL WHERE usedByCharacterId IN (:characterIds)")
    suspend fun resetUsageByCharacterIds(characterIds: List<Long>)

    /**
     * **고아가 된 '사용 중' 표시를 내린다 — 몇 줄을 내렸는지 돌려준다.**
     *
     * 이 표의 '사용 중'은 두 벌이다: `isUsed`(불리언)와 `usedByCharacterId`(외래키).
     * 외래키는 `onDelete = SET_NULL`이라 캐릭터가 어떤 경로로든 지워지면 **DB가 알아서
     * NULL로 만드는데**, `isUsed`는 앱이 짝지어 내려 줘야 한다. 그 짝을 지나지 않는 삭제가
     * 실재한다 — 엑셀 덮어쓰기의 `characterDao().deleteAll()`이 그렇다.
     *
     * 그러면 **모든 이름이 임자 없이 '사용 중'으로 굳는다**: 사용률이 정의상 100%가 되고
     * '미사용 이름'이 영영 빈 목록이며, 사용자가 이름을 하나씩 열어 고치는 것 말고는
     * 되돌릴 길이 없다. 유지보수 화면이 같은 수리를 들고 있었지만 **사용자가 그것을 눌러야만**
     * 돌았다 — 자기가 만든 어긋남은 만든 자리에서 갚는 것이 맞다.
     */
    @Query(
        "UPDATE name_bank SET isUsed = 0 WHERE isUsed = 1 AND " +
            "(usedByCharacterId IS NULL OR usedByCharacterId NOT IN (SELECT id FROM characters))"
    )
    suspend fun clearOrphanedUsage(): Int

    @Query("DELETE FROM name_bank")
    suspend fun deleteAll()

    @Query("SELECT id FROM name_bank")
    suspend fun getAllEntryIds(): List<Long>

    @Query("DELETE FROM name_bank WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * 내보내는 캐릭터가 쓰고 있는 이름만 — 월드패키지 내보내기의 범위 질의(R-54 통로 필수).
     * 전체 은행을 실으면 무관한 전역 데이터가 수신자에게 넘어간다(범위 밖 데이터는 패키지의 것이 아니다).
     */
    @Query("SELECT * FROM name_bank WHERE usedByCharacterId IN (:characterIds)")
    suspend fun getNamesByUsedCharacterIds(characterIds: List<Long>): List<NameBankEntry>
}
