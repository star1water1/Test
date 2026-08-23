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

    /**
     * 순서 한 줄만 바꾼다 — 재정렬이 축 전체를 되쓰지 않게 (형제 축들과 같은 모양).
     * 일괄 갱신은 `DuelRepository.updateAxisDisplayOrders`가 **한 트랜잭션**으로 묶는다.
     */
    @Query("UPDATE duel_axes SET displayOrder = :order WHERE id = :id")
    suspend fun setDisplayOrder(id: Long, order: Int)

    @Query("SELECT * FROM duel_axes WHERE id = :id")
    suspend fun getById(id: Long): DuelAxis?

    @Query("SELECT * FROM duel_axes WHERE code = :code LIMIT 1")
    suspend fun getByCode(code: String): DuelAxis?

    /**
     * 주어진 코드 중 **이미 쓰이고 있는 것**만 — 월드패키지 가져오기의 코드 충돌 판정
     * ([com.novelcharacter.app.share.WorldPackageCodes.Registry])이 쓴다. 비용이 기기에 쌓인
     * 양이 아니라 **패키지 크기**에 붙는다(형제 표 둘과 같은 규약).
     */
    @Query("SELECT code FROM duel_axes WHERE code IN (:codes)")
    suspend fun getExistingCodes(codes: List<String>): List<String>

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
     * 이 세계관에서 [exceptId]를 뺀 **같은 대상의** 축들에서 기준 표식을 내린다.
     *
     * **[getBasisAxis]와 대상 범위가 같아야 한다.** 종전 판은 여기서만 대상을 가리지 않아
     * (*"캐릭터 축에 잘못 켜진 표식도 함께 내린다"*는 뜻이었다) 실제로는 **거꾸로 동작했다** —
     * `기준축=Y`가 적힌 **캐릭터 축 행 하나가 엑셀로 들어오면 살아 있는 이미지 축의 기준을
     * 조용히 풀었고**, 축 목록을 드래그해 순서만 바꿔도 같은 일이 났다(`saveAxis`가 매번
     * 부른다). 사용자가 한 일과 결과 사이에 아무 연결도 없는 부류라 원인을 찾을 수 없다.
     *
     * **표식은 이미지 축의 속성이다**([DuelAxis.isImageBasis]) — 캐릭터 축에 켜져 들어온 값은
     * *아무 일도 하지 않는 채 그대로 남는다*(엔티티 KDoc과 엑셀 시트가 약속한 그대로다.
     * 사용자가 적은 것을 지우지 않는다). 그러므로 유일성도 **대상 안에서** 성립하면 충분하다.
     */
    @Query(
        "UPDATE duel_axes SET isBasisAxis = 0 " +
            "WHERE universeId = :universeId AND targetType = :targetType AND id != :exceptId"
    )
    suspend fun clearBasisExcept(universeId: Long, targetType: String, exceptId: Long)

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
