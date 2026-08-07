package com.novelcharacter.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * **전역 기본 필드의 템플릿** (B-119) — *"모든 세계관의 모든 캐릭터가 기본으로 가지는 필드"*.
 *
 * 설계 정본은 `docs/feature_roadmap_2026-08.md` **1장**이다.
 *
 * ## 참조가 아니라 구체화(materialization)다
 *
 * 이 표는 **원본**이지 읽기 경로가 아니다. 실제로 화면·통계·엑셀·검색이 보는 것은 각 세계관에
 * 심긴 **보통 [FieldDefinition]**이고, 그 필드가 config에 [DefaultFieldRef] 표식 하나를 달아
 * *"나는 이 템플릿에서 왔다"*를 말할 뿐이다.
 *
 * 참조형(모든 읽기 경로가 `세계관 필드 ∪ 전역 필드`를 본다)을 기각한 근거가 설계 1-2에 있다:
 * `FieldDefinitionDao`의 `entityType` 기본값 함정(R-29)과 **같은 부류의 사각이 경로 수만큼**
 * 생기고, 하나만 빠져도 *"전역 필드가 이 화면에서만 안 보인다"*가 조용히 난다. 이 저장소는
 * 이미 같은 모양을 검증해 뒀다 — 등급 체계가 실효 표를 필드 config에 **물질화**하고(R-30),
 * 다른 세계관 출신 필드는 참조를 **강등**한다(R-35).
 *
 * ## 왜 `universeId`가 없는가 — 그리고 그 대가
 *
 * 전역이라 소속이 없다. 그래서 **[FieldConfigTransfer]가 걷어내는 두 참조를 애초에 가질 수
 * 없다**(설계 1-2): 등급 체계도 대결 축도 세계관 단위라 전역 템플릿이 가리킬 대상이 없다.
 * 승격([DefaultFieldPlan.promote])이 그 자리에서 강등을 건다 — 템플릿은 실효 표만 든다.
 *
 * ## 유니크가 둘인 이유
 *
 * - `code` — 안정 식별자(R-1). 엑셀·월드패키지·기기 이전이 id 재발급을 겪어도 같은 템플릿에
 *   다시 붙는다. 심긴 필드가 이 값으로 원본을 가리킨다.
 * - `(entityType, key)` — 심는 자리의 제약이 `(universeId, entityType, key)`이므로, 같은 자리를
 *   노리는 템플릿이 둘이면 **어느 세계관에서든 반드시 하나가 삽입에 실패한다.** 그 충돌을
 *   심는 순간이 아니라 만드는 순간에 막는다.
 */
@Entity(
    tableName = "default_field_templates",
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["entityType", "key"], unique = true)
    ]
)
data class DefaultFieldTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 심길 자리의 key. 세계관을 넘어 **안정**하다 — 통계의 `(key, type)` 머지가 그 위에 선다(설계 1-7). */
    val key: String,
    val name: String,
    /** [FieldType] 이름. 심긴 필드와 같은 어휘를 쓴다 — 새 타입 체계를 만들지 않는다. */
    val type: String,
    /** 심을 때 그대로 복사되는 설정. 세계관 한정 참조는 들어올 수 없다(위 KDoc). */
    val config: String = "{}",
    val groupName: String = "기본 정보",
    val displayOrder: Int = 0,
    val isRequired: Boolean = false,
    /**
     * 대상 종류. **캐릭터가 원문 요청이지만 구조는 사건·작품에도 열어 둔다**(원칙 01) —
     * 종류를 캐릭터로 못 박으면 사건 기본 필드를 원할 때 표를 다시 만들어야 한다.
     */
    val entityType: String = FieldDefinition.ENTITY_CHARACTER,
    val createdAt: Long = System.currentTimeMillis(),
    /** 안정 식별자 — 심긴 필드의 config·엑셀 `기본필드코드` 열이 이 값으로 가리킨다. */
    val code: String = generateEntityCode()
) {
    /** 심는 자리의 정체 — `(entityType, key)`. 템플릿과 필드가 같은 규칙으로 짝지어진다. */
    val slot: String get() = DefaultFieldPlanKeys.slot(entityType, key)
}

/**
 * [DefaultFieldTemplate.slot]과 [com.novelcharacter.app.util.DefaultFieldPlan]이 **같은 문자열**을
 * 쓰도록 규칙을 한 자리에 둔다.
 *
 * 모델이 `util`을 부르면 계층이 거꾸로 서므로 규칙만 여기 두고 양쪽이 이것을 부른다 —
 * 두 벌로 적으면 *"같은 자리"* 판정이 자리마다 갈리고, 그 어긋남은 삽입 실패로만 드러난다.
 */
object DefaultFieldPlanKeys {
    fun slot(entityType: String, key: String): String = "$entityType $key"
}
