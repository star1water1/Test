package com.novelcharacter.app.ui.timeline

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val timelineRepository = app.timelineRepository
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    val allEvents: LiveData<List<TimelineEvent>> = timelineRepository.allEvents
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels
    val allCharacters: LiveData<List<Character>> = characterRepository.allCharacters

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
            characterId != null -> timelineRepository.getEventsForCharacterInRange(characterId, start, end)
            novelId != null -> timelineRepository.getEventsByNovelInRange(novelId, start, end)
            else -> timelineRepository.getEventsByYearRange(start, end)
        }
    }

    /**
     * Zoom level display label.
     */
    val zoomLevelLabel: LiveData<String> = _zoomLevel.map { level ->
        val resId = when (level) {
            1 -> R.string.zoom_level_1000
            2 -> R.string.zoom_level_100
            3 -> R.string.zoom_level_10
            4 -> R.string.zoom_level_1
            5 -> R.string.zoom_level_month
            else -> R.string.zoom_level_1
        }
        application.getString(resId)
    }

    // ===== Search =====
    private val _searchQuery = MutableLiveData("")
    private val _searchTrigger = MediatorLiveData<Unit>().apply {
        addSource(_searchQuery) { value = Unit }
        addSource(visibleRange) { value = Unit }
        addSource(_filterNovelId) { value = Unit }
    }
    val searchResults: LiveData<List<TimelineEvent>> = _searchTrigger.switchMap {
        val query = _searchQuery.value
        if (query.isNullOrBlank()) {
            filteredEvents
        } else {
            val (start, end) = visibleRange.value ?: Pair(-5, 5)
            val novelId = _filterNovelId.value
            timelineRepository.searchEvents(query).map { events ->
                events.filter { event ->
                    event.year in start..end &&
                        (novelId == null || event.novelId == novelId)
                }
            }
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
    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()
    suspend fun getAllCharactersList(): List<Character> = characterRepository.getAllCharactersList()
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    private var errorClearJob: Job? = null

    private fun showError(message: String?) {
        _error.value = message
        errorClearJob?.cancel()
        errorClearJob = viewModelScope.launch {
            delay(3000)
            _error.value = null
        }
    }

    fun insertEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        try {
            val eventId = timelineRepository.insertEvent(event)
            timelineRepository.updateEventCharacters(eventId, characterIds)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to insert event", e)
            showError(e.message)
        }
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        try {
            timelineRepository.updateEvent(event)
            timelineRepository.updateEventCharacters(event.id, characterIds)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to update event", e)
            showError(e.message)
        }
    }

    fun deleteEvent(event: TimelineEvent) = viewModelScope.launch {
        try {
            timelineRepository.deleteEvent(event)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to delete event", e)
            showError(e.message)
        }
    }
}
