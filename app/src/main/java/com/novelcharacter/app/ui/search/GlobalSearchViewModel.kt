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
import kotlinx.coroutines.launch
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

    // 필드 필터 재조회 캐시 무효화 — character_field_values 변경을 관측(오프메인)해 에폭만 bump.
    // 결과 재발화는 기존과 동일하게 다음 트리거(검색어/정렬/필터 변경) 때 일어난다: 그때 에폭이 올라 있으면
    // 메모가 미스되어 최신값으로 재조회하므로, 편집 후 stale한 id셋을 재사용하지 않는다(정확성 필수 옵저버).
    private val fieldValueEpoch = AtomicInteger(0)
    // field_definitions 삭제는 FK 캐스케이드로 character_field_values를 지우지만 recursive_triggers=OFF라
    // 자식 트리거가 안 울린다 → 필드 정의 변경도 관측해 stale한 필터 id셋 재사용을 막는다.
    private val fieldValueObserver =
        object : InvalidationTracker.Observer("character_field_values", "field_definitions") {
            override fun onInvalidated(tables: Set<String>) { fieldValueEpoch.incrementAndGet() }
        }
    // (필터, character_field_values 에폭) 캐시 — 검색어/정렬만 바뀌면 필드값 재조회 없음(캐릭터 탭과 동일 결함 해소).
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

    private data class SearchTriggerData(val query: String, val sort: String, val filters: List<FieldFilter>)

    private val searchTrigger = MediatorLiveData<SearchTriggerData>().apply {
        addSource(_searchQuery) { value = SearchTriggerData(it ?: "", _sortMode.value ?: SearchPreset.SORT_RELEVANCE, _fieldFilters.value ?: emptyList()) }
        addSource(_sortMode) { value = SearchTriggerData(_searchQuery.value ?: "", it ?: SearchPreset.SORT_RELEVANCE, _fieldFilters.value ?: emptyList()) }
        addSource(_fieldFilters) { value = SearchTriggerData(_searchQuery.value ?: "", _sortMode.value ?: SearchPreset.SORT_RELEVANCE, it ?: emptyList()) }
    }

    private var previousCharSource: LiveData<List<Character>>? = null
    private var previousEventSource: LiveData<List<TimelineEvent>>? = null
    private var previousNovelSource: LiveData<List<Novel>>? = null
    private var previousMediator: MediatorLiveData<List<SearchResultItem>>? = null

    val searchResults: LiveData<List<SearchResultItem>> = searchTrigger.switchMap { triggerData ->
        val query = triggerData.query
        val sort = triggerData.sort
        val filters = triggerData.filters

        // Clean up previous MediatorLiveData sources to prevent memory leaks
        previousMediator?.let { med ->
            previousCharSource?.let { med.removeSource(it) }
            previousEventSource?.let { med.removeSource(it) }
            previousNovelSource?.let { med.removeSource(it) }
        }
        previousCharSource = null
        previousEventSource = null
        previousNovelSource = null
        previousMediator = null

        if (query.isBlank() && filters.isEmpty()) {
            MutableLiveData(emptyList())
        } else {
            val mediator = MediatorLiveData<List<SearchResultItem>>()
            val charResults = if (query.isNotBlank()) characterRepository.searchCharacters(query) else characterRepository.allCharacters
            val eventResults = if (query.isNotBlank()) timelineRepository.searchEvents(query) else MutableLiveData(emptyList())
            val novelResults = if (query.isNotBlank()) novelRepository.searchNovels(query) else MutableLiveData(emptyList())

            previousCharSource = charResults
            previousEventSource = eventResults
            previousNovelSource = novelResults
            previousMediator = mediator

            var chars: List<Character> = emptyList()
            var events: List<TimelineEvent> = emptyList()
            var novels: List<Novel> = emptyList()

            // Pre-compute filtered character IDs if filters are active
            var filteredCharIds: Set<Long>? = null
            var filterReady = filters.isEmpty()

            fun combine() {
                if (!filterReady) return // 필터 계산 완료 전까지 결과 미출력
                val q = query.lowercase()
                val items = mutableListOf<SearchResultItem>()

                // 필드 필터 적용
                val filteredChars = if (filteredCharIds != null) {
                    chars.filter { it.id in filteredCharIds!! }
                } else chars

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
                    SearchPreset.SORT_NAME -> novels.sortedBy { it.title.lowercase() }
                    SearchPreset.SORT_RECENT -> novels.sortedByDescending { it.createdAt }
                    else -> novels.sortedByDescending { n ->
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
                    SearchPreset.SORT_NAME -> events.sortedBy { it.description.lowercase() }
                    SearchPreset.SORT_RECENT -> events.sortedByDescending { it.createdAt }
                    else -> events.sortedByDescending { e ->
                        val desc = e.description.lowercase()
                        when {
                            desc == q -> 100
                            desc.startsWith(q) -> 80
                            desc.contains(q) -> 40
                            else -> 10
                        }
                    }
                }

                // Build result list; tag sort mode shows only characters
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
                mediator.value = items
            }

            if (filters.isNotEmpty()) {
                viewModelScope.launch {
                    // (필터, 에폭) 캐시 — query/sort만 바뀐 재실행에선 character_field_values 재조회를 건너뛴다.
                    filteredCharIds = fieldFilterMemo.get(filters, fieldValueEpoch.get())
                    filterReady = true
                    // addSource 콜백이 이미 실행되어 데이터가 채워졌을 수 있으므로 combine 호출
                    combine()
                }
            }

            mediator.addSource(charResults) { chars = it; if (filterReady) combine() }
            mediator.addSource(eventResults) { events = it; if (filterReady) combine() }
            mediator.addSource(novelResults) { novels = it; if (filterReady) combine() }
            mediator
        }
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
