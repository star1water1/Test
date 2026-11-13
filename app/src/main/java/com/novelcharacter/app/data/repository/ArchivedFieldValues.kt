package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 편집 창이 그리지 않는 구역에 남아 있는 필드값 (B-258 ⓑ).
 *
 * 사건·작품을 다른 세계관으로 옮기면 옛 구역의 값은 **지우지 않고 보관한다**(커버 밖 보존 —
 * [EventFieldValueMerge]·[NovelFieldValueMerge]). 엑셀도 그 값을 그대로 내보내고 되읽는다.
 * 그래서 가져오기가 이 조합을 교정하거나 이미 들어온 것을 소급하지 않는다(B-258 ⓒ·ⓓ를 닫은 이유) —
 * 교정하면 보관한 값과 백업 왕복이 함께 깨진다. 대신 **있다는 사실을 창에서 보여 준다.**
 * 읽기 전용이다: 그 구역의 정의·제한값으로 검증할 입력 칸을 이 창은 세우지 않는다.
 */
object ArchivedFieldValues {

    /** [zone]이 null이면 전역(무소속) 구역이다. */
    data class Line(val zone: String?, val fieldName: String, val value: String)

    /**
     * 저장된 값 중 창이 덮지 않는 것만 고른다. 빈 값과 정의를 잃은 값은 보여 줄 것이 없어 뺀다.
     *
     * @param stored (필드 정의 id, 값)
     * @param covered 창이 조회한 정의 id 전체 — 저장이 책임지는 범위와 같은 집합이다
     */
    fun select(
        stored: List<Pair<Long, String>>,
        covered: Set<Long>,
        definitions: Map<Long, FieldDefinition>,
        universeNames: Map<Long, String>
    ): List<Line> = stored.asSequence()
        .filter { (fieldId, value) -> fieldId !in covered && value.isNotBlank() }
        .mapNotNull { (fieldId, value) ->
            val definition = definitions[fieldId] ?: return@mapNotNull null
            val zone = definition.universeId?.let { universeNames[it] ?: return@mapNotNull null }
            Triple(definition, Line(zone, definition.name, value), zone)
        }
        // 전역이 먼저, 그다음 세계관 이름·필드 순서 — 같은 구역의 값이 흩어지지 않게 한다.
        .sortedWith(compareBy({ it.third != null }, { it.third.orEmpty() }, { it.first.displayOrder }, { it.first.name }))
        .map { it.second }
        .toList()
}
