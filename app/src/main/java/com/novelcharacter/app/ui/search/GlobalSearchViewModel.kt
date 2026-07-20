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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

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
    private val _fieldFilters = MutableLiveData<List<FieldFilter>>(emptyList())

    val presets: LiveData<List<SearchPreset>> = searchPresetRepository.allPresets
    val sortMode: LiveData<String> = _sortMode
    val fieldFilters: LiveData<List<FieldFilter>> = _fieldFilters

    private val _presetAppliedEvent = MutableLiveData<Event<String>?>()
    val presetAppliedEvent: LiveData<Event<String>?> = _presetAppliedEvent

    private val db = app.database

    // 필드 필터 캐시 무효화 — character_field_values / field_definitions 변경을 관측(오프메인).
    // field_definitions 삭제는 FK 캐스케이드로 값을 지우지만 recursive_triggers=OFF라 자식 트리거가 안 울린다 →
    // 함께 관측. 에폭 bump로 fieldFilterMemo를 무효화하고, _fieldValueInvalidation로 결과를 재발화한다(편집 즉시 반영).
    private val fieldValueEpoch = AtomicInteger(0)
    private val _fieldValueInvalidation = MutableLiveData<Unit>()
    private val fieldValueObserver =
        object : InvalidationTracker.Observer("character_field_values", "field_definitions") {
            override fun onInvalidated(tables: Set<String>) {
                fieldValueEpoch.incrementAndGet()
                _fieldValueInvalidation.postValue(Unit)  // 오프메인 → postValue
            }
        }
    // (필터, 에폭) 캐시 — 검색어/정렬만 바뀌면 필드값 재조회 없음(캐릭터 탭과 동일 결함 해소).
    private val fieldFilterMemo = EpochMemo<List<FieldFilter>, Set<Long>> { filters ->
        FieldFilterHelper.applyFieldFilters(db.characterFieldValueDao(), filters)
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

    // ── base 조회: 검색어(query)에만 의존. 정렬/필터 변경은 여기서 재조회를 유발하지 않는다. ──
    private data class RawResults(
        val chars: List<Character>,
        val events: List<TimelineEvent>,
        val novels: List<Novel>
    )

    // query가 바뀔 때만 Room LIKE 검색을 재발급(캐릭터·사건·작품). switchMap이 이전 내부 소스를 자동 비활성화하므로
    // 수동 소스 정리가 필요 없다. blank query면 캐릭터는 전체(필터 대상), 사건/작품은 빈 목록.
    private val rawResults: LiveData<RawResults> = _searchQuery.switchMap { q ->
        val query = q ?: ""
        val mediator = MediatorLiveData<RawResults>()
        val charSrc = if (query.isNotBlank()) characterRepository.searchCharacters(query) else characterRepository.allCharacters
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
        // 같은 필드에 대한 기존 필터 제거 후 추가
        current.removeAll { it.fieldId == filter.fieldId }
        current.add(filter)
        _fieldFilters.value = current
    }

    fun removeFieldFilter(fieldId: Long) {
        val current = _fieldFilters.value?.toMutableList() ?: mutableListOf()
        current.removeAll { it.fieldId == fieldId }
        _fieldFilters.value = current
    }

    fun clearFieldFilters() {
        _fieldFilters.value = emptyList()
    }

    fun getFiltersJson(): String =
        FieldFilterHelper.filtersToJson(_fieldFilters.value ?: emptyList())

    fun applyFiltersFromJson(json: String) {
        _fieldFilters.value = FieldFilterHelper.filtersFromJson(json)
    }

    /** 특정 필드의 유니크 값 목록 조회 (필터 UI용) */
    suspend fun getFieldValues(fieldDefId: Long): List<String> {
        return db.characterFieldValueDao().getValuesByFieldDef(fieldDefId)
            .filter { it.value.isNotBlank() }
            .map { it.value }
            .distinct()
            .sorted()
    }

    /** 현재 세계관의 필드 목록 조회 (필터 UI용, CALCULATED 제외 — DB에 값이 없어 필터링 불가) */
    suspend fun getFieldDefinitions(universeId: Long): List<com.novelcharacter.app.data.model.FieldDefinition> {
        return db.fieldDefinitionDao().getFieldsByUniverseList(universeId)
            .filter { it.type != "CALCULATED" }
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

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            try {
                val preset = SearchPreset(
                    name = name,
                    query = _searchQuery.value ?: "",
                    filtersJson = getFiltersJson(),
                    sortMode = _sortMode.value ?: SearchPreset.SORT_RELEVANCE
                )
                searchPresetRepository.insertPreset(preset)
            } catch (_: IllegalStateException) {
                // Max limit reached - handled in UI
            }
        }
    }

    fun deletePreset(id: Long) {
        viewModelScope.launch {
            searchPresetRepository.deletePreset(id)
        }
    }

    fun updatePreset(preset: SearchPreset) {
        viewModelScope.launch {
            searchPresetRepository.updatePreset(preset)
        }
    }

    suspend fun getPresetCount(): Int = searchPresetRepository.getPresetCount()
}
