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
 *
 * ## 필드를 무엇으로 가리키는가 — 키가 정본이다 (B-11)
 * [resolveFieldIds]의 사다리를 보라. 종전에는 [FieldFilter.fieldId] 하나로만 걸어서
 * **전역 뷰가 세계관 A·B의 같은 키를 한 번에 못 걸렀다** — 그런데 같은 화면의 *정렬*은
 * 이미 키로 병합돼 있어(`CharacterListPreset.sortFieldKey`) 한 화면 안에서 두 잣대가
 * 갈려 있었다. 키를 정본으로 올려 그 어긋남을 없앤다.
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
            // 한 필터가 여러 필드를 가리킬 수 있다(같은 키의 세계관별 필드) — 그 합집합이 이 필터의 답이다.
            val fieldIds = resolveFieldIds(fieldDefinitionDao, filter)
            var evaluated = false
            for (fieldId in fieldIds) {
                if (matchOneField(dao, fieldDefinitionDao, entryDao, filter, fieldId, idsForFilter)) {
                    evaluated = true
                }
            }
            // **걸 필드는 있는데 값이 하나도 조건이 되지 못하면 그 필터는 조건이 없는 것과 같다 —
            // 종전 그대로 건너뛴다.** 좁히지도 넓히지도 않는 자리이고, 여기서 빈 집합으로 떨어뜨리면
            // 빈 값 하나가 교집합을 0으로 만들어 **결과가 통째로 사라진다**(손편집 엑셀 프리셋이 그 입력).
            // 걸 필드가 **아예 없는 것**(죽은 키·`sys:`)은 다른 사건이라 여기 걸리지 않는다 —
            // 그쪽은 아무도 통과시키지 않는 것이 답이다([resolveFieldIds] 2번).
            if (fieldIds.isNotEmpty() && !evaluated) continue
            resultIds = resultIds?.intersect(idsForFilter) ?: idsForFilter
        }
        return resultIds ?: emptySet()
    }

    /**
     * 필터 하나가 실제로 걸리는 필드 id들 — **키 → (시스템 열) → id 사다리** (B-11).
     * `DuelCandidateFilter.resolve`가 이미 세운 사다리와 같은 순서이고, 다른 것은 마지막 한 걸음뿐이다.
     *
     * 1. **키가 있고 그 키의 필드가 실재하면 그 전부** — 세계관 A의 '성별'과 세계관 B의 '성별'이
     *    함께 걸린다. 키는 세계관 안에서만 유니크하므로 여러 건이 정상이다.
     * 2. **키가 있는데 필드가 하나도 없으면 아무것도 걸지 않는다.** `sys:` 어휘(태그·작품 같은
     *    표의 열)는 이 해석기의 것이 아니고 — 그쪽은 `DuelCandidateFilter.matchesSystem`이 든다 —
     *    지워진 필드도 여기로 온다. **어느 쪽이든 id로 떨어뜨리지 않는 것이 요점이다:**
     *    id는 기기 이전·복원에서 재발급되므로, 키가 죽은 필터를 id로 구제하면 *'성별' 라벨 아래
     *    실제로는 '거주지'를 거르는* 필터가 된다(`PortableFieldFilters`가 경계하는 그 오배정).
     *    걸리는 것이 없으면 그 필터는 아무도 통과시키지 않는다 — 조용히 넓어지지 않는다.
     * 3. **키가 없으면 종전 그대로 id 하나.** 키를 안 적던 시절의 프리셋과, 키 없이 id만 적은
     *    손편집 엑셀이 그대로 산다.
     */
    private suspend fun resolveFieldIds(
        fieldDefinitionDao: FieldDefinitionDao,
        filter: FieldFilter
    ): List<Long> {
        val key = filter.fieldKey?.takeIf { it.isNotBlank() } ?: return listOf(filter.fieldId)
        return fieldDefinitionDao.getFieldsByKey(key).map { it.id }
    }

    /**
     * 필드 **하나**의 대조 — 결과를 [into]에 더하고, **이 필드에서 조건이 실제로 섰는가**를 돌려준다.
     * false는 *걸린 것이 없다*가 아니라 **잴 조건 자체가 없었다**는 뜻이다(필터 값이 이 필드의
     * 토큰 규칙으로는 빈 것이 된 경우). 그 구분을 호출부가 써서 필터를 건너뛴다.
     *
     * 값 라이브러리(별칭)와 토큰 규칙은 **필드마다 다르다**. 그래서 여러 필드를 걸 때도 해석을
     * 필드별로 다시 한다: 세계관 A가 `남성`을 canonical로, 세계관 B가 `male`을 canonical로
     * 두고 서로를 별칭에 담고 있으면, A에서 고른 칩 하나가 **양쪽 모두**에 걸린다.
     * 해석을 한 번만 하고 돌려쓰면 B의 저장값은 조용히 빠진다.
     */
    private suspend fun matchOneField(
        dao: CharacterFieldValueDao,
        fieldDefinitionDao: FieldDefinitionDao,
        entryDao: FieldValueEntryDao,
        filter: FieldFilter,
        fieldId: Long,
        into: MutableSet<Long>
    ): Boolean {
        if (filter.matchMode == "contains") {
            // contains는 종전에도 값을 거르지 않았다(빈 값은 LIKE '%%'로 그 필드를 가진 전원) —
            // 조건이 선 것으로 본다. 두 모드의 이 차이는 이 판이 만든 것이 아니다.
            for (value in filter.values) {
                into.addAll(dao.getCharacterIdsByFieldValueContains(fieldId, sanitizeLikeQuery(value)))
            }
            return true
        }
        val fd = fieldDefinitionDao.getFieldById(fieldId)
        val resolver = FieldValueResolver(entryDao.getByField(fieldId))
        val targets = filter.values
            // 구버전 프리셋은 다중값 원문("서울, 부산")을 통째로 저장했다 — 토큰 단위로 분해해
            // 새 토큰 매칭에서도 계속 동작하게 한다 (필터 내 값 간 OR 규칙과 합치)
            .flatMap { v -> fd?.let { FieldValueTokenizer.tokenize(it, v) } ?: listOf(v.trim()) }
            .map { resolver.canonical(it) }              // trim + 별칭→canonical
            .flatMap { resolver.expandForFilter(it) }    // canonical + 별칭 전체
            .toSet()
        if (targets.isEmpty()) return false
        if (fd == null) {
            // 필드 정의가 사라진 필터 — 기존 정확일치로 관대 처리
            for (value in targets) {
                into.addAll(dao.getCharacterIdsByFieldValue(fieldId, value))
            }
        } else {
            for (row in dao.getValuesByFieldDef(fieldId)) {
                if (FieldValueTokenizer.tokenize(fd, row.value).any { it in targets }) {
                    into.add(row.characterId)
                }
            }
        }
        return true
    }

    /**
     * 두 필터가 **같은 대상**을 가리키는가 — 화면이 필터를 갈아 끼우거나 지울 때의 동일성 판정.
     *
     * 키가 양쪽에 있으면 키가 정본이다. 그래야 세계관 A의 '성별'을 걸어 둔 채 세계관 B의 '성별'을
     * 고르면 **덧붙지 않고 갈린다** — 둘은 이제 같은 필터이므로 둘로 두면 같은 조건이 AND로 두 번
     * 걸리고, 칩 하나를 지워도 나머지 하나가 남아 사용자가 지운 조건이 계속 산다.
     * 한쪽이라도 키가 없으면 옛 규약대로 id로 가른다.
     */
    fun sameTarget(a: FieldFilter, b: FieldFilter): Boolean {
        val ak = a.fieldKey?.takeIf { it.isNotBlank() }
        val bk = b.fieldKey?.takeIf { it.isNotBlank() }
        return if (ak != null && bk != null) ak == bk else a.fieldId == b.fieldId
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
