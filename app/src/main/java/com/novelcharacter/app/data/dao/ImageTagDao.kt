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

    @Query("DELETE FROM image_tags WHERE imageId IN (:imageIds) AND tag IN (:tags)")
    suspend fun deleteTagsFromImages(imageIds: List<Long>, tags: List<String>)
}
