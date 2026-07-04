package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "field_definitions",
    foreignKeys = [
        ForeignKey(
            entity = Universe::class,
            parentColumns = ["id"],
            childColumns = ["universeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("universeId"), Index(value = ["universeId", "entityType", "key"], unique = true)]
)
data class FieldDefinition(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val universeId: Long,
    val key: String,               // 고유 키: "mana_affinity"
    val name: String,              // 표시 이름: "마나친화"
    val type: String,              // FieldType.name: TEXT, NUMBER, SELECT, MULTI_TEXT, GRADE, CALCULATED, BODY_SIZE
    val config: String = "{}",     // JSON: 타입별 설정
    val groupName: String = "기본 정보",
    val displayOrder: Int = 0,
    val isRequired: Boolean = false,
    // 필드가 붙는 대상 (B-10): 캐릭터 필드와 사건 필드가 같은 정의 시스템을 공유한다.
    // 조회는 DAO 레벨에서 entityType으로 격리되므로 기존 캐릭터 경로는 영향받지 않는다.
    val entityType: String = ENTITY_CHARACTER
) {
    companion object {
        const val ENTITY_CHARACTER = "character"
        const val ENTITY_EVENT = "event"
    }
}
