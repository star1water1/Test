package com.novelcharacter.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.novelcharacter.app.data.model.ImageTag

@Dao
interface ImageTagDao {
    @Query("SELECT * FROM image_tags WHERE imageId = :imageId ORDER BY tag ASC")
    suspend fun getTagsByImageList(imageId: Long): List<ImageTag>

    @Query("SELECT * FROM image_tags ORDER BY tag ASC")
    suspend fun getAllList(): List<ImageTag>

    @Query("SELECT DISTINCT tag FROM image_tags ORDER BY tag ASC")
    suspend fun getAllDistinctTags(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<ImageTag>)

    @Query("DELETE FROM image_tags WHERE imageId = :imageId")
    suspend fun deleteAllByImage(imageId: Long)

    @Transaction
    suspend fun replaceAllForImage(imageId: Long, tags: List<ImageTag>) {
        deleteAllByImage(imageId)
        insertAll(tags)
    }

    // ===== 일괄 편집용 배치 메서드 (G2) =====

    @Query("SELECT DISTINCT tag FROM image_tags WHERE imageId IN (:imageIds) ORDER BY tag ASC")
    suspend fun getDistinctTagsForImages(imageIds: List<Long>): List<String>

    /**
     * [getTagsByImageList]의 일괄판 — **어느 이미지의 태그인지가 남는다** (B-238).
     *
     * [getDistinctTagsForImages]와 갈리는 자리가 여기다: 그쪽은 `DISTINCT tag`라 여러 이미지의
     * 태그가 한 자루로 섞여 *이미지별 현재 태그 집합*을 되살릴 수 없다. 가져오기·미리보기의
     * '무엇이 바뀌는가' 판정은 이미지마다 제 집합을 비교해야 하므로 행을 그대로 돌려준다.
     */
    @Query("SELECT * FROM image_tags WHERE imageId IN (:imageIds) ORDER BY tag ASC")
    suspend fun getTagsByImages(imageIds: List<Long>): List<ImageTag>

    @Query("DELETE FROM image_tags WHERE imageId IN (:imageIds) AND tag IN (:tags)")
    suspend fun deleteTagsFromImages(imageIds: List<Long>, tags: List<String>)
}
