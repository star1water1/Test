package com.novelcharacter.app.data

import com.novelcharacter.app.data.dao.NovelFieldValueDao
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.util.SqlInChunks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NovelFieldValueDao.replaceForFields`의 **커버 집합 계약과 청크 통로**를 고정한다 (B-245).
 *
 * **왜 이 판에서 생겼는가:** 이 함수는 사건판([com.novelcharacter.app.data.dao.EventFieldValueDao])의
 * 쌍둥이인데(그쪽 KDoc이 *"같은 규약을 따른다"*고 선언한다) **사건판만 시험이 있었다.**
 * 값을 각자 적어 두었던 시절에는 그 비대칭이 눈에 띌 일이 없었지만, B-245가 둘을
 * **같은 통로**([SqlInChunks])로 옮기면서 *한쪽만 재는* 상태가 그대로 드러났다 —
 * 통로를 잘못 부르면 사건판은 빨개지고 작품판은 조용하다.
 *
 * 고정하는 것 셋:
 *  - **커버 밖은 손대지 않는다**(R-5). 폼을 '전체 진실'로 다루면 화면에 못 그린 값이 저장 한 번에 전멸한다.
 *  - **삭제는 통로를 지난다**(R-54). 커버 필드가 상한을 넘어도 삭제가 깨지지 않고, 누락·중복이 없다.
 *  - **커버가 비면 삭제 질의가 아예 나가지 않는다.** 제출값은 그래도 REPLACE로 들어간다.
 */
class NovelFieldValueDaoReplaceTest {

    /** 실제 스키마의 유니크 인덱스 + REPLACE 전략을 모사하는 인메모리 Fake */
    private class FakeDao : NovelFieldValueDao {
        val rows = mutableListOf<NovelFieldValue>()
        val deleteByFieldsCalls = mutableListOf<List<Long>>()
        private var nextId = 1L

        override suspend fun getValuesByNovelList(novelId: Long) = rows.filter { it.novelId == novelId }
        override suspend fun getAllValuesList() = rows.toList()
        override suspend fun getValuesByNovels(novelIds: List<Long>) = rows.filter { it.novelId in novelIds }
        override suspend fun getValuesByFieldDef(fieldDefId: Long) = rows.filter { it.fieldDefinitionId == fieldDefId }
        override suspend fun getValuesByFieldDefs(fieldDefIds: List<Long>) =
            rows.filter { it.fieldDefinitionId in fieldDefIds }

        // 아래 둘은 작품 표 조인이라 이 Fake의 모사 범위 밖이다 — 쓰지 않는 자리에서 0/빈 목록을
        // 돌려주면 '세어 봤더니 없더라'와 구별되지 않으므로 던진다(사건판 Fake와 같은 규약).
        override suspend fun getOrphanValuesForUniverseFields(fieldDefIds: List<Long>, universeId: Long) =
            throw UnsupportedOperationException("이 테스트 범위 아님")

        override suspend fun countValuesByUniverse(universeId: Long) =
            throw UnsupportedOperationException("이 테스트 범위 아님")

        override suspend fun getValue(novelId: Long, fieldDefId: Long) =
            rows.firstOrNull { it.novelId == novelId && it.fieldDefinitionId == fieldDefId }

        override suspend fun insertAll(values: List<NovelFieldValue>) {
            for (v in values) {
                // OnConflictStrategy.REPLACE — 유니크 (novelId, fieldDefinitionId) 충돌 행을 덮는다
                rows.removeAll { it.novelId == v.novelId && it.fieldDefinitionId == v.fieldDefinitionId }
                rows.add(if (v.id == 0L) v.copy(id = nextId++) else v)
            }
        }

        override suspend fun update(value: NovelFieldValue) {
            rows.replaceAll { if (it.id == value.id) value else it }
        }

        override suspend fun delete(value: NovelFieldValue) {
            rows.removeAll { it.id == value.id }
        }

        override suspend fun deleteByNovel(novelId: Long) {
            rows.removeAll { it.novelId == novelId }
        }

        override suspend fun deleteByNovelAndFields(novelId: Long, fieldIds: List<Long>) {
            deleteByFieldsCalls.add(fieldIds)
            rows.removeAll { it.novelId == novelId && it.fieldDefinitionId in fieldIds }
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private fun v(fieldId: Long, value: String, id: Long = 0L, novelId: Long = 7L) =
        NovelFieldValue(id = id, novelId = novelId, fieldDefinitionId = fieldId, value = value)

    /** (필드 정의, 값) 집합으로 비교 — 행 id·순서는 계약이 아니다 */
    private fun keyed(values: List<NovelFieldValue>) =
        values.map { it.fieldDefinitionId to it.value }.toSet()

    @Test
    fun `커버 밖 필드값은 저장에 휩쓸리지 않는다`() = runBlocking {
        val dao = FakeDao()
        dao.insertAll(listOf(v(1, "커버밖 보존"), v(2, "대체될 값"), v(3, "삭제될 값")))

        dao.replaceForFields(7L, listOf(2L, 3L), listOf(v(2, "대체")))

        assertEquals(setOf(1L to "커버밖 보존", 2L to "대체"), keyed(dao.rows))
    }

    @Test
    fun `다른 작품의 값은 같은 필드라도 건드리지 않는다`() = runBlocking {
        val dao = FakeDao()
        dao.insertAll(listOf(v(2, "7번 작품 값"), v(2, "8번 작품 값", novelId = 8L)))

        dao.replaceForFields(7L, listOf(2L), emptyList())

        assertEquals(listOf(8L to "8번 작품 값"), dao.rows.map { it.novelId to it.value })
    }

    @Test
    fun `커버가 비면 삭제 질의가 나가지 않고 제출값만 대체한다`() = runBlocking {
        val dao = FakeDao()
        dao.insertAll(listOf(v(1, "옛값")))

        dao.replaceForFields(7L, emptyList(), listOf(v(1, "새값")))

        assertEquals(setOf(1L to "새값"), keyed(dao.rows))
        assertTrue(dao.deleteByFieldsCalls.isEmpty())
    }

    @Test
    fun `커버 필드 삭제는 통로를 지나 상한 아래로 나뉜다`() = runBlocking {
        val dao = FakeDao()
        val cover = (1L..2000L).toList()
        dao.insertAll(listOf(v(1500, "지워질 값")))

        dao.replaceForFields(7L, cover, emptyList())

        assertTrue(dao.rows.isEmpty())
        assertEquals(listOf(900, 900, 200), dao.deleteByFieldsCalls.map { it.size })
        // 값의 단일 소스는 [SqlInChunks.LIMIT]다 — 사본과 견주면 빠짐을 잡을 수 없다(B-245).
        assertTrue(dao.deleteByFieldsCalls.all { it.size <= SqlInChunks.LIMIT })
        assertEquals(cover, dao.deleteByFieldsCalls.flatten())  // 누락·중복 없이 전부 지운다
    }

    @Test
    fun `한 덩이에 들어가면 쪼개지 않는다`() = runBlocking {
        val dao = FakeDao()
        val cover = (1L..SqlInChunks.LIMIT.toLong()).toList()

        dao.replaceForFields(7L, cover, emptyList())

        // **여기서 재는 것은 이 DAO의 몫뿐이다** — 상한 이하면 질의가 한 번이고, 이 함수가
        // 통로 위에 제 나름의 쪼개기를 더하지 않는다는 것. *새 리스트를 만들지 않는다*는
        // 통로 자신의 성질이라 `SqlInChunksTest`가 `assertSame`으로 잠근다 — 여기서 또 재면
        // 같은 성질이 두 자리에 살고, 그것이 이 판이 코드에서 걷어낸 바로 그 모양이다.
        assertEquals(1, dao.deleteByFieldsCalls.size)
        assertEquals(cover, dao.deleteByFieldsCalls.single())
    }
}
