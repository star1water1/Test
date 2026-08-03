package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "characters",
    foreignKeys = [
        ForeignKey(
            entity = Novel::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("novelId"), Index(value = ["code"], unique = true),
               Index(value = ["novelId", "isPinned", "displayOrder"]),
               Index(value = ["isPinned", "displayOrder"]),
               Index("name")]
)
data class Character(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val firstName: String = "",
    val lastName: String = "",
    val anotherName: String = "",
    val novelId: Long? = null,
    val imagePaths: String = "[]",
    /**
     * 대표 이미지의 **파일 경로**. 빈 문자열 = 지정 없음(화면 진입마다 랜덤).
     *
     * **모드 칸을 따로 두지 않는다**(B-103 D1). 세계관·작품은 이미지 출처가 여럿이라
     * `imageMode`가 필요하지만 캐릭터의 출처는 자기 것 하나뿐이고 선택지는 *고정했나 / 안 했나*
     * 뿐이다. 칸을 둘로 나누면 `mode=select`인데 포인터가 빈 상태가 **표현 가능해지고**
     * 그 순간 두 칸이 서로를 부정한다.
     *
     * **[imagePaths]를 고치는 모든 경로가 이 칸도 함께 고친다** — 판정·유지·해제는
     * `util/CharacterRepresentativeImage`가 단일 소스다. 빠뜨려도 오류는 나지 않고
     * 포인터만 조용히 어긋난다.
     */
    val representativeImagePath: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val memo: String = "",
    val code: String = generateEntityCode(),
    val displayOrder: Long = 0,
    val isPinned: Boolean = false
) {
    /** 표시용 이름: firstName + lastName이 있으면 조합, 없으면 name 사용 */
    val displayName: String
        @Ignore get() = if (firstName.isNotBlank() || lastName.isNotBlank()) {
            listOf(lastName, firstName).filter { it.isNotBlank() }.joinToString(" ")
        } else {
            name
        }

    /** 이명/별칭 목록 (콤마 구분 파싱) */
    val aliases: List<String>
        @Ignore get() = if (anotherName.isNotBlank()) {
            anotherName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
}
