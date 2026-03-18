package com.novelcharacter.app.ui.search

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SearchPreset
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.launch

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

    val presets: LiveData<List<SearchPreset>> = searchPresetRepository.allPresets
    val sortMode: LiveData<String> = _sortMode

    private val _presetAppliedEvent = MutableLiveData<String?>()
    val presetAppliedEvent: LiveData<String?> = _presetAppliedEvent

    init {
        viewModelScope.launch {
            searchPresetRepository.ensureDefaultPresets()
        }
    }

    private val searchTrigger = MediatorLiveData<Pair<String, String>>().apply {
        addSource(_searchQuery) { value = Pair(it ?: "", _sortMode.value ?: SearchPreset.SORT_RELEVANCE) }
        addSource(_sortMode) { value = Pair(_searchQuery.value ?: "", it ?: SearchPreset.SORT_RELEVANCE) }
    }

    private var previousCharSource: LiveData<List<Character>>? = null
    private var previousEventSource: LiveData<List<TimelineEvent>>? = null
    private var previousNovelSource: LiveData<List<Novel>>? = null
    private var previousMediator: MediatorLiveData<List<SearchResultItem>>? = null

    val searchResults: LiveData<List<SearchResultItem>> = searchTrigger.switchMap { (query, sort) ->
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

        if (query.isBlank()) {
            MutableLiveData(emptyList())
        } else {
            val mediator = MediatorLiveData<List<SearchResultItem>>()
            val charResults = characterRepository.searchCharacters(query)
            val eventResults = timelineRepository.searchEvents(query)
            val novelResults = novelRepository.searchNovels(query)

            previousCharSource = charResults
            previousEventSource = eventResults
            previousNovelSource = novelResults
            previousMediator = mediator

            var chars: List<Character> = emptyList()
            var events: List<TimelineEvent> = emptyList()
            var novels: List<Novel> = emptyList()

            fun combine() {
                val q = query.lowercase()
                val items = mutableListOf<SearchResultItem>()

                val rankedChars = when (sort) {
                    SearchPreset.SORT_NAME -> chars.sortedBy { it.name.lowercase() }
                    SearchPreset.SORT_TAG -> chars.sortedBy { it.name.lowercase() }
                    SearchPreset.SORT_RECENT -> chars.sortedByDescending { it.updatedAt }
                    else -> chars.sortedByDescending { c ->
                        val name = c.name.lowercase()
                        val alias = c.anotherName.lowercase()
                        when {
                            name == q -> 100
                            name.startsWith(q) -> 80
                            alias == q -> 70
                            alias.startsWith(q) -> 60
                            name.contains(q) -> 40
                            alias.contains(q) -> 30
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

            mediator.addSource(charResults) { chars = it; combine() }
            mediator.addSource(eventResults) { events = it; combine() }
            mediator.addSource(novelResults) { novels = it; combine() }
            mediator
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortMode(mode: String) {
        _sortMode.value = mode
    }

    fun applyPreset(preset: SearchPreset) {
        _sortMode.value = preset.sortMode
        if (preset.query.isNotBlank()) {
            _searchQuery.value = preset.query
        }
        _presetAppliedEvent.value = preset.name
    }

    fun clearPresetEvent() {
        _presetAppliedEvent.value = null
    }

    fun saveCurrentAsPreset(name: String) {
        viewModelScope.launch {
            try {
                val preset = SearchPreset(
                    name = name,
                    query = _searchQuery.value ?: "",
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
