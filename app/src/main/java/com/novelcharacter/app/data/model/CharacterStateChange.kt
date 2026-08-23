package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "character_state_changes",
    foreignKeys = [
        ForeignKey(
            entity = Character::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("characterId"), Index("year"), Index("fieldKey"), Index(value = ["code"], unique = true)]
)
data class CharacterStateChange(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val characterId: Long,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null,
    val fieldKey: String,          // 필드의 key 또는 특수키: __birth, __death, __alive
    val newValue: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    // 엑셀 왕복 안정 식별자 — 자연키(캐릭터+연도+필드키+새 값) 편집이 중복 생성으로 이어지지 않게 하는 기준.
    // nullable인 이유: v35 이전 Gson 스냅샷(휴지통) 역직렬화 시 null이 주입될 수 있다 (레거시 수용).
    val code: String? = generateEntityCode()
) {
    companion object {
        const val KEY_BIRTH = "__birth"
        const val KEY_DEATH = "__death"
        const val KEY_ALIVE = "__alive"
        const val KEY_AGE = "__age"

        /**
         * [KEY_ALIVE] 행의 `newValue`가 담는 **원본 어휘** — 화면이 쓰는 출력 어휘
         * (`"true"`/`"false"`)와 다르다.
         *
         * 이 셋이 상수가 된 것은 그 둘이 실제로 섞였기 때문이다: 관계도 시간뷰가 원본 행을
         * *출력 어휘로* 비교해(`newValue == "false"`) **언제나 거짓인 조건**을 들고 있었다
         * ([com.novelcharacter.app.util.AliveAtYear]의 머리말). 적는 자리와 읽는 자리가
         * 같은 상수를 보면 그 부류가 다시 생기지 않는다.
         *
         * (`AppDatabase`의 Room 마이그레이션은 일부러 글자 그대로 둔다 — 지나간 마이그레이션은
         * 그때의 값으로 굳은 기록이라, 상수가 바뀌면 과거가 함께 바뀐다.)
         */
        const val ALIVE_MARKER_ALIVE = "alive"
        const val ALIVE_MARKER_DEAD = "dead"
        const val ALIVE_MARKER_UNKNOWN = "unknown"
    }
}
