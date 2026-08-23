package com.novelcharacter.app.ui.search

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.TimelineEvent
import androidx.room.InvalidationTracker
import com.novelcharacter.app.util.EpochMemo
import com.novelcharacter.app.util.Event
import com.novelcharacter.app.util.FieldFilterHelper
import com.novelcharacter.app.util.PresetNameConflict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import com.novelcharacter.app.data.model.FieldType

sealed class SearchResultItem {
    data class SectionHeader(val title: String) : SearchResultItem()
    data class CharacterResult(val character: Character) : SearchResultItem()
    data class EventResult(val event: TimelineEvent) : SearchResultItem()
    data class NovelResult(val novel: Novel) : SearchResultItem()
}

class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application
    private val app = application as NovelCharacterApp
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository
    private val timelineRepository = app.timelineRepository
    private val searchPresetRepository = app.searchPresetRepository
    private val prefs = application.getSharedPreferences("search_ui_state", android.content.Context.MODE_PRIVATE)

    private val _searchQuery = MutableLiveData("")
    private val _sortMode = MutableLiveData(prefs.getString("sort_mode", SearchPreset.SORT_RELEVANCE) ?: SearchPreset.SORT_RELEVANCE)
    // 필드 필터도 영속 — 정렬만 저장되고 필터는 콜드 스타트에 조용히 사라지던 비대칭 해소
    // (캐릭터 목록과 동일하게 field_filters_json으로 저장). 검색어(_searchQuery)만 세션 한정.
    private val _fieldFilters = MutableLiveData(
        FieldFilterHelper.filtersFromJson(prefs.getString("field_filters_json", "{}") ?: "{}")
    )

    val presets: LiveData<List<SearchPreset>> = searchPresetRepository.allPresets
    val sortMode: LiveData<String> = _sortMode
    val fieldFilters: LiveData<List<FieldFilter>> = _fieldFilters

    private val _presetAppliedEvent = MutableLiveData<Event<String>?>()
    val presetAppliedEvent: LiveData<Event<String>?> = _presetAppliedEvent

    /** 프리셋 저장 완료 — 담긴 값은 *권고 개수를 넘었는가*다(B-75). 저장 자체는 언제나 성공한다. */
    private val _presetSavedEvent = MutableLiveData<Event<Boolean>?>()
    val presetSavedEvent: LiveData<Event<Boolean>?> = _presetSavedEvent

    /** 저장·편집이 실패했다 — 화면이 그 사실을 말한다(B-191: 종전에는 예외가 앱을 죽였다). */
    private val _presetSaveFailedEvent = MutableLiveData<Event<String>?>()
    val presetSaveFailedEvent: LiveData<Event<String>?> = _presetSaveFailedEvent

    /** 프리셋 삭제 완료 — 고지는 **실제로 지운 뒤에** 나간다(종전에는 걸어 놓고 바로 띄웠다). */
    private val _presetDeletedEvent = MutableLiveData<Event<String>?>()
    val presetDeletedEvent: LiveData<Event<String>?> = _presetDeletedEvent

    private val db = app.database

    // 필드 필터 캐시 무효화 — character_field_values / field_definitions 변경을 관측(오프메인).
    // field_definitions 삭제는 FK 캐스케이드로 값을 지우지만 recursive_triggers=OFF라 자식 트리거가 안 울린다 →
    // 함께 관측. 에폭 bump로 fieldFilterMemo를 무효화하고, _fieldValueInvalidation로 결과를 재발화한다(편집 즉시 반영).
    private val fieldValueEpoch = AtomicInteger(0)
    private val _fieldValueInvalidation = MutableLiveData<Unit>()
    private val fieldValueObserver =
        object : InvalidationTracker.Observer("character_field_values", "field_definitions", "field_value_entries") {
            override fun onInvalidated(tables: Set<String>) {
                fieldValueEpoch.incrementAndGet()
                _fieldValueInvalidation.postValue(Unit)  // 오프메인 → postValue
            }
        }
    // (필터, 에폭) 캐시 — 검색어/정렬만 바뀌면 필드값 재조회 없음(캐릭터 탭과 동일 결함 해소).
    // field_value_entries도 관측: 별칭 추가/병합은 값 테이블을 안 건드려도 exact 매칭 결과를 바꾼다.
    private val fieldFilterMemo = EpochMemo<List<FieldFilter>, Set<Long>> { filters ->
        FieldFilterHelper.applyFieldFilters(
            db.characterFieldValueDao(), db.fieldDefinitionDao(), db.fieldValueEntryDao(), filters)
    }

    init {
        db.invalidationTracker.addObserver(fieldValueObserver)
        viewModelScope.launch {
            searchPresetRepository.ensureDefaultPresets()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // InvalidationTracker는 옵저버를 강한 참조로 보관하고 AppDatabase는 프로세스 싱글턴 → 반드시 해제(누수 방지).
        db.invalidationTracker.removeObserver(fieldValueObserver)
    }

    // ── base 조회: 검색어(query)에 의존. blank query에서는 "필터 활성 여부"에만 추가로 의존한다. ──
    private data class RawResults(
        val chars: List<Character>,
        val events: List<TimelineEvent>,
        val novels: List<Novel>
    )

    // blank query일 때만 필터 활성이 base에 영향을 준다(전체 캐릭터를 불러와 걸러야 하므로).
    // query가 있으면 loadAllForFilter는 항상 false → active query에서 필터 토글은 키를 바꾸지 않아 base 재조회가 없다.
    private data class BaseKey(val query: String, val loadAllForFilter: Boolean)

    private val baseKey = MediatorLiveData<BaseKey>().apply {
        val update = {
            val query = _searchQuery.value ?: ""
            val next = BaseKey(query, query.isBlank() && !_fieldFilters.value.isNullOrEmpty())
            if (value != next) value = next  // 중복 방출 억제(같은 키면 switchMap 재실행 없음)
        }
        addSource(_searchQuery) { update() }
        addSource(_fieldFilters) { update() }
    }

    // query(또는 blank일 때 필터 활성 전이)가 바뀔 때만 Room 검색을 재발급. switchMap이 이전 내부 소스를 자동
    // 비활성화하므로 수동 정리 불필요. blank+무필터면 빈 소스(전체 캐릭터 로드 안 함), blank+필터면 전체(필터 대상).
    private val rawResults: LiveData<RawResults> = baseKey.switchMap { key ->
        val query = key.query
        val mediator = MediatorLiveData<RawResults>()
        val charSrc: LiveData<List<Character>> = when {
            query.isNotBlank() -> characterRepository.searchCharacters(query)
            key.loadAllForFilter -> characterRepository.allCharacters
            else -> MutableLiveData<List<Character>>(emptyList())
        }
        val eventSrc = if (query.isNotBlank()) timelineRepository.searchEvents(query) else MutableLiveData(emptyList())
        val novelSrc = if (query.isNotBlank()) novelRepository.searchNovels(query) else MutableLiveData(emptyList())
        var chars: List<Character> = emptyList()
        var events: List<TimelineEvent> = emptyList()
        var novels: List<Novel> = emptyList()
        fun emit() { mediator.value = RawResults(chars, events, novels) }
        mediator.addSource(charSrc) { chars = it; emit() }
        mediator.addSource(eventSrc) { events = it; emit() }
        mediator.addSource(novelSrc) { novels = it; emit() }
        mediator
    }

    // 정렬/필터/필드값 변경은 base 재조회 없이 인메모리 재랭킹만 유발한다.
    private val resultsTrigger = MediatorLiveData<Unit>().apply {
        addSource(rawResults) { value = Unit }
        addSource(_sortMode) { value = Unit }
        addSource(_fieldFilters) { value = Unit }
        addSource(_fieldValueInvalidation) { value = Unit }
    }

    private var combineJob: Job? = null

    private val _searchResults = MediatorLiveData<List<SearchResultItem>>().apply {
        addSource(resultsTrigger) { rebuild() }
    }
    val searchResults: LiveData<List<SearchResultItem>> = _searchResults

    /**
     * 현재 base(rawResults) + 정렬 + 필터로 결과를 재구성. 필드필터 id셋은 (필터, 에폭) 캐시로 재조회 최소화.
     * 필터 계산을 먼저 await한 뒤 build하므로 미완성 상태가 노출되지 않는다(기존 filterReady 게이팅 대체).
     */
    private fun rebuild() {
        combineJob?.cancel()
        combineJob = viewModelScope.launch {
            val query = _searchQuery.value ?: ""
            val sort = _sortMode.value ?: SearchPreset.SORT_RELEVANCE
            val filters = _fieldFilters.value ?: emptyList()
            if (query.isBlank() && filters.isEmpty()) { _searchResults.value = emptyList(); return@launch }
            val raw = rawResults.value ?: RawResults(emptyList(), emptyList(), emptyList())
            val filteredCharIds: Set<Long>? =
                if (filters.isEmpty()) null else fieldFilterMemo.get(filters, fieldValueEpoch.get())
            val items = withContext(Dispatchers.Default) { buildItems(raw, query, sort, filteredCharIds) }
            _searchResults.value = items
        }
    }

    /** 순수 조합 — 필드필터 적용 + 정렬(랭킹) + 섹션 헤더. tag 정렬은 캐릭터만 표시. */
    private fun buildItems(
        raw: RawResults, query: String, sort: String, filteredCharIds: Set<Long>?
    ): List<SearchResultItem> {
        val q = query.lowercase()
        val items = mutableListOf<SearchResultItem>()

        val filteredChars = if (filteredCharIds != null) raw.chars.filter { it.id in filteredCharIds } else raw.chars

        val rankedChars = when (sort) {
            SearchPreset.SORT_NAME -> filteredChars.sortedBy { it.name.lowercase() }
            SearchPreset.SORT_TAG -> filteredChars.sortedBy { it.name.lowercase() }
            SearchPreset.SORT_RECENT -> filteredChars.sortedByDescending { it.updatedAt }
            else -> filteredChars.sortedByDescending { c ->
                val name = c.name.lowercase()
                val alias = c.anotherName.lowercase()
                val first = c.firstName.lowercase()
                val last = c.lastName.lowercase()
                when {
                    name == q -> 100
                    name.startsWith(q) -> 80
                    last == q || first == q -> 75
                    alias == q -> 70
                    alias.startsWith(q) -> 60
                    last.startsWith(q) || first.startsWith(q) -> 55
                    name.contains(q) -> 40
                    alias.contains(q) -> 30
                    first.contains(q) || last.contains(q) -> 25
                    else -> 10
                }
            }
        }

        val rankedNovels = when (sort) {
            SearchPreset.SORT_NAME -> raw.novels.sortedBy { it.title.lowercase() }
            SearchPreset.SORT_RECENT -> raw.novels.sortedByDescending { it.createdAt }
            else -> raw.novels.sortedByDescending { n ->
                val title = n.title.lowercase()
                when {
                    title == q -> 100
                    title.startsWith(q) -> 80
                    title.contains(q) -> 40
                    else -> 10
                }
            }
        }

        val rankedEvents = when (sort) {
            SearchPreset.SORT_NAME -> raw.events.sortedBy { it.description.lowercase() }
            SearchPreset.SORT_RECENT -> raw.events.sortedByDescending { it.createdAt }
            else -> raw.events.sortedByDescending { e ->
                val desc = e.description.lowercase()
                when {
                    desc == q -> 100
                    desc.startsWith(q) -> 80
                    desc.contains(q) -> 40
                    else -> 10
                }
            }
        }

        // tag 정렬 모드는 캐릭터만 표시
        if (rankedChars.isNotEmpty()) {
            items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_characters), rankedChars.size)))
            items.addAll(rankedChars.map { SearchResultItem.CharacterResult(it) })
        }
        if (sort != SearchPreset.SORT_TAG) {
            if (rankedEvents.isNotEmpty()) {
                items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_timeline), rankedEvents.size)))
                items.addAll(rankedEvents.map { SearchResultItem.EventResult(it) })
            }
            if (rankedNovels.isNotEmpty()) {
                items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_novels), rankedNovels.size)))
                items.addAll(rankedNovels.map { SearchResultItem.NovelResult(it) })
            }
        }
        return items
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: String) {
        _sortMode.value = mode
        prefs.edit().putString("sort_mode", mode).apply()
    }

    fun addFieldFilter(filter: FieldFilter) {
        val current = _fieldFilters.value?.toMutableList() ?: mutableListOf()
        // 같은 대상에 대한 기존 필터 제거 후 추가 — 동일성은 키가 정본이다(B-11).
        current.removeAll { FieldFilterHelper.sameTarget(it, filter) }
        current.add(filter)
        _fieldFilters.value = current
        persistFieldFilters(current)
    }

    fun removeFieldFilter(filter: FieldFilter) {
        val current = _fieldFilters.value?.toMutableList() ?: mutableListOf()
        current.removeAll { FieldFilterHelper.sameTarget(it, filter) }
        _fieldFilters.value = current
        persistFieldFilters(current)
    }

    fun clearFieldFilters() {
        _fieldFilters.value = emptyList()
        persistFieldFilters(emptyList())
    }

    fun getFiltersJson(): String =
        FieldFilterHelper.filtersToJson(_fieldFilters.value ?: emptyList())

    fun applyFiltersFromJson(json: String) {
        val filters = FieldFilterHelper.filtersFromJson(json)
        _fieldFilters.value = filters
        persistFieldFilters(filters)
    }

    private fun persistFieldFilters(filters: List<FieldFilter>) {
        prefs.edit().putString("field_filters_json", FieldFilterHelper.filtersToJson(filters)).apply()
    }

    /** 특정 필드의 유니크 값 목록 조회 (필터 UI용).
     *  라이브러리 규칙으로 토큰화·trim·별칭 접기 — "서울"/"서울 "이 별개 칩이 되지 않고,
     *  콤마 다중값은 토큰 단위 칩이 되며, 별칭은 canonical 칩 하나로 접힌다 (검토 A16).
     *  저장값 기준이라 라이브러리에서 정리된 값도 사용 중이면 반드시 칩으로 남는다 (원칙 04). */
    suspend fun getFieldValues(fieldDefId: Long): List<String> {
        val fd = db.fieldDefinitionDao().getFieldById(fieldDefId)
        val rows = db.characterFieldValueDao().getValuesByFieldDef(fieldDefId)
        if (fd == null) {
            return rows.filter { it.value.isNotBlank() }.map { it.value.trim() }.distinct().sorted()
        }
        val resolver = com.novelcharacter.app.util.FieldValueResolver(
            db.fieldValueEntryDao().getByField(fieldDefId))
        return rows.flatMap { com.novelcharacter.app.util.FieldValueTokenizer.tokenize(fd, it.value) }
            .map { resolver.canonical(it) }
            .distinct()
            .sorted()
    }

    /** 현재 세계관의 필드 목록 조회 (필터 UI용, CALCULATED 제외 — DB에 값이 없어 필터링 불가) */
    suspend fun getFieldDefinitions(universeId: Long): List<com.novelcharacter.app.data.model.FieldDefinition> {
        return db.fieldDefinitionDao().getFieldsByUniverseList(universeId)
            .filter { it.fieldType != FieldType.CALCULATED }
    }

    /** 모든 세계관 조회 (필터 UI용) */
    suspend fun getAllUniverses(): List<com.novelcharacter.app.data.model.Universe> {
        return db.universeDao().getAllUniversesList()
    }

    fun applyPreset(preset: SearchPreset) {
        _sortMode.value = preset.sortMode
        if (preset.query.isNotBlank()) {
            _searchQuery.value = preset.query
        }
        applyFiltersFromJson(preset.filtersJson)
        _presetAppliedEvent.value = Event(preset.name)
    }

    /**
     * 저장하고 **권고 개수를 넘었는지**를 [presetSavedEvent]로 알린다 (B-75 — 개수로 막지 않는다).
     *
     * **`viewModelScope`인 것이 중요하다.** 화면 스코프에서 돌리면 저장을 누른 직후 회전·이동으로
     * 뷰가 사라질 때 **코루틴이 함께 취소돼 프리셋이 저장되지 않는다** — 사용자는 누르고 아무
     * 오류도 못 봤으므로 저장된 줄 안다(조용한 유실). 고지만 뷰 수명을 타면 된다.
     *
     * **[query]를 밖에서 받는 것은 *누른 순간의 값*을 저장하기 위해서다**(R-27, B-191) —
     * 겹침 판정이 비동기라, 판정을 기다린 뒤 화면을 다시 읽으면 그사이 바뀐 검색어를 읽는다.
     */
    fun saveCurrentAsPreset(name: String, query: String = _searchQuery.value ?: "") {
        viewModelScope.launch {
            try {
                searchPresetRepository.insertPreset(
                    SearchPreset(
                        name = name,
                        query = query,
                        filtersJson = getFiltersJson(),
                        sortMode = _sortMode.value ?: SearchPreset.SORT_RELEVANCE
                    )
                )
                _presetSavedEvent.value = Event(searchPresetRepository.exceedsRecommended())
            } catch (e: Exception) {
                // 이름이 겹치면 유니크 색인이 막는다(REPLACE가 아니다 — R-60). 말하고 끝낸다.
                android.util.Log.e("GlobalSearchViewModel", "Failed to save preset", e)
                _presetSaveFailedEvent.value = Event(name)
            }
        }
    }

    /**
     * 이 이름을 이미 쓰고 있는 프리셋 — 저장·편집 창이 겹침을 묻는 근거다(B-191).
     * `name`이 유니크 색인이라 색인 한 번의 조회이고, 전량 적재를 하지 않는다.
     */
    suspend fun presetNamed(name: String): SearchPreset? =
        searchPresetRepository.getPresetByName(name)

    /** '다른 이름으로 저장'이 채워 줄 이름 — 이름 목록만 뜬다(필터 JSON 본문을 싣지 않는다). */
    suspend fun suggestPresetName(name: String): String =
        PresetNameConflict.suggestAlternative(name, searchPresetRepository.getAllNames())

    /**
     * 이름이 겹쳐 '덮어쓰기'를 고른 자리 — **id를 지킨 채** 내용만 바꾼다(확정 15장 1번 ⓐ).
     * 지우고-다시-넣으면 그 프리셋을 가리키던 참조가 끊긴다.
     */
    fun overwritePresetById(id: Long, name: String, query: String) {
        viewModelScope.launch {
            try {
                val existing = searchPresetRepository.getPresetById(id)
                if (existing == null) {
                    // 그새 사라졌다면 덮어쓸 것이 없다 — 새로 저장하는 것이 사용자의 뜻에 가깝다.
                    saveCurrentAsPreset(name, query)
                    return@launch
                }
                searchPresetRepository.updatePreset(
                    existing.copy(
                        name = name,
                        query = query,
                        filtersJson = getFiltersJson(),
                        sortMode = _sortMode.value ?: SearchPreset.SORT_RELEVANCE
                    )
                )
                _presetSavedEvent.value = Event(searchPresetRepository.exceedsRecommended())
            } catch (e: Exception) {
                // 아래 [updatePreset]과 **같은 이유로 같은 모양이다** — 이 자리만 맨몸이면
                // 이 판이 없앤 그 결함(예외가 코루틴 밖으로 나가 앱이 죽는 것)이 덮어쓰기
                // 경로에만 그대로 남는다. 짝인 목록 프리셋 쪽은 처음부터 감싸여 있었다.
                android.util.Log.e("GlobalSearchViewModel", "Failed to overwrite preset", e)
                _presetSaveFailedEvent.value = Event(name)
            }
        }
    }

    /**
     * 프리셋 삭제 — **한 일과 고지가 어긋나지 않게** 결과를 낸다.
     *
     * 종전에는 화면이 이 함수를 걸어 놓고 **곧바로** «삭제했습니다»를 띄웠다(한 일과 무관하게
     * 무조건 뜨는 고지). 게다가 이 파일에서 **유일하게 `try`가 없는 쓰기**라, 예외가 나면
     * 코루틴 밖으로 나가 앱이 죽는데 그 직전에 이미 성공을 말한 뒤였다 — B-191이 저장
     * 경로에서 없앤 바로 그 모양이 삭제 경로에만 남아 있었다.
     */
    fun deletePreset(id: Long, name: String) {
        viewModelScope.launch {
            try {
                searchPresetRepository.deletePreset(id)
                _presetDeletedEvent.value = Event(name)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("GlobalSearchViewModel", "Failed to delete preset", e)
                _presetSaveFailedEvent.value = Event(name)
            }
        }
    }

    /**
     * **실패를 삼키지 않는다**(B-191). 종전에는 `try`가 없어, 이름이 겹치면 유니크 색인이
     * 던지는 예외가 코루틴 밖으로 나가 **앱이 죽었다** — 창은 이미 *"저장했습니다"*를
     * 띄운 뒤였다. 겹침은 이제 창이 먼저 가르지만, 뚫고 온 실패도 말은 하고 끝나야 한다.
     */
    fun updatePreset(preset: SearchPreset) {
        viewModelScope.launch {
            try {
                searchPresetRepository.updatePreset(preset)
                _presetSavedEvent.value = Event(searchPresetRepository.exceedsRecommended())
            } catch (e: Exception) {
                android.util.Log.e("GlobalSearchViewModel", "Failed to update preset", e)
                _presetSaveFailedEvent.value = Event(preset.name)
            }
        }
    }

}
