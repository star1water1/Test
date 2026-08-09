package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.novelcharacter.app.data.model.DuelAxis

@Dao
interface DuelAxisDao {

    @Query("SELECT * FROM duel_axes WHERE universeId = :universeId ORDER BY displayOrder ASC, id ASC")
    suspend fun getByUniverseList(universeId: Long): List<DuelAxis>

    @Query(
        "SELECT * FROM duel_axes WHERE universeId = :universeId AND targetType = :targetType " +
            "ORDER BY displayOrder ASC, id ASC"
    )
    suspend fun getByUniverseAndTarget(universeId: Long, targetType: String): List<DuelAxis>

    @Query("SELECT * FROM duel_axes ORDER BY universeId ASC, displayOrder ASC, id ASC")
    suspend fun getAllList(): List<DuelAxis>

    @Query("SELECT * FROM duel_axes WHERE id = :id")
    suspend fun getById(id: Long): DuelAxis?

    @Query("SELECT * FROM duel_axes WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelAxis?

    @Query(
        "SELECT * FROM duel_axes WHERE universeId = :universeId AND targetType = :targetType " +
            "AND name = :name LIMIT 1"
    )
    suspend fun getByUniverseAndName(universeId: Long, targetType: String, name: String): DuelAxis?

    /**
     * 이 세계관의 **기준 이미지 축** (B-104 ⓑ·ⓒ — `DuelAxis.isBasisAxis`).
     *
     * 대상을 이미지로 좁히는 것이 요점이다 — 캐릭터 축에 표식이 켜져 들어와도(엑셀·구버전)
     * 대표 추첨과 걸러낼 후보는 이미지 축의 순위만 쓴다.
     *
     * **`LIMIT 1`이 유일성을 대신하지 않는다.** 유일성은 쓰기가 지킨다(`DuelRepository.saveAxis`);
     * 여기 상한은 그 보장이 어떤 이유로든 깨졌을 때 화면이 **둘 사이에서 흔들리지 않게**
     * 하는 것이다(같은 정렬이면 언제나 같은 축을 고른다).
     */
    @Query(
        "SELECT * FROM duel_axes WHERE universeId = :universeId AND targetType = :targetType " +
            "AND isBasisAxis = 1 ORDER BY displayOrder ASC, id ASC LIMIT 1"
    )
    suspend fun getBasisAxis(universeId: Long, targetType: String): DuelAxis?

    /**
     * 이 세계관에서 [exceptId]를 뺀 나머지의 기준 표식을 내린다 — **세계관당 하나**의 실행.
     *
     * 대상을 가리지 않는 것이 [getBasisAxis]와 다른 점이다: 캐릭터 축에 잘못 켜져 있던
     * 표식도 이 기회에 함께 내린다(읽는 쪽이 무시하더라도, 남겨 두면 엑셀 왕복에서
     * *"기준이 둘"*로 보인다).
     */
    @Query("UPDATE duel_axes SET isBasisAxis = 0 WHERE universeId = :universeId AND id != :exceptId")
    suspend fun clearBasisExcept(universeId: Long, exceptId: Long)

    @Insert
    suspend fun insert(axis: DuelAxis): Long

    @Update
    suspend fun update(axis: DuelAxis)

    @Delete
    suspend fun delete(axis: DuelAxis)

    /**
     * 덮어쓰기 가져오기가 쓰는 전량 삭제 — **판·처분이 CASCADE로 함께 죽는다.**
     * 부르는 쪽이 *"이 파일로 되살릴 수 있는가"*를 먼저 판정할 것(`ExcelImportService`).
     */
    @Query("DELETE FROM duel_axes")
    suspend fun deleteAll()
}
