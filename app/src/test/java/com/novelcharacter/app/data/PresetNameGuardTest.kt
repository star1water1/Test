package com.novelcharacter.app.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.data.dao.CharacterListPresetDao
import com.novelcharacter.app.data.dao.SearchPresetDao
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.repository.CharacterListPresetRepository
import com.novelcharacter.app.data.repository.SearchPresetRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 두 프리셋 표의 **이름 유니크 색인 위에서 무엇이 일어나는가**를 고정한다 (B-191 · R-60).
 *
 * ## 무엇을 재는가
 * 종전 `@Insert`는 `OnConflictStrategy.REPLACE`였다 — `name`이 유니크 색인이라 같은 이름을
 * 적은 순간 **옛 행이 지워지고 새 행이 들어갔다.** 예외도 경고도 없고 id까지 바뀌어, 그
 * 프리셋을 가리키던 참조가 함께 끊겼다. 프리셋은 휴지통을 타지 않으므로 되돌릴 길도 없다.
 *
 * ## 왜 Fake로 재는가
 * 겹침을 실제로 판정하는 것은 SQLite의 색인이고, 그 계약은 화면(Fragment)과 Room에 걸쳐 있어
 * 이 저장소의 순수 시험이 원리적으로 닿지 않는다. **닿는 것은 두 가지다** — ⓐ 색인이 막았을 때
 * 저장소가 그것을 *삼키지 않고* 올려 보내는가 ⓑ 덮어쓰기가 **id를 지키는가**(확정 15장 1번 ⓐ).
 * Fake는 그 색인을 모사하되 **ABORT로** 모사한다 — DAO의 선언과 갈리면 이 시험이 거짓말을 한다.
 * 그래서 선언 자체는 `tools/check_name_unique_replace.sh`가 따로 지킨다(기계가 보는 축).
 */
class PresetNameGuardTest {

    // ── 목록 프리셋 ──────────────────────────────────────────────

    private class FakeListDao : CharacterListPresetDao {
        val rows = mutableListOf<CharacterListPreset>()
        private var nextId = 1L

        override fun getAllPresets(): LiveData<List<CharacterListPreset>> = MutableLiveData(rows.toList())
        override suspend fun getAllPresetsList(): List<CharacterListPreset> = rows.toList()
        override suspend fun getPresetById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getPresetCount(): Int = rows.size
        override suspend fun getPresetByName(name: String) = rows.firstOrNull { it.name == name }
        override suspend fun getAllNames(): List<String> = rows.map { it.name }

        /** 유니크 색인 + **ABORT** — 겹치면 지우지 않고 던진다. */
        override suspend fun insert(preset: CharacterListPreset): Long {
            if (rows.any { it.name == preset.name }) error("UNIQUE constraint failed: character_list_presets.name")
            val row = preset.copy(id = nextId++)
            rows.add(row)
            return row.id
        }

        override suspend fun update(preset: CharacterListPreset) {
            val i = rows.indexOfFirst { it.id == preset.id }
            if (i >= 0) rows[i] = preset
        }

        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteAll() { rows.clear() }
    }

    @Test
    fun `목록 프리셋에 같은 이름을 넣으면 옛 프리셋이 살아 있고 실패가 올라온다`() = runBlocking {
        val dao = FakeListDao()
        val repo = CharacterListPresetRepository(dao)
        val firstId = repo.insertPreset(CharacterListPreset(name = "주요 인물", tagsJson = """["주역"]"""))

        try {
            repo.insertPreset(CharacterListPreset(name = "주요 인물", tagsJson = """["단역"]"""))
            fail("겹친 이름의 저장이 조용히 통과했다 — REPLACE 시절의 그 유실이다")
        } catch (_: IllegalStateException) {
            // 기대한 자리 — 호출부가 이것을 받아 사용자에게 말한다.
        }

        // 옛 프리셋이 **그대로 있다**: 내용도 id도.
        assertEquals(1, repo.getPresetCount())
        val kept = repo.getPresetByName("주요 인물")
        assertNotNull(kept)
        assertEquals(firstId, kept!!.id)
        assertEquals("""["주역"]""", kept.tagsJson)
    }

    @Test
    fun `목록 프리셋의 덮어쓰기는 id를 지킨다`() = runBlocking {
        val dao = FakeListDao()
        val repo = CharacterListPresetRepository(dao)
        val id = repo.insertPreset(CharacterListPreset(name = "주요 인물", tagsJson = """["주역"]"""))

        // 화면이 '덮어쓰기'를 고른 자리 — 지우고-다시-넣기가 아니라 update다.
        val existing = repo.getPresetByName("주요 인물")!!
        repo.updatePreset(existing.copy(tagsJson = """["단역"]"""))

        assertEquals(1, repo.getPresetCount())
        val after = repo.getPresetByName("주요 인물")!!
        assertEquals("덮어써도 id는 그대로여야 한다(참조가 끊기지 않게)", id, after.id)
        assertEquals("""["단역"]""", after.tagsJson)
    }

    @Test
    fun `목록 프리셋의 이름 조회는 정확 일치다`() = runBlocking {
        val dao = FakeListDao()
        val repo = CharacterListPresetRepository(dao)
        repo.insertPreset(CharacterListPreset(name = "주요 인물"))

        // DB의 유니크 색인이 보는 잣대와 같아야 한다 — 여기서 더 관대해지면 있지도 않은
        // 충돌을 알리고, 덮어쓰기를 고른 사용자가 남의 프리셋 이름을 바꾸게 된다.
        assertNull(repo.getPresetByName("주요인물"))
        assertNull(repo.getPresetByName("주요 인물 "))
        assertNotNull(repo.getPresetByName("주요 인물"))
    }

    // ── 검색 프리셋 ──────────────────────────────────────────────

    private class FakeSearchDao : SearchPresetDao {
        val rows = mutableListOf<SearchPreset>()
        private var nextId = 1L

        override fun getAllPresets(): LiveData<List<SearchPreset>> = MutableLiveData(rows.toList())
        override suspend fun getAllPresetsList(): List<SearchPreset> = rows.toList()
        override suspend fun getPresetById(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun getPresetCount(): Int = rows.size
        override suspend fun getPresetByName(name: String) = rows.firstOrNull { it.name == name }
        override suspend fun getAllNames(): List<String> = rows.map { it.name }

        override suspend fun insert(preset: SearchPreset): Long {
            if (rows.any { it.name == preset.name }) error("UNIQUE constraint failed: search_presets.name")
            val row = preset.copy(id = nextId++)
            rows.add(row)
            return row.id
        }

        override suspend fun update(preset: SearchPreset) {
            val i = rows.indexOfFirst { it.id == preset.id }
            if (i >= 0) rows[i] = preset
        }

        override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override suspend fun deleteAllUserPresets() { rows.removeAll { !it.isDefault } }
        override suspend fun deleteAll() { rows.clear() }
    }

    @Test
    fun `검색 프리셋에 같은 이름을 넣으면 옛 프리셋이 살아 있고 실패가 올라온다`() = runBlocking {
        val dao = FakeSearchDao()
        val repo = SearchPresetRepository(dao)
        val firstId = repo.insertPreset(SearchPreset(name = "북부 전투", query = "전투"))

        try {
            repo.insertPreset(SearchPreset(name = "북부 전투", query = "회담"))
            fail("겹친 이름의 저장이 조용히 통과했다 — REPLACE 시절의 그 유실이다")
        } catch (_: IllegalStateException) {
        }

        assertEquals(1, repo.getPresetCount())
        val kept = repo.getPresetByName("북부 전투")!!
        assertEquals(firstId, kept.id)
        assertEquals("전투", kept.query)
    }

    @Test
    fun `검색 프리셋은 기본 프리셋의 이름도 색인이 지킨다`() = runBlocking {
        val dao = FakeSearchDao()
        val repo = SearchPresetRepository(dao)
        repo.ensureDefaultPresets()
        val defaults = repo.getAllPresetsList()
        assertTrue("기본 프리셋 셋이 서야 이 시험이 뜻을 갖는다", defaults.size == 3)

        // 화면은 이 이름을 [PresetNameConflict.Verdict.TAKEN_BY_DEFAULT]로 가려 덮어쓰기를
        // 제안하지 않는다 — 그 판정이 없더라도 색인이 마지막에 막는다는 것을 여기서 잠근다.
        try {
            repo.insertPreset(SearchPreset(name = SearchPresetRepository.DEFAULT_RECENT, query = "x"))
            fail("기본 프리셋 이름이 조용히 덮였다")
        } catch (_: IllegalStateException) {
        }
        assertEquals(3, repo.getPresetCount())
    }

    @Test
    fun `검색 프리셋의 덮어쓰기는 id와 기본 여부를 지킨다`() = runBlocking {
        val dao = FakeSearchDao()
        val repo = SearchPresetRepository(dao)
        val id = repo.insertPreset(SearchPreset(name = "북부 전투", query = "전투"))

        val existing = repo.getPresetById(id)!!
        repo.updatePreset(existing.copy(query = "회담", sortMode = SearchPreset.SORT_NAME))

        assertEquals(1, repo.getPresetCount())
        val after = repo.getPresetById(id)!!
        assertEquals(id, after.id)
        assertEquals("회담", after.query)
        assertEquals(SearchPreset.SORT_NAME, after.sortMode)
        assertEquals(false, after.isDefault)
    }
}
