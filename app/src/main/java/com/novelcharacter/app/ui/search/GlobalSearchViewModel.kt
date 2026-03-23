package com.novelcharacter.app.ui.search

import android.app.Application
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.ui.universe.UniverseViewModel.Event
import kotlinx.coroutines.launch

data class FieldFilter(
    val fieldId: Long,
    val fieldName: String,
    val values: List<String>,
    val matchMode: String = "exact"  // "exact" | "contains"
)

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

    private val _searchQuery = MutableLiveData("")
    private val _sortMode = MutableLiveData(SearchPreset.SORT_RELEVANCE)
    private val _fieldFilters = MutableLiveData<List<FieldFilter>>(emptyList())

    val presets: LiveData<List<SearchPreset>> = searchPresetRepository.allPresets
    val sortMode: LiveData<String> = _sortMode
    val fieldFilters: LiveData<List<FieldFilter>> = _fieldFilters

    private val _presetAppliedEvent = MutableLiveData<Event<String>?>()
    val presetAppliedEvent: LiveData<Event<String>?> = _presetAppliedEvent

    private val gson = Gson()
    private val db = app.database

    init {
        viewModelScope.launch {
            searchPresetRepository.ensureDefaultPresets()
        }
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
                    filteredCharIds = applyFieldFilters(filters)
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

    fun getFiltersJson(): String {
        val filters = _fieldFilters.value ?: emptyList()
        return if (filters.isEmpty()) "{}" else gson.toJson(filters)
    }

    fun applyFiltersFromJson(json: String) {
        if (json.isBlank() || json == "{}") {
            _fieldFilters.value = emptyList()
            return
        }
        try {
            val type = object : TypeToken<List<FieldFilter>>() {}.type
            val filters: List<FieldFilter> = gson.fromJson(json, type)
            _fieldFilters.value = filters
        } catch (_: Exception) {
            _fieldFilters.value = emptyList()
        }
    }

    private suspend fun applyFieldFilters(filters: List<FieldFilter>): Set<Long> {
        var resultIds: Set<Long>? = null
        for (filter in filters) {
            val idsForFilter = mutableSetOf<Long>()
            for (value in filter.values) {
                val ids = when (filter.matchMode) {
                    "contains" -> db.characterFieldValueDao().getCharacterIdsByFieldValueContains(filter.fieldId, com.novelcharacter.app.data.repository.sanitizeLikeQuery(value))
                    else -> db.characterFieldValueDao().getCharacterIdsByFieldValue(filter.fieldId, value)
                }
                idsForFilter.addAll(ids)
            }
            resultIds = resultIds?.intersect(idsForFilter) ?: idsForFilter
        }
        return resultIds ?: emptySet()
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
