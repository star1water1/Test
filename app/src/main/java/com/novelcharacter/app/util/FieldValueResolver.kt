package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldValueEntry

/**
 * 한 필드의 라이브러리 엔트리로 값 토큰을 해석하는 순수 해석기.
 *
 * - 별칭 → canonical 접기 (통계·검색·자동완성·엑셀이 공유하는 단일 규칙)
 * - canonical → 표시 라벨/카테고리
 * - 통계 그룹핑 키 산출: 구 FieldStatsConfig.resolveStatsKeys의 "라벨 적용 후 카테고리 조회"
 *   체인을 재현한다 — 카테고리는 해석된 엔트리에서 먼저 찾고, 없으면 표시 라벨과 값이 일치하는
 *   다른 엔트리에서 찾는다 (구 valueCategories가 라벨 키공간으로 조회되던 동작 보존).
 *
 * isHidden은 여기서 무시한다 — 숨김은 입력 제안 전용이며 통계·해석을 왜곡하지 않는다.
 */
class FieldValueResolver(entries: List<FieldValueEntry>) {

    private val byValue: Map<String, FieldValueEntry> = entries.associateBy { it.value }
    private val byAlias: Map<String, FieldValueEntry> = buildMap {
        for (e in entries) {
            for (alias in e.aliases()) {
                putIfAbsent(alias, e)
            }
        }
    }

    val isEmpty: Boolean = entries.isEmpty()

    private fun entryOf(token: String): FieldValueEntry? {
        val t = token.trim()
        return byValue[t] ?: byAlias[t]
    }

    /** 토큰의 정규값 — 별칭이면 canonical, 미등록이면 trim된 원 토큰 */
    fun canonical(token: String): String = entryOf(token)?.value ?: token.trim()

    /** 토큰의 표시 라벨 — 엔트리 없으면 trim된 원 토큰 */
    fun display(token: String): String = entryOf(token)?.display() ?: token.trim()

    /** 토큰의 카테고리 (라벨 체인 포함). 없으면 null */
    fun category(token: String): String? {
        val entry = entryOf(token) ?: return null
        entry.category.takeIf { it.isNotBlank() }?.let { return it }
        // 구 동작 보존: 카테고리가 라벨 키공간에 정의된 경우 (라벨값과 동일한 value의 엔트리)
        val label = entry.display()
        if (label != entry.value) {
            byValue[label]?.category?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    /** 통계 그룹핑 키 — 구 FieldStatsConfig.resolveStatsKeys 대체 */
    fun statsKeys(token: String, statsGroupBy: String): List<String> {
        val label = display(token)
        val cat = category(token)
        return when (statsGroupBy) {
            "category" -> listOf(cat ?: label)
            "both" -> if (cat != null) listOf(label, cat) else listOf(label)
            else -> listOf(label)  // "value" (기본)
        }
    }

    /** 검색 필터 확장: canonical 칩 하나가 별칭 저장값까지 매칭되도록 전체 표기 반환 */
    fun expandForFilter(canonicalValue: String): List<String> {
        val t = canonicalValue.trim()
        val entry = byValue[t] ?: return listOf(t)
        return listOf(entry.value) + entry.aliases()
    }
}
