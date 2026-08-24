package com.novelcharacter.app.util

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
import com.novelcharacter.app.data.dao.CharacterValueUniverse
import com.novelcharacter.app.data.dao.FieldDefinitionDao
import com.novelcharacter.app.data.dao.FieldEntryCount
import com.novelcharacter.app.data.dao.FieldValueEntryDao
import com.novelcharacter.app.data.dao.UniverseFieldCount
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.FieldValueEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 필드 필터 규칙(필터 간 AND, 값 간 OR) + exact/contains 라우팅 + 빈 필터 단락.
 * exact 모드는 라이브러리 토큰 규칙(trim·다중값 토큰·별칭 확장)으로 매칭한다 — 검토 A16의 회귀 가드:
 * "서울 " 미trim 행, 콤마 다중값, 별칭 저장값, 구버전 프리셋의 미trim 필터 값이 모두 매칭되어야 한다.
 */
class FieldFilterHelperTest {

    private fun fd(id: Long, type: String = "TEXT", config: String = "{}") = FieldDefinition(
        id = id, universeId = 1L, key = "k$id", name = "필드$id", type = type, config = config
    )

    /** 키를 공유하는 세계관별 필드 — 크로스-세계관 필터(B-11)의 재료. */
    private fun fdKeyed(id: Long, universeId: Long, key: String, type: String = "TEXT") = FieldDefinition(
        id = id, universeId = universeId, key = key, name = "성별", type = type
    )

    private fun row(charId: Long, fieldId: Long, value: String) =
        CharacterFieldValue(id = charId * 1000 + fieldId, characterId = charId, fieldDefinitionId = fieldId, value = value)

    private class FakeValueDao(
        val rows: List<CharacterFieldValue> = emptyList(),
        val contains: Map<Pair<Long, String>, List<Long>> = emptyMap()
    ) : CharacterFieldValueDao {
        var exactCalls = 0
        var containsCalls = 0
        var rowLoads = 0

        override suspend fun getCharacterIdsByFieldValue(fieldDefId: Long, value: String): List<Long> {
            exactCalls++
            return rows.filter { it.fieldDefinitionId == fieldDefId && it.value == value }.map { it.characterId }
        }

        override suspend fun getCharacterIdsByFieldValueContains(fieldDefId: Long, value: String): List<Long> {
            containsCalls++
            return contains[fieldDefId to value] ?: emptyList()
        }

        override suspend fun getValuesByFieldDef(fieldDefId: Long): List<CharacterFieldValue> {
            rowLoads++
            return rows.filter { it.fieldDefinitionId == fieldDefId }
        }

        // ---- 테스트 경로에서 호출되지 않는 나머지 인터페이스 멤버 ----
        override fun getValuesByCharacter(characterId: Long): LiveData<List<CharacterFieldValue>> = unused()
        override suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> = unused()
        override suspend fun getAllValuesList(): List<CharacterFieldValue> = unused()
        override suspend fun getValue(characterId: Long, fieldId: Long): CharacterFieldValue? = unused()
        override suspend fun insert(value: CharacterFieldValue): Long = unused()
        override suspend fun insertAll(values: List<CharacterFieldValue>) = unused()
        override suspend fun update(value: CharacterFieldValue) = unused()
        override suspend fun deleteAllByCharacter(characterId: Long) = unused()
        override suspend fun deleteValue(characterId: Long, fieldId: Long) = unused()
        override suspend fun getValueByFieldKey(characterId: Long, fieldKey: String): CharacterFieldValue? = unused()
        override suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String> = unused()
        override suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String> = unused()
        override suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue> = unused()
        override suspend fun getValuesForFields(fieldDefIds: List<Long>): List<CharacterFieldValue> = unused()
        override suspend fun countValuesNotInUniverse(characterId: Long, universeId: Long): Int = unused()
        override suspend fun deleteValuesNotInUniverse(characterId: Long, universeId: Long) = unused()
        override suspend fun countValuesByUniverse(universeId: Long): Int = unused()
        override suspend fun deleteFieldValueForCharacters(characterIds: List<Long>, fieldDefId: Long) = unused()
        override suspend fun upsert(value: CharacterFieldValue): Long = unused()
        override suspend fun getValueUniversesForCharacters(characterIds: List<Long>): List<CharacterValueUniverse> = unused()
        override suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>): List<CharacterFieldValue> = unused()
        override suspend fun getOrphanValuesForUniverseFields(fieldDefIds: List<Long>, universeId: Long): List<CharacterFieldValue> = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private class FakeFieldDao(val fields: Map<Long, FieldDefinition> = emptyMap()) : FieldDefinitionDao {
        var keyLookups = 0

        override suspend fun getFieldById(id: Long): FieldDefinition? = fields[id]
        override suspend fun getFieldsByIds(ids: List<Long>): List<FieldDefinition> = ids.mapNotNull { fields[it] }
        override suspend fun getFieldsByKey(key: String, entityType: String): List<FieldDefinition> {
            keyLookups++
            return fields.values.filter { it.key == key && it.entityType == entityType }
        }
        override suspend fun getFieldsByUniverseAllTypes(universeId: Long): List<FieldDefinition> = unused()
        override suspend fun getGlobalFieldsList(entityType: String): List<FieldDefinition> = unused()
        override suspend fun getGlobalFieldsAllTypes(): List<FieldDefinition> = unused()
        override suspend fun deleteGlobalByIds(ids: List<Long>) = unused()
        override suspend fun getGlobalFieldByKey(key: String, entityType: String): FieldDefinition? = unused()

        override fun getFieldsByUniverse(universeId: Long, entityType: String): LiveData<List<FieldDefinition>> = unused()
        override suspend fun getFieldsByUniverseList(universeId: Long, entityType: String): List<FieldDefinition> = unused()
        override suspend fun getFieldByKey(universeId: Long, key: String, entityType: String): FieldDefinition? = unused()
        override suspend fun getMaxDisplayOrder(universeId: Long, entityType: String): Int? = unused()
        override suspend fun getMaxDisplayOrderGlobal(entityType: String): Int? = unused()
        override suspend fun insert(field: FieldDefinition): Long = unused()
        override suspend fun insertAll(fields: List<FieldDefinition>) = unused()
        override suspend fun update(field: FieldDefinition) = unused()
        override suspend fun setDisplayOrder(id: Long, order: Int) = unused()
        override suspend fun updateAll(fields: List<FieldDefinition>) = unused()
        override suspend fun delete(field: FieldDefinition) = unused()
        override suspend fun getAllFieldsList(entityType: String): List<FieldDefinition> = unused()
        override suspend fun getAllFieldsAllTypes(): List<FieldDefinition> = unused()
        override suspend fun deleteAllByUniverse(universeId: Long) = unused()
        override suspend fun getFieldCountsByUniverses(universeIds: List<Long>): List<UniverseFieldCount> = unused()
        override suspend fun countFieldsByKeyExcluding(key: String, excludeId: Long, entityType: String): Int = unused()
        override suspend fun deleteAll() = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private class FakeEntryDao(val entries: Map<Long, List<FieldValueEntry>> = emptyMap()) : FieldValueEntryDao {
        override suspend fun getByField(fieldDefId: Long): List<FieldValueEntry> = entries[fieldDefId].orEmpty()

        override fun getByFieldLive(fieldDefId: Long): LiveData<List<FieldValueEntry>> = unused()
        override suspend fun getForFields(fieldDefIds: List<Long>): List<FieldValueEntry> = unused()
        override suspend fun getAllList(): List<FieldValueEntry> = unused()
        override suspend fun countByField(): List<FieldEntryCount> = unused()
        override suspend fun getByFieldAndValue(fieldDefId: Long, value: String): FieldValueEntry? = unused()
        override suspend fun getByCode(code: String): FieldValueEntry? = unused()
        override suspend fun getByCodes(codes: List<String>): List<FieldValueEntry> = unused()
        override suspend fun insertAllIgnore(entries: List<FieldValueEntry>): List<Long> = unused()
        override suspend fun insert(entry: FieldValueEntry): Long = unused()
        override suspend fun update(entry: FieldValueEntry) = unused()
        override suspend fun updateAll(entries: List<FieldValueEntry>) = unused()
        override suspend fun delete(entry: FieldValueEntry) = unused()
        override suspend fun deleteAll() = unused()
        override suspend fun pruneUncuratedUnused(fieldDefId: Long): Int = unused()
        override suspend fun countUncuratedUnused(fieldDefId: Long): Int = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private suspend fun apply(
        valueDao: FakeValueDao,
        filters: List<FieldFilter>,
        fields: Map<Long, FieldDefinition> = emptyMap(),
        entries: Map<Long, List<FieldValueEntry>> = emptyMap()
    ): Set<Long> = FieldFilterHelper.applyFieldFilters(valueDao, FakeFieldDao(fields), FakeEntryDao(entries), filters)

    @Test
    fun emptyFilters_returnsEmpty_daoNotCalled() = runTest {
        val dao = FakeValueDao()
        assertEquals(emptySet<Long>(), apply(dao, emptyList()))
        assertEquals(0, dao.exactCalls)
        assertEquals(0, dao.containsCalls)
        assertEquals(0, dao.rowLoads)
    }

    @Test
    fun singleFilter_orWithinValues() = runTest {
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "red"), row(2, 10, "red"), row(2, 10, "blue"), row(3, 10, "blue")
        ))
        val filter = FieldFilter(fieldId = 10L, fieldName = "color", values = listOf("red", "blue"), matchMode = "exact")
        assertEquals(setOf(1L, 2L, 3L), apply(dao, listOf(filter), fields = mapOf(10L to fd(10))))
    }

    @Test
    fun multipleFilters_andAcrossFilters() = runTest {
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "red"), row(2, 10, "red"), row(3, 10, "red"),
            row(2, 20, "tall"), row(3, 20, "tall"), row(4, 20, "tall")
        ))
        val f1 = FieldFilter(10L, "color", listOf("red"), "exact")
        val f2 = FieldFilter(20L, "height", listOf("tall"), "exact")
        assertEquals(setOf(2L, 3L), apply(dao, listOf(f1, f2), fields = mapOf(10L to fd(10), 20L to fd(20))))
    }

    @Test
    fun containsMode_routesToContainsQuery() = runTest {
        val dao = FakeValueDao(contains = mapOf((10L to "ed") to listOf(1L)))
        val filter = FieldFilter(10L, "color", listOf("ed"), "contains")
        assertEquals(setOf(1L), apply(dao, listOf(filter)))
        assertEquals(1, dao.containsCalls)
        assertEquals(0, dao.exactCalls)
    }

    @Test
    fun exact_matchesUntrimmedRows_andUntrimmedFilterValues() = runTest {
        // 저장 행 "서울 "(미trim)과 구버전 프리셋의 필터 값 " 서울" 모두 매칭 (A16 핵심 회귀)
        val dao = FakeValueDao(rows = listOf(row(1, 10, "서울 "), row(2, 10, "서울")))
        val filter = FieldFilter(10L, "거주지", listOf(" 서울"), "exact")
        assertEquals(setOf(1L, 2L), apply(dao, listOf(filter), fields = mapOf(10L to fd(10))))
    }

    @Test
    fun exact_multiTokenField_matchesPerToken() = runTest {
        val multi = fd(10, type = "MULTI_TEXT")
        val dao = FakeValueDao(rows = listOf(row(1, 10, "불, 얼음"), row(2, 10, "번개")))
        val filter = FieldFilter(10L, "속성", listOf("얼음"), "exact")
        assertEquals(setOf(1L), apply(dao, listOf(filter), fields = mapOf(10L to multi)))
    }

    @Test
    fun exact_legacyWholeStringFilterValue_onMultiTokenField_matchesPerToken() = runTest {
        // 구버전 프리셋은 다중값 원문("서울, 부산")을 필터 값으로 통째 저장했다 —
        // 토큰 분해로 두 토큰 중 하나라도 가진 행이 매칭되어야 한다 (0건 무음 회귀 방지)
        val multi = fd(10, type = "MULTI_TEXT")
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "서울, 대전"), row(2, 10, "부산"), row(3, 10, "광주")
        ))
        val filter = FieldFilter(10L, "거주지", listOf("서울, 부산"), "exact")
        assertEquals(setOf(1L, 2L), apply(dao, listOf(filter), fields = mapOf(10L to multi)))
    }

    @Test
    fun exact_aliasExpansion_matchesAliasFormRows() = runTest {
        // canonical 칩 "서울" 하나로 별칭 저장값 "서울시"까지 매칭, 별칭을 필터 값으로 넣어도 동작
        val entry = FieldValueEntry(
            id = 1, fieldDefinitionId = 10, value = "서울",
            aliasesJson = FieldValueEntry.aliasesToJson(listOf("서울시"))
        )
        val dao = FakeValueDao(rows = listOf(row(1, 10, "서울"), row(2, 10, "서울시"), row(3, 10, "부산")))
        val byCanonical = FieldFilter(10L, "거주지", listOf("서울"), "exact")
        val byAlias = FieldFilter(10L, "거주지", listOf("서울시"), "exact")
        val fields = mapOf(10L to fd(10))
        val entries = mapOf(10L to listOf(entry))
        assertEquals(setOf(1L, 2L), apply(dao, listOf(byCanonical), fields, entries))
        assertEquals(setOf(1L, 2L), apply(dao, listOf(byAlias), fields, entries))
    }

    @Test
    fun exact_filterWithNoUsableValue_isSkipped_notTreatedAsNoMatch() = runTest {
        // **조건이 되지 못하는 값만 든 필터는 조건이 없는 것과 같다 — 건너뛴다.**
        // 종전 코드의 `if (targets.isEmpty()) continue`가 그것이고, 키 사다리를 들이면서
        // 그 `continue`가 *필터 하나*에서 *필드 하나*로 좁아질 뻔했다. 좁아지면 빈 값 필터가
        // 교집합을 0으로 만들어 **결과가 통째로 사라진다**(엑셀에서 손편집한 프리셋이 그 입력이다).
        // 걸리는 필드가 아예 없는 것(죽은 키·`sys:`)과는 다른 사건이라 처분도 다르다.
        val dao = FakeValueDao(rows = listOf(row(1, 10, "red"), row(1, 20, "tall"), row(2, 20, "tall")))
        val fields = mapOf(10L to fd(10), 20L to fd(20))
        val real = FieldFilter(20L, "height", listOf("tall"), "exact")
        val blank = FieldFilter(10L, "color", listOf("  "), "exact")
        assertEquals(setOf(1L, 2L), apply(dao, listOf(real, blank), fields))
        // 빈 값 필터 하나뿐이면 아무것도 안 거른 것과 같다(빈 필터 목록과 같은 답).
        assertEquals(emptySet<Long>(), apply(dao, listOf(blank), fields))
    }

    @Test
    fun key_someFieldsHaveNoUsableValue_othersStillMatch() = runTest {
        // 필드마다 토큰 규칙이 달라 **어떤 필드에서만** 값이 조건이 되지 못할 수 있다.
        // 그때 그 필드만 건너뛰고 나머지는 그대로 걸려야 한다 — 건너뛰기가 필터 전체로
        // 번지면 나머지 세계관이 함께 사라진다.
        val fields = mapOf(
            10L to fdKeyed(10, 1, "trait", type = "MULTI_TEXT"),
            20L to fdKeyed(20, 2, "trait")
        )
        val dao = FakeValueDao(rows = listOf(row(1, 10, ","), row(2, 20, ",")))
        // 다중값 필드에서는 ","가 토큰 0개로 갈리고, 단일값 필드에서는 ","가 그대로 한 토큰이다.
        val filter = FieldFilter(10L, "속성", listOf(","), "exact", fieldKey = "trait")
        assertEquals(setOf(2L), apply(dao, listOf(filter), fields))
    }

    // ── 키 사다리 — 전역 뷰가 세계관 A·B·C의 같은 키를 한 번에 거른다 (B-11) ──

    @Test
    fun key_matchesSameKeyAcrossUniverses_idOnlyMatchesOne() = runTest {
        // 세계관 셋이 같은 키 'gender'를 각자의 id로 들고 있다(키는 세계관 안에서만 유니크).
        val fields = mapOf(
            10L to fdKeyed(10, 1, "gender"),
            20L to fdKeyed(20, 2, "gender"),
            30L to fdKeyed(30, 3, "gender")
        )
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "여성"), row(2, 20, "여성"), row(3, 30, "여성"), row(4, 20, "남성")
        ))
        val byKey = FieldFilter(10L, "성별", listOf("여성"), "exact", fieldKey = "gender")
        assertEquals(setOf(1L, 2L, 3L), apply(dao, listOf(byKey), fields))

        // 키가 없으면 종전 그대로 — id 하나만 걸린다(이 행이 열려 있던 이유 그 자체다).
        val byId = FieldFilter(10L, "성별", listOf("여성"), "exact", fieldKey = null)
        assertEquals(setOf(1L), apply(dao, listOf(byId), fields))
    }

    @Test
    fun key_resolvesAliasesPerField_notOnceForAll() = runTest {
        // **착수 조건이 요구한 실측**(로드맵 9판 — "세 세계관 표본으로 현행 별칭 처리를 실측").
        // 값 라이브러리는 필드마다 다르다: A는 '여성'을 canonical로 두고 'F'를 별칭에,
        // B는 반대로 'F'를 canonical로 두고 '여성'을 별칭에 뒀다. C는 라이브러리가 없다.
        // 해석을 한 번만 하고 돌려쓰면 B의 저장값('F')이 조용히 빠진다 —
        // 그래서 필드마다 다시 해석한다.
        val fields = mapOf(
            10L to fdKeyed(10, 1, "gender"),
            20L to fdKeyed(20, 2, "gender"),
            30L to fdKeyed(30, 3, "gender")
        )
        val entries = mapOf(
            10L to listOf(FieldValueEntry(
                id = 1, fieldDefinitionId = 10, value = "여성",
                aliasesJson = FieldValueEntry.aliasesToJson(listOf("F"))
            )),
            20L to listOf(FieldValueEntry(
                id = 2, fieldDefinitionId = 20, value = "F",
                aliasesJson = FieldValueEntry.aliasesToJson(listOf("여성"))
            ))
        )
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "여성"), row(2, 10, "F"),   // A: canonical과 별칭 저장값이 섞여 있다
            row(3, 20, "F"),                       // B: 이 기기의 canonical은 'F'다
            row(4, 30, "여성"),                    // C: 라이브러리 없음 — 글자 그대로
            row(5, 20, "남성")
        ))
        val filter = FieldFilter(10L, "성별", listOf("여성"), "exact", fieldKey = "gender")
        assertEquals(setOf(1L, 2L, 3L, 4L), apply(dao, listOf(filter), fields, entries))
    }

    @Test
    fun key_perFieldTokenRules_multiValueInOneUniverseOnly() = runTest {
        // 토큰 규칙도 필드마다다 — 같은 키인데 A만 다중값(콤마 분해) 필드인 경우.
        val fields = mapOf(
            10L to fdKeyed(10, 1, "trait", type = "MULTI_TEXT"),
            20L to fdKeyed(20, 2, "trait", type = "TEXT")
        )
        val dao = FakeValueDao(rows = listOf(
            row(1, 10, "불, 얼음"),   // 다중값 — '얼음' 토큰이 산다
            row(2, 20, "불, 얼음"),   // 단일값 — 통째로 한 값이라 '얼음'과 다르다
            row(3, 20, "얼음")
        ))
        val filter = FieldFilter(10L, "속성", listOf("얼음"), "exact", fieldKey = "trait")
        assertEquals(setOf(1L, 3L), apply(dao, listOf(filter), fields))
    }

    @Test
    fun key_containsMode_alsoSpansUniverses() = runTest {
        val fields = mapOf(10L to fdKeyed(10, 1, "city"), 20L to fdKeyed(20, 2, "city"))
        val dao = FakeValueDao(contains = mapOf(
            (10L to "서울") to listOf(1L),
            (20L to "서울") to listOf(2L)
        ))
        val filter = FieldFilter(10L, "거주지", listOf("서울"), "contains", fieldKey = "city")
        assertEquals(setOf(1L, 2L), apply(dao, listOf(filter), fields))
    }

    @Test
    fun key_deadKey_matchesNothing_neverFallsBackToId() = runTest {
        // **id로 떨어뜨리지 않는 것이 요점이다.** id는 기기 이전·복원에서 재발급되므로,
        // 키가 죽은 필터를 id로 구제하면 '성별' 라벨 아래 실제로는 '거주지'를 거르게 된다.
        // 20번 필드는 실재하지만 키가 다르다 — 그 자리에 옛 id가 그대로 남아 있는 상황.
        val fields = mapOf(20L to fdKeyed(20, 2, "residence"))
        val dao = FakeValueDao(rows = listOf(row(1, 20, "여성")))
        val dead = FieldFilter(20L, "성별", listOf("여성"), "exact", fieldKey = "gender")
        assertEquals(emptySet<Long>(), apply(dao, listOf(dead), fields))
    }

    @Test
    fun key_systemKey_matchesNothing_notOwnedByThisResolver() = runTest {
        // `sys:` 어휘(태그·작품 같은 표의 열)는 DuelCandidateFilter.matchesSystem이 든다.
        // 여기로 흘러들면(손편집 엑셀이 검색 프리셋에 적어 넣는 길) 아무도 통과시키지 않는다 —
        // id로 떨어뜨려 엉뚱한 커스텀 필드를 거르는 것보다 낫다.
        val fields = mapOf(10L to fdKeyed(10, 1, "gender"))
        val dao = FakeValueDao(rows = listOf(row(1, 10, "여성")))
        val sys = FieldFilter(10L, "태그", listOf("여성"), "exact", fieldKey = "sys:tags")
        assertEquals(emptySet<Long>(), apply(dao, listOf(sys), fields))
    }

    @Test
    fun key_realFieldWins_overSystemVocabulary() = runTest {
        // 사용자가 `sys:tags`라는 키의 진짜 필드를 만들었으면 그 필드가 답한다
        // (B-167이 세운 규칙 그대로 — DuelCandidateFilter.resolve와 같은 순서다).
        val fields = mapOf(10L to fdKeyed(10, 1, "sys:tags"))
        val dao = FakeValueDao(rows = listOf(row(1, 10, "여성")))
        val filter = FieldFilter(10L, "태그", listOf("여성"), "exact", fieldKey = "sys:tags")
        assertEquals(setOf(1L), apply(dao, listOf(filter), fields))
    }

    @Test
    fun noKey_neverAsksForKeyLookup() = runTest {
        // 키가 없으면 조회 자체를 하지 않는다 — 옛 프리셋이 세계관 수만큼 질의를 늘리지 않는다.
        val fieldDao = FakeFieldDao(mapOf(10L to fd(10)))
        val dao = FakeValueDao(rows = listOf(row(1, 10, "red")))
        FieldFilterHelper.applyFieldFilters(
            dao, fieldDao, FakeEntryDao(),
            listOf(FieldFilter(10L, "color", listOf("red"), "exact"))
        )
        assertEquals(0, fieldDao.keyLookups)
    }

    // ── 같은 대상 판정 — 칩 교체·제거의 동일성 (B-11) ──

    @Test
    fun sameTarget_keyWinsWhenBothHaveIt_idOtherwise() {
        val a = FieldFilter(10L, "성별", listOf("여성"), "exact", fieldKey = "gender")
        val b = FieldFilter(20L, "성별", listOf("남성"), "exact", fieldKey = "gender")
        val c = FieldFilter(20L, "거주지", listOf("서울"), "exact", fieldKey = "residence")
        // 세계관이 달라 id는 다르지만 같은 조건 자리다 — 덧붙지 않고 갈려야 한다.
        assertTrue(FieldFilterHelper.sameTarget(a, b))
        assertTrue(!FieldFilterHelper.sameTarget(b, c))

        // 한쪽이라도 키가 없으면 옛 규약대로 id로 가른다.
        val legacy = FieldFilter(20L, "성별", listOf("여성"), "exact")
        assertTrue(FieldFilterHelper.sameTarget(legacy, c))
        assertTrue(!FieldFilterHelper.sameTarget(legacy, a))
        // 빈 문자열 키는 키가 없는 것과 같게 다룬다(엑셀 손편집이 빈 칸을 남기는 자리).
        val blankKey = FieldFilter(20L, "성별", listOf("여성"), "exact", fieldKey = "")
        assertTrue(FieldFilterHelper.sameTarget(blankKey, c))
    }

    @Test
    fun epochMemo_reusesFilterIds_untilEpochBumps() = runTest {
        val dao = FakeValueDao(rows = listOf(row(1, 10, "red"), row(2, 10, "red")))
        val fields = mapOf(10L to fd(10))
        val memo = EpochMemo<List<FieldFilter>, Set<Long>> { apply(dao, it, fields) }
        val filters = listOf(FieldFilter(10L, "color", listOf("red"), "exact"))
        assertEquals(setOf(1L, 2L), memo.get(filters, 0))
        assertEquals(setOf(1L, 2L), memo.get(filters, 0)) // 캐시 HIT
        assertEquals(1, dao.rowLoads)                      // 재조회 없음
        memo.get(filters, 1)                               // 에폭 상승(테이블 변경)
        assertEquals(2, dao.rowLoads)                      // 재조회
    }
}
