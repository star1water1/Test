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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(meta: ImageMeta): Long

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
    @Query(
        """DELETE FROM image_meta WHERE adoptSource = 'auto' AND linkGroupId IS NULL
           AND NOT EXISTS (SELECT 1 FROM image_tags WHERE image_tags.imageId = image_meta.id)"""
    )
    suspend fun sweepBareAutoAdopted()

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
