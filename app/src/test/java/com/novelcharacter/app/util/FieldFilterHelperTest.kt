package com.novelcharacter.app.util

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
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
        override suspend fun getAllValuesForUniverse(universeId: Long): List<CharacterFieldValue> = unused()
        override suspend fun deleteValuesNotInUniverse(characterId: Long, universeId: Long) = unused()
        override suspend fun countValuesByUniverse(universeId: Long): Int = unused()
        override suspend fun deleteFieldValueForCharacters(characterIds: List<Long>, fieldDefId: Long) = unused()
        override suspend fun upsert(value: CharacterFieldValue): Long = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private class FakeFieldDao(val fields: Map<Long, FieldDefinition> = emptyMap()) : FieldDefinitionDao {
        override suspend fun getFieldById(id: Long): FieldDefinition? = fields[id]

        override fun getFieldsByUniverse(universeId: Long, entityType: String): LiveData<List<FieldDefinition>> = unused()
        override suspend fun getFieldsByUniverseList(universeId: Long, entityType: String): List<FieldDefinition> = unused()
        override suspend fun getFieldByKey(universeId: Long, key: String, entityType: String): FieldDefinition? = unused()
        override suspend fun getFieldsByType(universeId: Long, type: String, entityType: String): List<FieldDefinition> = unused()
        override suspend fun getGroupNames(universeId: Long, entityType: String): List<String> = unused()
        override suspend fun insert(field: FieldDefinition): Long = unused()
        override suspend fun insertAll(fields: List<FieldDefinition>) = unused()
        override suspend fun update(field: FieldDefinition) = unused()
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
        override suspend fun getForUniverse(universeId: Long, entityType: String): List<FieldValueEntry> = unused()
        override suspend fun getForFields(fieldDefIds: List<Long>): List<FieldValueEntry> = unused()
        override suspend fun getAllList(): List<FieldValueEntry> = unused()
        override suspend fun countByField(): List<FieldEntryCount> = unused()
        override suspend fun getByFieldAndValue(fieldDefId: Long, value: String): FieldValueEntry? = unused()
        override suspend fun getByCode(code: String): FieldValueEntry? = unused()
        override suspend fun insertAllIgnore(entries: List<FieldValueEntry>): List<Long> = unused()
        override suspend fun insert(entry: FieldValueEntry): Long = unused()
        override suspend fun update(entry: FieldValueEntry) = unused()
        override suspend fun updateAll(entries: List<FieldValueEntry>) = unused()
        override suspend fun delete(entry: FieldValueEntry) = unused()
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
