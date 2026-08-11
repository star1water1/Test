package com.novelcharacter.app.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.novelcharacter.app.data.dao.CharacterListPresetDao
import com.novelcharacter.app.data.dao.SearchPresetDao
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.repository.CharacterListPresetRepository
import com.novelcharacter.app.data.repository.SearchPresetRepository
import com.novelcharacter.app.util.PresetLimit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 프리셋 개수 한도가 **권고**임을 두 저장소에서 함께 잰다 (B-75 — 확정 19번 ㄱ1).
 *
 * 종전에는 같은 수 20이 경로마다 법과 권고로 갈려 있었다: 인앱 저장은 21번째에서 예외를 던져
 * 막았고, 엑셀 가져오기는 초과를 받아들이면서 같은 값을 *"인앱 권장 한도"*라 불렀다.
 * **그래서 21개를 원하는 사용자에게 인앱 확장 경로가 아예 없었다** — 파일을 고쳐 넣으면 되는데
 * 앱에서는 못 한다는 것이 특히 어긋난 자리였다.
 *
 * 이 시험이 지키는 것은 *숫자*가 아니라 **막지 않는다는 사실**이다. 되돌려 `throw`를 다시 넣으면
 * [insert_atRecommendedMax_stillSaves]가 빨간불이다.
 */
class PresetLimitTest {

    // ── 가짜 저장소 — 개수를 세고 넣기만 한다(Room 비의존) ──

    private class FakeSearchDao : SearchPresetDao {
        val rows = mutableListOf<SearchPreset>()
        override suspend fun getPresetCount(): Int = rows.size
        override suspend fun insert(preset: SearchPreset): Long {
            rows.add(preset)
            return rows.size.toLong()
        }

        override fun getAllPresets(): LiveData<List<SearchPreset>> = MutableLiveData()
        override suspend fun getAllPresetsList(): List<SearchPreset> = rows
        override suspend fun getPresetById(id: Long): SearchPreset? = unused()
        override suspend fun update(preset: SearchPreset) = unused()
        override suspend fun deleteById(id: Long) = unused()
        override suspend fun deleteAllUserPresets() = unused()
        override suspend fun deleteAll() = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private class FakeListDao : CharacterListPresetDao {
        val rows = mutableListOf<CharacterListPreset>()
        override suspend fun getPresetCount(): Int = rows.size
        override suspend fun insert(preset: CharacterListPreset): Long {
            rows.add(preset)
            return rows.size.toLong()
        }

        override fun getAllPresets(): LiveData<List<CharacterListPreset>> = MutableLiveData()
        override suspend fun getAllPresetsList(): List<CharacterListPreset> = rows
        override suspend fun getPresetById(id: Long): CharacterListPreset? = unused()
        override suspend fun update(preset: CharacterListPreset) = unused()
        override suspend fun deleteById(id: Long) = unused()
        override suspend fun deleteAll() = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in test")
    }

    private fun fillSearch(dao: FakeSearchDao, n: Int) {
        repeat(n) { dao.rows.add(SearchPreset(id = it + 1L, name = "p$it")) }
    }

    private fun fillList(dao: FakeListDao, n: Int) {
        repeat(n) { dao.rows.add(CharacterListPreset(id = it + 1L, name = "p$it")) }
    }

    // ── 막지 않는다 ──

    @Test
    fun insert_atRecommendedMax_stillSaves() = runTest {
        // 권고 한도만큼 이미 차 있는 상태에서 하나 더 — **저장된다.**
        // 종전에는 여기가 IllegalStateException이었고, 그것이 이 행이 열려 있던 이유다.
        val searchDao = FakeSearchDao().also { fillSearch(it, PresetLimit.RECOMMENDED_MAX) }
        SearchPresetRepository(searchDao).insertPreset(SearchPreset(name = "스물한 번째"))
        assertEquals(PresetLimit.RECOMMENDED_MAX + 1, searchDao.rows.size)

        val listDao = FakeListDao().also { fillList(it, PresetLimit.RECOMMENDED_MAX) }
        CharacterListPresetRepository(listDao).insertPreset(CharacterListPreset(name = "스물한 번째"))
        assertEquals(PresetLimit.RECOMMENDED_MAX + 1, listDao.rows.size)
    }

    @Test
    fun exceedsRecommended_flipsOnlyAfterTheLimitIsPassed() = runTest {
        // 고지는 **넘은 뒤에만** 뜬다 — 딱 한도면 아직 아니다(정상 사용에 잔소리를 붙이지 않는다).
        val searchDao = FakeSearchDao().also { fillSearch(it, PresetLimit.RECOMMENDED_MAX) }
        val searchRepo = SearchPresetRepository(searchDao)
        assertTrue(!searchRepo.exceedsRecommended())
        searchRepo.insertPreset(SearchPreset(name = "하나 더"))
        assertTrue(searchRepo.exceedsRecommended())

        val listDao = FakeListDao().also { fillList(it, PresetLimit.RECOMMENDED_MAX) }
        val listRepo = CharacterListPresetRepository(listDao)
        assertTrue(!listRepo.exceedsRecommended())
        listRepo.insertPreset(CharacterListPreset(name = "하나 더"))
        assertTrue(listRepo.exceedsRecommended())
    }

    @Test
    fun defaultPresets_areNotSpecialCasedAnymore() = runTest {
        // 종전 차단은 `!preset.isDefault`라는 예외를 달고 있었다 — 막지 않으니 그 예외도 없다.
        // 기본 프리셋이 한도를 넘겨 들어오는 것도, 사용자 프리셋과 **같은 규칙**으로 다뤄진다.
        val dao = FakeSearchDao().also { fillSearch(it, PresetLimit.RECOMMENDED_MAX + 5) }
        val repo = SearchPresetRepository(dao)
        repo.insertPreset(SearchPreset(name = "기본", isDefault = true))
        repo.insertPreset(SearchPreset(name = "사용자", isDefault = false))
        assertEquals(PresetLimit.RECOMMENDED_MAX + 7, dao.rows.size)
    }

    // ── 두 경로가 같은 수를 본다 ──

    @Test
    fun bothPaths_readTheSameNumber() {
        // 인앱과 엑셀이 **같은 상수**를 봐야 한 쪽만 바뀌는 드리프트가 안 생긴다.
        // 종전에는 `CharacterListPreset.MAX_PRESETS`와 `SearchPreset.MAX_PRESETS`가 두 벌이었다.
        assertEquals(20, PresetLimit.RECOMMENDED_MAX)
        assertTrue(!PresetLimit.exceeded(PresetLimit.RECOMMENDED_MAX))
        assertTrue(PresetLimit.exceeded(PresetLimit.RECOMMENDED_MAX + 1))
        // 저장 *전에* 묻는 자리와 *뒤에* 묻는 자리가 같은 경계를 본다.
        assertTrue(!PresetLimit.wouldExceed(PresetLimit.RECOMMENDED_MAX - 1))
        assertTrue(PresetLimit.wouldExceed(PresetLimit.RECOMMENDED_MAX))
    }
}
