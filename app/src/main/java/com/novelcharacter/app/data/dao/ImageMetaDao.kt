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

    @Query("DELETE FROM image_meta WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM image_meta")
    suspend fun deleteAll()

    /**
     * 입양(insert-or-get): 경로에 meta 행이 없으면 만들고, 있으면 기존 행 id를 돌려준다.
     * 태그 부착·링크·배정해제가 이미지를 라이브러리 관리로 편입하는 단일 진입점.
     */
    @Transaction
    suspend fun adopt(path: String, now: Long): Long {
        val inserted = insert(ImageMeta(path = path, importedAt = now))
        return if (inserted != -1L) inserted else requireNotNull(getByPath(path)).id
    }
}
