package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CardDisplayConfig
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 목록 카드에 얹을 필드값 요약을 만든다 (B-5).
 *
 * 순수 계산 — 어떤 엔티티(사건·작품·캐릭터)든 "정의 목록 + 엔티티별 (필드id, 값) 행"만 주면 된다.
 * 소비처는 **연표 사건 카드와 작품 목록 카드 둘**이다(B-67에서 둘이 됐다).
 * **정의 목록에 구역이 섞여 들어와도 된다** — 작품 축은 목록이 전 세계관을 걸치고 무소속
 * 작품이 전역 구역 정의를 들어서, 실제로 섞어 넣는다. 어느 정의를 쓸지는 값 행이 고른다.
 */
object CardFieldSummary {

    data class Line(val label: String, val value: String)

    /**
     * @param lines 카드에 그릴 줄 (상한까지)
     * @param hiddenCount 상한을 넘어 잘린 줄 수. 0이 아니면 소비처가 **반드시 개수를 표시**해
     *   값이 더 있다는 사실을 알린다 — 열어보지 않으면 존재조차 모르는 데이터를 만들지 않는다.
     */
    data class Summary(val lines: List<Line>, val hiddenCount: Int) {
        val isEmpty: Boolean get() = lines.isEmpty()
    }

    private val EMPTY = Summary(emptyList(), 0)

    /**
     * @param defs 이 엔티티 종류의 필드 정의 전체 (세계관 구분 없이 줘도 된다 — 값 행이 필드를 고른다)
     * @param rowsByEntity 엔티티 id → (필드 정의 id, 값) 목록
     * @param entityIds 요약을 만들 엔티티 id
     * @param limit 카드 한 장에 그릴 최대 줄 수
     */
    fun build(
        defs: List<FieldDefinition>,
        rowsByEntity: Map<Long, List<Pair<Long, String>>>,
        entityIds: Collection<Long>,
        limit: Int = CardDisplayConfig.MAX_ON_CARD
    ): Map<Long, Summary> {
        if (defs.isEmpty() || entityIds.isEmpty()) return emptyMap()

        // 카드 표시가 켜진 필드만. 설정 파싱은 필드당 한 번 — 엔티티 수만큼 반복하지 않는다.
        val shown = defs.filter { CardDisplayConfig.fromConfig(it.config).show }
        if (shown.isEmpty()) return emptyMap()

        val defById = shown.associateBy { it.id }
        // 정렬 기준은 필드 관리 화면의 순서와 같게 — 카드마다 줄 순서가 흔들리지 않는다.
        val orderById = shown
            .sortedWith(compareBy({ it.displayOrder }, { it.name }, { it.id }))
            .withIndex().associate { (idx, fd) -> fd.id to idx }

        val out = HashMap<Long, Summary>(entityIds.size)
        for (entityId in entityIds) {
            val rows = rowsByEntity[entityId] ?: continue
            val candidates = rows
                .mapNotNull { (fieldId, raw) ->
                    val fd = defById[fieldId] ?: return@mapNotNull null
                    val value = raw.trim()
                    if (value.isEmpty()) null else Triple(orderById.getValue(fieldId), fd.name, value)
                }
                .sortedBy { it.first }

            if (candidates.isEmpty()) continue
            val lines = candidates.take(limit).map { Line(it.second, it.third) }
            out[entityId] = Summary(lines, (candidates.size - lines.size).coerceAtLeast(0))
        }
        return out
    }

    /** 요약이 없는 엔티티가 쓰는 빈 값 — 매번 새 객체를 만들지 않게. */
    fun empty(): Summary = EMPTY
}
