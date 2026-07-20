package com.novelcharacter.app.util

import androidx.lifecycle.LiveData
import com.novelcharacter.app.data.dao.CharacterFieldValueDao
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldFilter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 필드 필터 규칙(필터 간 AND, 값 간 OR) + exact/contains 라우팅 + 빈 필터 단락.
 * 캐시 HIT가 재현해야 하는 정확한 시맨틱이자, EpochMemo와 결합한 무효화 회귀 가드(마지막 테스트).
 */
class FieldFilterHelperTest {

    /** exact/contains 조회만 스크립트하고 호출 횟수를 센다. 그 외 메서드는 테스트 경로에서 미사용. */
    private class FakeDao(
        val exact: Map<Pair<Long, String>, List<Long>> = emptyMap(),
        val contains: Map<Pair<Long, String>, List<Long>> = emptyMap()
    ) : CharacterFieldValueDao {
        var exactCalls = 0
        var containsCalls = 0

        override suspend fun getCharacterIdsByFieldValue(fieldDefId: Long, value: String): List<Long> {
            exactCalls++
            return exact[fieldDefId to value] ?: emptyList()
        }

        override suspend fun getCharacterIdsByFieldValueContains(fieldDefId: Long, value: String): List<Long> {
            containsCalls++
            return contains[fieldDefId to value] ?: emptyList()
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
        override suspend fun getValuesByFieldDef(fieldDefId: Long): List<CharacterFieldValue> = unused()
        override suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue> = unused()
        override suspend fun getAllValuesForUniverse(universeId: Long): List<CharacterFieldValue> = unused()
        override suspend fun deleteValuesNotInUniverse(characterId: Long, universeId: Long) = unused()
        override suspend fun countValuesByUniverse(universeId: Long): Int = unused()
        override suspend fun deleteFieldValueForCharacters(characterIds: List<Long>, fieldDefId: Long) = unused()
        override suspend fun upsert(value: CharacterFieldValue): Long = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    @Test
    fun emptyFilters_returnsEmpty_daoNotCalled() = runTest {
        val dao = FakeDao()
        val result = FieldFilterHelper.applyFieldFilters(dao, emptyList())
        assertEquals(emptySet<Long>(), result)
        assertEquals(0, dao.exactCalls)
        assertEquals(0, dao.containsCalls)
    }

    @Test
    fun singleFilter_orWithinValues() = runTest {
        val dao = FakeDao(
            exact = mapOf(
                (10L to "red") to listOf(1L, 2L),
                (10L to "blue") to listOf(2L, 3L)
            )
        )
        val filter = FieldFilter(fieldId = 10L, fieldName = "color", values = listOf("red", "blue"), matchMode = "exact")
        assertEquals(setOf(1L, 2L, 3L), FieldFilterHelper.applyFieldFilters(dao, listOf(filter)))
    }

    @Test
    fun multipleFilters_andAcrossFilters() = runTest {
        val dao = FakeDao(
            exact = mapOf(
                (10L to "red") to listOf(1L, 2L, 3L),
                (20L to "tall") to listOf(2L, 3L, 4L)
            )
        )
        val f1 = FieldFilter(10L, "color", listOf("red"), "exact")
        val f2 = FieldFilter(20L, "height", listOf("tall"), "exact")
        assertEquals(setOf(2L, 3L), FieldFilterHelper.applyFieldFilters(dao, listOf(f1, f2)))
    }

    @Test
    fun containsMode_routesToContainsQuery() = runTest {
        val dao = FakeDao(contains = mapOf((10L to "ed") to listOf(1L)))
        val filter = FieldFilter(10L, "color", listOf("ed"), "contains")
        assertEquals(setOf(1L), FieldFilterHelper.applyFieldFilters(dao, listOf(filter)))
        assertEquals(1, dao.containsCalls)
        assertEquals(0, dao.exactCalls)
    }

    @Test
    fun epochMemo_reusesFilterIds_untilEpochBumps() = runTest {
        val dao = FakeDao(exact = mapOf((10L to "red") to listOf(1L, 2L)))
        val memo = EpochMemo<List<FieldFilter>, Set<Long>> { FieldFilterHelper.applyFieldFilters(dao, it) }
        val filters = listOf(FieldFilter(10L, "color", listOf("red"), "exact"))
        assertEquals(setOf(1L, 2L), memo.get(filters, 0))
        assertEquals(setOf(1L, 2L), memo.get(filters, 0)) // 캐시 HIT
        assertEquals(1, dao.exactCalls)                    // 재조회 없음
        memo.get(filters, 1)                               // 에폭 상승(테이블 변경)
        assertEquals(2, dao.exactCalls)                    // 재조회
    }
}
