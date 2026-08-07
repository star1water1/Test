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
    /**
     * 소속 세계관. **null = 전역 구역**(세계관 소속이 없는 캐릭터의 필드 — 2026.08.07 사용자
     * 확정, B-119 확장).
     *
     * 무소속 캐릭터는 종전에 필드가 **아예 0개**였다 — 값(`CharacterFieldValue`)이 실제
     * 필드 행을 가리켜야 하므로, 전역 기본 필드를 무소속에게 주는 유일하게 정직한 길은
     * 이 구역에 실제 행을 심는 것이다(0 센티널은 FK CASCADE를 부수고, 숨은 세계관 행은
     * 모든 목록 화면이 그것을 빼야 한다 — 설계 1-2가 기각한 참조형의 역상).
     *
     * 전역 구역의 행은 템플릿에서 심긴 그림자이고, **세계관과 똑같이 명시적 전파의 대상**이다
     * (전파 미리보기의 "무소속" 행 — 자동 동기화로 두면 타입이 바뀌는 템플릿 편집이 이 구역의
     * 값을 영향 분석 없이 깨뜨린다). 정의를 편집하는 화면은 없다 — 심기·전파·삭제 전부
     * [com.novelcharacter.app.data.repository.DefaultFieldTemplateRepository]를 지난다.
     *
     * ⚠️ 유니크 색인 `(universeId, entityType, key)`는 **전역 구역에서는 강제되지 않는다** —
     * SQLite는 NULL끼리를 서로 다른 값으로 본다. 전역 구역의 key 유일성은 심기 로직이
     * 지키고(`DefaultFieldPlan`이 기존 key를 걸러 심는다), 그 로직이 유일한 쓰기 경로다.
     */
    val universeId: Long?,
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
        /** 작품 커스텀 필드 (확-3). 값은 `novel_field_values`가 든다. */
        const val ENTITY_NOVEL = "novel"
    }
}
