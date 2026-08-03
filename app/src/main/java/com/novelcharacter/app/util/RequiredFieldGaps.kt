package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType

/**
 * **필수인데 비어 있는 칸**을 세는 계층 (B-99 — B-90이 설계한 '필수 표식'의 소비처 ②).
 *
 * ## 왜 필요한가
 *
 * B-90은 '필수'의 뜻을 저장 차단에서 **표식**으로 옮겼다. 그래서 지금은 표식이 폼에 붙고
 * 저장할 때 세어 알린다(소비처 ①). 그런데 그것만으로는 **그 캐릭터를 열어 저장을 눌러 봐야
 * 안다** — 원칙 04가 금지한 *"일일이 확인하지 않으면 존재를 알 수 없는 데이터"*가 그대로 남는다.
 * 어시스턴트가 세어 주면 그 마지막 걸음이 채워진다.
 *
 * ## 세는 규칙은 폼과 같아야 한다
 *
 * 카드가 *"빈 칸 3개"*라 했는데 열어 보니 다른 칸이 비어 있으면, 그 카드는 없느니만 못하다.
 * 그래서 [countsAsSlot]이 **소비처 ①(`DynamicFieldFormBuilder.emptyRequiredFields`)과 같은
 * 규칙**을 든다 — 필수이면서 `CALCULATED`가 아닌 것. 계산 필드는 값을 사람이 넣는 자리가
 * 아니므로 폼도 세지 않는다.
 *
 * **강도(`RequiredEnforcement`)는 보지 않는다.** 막든 알리든 *"채워야 할 칸"*이라는 사실은
 * 같고, 강도는 저장할 때 무슨 일이 벌어지는가의 문제다. 폼의 같은 함수도 강도를 보지 않는다.
 *
 * ## 종류를 가리지 않는다
 *
 * 캐릭터·사건·작품 셋 다 같은 함수로 센다 — **표식이 종류를 가리지 않는 것이 B-90의 요점**이고,
 * 여기서 캐릭터만 세면 그 요점이 소비처에서 무너진다.
 */
object RequiredFieldGaps {

    /**
     * 이 필드가 '채워야 할 칸'으로 세어지는가.
     *
     * 소비처 ①과 같은 규칙이다(위 KDoc 참조). 규칙을 바꿔야 하면 **두 곳을 함께** 바꿔야 하며,
     * 그것을 잊지 않도록 양쪽 주석이 서로를 가리킨다.
     */
    fun countsAsSlot(field: FieldDefinition): Boolean =
        field.isRequired && FieldType.fromName(field.type) != FieldType.CALCULATED

    /**
     * 빈 필수 칸이 하나라도 있는 항목만 골라 돌려준다.
     *
     * @param definitions 한 종류(entityType)의 필드 정의 전부. 필수·계산 여부는 여기서 거른다.
     * @param entities 셀 대상. [GapEntity.universeId]가 null이면 **건너뛴다** — 어느 세계관의
     *   필드가 적용되는지 알 수 없어 세면 틀린 답이 나온다(작품 미배정 캐릭터가 그렇고,
     *   완성도 계산도 같은 이유로 같은 자리에서 건너뛴다).
     * @param filledDefIdsByEntity 항목 id → **값이 비어 있지 않은** 필드 정의 id 집합.
     *   호출부가 값 테이블에서 만든다(빈 문자열 행은 채운 것이 아니므로 미리 걸러 넘긴다).
     */
    fun compute(
        definitions: List<FieldDefinition>,
        entities: List<GapEntity>,
        filledDefIdsByEntity: Map<Long, Set<Long>>
    ): List<RequiredGap> {
        val requiredByUniverse = definitions
            .filter { countsAsSlot(it) }
            .groupBy { it.universeId }
        if (requiredByUniverse.isEmpty()) return emptyList()

        return entities.mapNotNull { entity ->
            val universeId = entity.universeId ?: return@mapNotNull null
            val required = requiredByUniverse[universeId] ?: return@mapNotNull null
            val filled = filledDefIdsByEntity[entity.id].orEmpty()
            // 폼에 보이는 차례로 든다 — 카드에서 읽은 순서와 열어서 보는 순서가 같아야 한다.
            val missing = required
                .filter { it.id !in filled }
                .sortedWith(compareBy({ it.displayOrder }, { it.name }))
                .map { it.name }
            if (missing.isEmpty()) null else RequiredGap(entity.id, entity.name, missing)
        }.sortedBy { it.entityName }
    }

    /** 빈 칸의 총합 — 카드가 "항목 N개"와 "칸 M개"를 함께 말할 때 쓴다. */
    fun slotCount(gaps: List<RequiredGap>): Int = gaps.sumOf { it.missingFieldNames.size }
}

/**
 * 셀 대상 하나. 캐릭터·사건·작품을 **같은 모양으로** 받아 계산 계층이 종류를 모르게 한다.
 *
 * @property universeId 이 항목에 어떤 세계관의 필드가 적용되는가. 캐릭터는 작품을 거쳐,
 *   사건·작품은 자기 값으로 정해진다. null이면 셀 수 없다.
 */
data class GapEntity(
    val id: Long,
    val name: String,
    val universeId: Long?
)

/** 한 항목의 빈 필수 칸. [missingFieldNames]는 폼에 보이는 차례다. */
data class RequiredGap(
    val entityId: Long,
    val entityName: String,
    val missingFieldNames: List<String>
)
