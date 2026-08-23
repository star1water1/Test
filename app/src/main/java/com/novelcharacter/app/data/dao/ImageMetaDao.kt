package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novelcharacter.app.data.model.ImageMeta

@Dao
interface ImageMetaDao {
    @Query("SELECT * FROM image_meta")
    suspend fun getAllList(): List<ImageMeta>

    @Query("SELECT path FROM image_meta")
    suspend fun getAllPaths(): List<String>

    @Query("SELECT * FROM image_meta WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ImageMeta?

    @Query("SELECT * FROM image_meta WHERE path IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<ImageMeta>

    @Query("SELECT * FROM image_meta WHERE linkGroupId = :groupId")
    suspend fun getByGroup(groupId: String): List<ImageMeta>

    /**
     * [getByGroup]의 일괄판 — **어느 묶음의 식구인지가 행에 남는다** (B-239).
     *
     * 토큰마다 묻던 자리를 한 번으로 내린다. `linkGroupId`에는 인덱스가 있으므로
     * (`index_image_meta_linkGroupId` — 스키마 56 실측. **B-238의 교훈대로 잣대를 빌리지 않고
     * 먼저 확인했다**) 조회 하나하나가 풀스캔은 아니었고, 없앨 값은 **왕복 × 토큰 수**다.
     */
    @Query("SELECT * FROM image_meta WHERE linkGroupId IN (:groupIds)")
    suspend fun getByGroups(groupIds: List<String>): List<ImageMeta>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(meta: ImageMeta): Long

    /**
     * [insert]의 일괄판 (B-244) — 입양이 경로 목록을 도는 자리가 쓴다.
     *
     * **`IN` 절이 아니므로 청크 통로(R-54)를 지나지 않는다** — 목록 길이만큼 바인드 변수를
     * 쓰는 것이 아니라 같은 문장을 목록 길이만큼 **다시 바인드**하는 것이라 상한과 무관하다.
     *
     * 돌려주는 것은 자리를 맞춘 rowId이고, **이미 있던 경로는 `-1`**이다(`IGNORE`). 그 `-1`을
     * *경합*으로 읽는 것이 [com.novelcharacter.app.util.ImageAdoptionPlanner]의 일이다 —
     * 부르는 쪽이 없는 것만 골라 넘기므로, 그래도 `-1`이 나왔다면 그 사이 다른 연결이 넣은 것이다.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(metas: List<ImageMeta>): List<Long>

    /** 재압축 등 파일 개명 시 경로 갱신. 저장형(absolutePath) 기준 정확 일치. */
    @Query("UPDATE image_meta SET path = :newPath WHERE path = :oldPath")
    suspend fun updatePath(oldPath: String, newPath: String)

    @Query("UPDATE image_meta SET linkGroupId = :groupId WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: String?)

    /** 그룹 인원이 1 이하로 줄면 잔존 "링크" 표식이 오해를 부르므로 자동 해제한다. */
    @Query(
        """UPDATE image_meta SET linkGroupId = NULL WHERE linkGroupId = :groupId
           AND (SELECT COUNT(*) FROM image_meta WHERE linkGroupId = :groupId) <= 1"""
    )
    suspend fun clearGroupIfSingleton(groupId: String)

    /**
     * [clearGroupIfSingleton]의 일괄판 (B-239) — 토큰마다 돌던 쓰기를 한 번으로 내린다.
     *
     * **한 문장으로 접어도 답이 같은 근거:** 하위 질의가 *상관 없는*(non-correlated) 꼴이라
     * 먼저 통째로 계산된다 — 준 토큰 중 **인원 1 이하인 것들의 집합**이다. 그 집합을 정한 뒤에야
     * 지우므로 지우는 행위가 제 판정을 되먹이지 않는다. 묶음은 행을 나눠 가지므로(행 하나의
     * `linkGroupId`는 하나) 토큰별 반복과 결과가 같고, **호출부가 청크로 갈라 불러도 같다.**
     * 인원 0인 토큰은 집합에 들지 않지만 지울 행도 없어 단수판과 다르지 않다.
     */
    @Query(
        """UPDATE image_meta SET linkGroupId = NULL WHERE linkGroupId IN (
               SELECT linkGroupId FROM image_meta WHERE linkGroupId IN (:groupIds)
               GROUP BY linkGroupId HAVING COUNT(*) <= 1
           )"""
    )
    suspend fun clearSingletonGroups(groupIds: List<String>)

    /**
     * 자동 입양 행 승격 — 사용자가 명시적으로 남긴(배정 해제 등) 경로의 행을 사용자 소유로
     * 바꿔, 이후 자동 링크 해제가 행을 반납(삭제)하지 않게 한다.
     */
    @Query("UPDATE image_meta SET adoptSource = NULL WHERE path IN (:paths)")
    suspend fun promoteToUserByPaths(paths: List<String>)

    /**
     * 자동 입양 반납 — 자동 링크가 만든 행은 **링크를 지니는 동안만** 존재한다. 링크가 풀렸고
     * (해제·singleton 정리 모두) 태그도 없는 자동 행을 전부 지운다. 사용자 행(adoptSource null)과
     * 사용자 투자(태그·잔존 링크)가 있는 행은 남는다 — 자동 링크가 편집창 제거 정책의
     * "라이브러리 보존" 범위를 조용히 넓히지 않게 한다.
     */
    // SQL 쌍둥이: ImageMeta.ADOPT_SOURCE_AUTO
    @Query(
        """DELETE FROM image_meta WHERE adoptSource = 'auto' AND linkGroupId IS NULL
           AND NOT EXISTS (SELECT 1 FROM image_tags WHERE image_tags.imageId = image_meta.id)"""
    )
    suspend fun sweepBareAutoAdopted()

    /**
     * 뗀 표식을 붙인다(B-107 D2). 이미 붙어 있으면 **덮어쓴다** — 마지막으로 뗀 시각이
     * 쓸모 있는 값이고, 출처도 마지막 것 하나만 남기는 것이 확정이다.
     *
     * 판정("캐릭터 소유가 0인가")은 여기서 하지 않는다 —
     * [com.novelcharacter.app.util.DetachedImageMarker]가 단일 소스이고 이것은 쓰기 손이다.
     */
    @Query("UPDATE image_meta SET detachedAt = :now, detachedFromCode = :fromCode WHERE path IN (:paths)")
    suspend fun markDetachedByPaths(paths: List<String>, now: Long, fromCode: String?)

    /**
     * 뗀 표식을 지운다 — 다시 캐릭터에 붙었거나(자동), 사용자가 직접 지웠거나(수동),
     * `_미배정/`으로 되돌렸을 때(폴더 왕복 D5). 셋 다 "서랍에서 뺀다"는 같은 뜻이다.
     */
    @Query("UPDATE image_meta SET detachedAt = NULL, detachedFromCode = NULL WHERE path IN (:paths)")
    suspend fun clearDetachedByPaths(paths: List<String>)

    @Query("DELETE FROM image_meta WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM image_meta")
    suspend fun deleteAll()

    /**
     * 입양(insert-or-get): 경로에 meta 행이 없으면 만들고, 있으면 기존 행 id를 돌려준다.
     * 태그 부착·링크·배정해제가 이미지를 라이브러리 관리로 편입하는 단일 진입점.
     * 기존 행이 자동 입양(adoptSource='auto')이면 사용자 소유로 승격한다 — adopt를 부르는
     * 곳은 전부 사용자의 명시적 행위라, 이후 자동 반납에서 보호되어야 한다.
     */
    @Transaction
    suspend fun adopt(path: String, now: Long): Long {
        val inserted = insert(ImageMeta(path = path, importedAt = now))
        if (inserted != -1L) return inserted
        promoteToUserByPaths(listOf(path))
        return requireNotNull(getByPath(path)).id
    }

    /**
     * 자동 링크 전용 입양: 행이 없으면 자동 소유(adoptSource='auto')로 만들고, 있으면
     * 소유를 건드리지 않고 기존 id를 돌려준다([adopt]와 달리 승격하지 않는다 — 자동화는
     * 사용자 의사 표시가 아니다).
     */
    @Transaction
    suspend fun adoptAuto(path: String, now: Long): Long {
        val inserted = insert(
            ImageMeta(path = path, importedAt = now, adoptSource = ImageMeta.ADOPT_SOURCE_AUTO)
        )
        return if (inserted != -1L) inserted else requireNotNull(getByPath(path)).id
    }
}
