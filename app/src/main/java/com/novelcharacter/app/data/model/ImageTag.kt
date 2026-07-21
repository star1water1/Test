package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 이미지 태그(속성) — 필터·검색·캐릭터 편집창 추천 매칭에 쓰인다.
 * [CharacterTag]와 동일한 형태: (imageId, tag) 1행, 이미지 삭제 시 CASCADE.
 */
@Entity(
    tableName = "image_tags",
    foreignKeys = [
        ForeignKey(
            entity = ImageMeta::class,
            parentColumns = ["id"],
            childColumns = ["imageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("imageId"),
        Index("tag"),
        Index(value = ["imageId", "tag"], unique = true)
    ]
)
data class ImageTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imageId: Long,
    val tag: String
)
