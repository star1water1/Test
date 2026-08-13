package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
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
    /**
     * 저장된 타입 이름([FieldType.name]). **분기에 쓰지 말 것 — [fieldType]이 그 자리다**(B-55).
     * 여기 글자를 그대로 견주면 새 타입이 그 자리에서 조용히 빠진다.
     * 이 문자열을 그대로 쓰는 곳은 저장·직렬화(엑셀 열·JSON·SQL)뿐이다.
     */
    val type: String,
    val config: String = "{}",     // JSON: 타입별 설정
    val groupName: String = "기본 정보",
    val displayOrder: Int = 0,
    val isRequired: Boolean = false,
    // 필드가 붙는 대상 (B-10): 캐릭터 필드와 사건 필드가 같은 정의 시스템을 공유한다.
    // 조회는 DAO 레벨에서 entityType으로 격리되므로 기존 캐릭터 경로는 영향받지 않는다.
    val entityType: String = ENTITY_CHARACTER
) {
    /**
     * [type] 문자열이 가리키는 타입 — **타입 분기의 유일한 입구다** (B-55, 2026.08.13).
     *
     * 모르는 글자면 `null`이다. 그 값이 실제로 들어올 수 있는가는 경로마다 다르다 —
     * 엑셀 '필드 정의' 시트는 가져오기가 미리 걸러 내지만(알 수 없는 타입은 행을 건너뛰고
     * 오류로 알린다), **월드패키지·프리셋 JSON·손으로 고친 DB는 그 관문을 안 지난다.**
     * 그래서 `null`을 없는 상태로 치지 않고, 분기마다 답하게 둔다.
     *
     * ## 왜 계산해 두지 않고 게터인가
     *
     * `@Ignore val fieldType = FieldType.fromName(type)`로 두면 인스턴스마다 한 번만 풀려
     * 더 빠르다. **그런데 그것은 백킹 필드를 만든다.** 이 클래스는 Room 엔티티인 동시에
     * **Gson으로 월드패키지·프리셋·화면 간 전달에 실려 나가는 클래스**라(`WorldPackageContents` ·
     * `PresetTemplates.fieldsToJson`), 필드가 생기면 그 JSON에 `"fieldType"`이 **말없이
     * 한 칸 늘어난다.** 저장·교환 형식이 바뀌는 것은 이 판의 성격(동작 무변경 치환)이 아니고,
     * 착수 규칙 3번이 *"되돌리기 어려운 것"*으로 묶어 둔 부류다.
     *
     * 게터는 백킹 필드가 없어 Room도 Gson도 못 본다. 값은 [FieldType.fromName]이 표에서
     * 집어 오므로 대신할 자리(생문자열 비교)와 비용이 사실상 같다.
     */
    val fieldType: FieldType?
        @Ignore get() = FieldType.fromName(type)

    companion object {
        const val ENTITY_CHARACTER = "character"
        const val ENTITY_EVENT = "event"
        /** 작품 커스텀 필드 (확-3). 값은 `novel_field_values`가 든다. */
        const val ENTITY_NOVEL = "novel"
    }
}
