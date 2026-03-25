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
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.util.EventEditDialogHelper.ShiftDirection
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    private val timelineRepository = app.timelineRepository
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository
    private val universeRepository = app.universeRepository
    private val semanticSyncHelper = SemanticFieldSyncHelper(characterRepository, universeRepository, novelRepository)
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
    private val _zoomLevel = MutableLiveData(prefs.getInt("zoom_level", 4).coerceIn(1, 5))
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
        // 작품 필터 기반 사건 ID 캐시 갱신
        viewModelScope.launch {
            _novelEventIds.value = if (novelId != null) {
                timelineRepository.getEventIdsByNovel(novelId).toSet()
            } else null
        }
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

    // 작품 필터 기반 사건 ID 캐시 (인메모리 검색 필터용)
    private val _novelEventIds = MutableLiveData<Set<Long>?>(null)

    // ===== Search =====
    private val _searchQuery = MutableLiveData("")
    private val _searchTrigger = MediatorLiveData<Unit>().apply {
        addSource(_searchQuery) { value = Unit }
        addSource(visibleRange) { value = Unit }
        addSource(_filterNovelId) { value = Unit }
        addSource(_filterCharacterId) { value = Unit }
        addSource(_novelEventIds) { value = Unit }
    }
    val searchResults: LiveData<List<TimelineEvent>> = _searchTrigger.switchMap {
        val query = _searchQuery.value
        if (query.isNullOrBlank()) {
            filteredEvents
        } else {
            val (start, end) = visibleRange.value ?: Pair(-5, 5)
            val novelId = _filterNovelId.value
            val characterId = _filterCharacterId.value
            val novelEventIdSet = _novelEventIds.value

            // 검색도 AND 조합 필터 적용
            when {
                characterId != null -> {
                    timelineRepository.getEventsForCharacterInRange(characterId, start, end).map { events ->
                        events.filter {
                            it.description.contains(query, ignoreCase = true) &&
                                (novelId == null || novelEventIdSet == null || it.id in novelEventIdSet)
                        }
                    }
                }
                else -> {
                    timelineRepository.searchEvents(query).map { events ->
                        events.filter { event ->
                            event.year in start..end &&
                                (novelId == null || novelEventIdSet == null || event.id in novelEventIdSet)
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

    fun insertEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long> = emptyList()) = viewModelScope.launch {
        try {
            db.withTransaction {
                val eventId = timelineRepository.insertEvent(event)
                timelineRepository.updateEventCharacters(eventId, characterIds)
                timelineRepository.updateEventNovels(eventId, novelIds)
            }
            // novelEventIds 캐시 갱신
            _filterNovelId.value?.let { nid ->
                _novelEventIds.value = timelineRepository.getEventIdsByNovel(nid).toSet()
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to insert event", e)
            showError(e.message)
        }
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>, novelIds: List<Long> = emptyList()) = viewModelScope.launch {
        try {
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)
            }
            // novelEventIds 캐시 갱신
            _filterNovelId.value?.let { nid ->
                _novelEventIds.value = timelineRepository.getEventIdsByNovel(nid).toSet()
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to update event", e)
            showError(e.message)
        }
    }

    fun updateEventAndShiftOthers(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long>,
        shiftDirection: ShiftDirection,
        delta: Int,
        originalNovelIds: List<Long>,
        originalUniverseId: Long?
    ) = viewModelScope.launch {
        try {
            val oldYear = event.year - delta
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)

                val scopeEvents = when {
                    originalNovelIds.isNotEmpty() ->
                        originalNovelIds.flatMap { timelineRepository.getEventsByNovelList(it) }
                            .distinctBy { it.id }
                    originalUniverseId != null -> timelineRepository.getEventsByUniverseList(originalUniverseId)
                    else -> timelineRepository.getAllEventsList()
                }.filter { it.id != event.id }

                val eventsToShift = scopeEvents.filter { e ->
                    when (shiftDirection) {
                        ShiftDirection.AFTER -> e.year >= oldYear
                        ShiftDirection.BEFORE -> e.year <= oldYear
                    }
                }

                if (eventsToShift.isNotEmpty()) {
                    val shifted = eventsToShift.mapNotNull { e ->
                        val newYear = e.year.toLong() + delta.toLong()
                        if (newYear in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            e.copy(year = newYear.toInt())
                        } else null
                    }
                    timelineRepository.updateAllEvents(shifted)

                    for (s in shifted) {
                        if (s.eventType == TimelineEvent.TYPE_BIRTH || s.eventType == TimelineEvent.TYPE_DEATH) {
                            val charIds = timelineRepository.getCharacterIdsForEvent(s.id)
                            syncEventTypeToStateChanges(s, charIds)
                        }
                    }
                }
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to shift events", e)
            showError(e.message)
        }
    }

    suspend fun getNovelIdsForEvent(eventId: Long) = timelineRepository.getNovelIdsForEvent(eventId)
    suspend fun getEventsByNovelList(novelId: Long) = timelineRepository.getEventsByNovelList(novelId)
    suspend fun getEventsByUniverseList(universeId: Long) = timelineRepository.getEventsByUniverseList(universeId)
    suspend fun getAllEventsList() = timelineRepository.getAllEventsList()
    suspend fun getAllEventNovelNames() = timelineRepository.getAllEventNovelNames()

    /**
     * 사건 유형이 birth/death이면 관련 캐릭터의 상태변화 + 필드 동기화.
     */
    private suspend fun syncEventTypeToStateChanges(event: TimelineEvent, characterIds: List<Long>) {
        val fieldKey = when (event.eventType) {
            TimelineEvent.TYPE_BIRTH -> CharacterStateChange.KEY_BIRTH
            TimelineEvent.TYPE_DEATH -> CharacterStateChange.KEY_DEATH
            else -> return
        }
        for (charId in characterIds) {
            try {
                val existing = characterRepository.getChangesByCharacterList(charId)
                    .find { it.fieldKey == fieldKey }
                val change = if (existing != null) {
                    existing.copy(
                        year = event.year,
                        month = event.month,
                        day = event.day,
                        newValue = event.year.toString()
                    ).also { characterRepository.updateStateChange(it) }
                } else {
                    CharacterStateChange(
                        characterId = charId,
                        year = event.year,
                        month = event.month,
                        day = event.day,
                        fieldKey = fieldKey,
                        newValue = event.year.toString()
                    ).also { characterRepository.insertStateChange(it) }
                }
                // 필드 동기화 (출생연도/사망연도 필드 + alive 필드)
                val character = characterRepository.getCharacterById(charId) ?: continue
                val novel = character.novelId?.let { novelRepository.getNovelById(it) } ?: continue
                val universeId = novel.universeId ?: continue
                semanticSyncHelper.syncStateChangeToField(charId, universeId, change)
            } catch (e: Exception) {
                Log.w("TimelineViewModel", "Failed to sync event type for character $charId", e)
            }
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
            // 삭제 전에 연결된 캐릭터의 상태변화 정리 (삭제 후에는 cross-ref 소실)
            if (event.eventType == TimelineEvent.TYPE_BIRTH || event.eventType == TimelineEvent.TYPE_DEATH) {
                cleanupStateChangesForDeletedEvent(event)
            }
            timelineRepository.deleteEvent(event)
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to delete event", e)
            showError(e.message)
        }
    }

    /**
     * 출생/사망 사건 삭제 전, 연결된 캐릭터의 상태변화 + 필드값을 정리.
     * syncEventTypeToStateChanges()의 역방향 처리.
     */
    private suspend fun cleanupStateChangesForDeletedEvent(event: TimelineEvent) {
        val characterIds = timelineRepository.getCharacterIdsForEvent(event.id)
        for (charId in characterIds) {
            try {
                val character = characterRepository.getCharacterById(charId) ?: continue
                val novel = character.novelId?.let { novelRepository.getNovelById(it) } ?: continue
                val universeId = novel.universeId ?: continue
                when (event.eventType) {
                    TimelineEvent.TYPE_BIRTH -> semanticSyncHelper.onBirthEventDeleted(charId, universeId)
                    TimelineEvent.TYPE_DEATH -> semanticSyncHelper.onDeathEventDeleted(charId, universeId)
                }
            } catch (e: Exception) {
                Log.w("TimelineViewModel", "Failed to cleanup state changes for character $charId", e)
            }
        }
    }
}
