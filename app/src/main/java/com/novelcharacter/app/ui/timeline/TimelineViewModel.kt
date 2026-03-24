package com.novelcharacter.app.ui.timeline

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    private val timelineRepository = app.timelineRepository
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository
    private val prefs = application.getSharedPreferences("timeline_ui_state", Context.MODE_PRIVATE)

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    val allEvents: LiveData<List<TimelineEvent>> = timelineRepository.allEvents
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels
    val allCharacters: LiveData<List<Character>> = characterRepository.allCharacters

    // ===== Event density data for density bar =====
    private val _eventDensity = MutableLiveData<Map<Int, Int>>()
    val eventDensity: LiveData<Map<Int, Int>> = _eventDensity

    private val densityObserver = Observer<List<TimelineEvent>> { events ->
        val density = events.groupBy { it.year }.mapValues { it.value.size }
        _eventDensity.value = density
    }

    init {
        allEvents.observeForever(densityObserver)
    }

    // ===== Zoom Level Management =====
    private val _zoomLevel = MutableLiveData(prefs.getInt("zoom_level", 4))
    val zoomLevel: LiveData<Int> = _zoomLevel

    private val _centerYear = MutableLiveData(prefs.getInt("center_year", 0))
    val centerYear: LiveData<Int> = _centerYear

    private val _selectedYear = MutableLiveData<Int?>(null)
    val selectedYear: LiveData<Int?> = _selectedYear

    // ===== Timeline Filters =====
    private val _filterNovelId = MutableLiveData<Long?>(
        if (prefs.contains("filter_novel_id")) prefs.getLong("filter_novel_id", -1L) else null
    )
    val filterNovelId: LiveData<Long?> = _filterNovelId
    private val _filterCharacterId = MutableLiveData<Long?>(
        if (prefs.contains("filter_character_id")) prefs.getLong("filter_character_id", -1L) else null
    )
    val filterCharacterId: LiveData<Long?> = _filterCharacterId

    // 소설 필터에 연동된 캐릭터 목록
    private val _filteredCharacters = MediatorLiveData<List<Character>>().apply {
        addSource(allCharacters) { updateFilteredCharacters() }
        addSource(_filterNovelId) { updateFilteredCharacters() }
    }
    val filteredCharacters: LiveData<List<Character>> = _filteredCharacters

    private fun updateFilteredCharacters() {
        val all = allCharacters.value ?: emptyList()
        val novelId = _filterNovelId.value
        _filteredCharacters.value = if (novelId != null) {
            all.filter { it.novelId == novelId }
        } else {
            all
        }
    }

    fun setFilterNovel(novelId: Long?) {
        _filterNovelId.value = novelId
        prefs.edit().apply {
            if (novelId != null) putLong("filter_novel_id", novelId) else remove("filter_novel_id")
        }.apply()
        // 소설 필터 변경 시 캐릭터 필터 초기화
        if (novelId != null && _filterCharacterId.value != null) {
            val chars = allCharacters.value ?: emptyList()
            val selectedChar = chars.find { it.id == _filterCharacterId.value }
            if (selectedChar?.novelId != novelId) {
                setFilterCharacter(null)
            }
        }
    }
    fun setFilterCharacter(characterId: Long?) {
        _filterCharacterId.value = characterId
        prefs.edit().apply {
            if (characterId != null) putLong("filter_character_id", characterId) else remove("filter_character_id")
        }.apply()
    }
    fun clearFilters() {
        _filterNovelId.value = null
        _filterCharacterId.value = null
        prefs.edit().remove("filter_novel_id").remove("filter_character_id").apply()
    }

    val visibleRange: LiveData<Pair<Int, Int>> = MediatorLiveData<Pair<Int, Int>>().apply {
        fun update() {
            val zoom = _zoomLevel.value ?: 4
            val center = _centerYear.value ?: 0
            val range = when (zoom) {
                1 -> 5000
                2 -> 500
                3 -> 50
                4 -> 5
                5 -> 0
                else -> 5
            }
            value = Pair(center - range, center + range)
        }
        addSource(_zoomLevel) { update() }
        addSource(_centerYear) { update() }
    }

    /**
     * Filtered events — 소설+캐릭터 AND 조합 필터 지원
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
            // AND 조합: 소설 + 캐릭터 동시 필터
            characterId != null && novelId != null ->
                timelineRepository.getEventsForCharacterAndNovelInRange(characterId, novelId, start, end)
            characterId != null ->
                timelineRepository.getEventsForCharacterInRange(characterId, start, end)
            novelId != null ->
                timelineRepository.getEventsByNovelInRange(novelId, start, end)
            else ->
                timelineRepository.getEventsByYearRange(start, end)
        }
    }

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
        addSource(_filterCharacterId) { value = Unit }
    }
    val searchResults: LiveData<List<TimelineEvent>> = _searchTrigger.switchMap {
        val query = _searchQuery.value
        if (query.isNullOrBlank()) {
            filteredEvents
        } else {
            val (start, end) = visibleRange.value ?: Pair(-5, 5)
            val novelId = _filterNovelId.value
            val characterId = _filterCharacterId.value

            // 검색도 AND 조합 필터 적용
            when {
                characterId != null -> {
                    timelineRepository.getEventsForCharacterInRange(characterId, start, end).map { events ->
                        events.filter {
                            it.description.contains(query, ignoreCase = true) &&
                                (novelId == null || it.novelId == novelId)
                        }
                    }
                }
                else -> {
                    timelineRepository.searchEvents(query).map { events ->
                        events.filter { event ->
                            event.year in start..end &&
                                (novelId == null || event.novelId == novelId)
                        }
                    }
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
            prefs.edit().putInt("zoom_level", current + 1).apply()
        }
    }

    fun zoomOut() {
        val current = _zoomLevel.value ?: 4
        if (current > 1) {
            _zoomLevel.value = current - 1
            prefs.edit().putInt("zoom_level", current - 1).apply()
        }
    }

    fun setZoomLevel(level: Int) {
        val clamped = level.coerceIn(1, 5)
        _zoomLevel.value = clamped
        prefs.edit().putInt("zoom_level", clamped).apply()
    }

    private var centerYearSaveJob: Job? = null

    private fun debounceSaveCenterYear(year: Int) {
        centerYearSaveJob?.cancel()
        centerYearSaveJob = viewModelScope.launch {
            delay(500)
            prefs.edit().putInt("center_year", year).apply()
        }
    }

    fun setCenter(year: Int) {
        _centerYear.value = year
        debounceSaveCenterYear(year)
    }

    fun setSelectedYear(year: Int?) {
        _selectedYear.value = year
        if (year != null) {
            _centerYear.value = year
            debounceSaveCenterYear(year)
        }
    }

    // ===== Data access =====
    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()
    suspend fun getAllCharactersList(): List<Character> = characterRepository.getAllCharactersList()
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    // 소설별 캐릭터 목록 (다이얼로그용)
    suspend fun getCharactersByNovel(novelId: Long): List<Character> =
        characterRepository.getCharactersByNovelList(novelId)

    private var errorClearJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        allEvents.removeObserver(densityObserver)
        // 디바운스 중인 center_year를 즉시 저장
        centerYearSaveJob?.cancel()
        _centerYear.value?.let { prefs.edit().putInt("center_year", it).apply() }
        errorClearJob?.cancel()
    }

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
            db.withTransaction {
                val eventId = timelineRepository.insertEvent(event)
                timelineRepository.updateEventCharacters(eventId, characterIds)
            }
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to insert event", e)
            showError(e.message)
        }
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        try {
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
            }
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to update event", e)
            showError(e.message)
        }
    }

    fun updateDisplayOrders(events: List<TimelineEvent>) = viewModelScope.launch {
        try {
            db.timelineDao().updateAll(events)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to update display orders", e)
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
