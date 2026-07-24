package com.novelcharacter.app.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.FieldValueEntryDao
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.repository.sanitizeLikeQuery

/**
 * `FieldFilter` 목록을 캐릭터 ID 집합으로 해석하고 JSON 직렬화를 담당하는 단일 소스.
 *
 * Global Search와 캐릭터 탭이 같은 규칙(필터 간 AND, 값 간 OR)을 공유하도록
 * 로직을 한 곳에 둔다(중복 정의 → 규칙 분기 방지).
 *
 * exact 모드는 값 라이브러리와 같은 토큰 규칙으로 매칭한다(검토 A16):
 * - 필터 값을 Kotlin trim → canonical 해석 → 별칭까지 확장 (canonical 칩 하나가 별칭 저장값도 매칭)
 * - 저장값은 SQL 정확일치 대신 로드→토큰화(trim) 비교 — "서울 " 같은 미trim 행,
 *   콤마 다중값의 토큰 단위 매칭, 구버전 프리셋에 저장된 미trim 필터 값이 모두 동작한다.
 * contains 모드는 원시 저장값 LIKE 유지.
 */
object FieldFilterHelper {

    private val gson = Gson()

    /** 필터 간 AND, 한 필터 내 값 간 OR로 매칭 캐릭터 ID 집합 반환. 필터가 없으면 빈 집합. */
    suspend fun applyFieldFilters(
        dao: CharacterFieldValueDao,
        fieldDefinitionDao: FieldDefinitionDao,
        entryDao: FieldValueEntryDao,
        filters: List<FieldFilter>
    ): Set<Long> {
        var resultIds: Set<Long>? = null
        for (filter in filters) {
            val idsForFilter = mutableSetOf<Long>()
            if (filter.matchMode == "contains") {
                for (value in filter.values) {
                    idsForFilter.addAll(
                        dao.getCharacterIdsByFieldValueContains(filter.fieldId, sanitizeLikeQuery(value))
                    )
                }
            } else {
                val fd = fieldDefinitionDao.getFieldById(filter.fieldId)
                val resolver = FieldValueResolver(entryDao.getByField(filter.fieldId))
                val targets = filter.values
                    // 구버전 프리셋은 다중값 원문("서울, 부산")을 통째로 저장했다 — 토큰 단위로 분해해
                    // 새 토큰 매칭에서도 계속 동작하게 한다 (필터 내 값 간 OR 규칙과 합치)
                    .flatMap { v -> fd?.let { FieldValueTokenizer.tokenize(it, v) } ?: listOf(v.trim()) }
                    .map { resolver.canonical(it) }              // trim + 별칭→canonical
                    .flatMap { resolver.expandForFilter(it) }    // canonical + 별칭 전체
                    .toSet()
                if (targets.isEmpty()) continue
                if (fd == null) {
                    // 필드 정의가 사라진 필터 — 기존 정확일치로 관대 처리
                    for (value in targets) {
                        idsForFilter.addAll(dao.getCharacterIdsByFieldValue(filter.fieldId, value))
                    }
                } else {
                    for (row in dao.getValuesByFieldDef(filter.fieldId)) {
                        if (FieldValueTokenizer.tokenize(fd, row.value).any { it in targets }) {
                            idsForFilter.add(row.characterId)
                        }
                    }
                }
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
