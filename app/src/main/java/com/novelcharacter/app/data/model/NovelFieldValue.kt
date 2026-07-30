package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 작품 커스텀 필드 값 (확-3). [CharacterFieldValue]·[EventFieldValue]와 **동형** —
 * `entityType = "novel"`인 [FieldDefinition]의 값을 작품별로 저장한다.
 *
 * **왜 통합 테이블이 아니라 세 번째 테이블인가.** `ownerType`+`ownerId` 한 벌로 합치면
 * 테이블은 하나로 줄지만 **FK CASCADE를 잃는다** — 소유자가 다형이면 Room이 걸 외래키가 없다.
 * 이 저장소는 삭제·복원의 정합을 CASCADE 위에 세워 두었으므로(휴지통 스냅샷·복원 순위),
 * 그것을 내주고 테이블 수를 줄이는 것은 남는 장사가 아니다. 대신 **모양을 똑같이** 두어
 * 규약(R-5 커버 집합 · R-29 종류 일치)이 세 테이블에 같은 형태로 적용되게 한다.
 */
@Entity(
    tableName = "novel_field_values",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FieldDefinition::class,
            parentColumns = ["id"],
            childColumns = ["fieldDefinitionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("novelId"), Index("fieldDefinitionId"),
        Index(value = ["novelId", "fieldDefinitionId"], unique = true)
    ]
)
data class NovelFieldValue(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val novelId: Long,
    val fieldDefinitionId: Long,
    val value: String = ""
)
