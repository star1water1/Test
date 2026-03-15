package com.novelcharacter.app.ui.timeline

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.launch

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NovelCharacterApp).repository

    val allEvents: LiveData<List<TimelineEvent>> = repository.allEvents
    val allNovels: LiveData<List<Novel>> = repository.allNovels
    val allCharacters: LiveData<List<Character>> = repository.allCharacters

    // ===== Zoom Level Management =====
    // Zoom levels: 1=1000년, 2=100년, 3=10년, 4=1년 (default), 5=월
    private val _zoomLevel = MutableLiveData(4)
    val zoomLevel: LiveData<Int> = _zoomLevel

    private val _centerYear = MutableLiveData(0)
    val centerYear: LiveData<Int> = _centerYear

    private val _selectedYear = MutableLiveData<Int?>(null)
    val selectedYear: LiveData<Int?> = _selectedYear

    // ===== Timeline Filters =====
    private val _filterNovelId = MutableLiveData<Long?>(null)
    val filterNovelId: LiveData<Long?> = _filterNovelId
    private val _filterCharacterId = MutableLiveData<Long?>(null)
    val filterCharacterId: LiveData<Long?> = _filterCharacterId

    fun setFilterNovel(novelId: Long?) { _filterNovelId.value = novelId }
    fun setFilterCharacter(characterId: Long?) { _filterCharacterId.value = characterId }
    fun clearFilters() {
        _filterNovelId.value = null
        _filterCharacterId.value = null
    }

    /**
     * Computes the visible year range based on zoom level and center year.
     * Returns a Pair of (startYear, endYear).
     */
    val visibleRange: LiveData<Pair<Int, Int>> = MediatorLiveData<Pair<Int, Int>>().apply {
        fun update() {
            val zoom = _zoomLevel.value ?: 4
            val center = _centerYear.value ?: 0
            val range = when (zoom) {
                1 -> 5000  // ±5000 years
                2 -> 500   // ±500 years
                3 -> 50    // ±50 years
                4 -> 5     // ±5 years
                5 -> 0     // single year (12 months)
                else -> 5
            }
            value = Pair(center - range, center + range)
        }
        addSource(_zoomLevel) { update() }
        addSource(_centerYear) { update() }
    }

    /**
     * Filtered events based on the current visible range and optional novel/character filters.
     */
    private val _filterTrigger = MediatorLiveData<Unit>().apply {
        addSource(visibleRange) { value = Unit }
        addSource(_filterNovelId) { value = Unit }
        addSource(_filterCharacterId) { value = Unit }
    }

    val filteredEvents: LiveData<List<TimelineEvent>> = _filterTrigger.switchMap {
        val (start, end) = visibleRange.value ?: Pair(-5, 5)
        val novelId = _filterNovelId.value
        val characterId = _filterCharacterId.value

        when {
            characterId != null -> repository.getEventsForCharacterInRange(characterId, start, end)
            novelId != null -> repository.getEventsByNovelInRange(novelId, start, end)
            else -> repository.getEventsByYearRange(start, end)
        }
    }

    /**
     * Zoom level display label.
     */
    val zoomLevelLabel: LiveData<String> = _zoomLevel.map { level ->
        when (level) {
            1 -> "1000년 단위"
            2 -> "100년 단위"
            3 -> "10년 단위"
            4 -> "1년 단위"
            5 -> "월 단위"
            else -> "1년 단위"
        }
    }

    // ===== Search =====
    private val _searchQuery = MutableLiveData("")
    val searchResults: LiveData<List<TimelineEvent>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            filteredEvents
        } else {
            repository.searchEvents(query)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ===== Zoom controls =====
    fun zoomIn() {
        val current = _zoomLevel.value ?: 4
        if (current < 5) {
            _zoomLevel.value = current + 1
        }
    }

    fun zoomOut() {
        val current = _zoomLevel.value ?: 4
        if (current > 1) {
            _zoomLevel.value = current - 1
        }
    }

    fun setZoomLevel(level: Int) {
        _zoomLevel.value = level.coerceIn(1, 5)
    }

    fun setCenter(year: Int) {
        _centerYear.value = year
    }

    fun setSelectedYear(year: Int?) {
        _selectedYear.value = year
        if (year != null) {
            _centerYear.value = year
        }
    }

    // ===== Data access =====
    suspend fun getAllNovelsList(): List<Novel> = repository.getAllNovelsList()
    suspend fun getAllCharactersList(): List<Character> = repository.getAllCharactersList()
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        repository.getCharacterIdsForEvent(eventId)
    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        repository.getCharactersForEvent(eventId)

    fun insertEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        val eventId = repository.insertEvent(event)
        repository.updateEventCharacters(eventId, characterIds)
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        repository.updateEvent(event)
        repository.updateEventCharacters(event.id, characterIds)
    }

    fun deleteEvent(event: TimelineEvent) = viewModelScope.launch {
        repository.deleteEvent(event)
    }
}
