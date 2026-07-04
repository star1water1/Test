package com.novelcharacter.app.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.repository.sanitizeLikeQuery

/**
 * `FieldFilter` 목록을 캐릭터 ID 집합으로 해석하고 JSON 직렬화를 담당하는 단일 소스.
 *
 * Global Search와 캐릭터 탭이 같은 규칙(필터 간 AND, 값 간 OR)을 공유하도록
 * 로직을 한 곳에 둔다(중복 정의 → 규칙 분기 방지).
 */
object FieldFilterHelper {

    private val gson = Gson()

    /** 필터 간 AND, 한 필터 내 값 간 OR로 매칭 캐릭터 ID 집합 반환. 필터가 없으면 빈 집합. */
    suspend fun applyFieldFilters(dao: CharacterFieldValueDao, filters: List<FieldFilter>): Set<Long> {
        var resultIds: Set<Long>? = null
        for (filter in filters) {
            val idsForFilter = mutableSetOf<Long>()
            for (value in filter.values) {
                val ids = when (filter.matchMode) {
                    "contains" -> dao.getCharacterIdsByFieldValueContains(filter.fieldId, sanitizeLikeQuery(value))
                    else -> dao.getCharacterIdsByFieldValue(filter.fieldId, value)
                }
                idsForFilter.addAll(ids)
            }
            resultIds = resultIds?.intersect(idsForFilter) ?: idsForFilter
        }
        return resultIds ?: emptySet()
    }

    /** 필터 목록 → JSON. 비어 있으면 "{}"(빈 상태 규약). */
    fun filtersToJson(filters: List<FieldFilter>): String =
        if (filters.isEmpty()) "{}" else gson.toJson(filters)

    /** JSON → 필터 목록. ""/"{}"/파손 입력은 빈 목록으로 관대 처리. */
    fun filtersFromJson(json: String): List<FieldFilter> {
        if (json.isBlank() || json == "{}") return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<FieldFilter>>() {}.type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
