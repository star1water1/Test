package com.novelcharacter.app.data

import com.novelcharacter.app.data.dao.EventFieldValueDao
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.repository.EventFieldValueMerge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DAO 기본 구현(replaceForFields)과 순수 모델
 * [EventFieldValueMerge.resultingValues]의 **일치**를 고정한다(S-6).
 * - REPLACE 삽입 + (eventId, fieldDefinitionId) 유니크를 Fake가 실제 스키마대로 흉내 낸다
 * - 커버 필드 삭제는 SQLite 999-변수 상한 아래로 청크되어야 한다(받쳐주는 확장성)
 */
class EventFieldValueDaoReplaceTest {

    /** 실제 스키마의 유니크 인덱스 + REPLACE 전략을 모사하는 인메모리 Fake */
    private class FakeDao : EventFieldValueDao {
        val rows = mutableListOf<EventFieldValue>()
        val deleteByFieldsCalls = mutableListOf<List<Long>>()
        private var nextId = 1L

        override suspend fun getValuesByEventList(eventId: Long) = rows.filter { it.eventId == eventId }
        override suspend fun getAllValuesList() = rows.toList()
        override suspend fun getValuesByEvents(eventIds: List<Long>) = rows.filter { it.eventId in eventIds }
        override suspend fun getValuesByFieldDef(fieldDefId: Long) = rows.filter { it.fieldDefinitionId == fieldDefId }
        override suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>) = rows.filter { it.fieldDefinitionId in fieldDefIds }
        override suspend fun getOrphanValuesForUniverseFields(fieldDefIds: List<Long>, universeId: Long) =
            throw UnsupportedOperationException("이 테스트 범위 아님")
        override suspend fun getValue(eventId: Long, fieldDefId: Long) =
            rows.firstOrNull { it.eventId == eventId && it.fieldDefinitionId == fieldDefId }
        // 세계관 경유 집계는 field_definitions 조인이라 이 Fake의 모사 범위 밖이다 —
        // 쓰지 않는 자리에서 0을 돌려주면 '세어 봤더니 없더라'와 구별되지 않으므로 던진다.
        override suspend fun countValuesByUniverse(universeId: Long) =
            throw UnsupportedOperationException("이 테스트 범위 아님")

        override suspend fun insertAll(values: List<EventFieldValue>) {
            for (v in values) {
                // OnConflictStrategy.REPLACE — 유니크 (eventId, fieldDefinitionId) 충돌 행을 덮는다
                rows.removeAll { it.eventId == v.eventId && it.fieldDefinitionId == v.fieldDefinitionId }
                rows.add(if (v.id == 0L) v.copy(id = nextId++) else v)
            }
        }

        override suspend fun update(value: EventFieldValue) {
            rows.replaceAll { if (it.id == value.id) value else it }
        }

        override suspend fun delete(value: EventFieldValue) {
            rows.removeAll { it.id == value.id }
        }

        override suspend fun deleteByEventAndFields(eventId: Long, fieldIds: List<Long>) {
            deleteByFieldsCalls.add(fieldIds)
            rows.removeAll { it.eventId == eventId && it.fieldDefinitionId in fieldIds }
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private fun v(fieldId: Long, value: String, id: Long = 0L, eventId: Long = 7L) =
        EventFieldValue(id = id, eventId = eventId, fieldDefinitionId = fieldId, value = value)

    /** (필드 정의, 값) 집합으로 비교 — 행 id·순서는 계약이 아니다 */
    private fun keyed(values: List<EventFieldValue>) =
        values.map { it.fieldDefinitionId to it.value }.toSet()

    @Test
    fun `replaceForFields의 결과는 순수 모델 resultingValues와 일치한다`() = runBlocking {
        val dao = FakeDao()
        val existing = listOf(v(1, "보존"), v(2, "대체될 값"), v(3, "삭제될 값"))
        dao.insertAll(existing)
        val form = listOf(v(2, "대체"))
        val cover = setOf(2L, 3L)

        dao.replaceForFields(7L, cover.toList(), form)

        val model = EventFieldValueMerge.resultingValues(form, cover, existing)
        assertEquals(keyed(model), keyed(dao.rows))
        assertEquals(setOf(1L to "보존", 2L to "대체"), keyed(dao.rows))
    }

    @Test
    fun `커버가 비어도 제출값은 REPLACE로 기존 행을 대체한다`() = runBlocking {
        // 순수 모델의 '커버 밖 제출값 대체' 계약과 DAO 동작의 일치 — 종전 early return은
        // 값을 조용히 버려 모델과 어긋났다
        val dao = FakeDao()
        dao.insertAll(listOf(v(1, "옛값")))
        dao.replaceForFields(7L, emptyList(), listOf(v(1, "새값")))
        assertEquals(setOf(1L to "새값"), keyed(dao.rows))
        assertTrue(dao.deleteByFieldsCalls.isEmpty())  // 삭제 쿼리는 아예 나가지 않는다
    }

    @Test
    fun `커버 필드 삭제는 900개 단위로 청크된다`() = runBlocking {
        val dao = FakeDao()
        val cover = (1L..2000L).toList()
        dao.insertAll(listOf(v(1500, "지워질 값")))
        dao.replaceForFields(7L, cover, emptyList())

        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(900, 900, 200), dao.deleteByFieldsCalls.map { it.size })
        assertTrue(dao.deleteByFieldsCalls.all { it.size <= EventFieldValueDao.SQLITE_VAR_CHUNK })
        assertEquals(cover, dao.deleteByFieldsCalls.flatten())  // 누락·중복 없이 전부 지운다
    }

    @Test
    fun `saveWithinCover는 반영 직전 스냅샷으로 보존 개수를 센다`() = runBlocking {
        val dao = FakeDao()
        dao.insertAll(listOf(v(1, "커버밖 보존"), v(2, "삭제될 값")))
        val submission = EventFieldValueMerge.Submission(
            values = listOf(v(3, "신규", eventId = 0L)),  // eventId는 saveWithinCover가 채운다
            coveredFieldDefinitionIds = setOf(2L, 3L)
        )
        val preserved = EventFieldValueMerge.saveWithinCover(dao, 7L, submission)
        assertEquals(1, preserved)
        assertEquals(setOf(1L to "커버밖 보존", 3L to "신규"), keyed(dao.rows))
        assertTrue(dao.rows.all { it.eventId == 7L })
    }

    @Test
    fun `다른 사건의 값은 커버 교체의 영향을 받지 않는다`() = runBlocking {
        val dao = FakeDao()
        dao.insertAll(listOf(v(1, "a"), v(9, "다른 사건", eventId = 8L)))
        dao.replaceForFields(7L, listOf(1L, 9L), emptyList())
        assertEquals(setOf(9L to "다른 사건"), keyed(dao.rows))
    }
}
