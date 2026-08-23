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
import com.novelcharacter.app.data.repository.EventFieldValueMerge
import com.novelcharacter.app.ui.timeline.EventEditDialogFragment.ShiftDirection
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.novelcharacter.app.util.toastAndLogResult
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.StandardYearSyncHelper
import com.novelcharacter.app.util.TimelineDisplayOrder
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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

    /**
     * 출생·사망 사건 → 상태변화·필드 동기화의 **단일 소스** — 캐릭터 화면과 같은 클래스다
     * (종전에는 이 화면과 `CharacterViewModel`에 글자까지 같은 루프가 두 벌 있었다).
     */
    private val eventStateSync = com.novelcharacter.app.util.EventStateChangeSync(
        timelineRepository, characterRepository, novelRepository, universeRepository,
        semanticSyncHelper, logTag = "TimelineViewModel"
    )
    private val prefs = application.getSharedPreferences("timeline_ui_state", Context.MODE_PRIVATE)

    // 데이터 처리 결과 알림 채널 — 사건 저장/수정/삭제 성공·실패를 OpResult로 일원화
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

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

    // ===== Timeline Filters =====
    private val _filterNovelId = MutableLiveData<Long?>(
        if (prefs.contains("filter_novel_id")) prefs.getLong("filter_novel_id", -1L) else null
    )
    private val _filterCharacterId = MutableLiveData<Long?>(
        if (prefs.contains("filter_character_id")) prefs.getLong("filter_character_id", -1L) else null
    )
    val filterNovelId: LiveData<Long?> = _filterNovelId
    val filterCharacterId: LiveData<Long?> = _filterCharacterId

    // 작품 필터 기반 사건 ID 캐시 (인메모리 검색 필터용)
    private val _novelEventIds = MutableLiveData<Set<Long>?>(null)
    // 캐릭터 필터 기반 사건 ID 캐시
    private val _characterEventIds = MutableLiveData<Set<Long>?>(null)

    init {
        allEvents.observeForever(densityObserver)
        // 저장된 작품 필터의 novelEventIds 캐시 초기 로딩
        _filterNovelId.value?.let { nid ->
            viewModelScope.launch {
                _novelEventIds.value = timelineRepository.getEventIdsByNovel(nid).toSet()
            }
        }
        // 저장된 캐릭터 필터의 characterEventIds 캐시 초기 로딩
        _filterCharacterId.value?.let { cid ->
            viewModelScope.launch {
                _characterEventIds.value = timelineRepository.getEventIdsForCharacter(cid).toSet()
            }
        }
    }

    // ===== Zoom Level Management =====
    private val _zoomLevel = MutableLiveData(prefs.getInt("zoom_level", 4).coerceIn(1, 5))
    val zoomLevel: LiveData<Int> = _zoomLevel

    private val _centerYear = MutableLiveData(prefs.getInt("center_year", 0))
    val centerYear: LiveData<Int> = _centerYear

    private val _selectedYear = MutableLiveData<Int?>(null)
    val selectedYear: LiveData<Int?> = _selectedYear

    // ===== 표시 순서 (B-47) =====
    // 뒤집기는 **표시 계층 한 자리**에서만 한다 — 근거는 [TimelineDisplayOrder] KDoc(실측).
    private val _sortDescending = MutableLiveData(
        prefs.getBoolean(TimelineDisplayOrder.PREF_KEY_DESCENDING, TimelineDisplayOrder.DEFAULT_DESCENDING)
    )
    val sortDescending: LiveData<Boolean> = _sortDescending

    private fun isDescending(): Boolean = _sortDescending.value == true

    /** 시간순 ↔ 역순 전환. 보기 설정이므로 기기에 남는다(다시 열 때 같은 방향). */
    fun toggleSortDescending() {
        val next = !isDescending()
        _sortDescending.value = next
        prefs.edit().putBoolean(TimelineDisplayOrder.PREF_KEY_DESCENDING, next).apply()
    }

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
        viewModelScope.launch {
            _characterEventIds.value = if (characterId != null) {
                timelineRepository.getEventIdsForCharacter(characterId).toSet()
            } else null
        }
    }
    fun clearFilters() {
        _filterNovelId.value = null
        _filterCharacterId.value = null
        _novelEventIds.value = null
        _characterEventIds.value = null
        prefs.edit().remove("filter_novel_id").remove("filter_character_id").apply()
    }

    /**
     * 걸려 있는 필터의 사건 id 캐시를 다시 읽는다 — **사건이 바뀌는 모든 자리가 여기를 지난다.**
     *
     * 종전에는 이 세 줄이 사건 CRUD 네 자리에 복사돼 있었고, **작품 캐시만** 갱신했다.
     * 캐릭터 필터를 걸어 둔 채 사건을 더하거나 지우면 그 캐시가 낡은 채로 남아
     * 이전/다음 이동과 `N / M` 표기가 **없는 사건을 세거나 새 사건을 못 봤다**(R-33 —
     * 같은 일을 네 곳에 적어 두면 한 곳이 반드시 뒤처진다).
     */
    private suspend fun refreshFilterCaches() {
        _filterNovelId.value?.let { nid ->
            _novelEventIds.value = timelineRepository.getEventIdsByNovel(nid).toSet()
        }
        _filterCharacterId.value?.let { cid ->
            _characterEventIds.value = timelineRepository.getEventIdsForCharacter(cid).toSet()
        }
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
            // novelId가 설정됐지만 캐시가 아직 로딩 중이면 빈 결과 반환 (레이스 방지)
            when {
                characterId != null -> {
                    timelineRepository.getEventsForCharacterInRange(characterId, start, end).map { events ->
                        events.filter {
                            it.description.contains(query, ignoreCase = true) &&
                                (novelId == null || (novelEventIdSet != null && it.id in novelEventIdSet))
                        }
                    }
                }
                else -> {
                    timelineRepository.searchEvents(query).map { events ->
                        events.filter { event ->
                            event.year in start..end &&
                                (novelId == null || (novelEventIdSet != null && event.id in novelEventIdSet))
                        }
                    }
                }
            }
        }
    }

    /**
     * 화면이 그리는 목록 — [searchResults](시간순)에 표시 순서를 입힌 것 (B-47).
     *
     * 화면은 이것 하나만 본다. `searchResults`를 직접 관측하는 자리가 남으면 그 자리만
     * 토글을 모른 채 시간순으로 남는다.
     */
    val displayEvents: LiveData<List<TimelineEvent>> = MediatorLiveData<List<TimelineEvent>>().apply {
        fun update() {
            val events = searchResults.value ?: return
            value = TimelineDisplayOrder.arrange(events, isDescending())
        }
        addSource(searchResults) { update() }
        addSource(_sortDescending) { update() }
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

    private var centerYearSaveJob: Job? = null

    private fun debounceSaveCenterYear(year: Int) {
        centerYearSaveJob?.cancel()
        centerYearSaveJob = viewModelScope.launch {
            delay(500)
            prefs.edit().putInt("center_year", year).apply()
        }
    }

    fun setSelectedYear(year: Int?) {
        _selectedYear.value = year
        if (year != null) {
            _centerYear.value = year
            debounceSaveCenterYear(year)
        }
    }

    // ===== Standard Year ====

    fun setNovelStandardYear(novelId: Long, newStdYear: Int) = viewModelScope.launch {
        try {
            // DB에서 최신 Novel을 읽어 stale 데이터 방지
            val novel = novelRepository.getNovelById(novelId) ?: return@launch
            val oldStdYear = novel.standardYear
            val updatedNovel = novel.copy(standardYear = newStdYear)
            novelRepository.updateNovel(updatedNovel)
            val syncHelper = StandardYearSyncHelper(characterRepository, universeRepository)
            syncHelper.onStandardYearChanged(updatedNovel, oldStdYear, newStdYear)

            // 표준연도 변경 후 시맨틱 필드 전체 재동기화 (나이/생존여부 재계산)
            val universeId = novel.universeId
            if (universeId != null) {
                val characters = characterRepository.getCharactersByNovelList(novelId)
                // **필드 목록은 루프 불변량이다** — 캐릭터마다 다시 읽으면 표준연도 한 번
                // 고치는 평범한 조작이 캐스트 수만큼 같은 질의를 친다.
                val fields = universeRepository.getFieldsByUniverseList(universeId)
                // **재료도 루프 불변량처럼 다룬다** — 종전에는 캐릭터마다 이력 한 번(연동
                // 판정)과 값 한 번을 물어, 위 `onStandardYearChanged`가 방금 훑은 표를
                // 인원 수만큼 다시 읽었다. 그 함수가 쓴 결과 **뒤에** 뜨므로 최신이다.
                val ids = characters.map { it.id }
                val changesById = characterRepository.getChangesForCharacters(ids)
                    .groupBy { it.characterId }
                val valuesById = characterRepository.getValuesForCharacters(ids)
                    .groupBy { it.characterId }
                for (character in characters) {
                    try {
                        val changes = changesById[character.id].orEmpty()
                        // 판정은 `syncHelper.isLinked`가 쓰는 그 순수 함수다(단일 소스).
                        if (!com.novelcharacter.app.util.StandardYearLink.isUnlinkedIn(changes)) {
                            semanticSyncHelper.syncFieldToStateChange(
                                character.id, fields, valuesById[character.id].orEmpty()
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("TimelineViewModel", "Failed to sync semantic fields for character ${character.id}", e)
                    }
                }
            }
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_stdyear_updated, newStdYear)))
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to set standard year", e)
            failEvent(R.string.result_novel_standard_year_failed, e)
        }
    }

    // ===== Event Navigation (이전/다음 사건 이동) =====

    data class EventNavState(
        val hasPrevious: Boolean,
        val hasNext: Boolean,
        val currentIndex: Int,   // 0-based, -1 if between events
        val totalCount: Int
    )

    private fun getFilteredEventsInMemory(): List<TimelineEvent> {
        val events = allEvents.value ?: emptyList()
        val novelId = _filterNovelId.value
        val characterId = _filterCharacterId.value
        val novelIds = _novelEventIds.value
        val charIds = _characterEventIds.value
        val query = _searchQuery.value?.takeIf { it.isNotBlank() }

        return events.filter { event ->
            val matchNovel = novelId == null || (novelIds != null && event.id in novelIds)
            val matchChar = characterId == null || (charIds != null && event.id in charIds)
            val matchQuery = query == null || event.description.contains(query, ignoreCase = true)
            matchNovel && matchChar && matchQuery
        }
    }

    private fun computeNavState(): EventNavState {
        val filtered = getFilteredEventsInMemory()
        val center = _centerYear.value ?: 0

        if (filtered.isEmpty()) return EventNavState(false, false, -1, 0)

        val descending = isDescending()
        // '이전'은 언제나 **목록 위쪽**이다 — 역순이면 그것이 더 늦은 연도다(B-47).
        // 방향을 여기서 갈라 두지 않으면 `N / M`은 표시 순서로 세는데 버튼만 시간순이라,
        // '다음'을 누를 때마다 번호가 줄어든다.
        val hasPrev = filtered.any { TimelineDisplayOrder.isEarlierInDisplay(it.year, center, descending) }
        val hasNext = filtered.any { TimelineDisplayOrder.isEarlierInDisplay(center, it.year, descending) }

        // 현재 center에 가장 가까운 위치 찾기 (시간순 기준으로 구한 뒤 표시 순서로 옮긴다)
        val exactIdx = filtered.indexOfFirst { it.year >= center }
        val ascIdx = when {
            exactIdx == -1 -> filtered.lastIndex               // 모든 사건이 center 이전
            filtered[exactIdx].year == center -> exactIdx      // 정확히 일치
            exactIdx > 0 -> exactIdx - 1                       // center가 두 사건 사이
            else -> -1                                         // center가 첫 사건 이전
        }
        val currentIdx = TimelineDisplayOrder.displayIndexOf(ascIdx, filtered.size, descending)

        return EventNavState(hasPrev, hasNext, currentIdx, filtered.size)
    }

    val navState: LiveData<EventNavState> = MediatorLiveData<EventNavState>().apply {
        val update = { _: Any? -> value = computeNavState() }
        addSource(allEvents, update)
        addSource(_centerYear, update)
        addSource(_filterNovelId, update)
        addSource(_filterCharacterId, update)
        addSource(_novelEventIds, update)
        addSource(_characterEventIds, update)
        addSource(_searchQuery, update)
    }

    /** 표시 순서에서 **한 칸 위**의 사건으로 이동 (시간순이면 직전, 역순이면 직후 연도) */
    fun navigateToPreviousEvent() = navigateInDisplayOrder(towardEnd = false)

    /** 표시 순서에서 **한 칸 아래**의 사건으로 이동 */
    fun navigateToNextEvent() = navigateInDisplayOrder(towardEnd = true)

    /**
     * 버튼이 가리키는 것은 목록의 위/아래이지 연도의 과거/미래가 아니다 (B-47).
     * 역순에서는 두 방향이 서로 바뀐다 — 그것이 이 함수가 하나인 이유다
     * (두 벌로 두면 한쪽만 토글을 아는 상태가 생긴다).
     */
    private fun navigateInDisplayOrder(towardEnd: Boolean) {
        val filtered = getFilteredEventsInMemory()   // 시간순
        val center = _centerYear.value ?: 0
        val toLaterYear = towardEnd != isDescending()
        val target = if (toLaterYear) {
            filtered.firstOrNull { it.year > center }
        } else {
            filtered.lastOrNull { it.year < center }
        } ?: return
        setSelectedYear(target.year)
    }

    // ===== Data access =====
    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()
    suspend fun getAllCharactersList(): List<Character> = characterRepository.getAllCharactersList()
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    /** 목록에 실린 사건의 등장 캐릭터를 한 번에 — 카드가 바인딩마다 묻지 않게 한다. */
    suspend fun getCharactersForEvents(eventIds: List<Long>): Map<Long, List<Character>> =
        timelineRepository.getCharactersForEvents(eventIds)

    // 소설별 캐릭터 목록 (다이얼로그용)
    suspend fun getCharactersByNovel(novelId: Long): List<Character> =
        characterRepository.getCharactersByNovelList(novelId)

    override fun onCleared() {
        super.onCleared()
        allEvents.removeObserver(densityObserver)
        // 디바운스 중인 center_year를 즉시 저장
        centerYearSaveJob?.cancel()
        _centerYear.value?.let { prefs.edit().putInt("center_year", it).apply() }
    }

    /**
     * 사건 축의 실패 고지 — **무엇이 실패했는가를 부르는 자리가 반드시 적는다.**
     *
     * 종전에는 `showError(e.message)` 하나를 여섯 자리가 공유했고, 예외 원문이 **요약**
     * 자리에 그대로 나갔다("database or disk is full (code 13 SQLITE_FULL)" 같은 글자가
     * 사용자 문구로 뜬다). 무엇을 하다 실패했는지는 한 글자도 없었고, detail이 비어
     * [상세] 경로도 붙지 않았다. 캐릭터 축은 catch마다 문구를 직접 적어 이 문제가 없다 —
     * 쓰는 문자열도 이미 있었는데 연표 축만 안 쓰고 있었다.
     *
     * **시그니처로 강제한다**: 요약 문자열을 인자로 받으므로 조작 이름을 빠뜨리면
     * 컴파일되지 않는다. 예외 원문은 detail로 내려가 [상세]가 붙는다.
     */
    private fun failEvent(summaryRes: Int, e: Exception) {
        reportResult(_result, OpResult.failure(OpResult.CAT_EVENT, app.getString(summaryRes), e.message))
    }

    /**
     * 간편 사건 추가 — 역법 시드 계산과 insert를 모두 viewModelScope에서 수행해 뷰가 죽어도 유실되지 않게 한다.
     * (뷰 스코프 코루틴에서 시드 쿼리를 await하다 회전/탭이탈로 취소되면 insert가 누락되던 문제 수정.)
     */
    fun quickAddEvent(year: Int, description: String, novelId: Long?, universeId: Long?) = viewModelScope.launch {
        val calendar = if (universeId != null) {
            getEventsByUniverseList(universeId).map { it.calendarType }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
        } else ""
        val event = TimelineEvent(
            year = year,
            description = description,
            isTemporary = true,
            universeId = universeId,
            calendarType = calendar
        )
        insertEvent(event, emptyList(), listOfNotNull(novelId))
    }

    fun insertEvent(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long> = emptyList(),
        fieldSubmission: EventFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
            val newEventId = db.withTransaction {
                val eventId = timelineRepository.insertEvent(event)
                timelineRepository.updateEventCharacters(eventId, characterIds)
                timelineRepository.updateEventNovels(eventId, novelIds)
                if (fieldSubmission != null) {
                    // 폼의 권한은 렌더한 필드까지(R-5/S-6) — 커버 밖 기존 값은 건드리지 않는다
                    EventFieldValueMerge.saveWithinCover(db.eventFieldValueDao(), eventId, fieldSubmission)
                }
                eventId
            }
            // 커밋 후 값 라이브러리 수확 (실패 무해)
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForEvent(newEventId)
            refreshFilterCaches()
            eventStateSync.sync(event, characterIds)
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_added)))
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to insert event", e)
            failEvent(R.string.result_event_save_failed, e)
        }
    }

    fun updateEvent(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long> = emptyList(),
        fieldSubmission: EventFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
            // 타입 변경 감지를 위해 기존 이벤트 조회
            val oldEvent = timelineRepository.getEventById(event.id)
            var preservedFieldValues = 0
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)
                if (fieldSubmission != null) {
                    // 폼의 권한은 렌더한 필드까지(R-5/S-6) — 커버 밖 기존 값은 건드리지 않는다
                    preservedFieldValues =
                        EventFieldValueMerge.saveWithinCover(db.eventFieldValueDao(), event.id, fieldSubmission)
                }
            }
            // 고지는 커밋 직후, 수확보다 먼저 — 보존은 이미 사실이 됐으므로 수확 실패에 연좌되면 안 된다
            notifyPreservedEventFieldValues(preservedFieldValues)
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForEvent(event.id)
            refreshFilterCaches()
            // 이전 타입이 birth/death였고 새 타입이 달라졌으면 상태변화 정리
            if (oldEvent != null && oldEvent.eventType != event.eventType &&
                (oldEvent.eventType == TimelineEvent.TYPE_BIRTH || oldEvent.eventType == TimelineEvent.TYPE_DEATH)) {
                cleanupStateChangesForDeletedEvent(oldEvent)
            }
            eventStateSync.sync(event, characterIds)
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_updated)))
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to update event", e)
            failEvent(R.string.result_event_update_failed, e)
        }
    }

    fun updateEventAndShiftOthers(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long>,
        shiftDirection: ShiftDirection,
        delta: Int,
        originalNovelIds: List<Long>,
        originalUniverseId: Long?,
        fieldSubmission: EventFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
            val oldYear = event.year - delta
            // 타입 변경 감지를 위해 기존 이벤트 조회
            val oldEvent = timelineRepository.getEventById(event.id)
            var preservedFieldValues = 0
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)
                if (fieldSubmission != null) {
                    // 폼의 권한은 렌더한 필드까지(R-5/S-6) — 커버 밖 기존 값은 건드리지 않는다
                    preservedFieldValues =
                        EventFieldValueMerge.saveWithinCover(db.eventFieldValueDao(), event.id, fieldSubmission)
                }

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

                    // **옮긴 사건 전부를 한 벌로 동기화한다** — 종전에는 사건마다 참여
                    // 캐릭터를 단건으로 묻고 캐릭터마다 다섯 질의 뭉치를 쳤고, 그 전부가
                    // 이 쓰기 트랜잭션 안이었다(진행 표시도 취소도 없다).
                    eventStateSync.syncShifted(shifted)
                }
            }
            // 고지는 커밋 직후, 수확보다 먼저 — 보존은 이미 사실이 됐으므로 수확 실패에 연좌되면 안 된다
            notifyPreservedEventFieldValues(preservedFieldValues)
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForEvent(event.id)
            refreshFilterCaches()
            // 이전 타입이 birth/death였고 새 타입이 달라졌으면 상태변화 정리
            if (oldEvent != null && oldEvent.eventType != event.eventType &&
                (oldEvent.eventType == TimelineEvent.TYPE_BIRTH || oldEvent.eventType == TimelineEvent.TYPE_DEATH)) {
                cleanupStateChangesForDeletedEvent(oldEvent)
            }
            eventStateSync.sync(event, characterIds)
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_updated)))
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to shift events", e)
            failEvent(R.string.result_event_update_failed, e)
        }
    }

    /**
     * 커버 밖이라 지우지 않고 남긴 사건 필드값을 알린다(원칙 04 — 유실은 막았으니
     * '존재를 알 수 없는 데이터'가 되지 않게). 다이얼로그는 이미 닫혔으므로 Toast 경로를 쓴다.
     */
    private fun notifyPreservedEventFieldValues(count: Int) {
        if (count <= 0) return
        toastAndLogResult(OpResult.success(OpResult.CAT_EVENT,
            app.getString(R.string.event_field_values_preserved, count)))
    }

    suspend fun getNovelIdsForEvent(eventId: Long) = timelineRepository.getNovelIdsForEvent(eventId)

    /**
     * 사건 하나를 id로 — **화면 목록이 아니라 표에서 뜬다**(B-198).
     *
     * 값을 고치러 오는 길은 연표의 거르개(작품·캐릭터)를 지나지 않는다. 목록에서 찾으면
     * 거르개가 걸린 사건은 못 찾고 **아무 일도 안 일어난 것처럼 보인다** — 누른 사람은
     * 자기가 왜 못 갔는지 알 길이 없다.
     */
    suspend fun getEventById(eventId: Long) = timelineRepository.getEventById(eventId)
    /**
     * 사건 폼이 그릴 필드 — **무소속이면 전역 구역이다**(B-258).
     *
     * 종전에는 세계관 질의 하나뿐이라 `universeId`가 없는 사건은 필드 구역이 통째로
     * 사라졌다. 무소속 캐릭터는 B-119 확장이, 무소속 작품은 B-129가 이미 전역 구역을
     * 받고 있었고 **사건만 못 받았다** — 앱 전체에 `globalFields(ENTITY_EVENT)`를 부르는
     * 자리가 하나도 없었다.
     *
     * 앱의 저장소를 쓴다 — `globalFields`는 **심을 수도 있는**(쓰기) 함수라, 옆에서
     * 새 인스턴스를 지으면 *둘 중 무엇이 맞는가*를 다음 사람이 매번 다시 묻는다
     * ([com.novelcharacter.app.ui.novel.NovelViewModel.getNovelFields]와 같은 근거).
     */
    suspend fun getEventFieldsForUniverse(universeId: Long?) =
        if (universeId == null) {
            app.defaultFieldTemplateRepository
                .globalFields(com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT)
        } else {
            db.fieldDefinitionDao().getFieldsByUniverseList(universeId, com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT)
        }

    /**
     * 사건 편집 자리에서 만든 사건 필드를 심는다(P5).
     *
     * 쓰기는 [viewModelScope]에서 돌린다 — 호출자(사건 편집 다이얼로그)의 코루틴이 도중에
     * 취소되어도 이미 시작한 삽입이 사라지지 않게. 고지는 호출부가 결과를 받아서 한다.
     */
    suspend fun insertEventField(field: com.novelcharacter.app.data.model.FieldDefinition): Long =
        viewModelScope.async { universeRepository.insertField(field) }.await()
    suspend fun getEventFieldValuesForEvent(eventId: Long) =
        db.eventFieldValueDao().getValuesByEventList(eventId)
    /**
     * 연표 카드에 얹을 사건 필드값 요약 (B-5).
     *
     * 목록에 실제로 그려지는 사건만 조회한다 — 사건 전량 조회는 세계관이 커질수록
     * 화면과 무관하게 비용이 늘어난다. `IN (:목록)`은 [com.novelcharacter.app.util.SqlInChunks]를
     * 지난다 — SQLite 변수 상한 방어이고, 한 덩이에 들어가면 쪼개지 않으므로 흔한 경우가 되레 싸다(R-54).
     */
    suspend fun getEventFieldSummaries(
        eventIds: List<Long>
    ): Map<Long, com.novelcharacter.app.util.CardFieldSummary.Summary> {
        if (eventIds.isEmpty()) return emptyMap()
        return try {
            val defs = db.fieldDefinitionDao()
                .getAllFieldsList(com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT)
            if (defs.isEmpty()) return emptyMap()

            val distinctIds = eventIds.distinct()
            val values = com.novelcharacter.app.util.SqlInChunks.flat(distinctIds) { chunk ->
                db.eventFieldValueDao().getValuesByEvents(chunk)
            }
            // 접기와 config JSON 파싱은 부른 쪽 스레드에서 돈다 — 호출부가 `lifecycleScope`라
            // 그대로 두면 사건이 방출될 때마다 정의마다의 파싱이 **주 스레드**에 얹힌다.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.novelcharacter.app.util.CardFieldSummary.build(
                    defs = defs,
                    rowsByEntity = values.groupBy({ it.eventId }, { it.fieldDefinitionId to it.value }),
                    entityIds = distinctIds
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // **취소는 실패가 아니다.** 목록이 다시 방출되면 앞선 조회는 취소되는데, 그것까지
            // 아래 갈래가 삼키면 낡은 호출이 살아나 빈 결과를 최신 요약 위에 덮어쓴다.
            throw e
        } catch (e: Exception) {
            // 카드 부가 정보다 — 실패해도 연표 자체는 그려야 한다. 다만 조용히 삼키지는 않는다.
            Log.e("TimelineViewModel", "Failed to load event field summaries", e)
            emptyMap()
        }
    }

    suspend fun getEventsByNovelList(novelId: Long) = timelineRepository.getEventsByNovelList(novelId)
    suspend fun getEventsByUniverseList(universeId: Long) = timelineRepository.getEventsByUniverseList(universeId)
    suspend fun getAllEventsList() = timelineRepository.getAllEventsList()
    suspend fun getAllEventNovelNames() = timelineRepository.getAllEventNovelNames()

    /**
     * 드래그 재정렬 저장 — 넘어오는 [visualOrder]는 **화면에 보이던 차례**다.
     * 저장 번호는 언제나 시간순 기준이므로 역순 화면이면 뒤에서부터 매긴다(B-47).
     *
     * **번호는 날짜 묶음마다 매기고, 묶음은 화면 밖 형제까지 읽어 다시 센다.**
     * `displayOrder`는 DAO 술어의 *같은 날짜 안 타이브레이크*일 뿐인데 종전에는 화면에 실린
     * 목록 전체에 전역 인덱스를 매겼다. 그 목록은 창(`visibleRange`)으로 잘려 있고 작품·
     * 캐릭터·검색 필터까지 걸릴 수 있는 **부분집합**이라, 손대지도 않은 같은 날짜의 사건들이
     * 옛 번호를 그대로 든 채 남아 번호가 겹치고 순서가 부정이 됐다.
     *
     * @return 저장에 성공했는가 — **고지는 이 답을 받은 뒤에 뜬다**(종전에는 결과를 안 기다렸다).
     *
     * **쓰기는 화면 수명이 아니라 [viewModelScope]에서 돈다**(콜드 검토 2026.08.21).
     * 부르는 쪽이 화면 스코프라, 순서 편집을 끄자마자 회전하면 ⓐ 쓰기가 시작조차 못 하는
     * 갈래가 있었고 ⓑ 이미 시작된 회차는 `withContext`가 던진 취소를 아래 `catch`가 삼켜
     * **DB에는 저장이 끝났는데 화면에는 "사건 순서 변경 실패"가 뜨고 작업 이력에도 실패로
     * 남았다.** 사용자는 저장된 순서를 다시 만든다. 같은 파일의 `insertEventField`가 이미
     * 쓰는 관례다 — 쓰기는 뷰모델이 들고, 화면은 결과를 받아 고지만 한다.
     */
    suspend fun updateDisplayOrders(visualOrder: List<TimelineEvent>): Boolean =
        viewModelScope.async {
            try {
                val ascending = TimelineDisplayOrder.canonicalReorder(visualOrder, isDescending())
                val dao = db.timelineDao()
                val updates = mutableListOf<TimelineEvent>()
                for ((key, visible) in ascending.groupBy { TimelineDisplayOrder.dateKeyOf(it) }) {
                    updates.addAll(
                        TimelineDisplayOrder.mergeDateGroup(
                            visible, dao.getEventsByDate(key.year, key.month, key.day)
                        )
                    )
                }
                if (updates.isNotEmpty()) dao.updateAll(updates)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // **취소는 실패가 아니다.** 삼키면 위 KDoc이 적은 그 거짓 고지가 난다.
                throw e
            } catch (e: Exception) {
                Log.e("TimelineViewModel", "Failed to update display orders", e)
                failEvent(R.string.result_event_order_failed, e)
                false
            }
        }.await()

    fun deleteEvent(event: TimelineEvent) = viewModelScope.launch {
        try {
            // 상태변화 정리를 저장소에 **넘겨서** 실행한다 (B-1).
            // 여기서 먼저 정리해 버리면 휴지통 스냅샷이 이미 지워진 출생·사망 이력을 담지 못해,
            // 사건만 되살아나고 캐릭터의 기록은 영영 사라진다. 저장소가 스냅샷 뒤·삭제 앞에,
            // 같은 트랜잭션 안에서 부른다(종전에는 서로 다른 자동커밋 단위였다).
            timelineRepository.deleteEvent(event) {
                if (event.eventType == TimelineEvent.TYPE_BIRTH ||
                    event.eventType == TimelineEvent.TYPE_DEATH
                ) {
                    cleanupStateChangesForDeletedEvent(event)
                }
            }
            refreshFilterCaches()
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_deleted)))
        } catch (e: Exception) {
            Log.e("TimelineViewModel", "Failed to delete event", e)
            failEvent(R.string.result_event_delete_failed, e)
        }
    }

    /**
     * 출생/사망 사건 삭제 전, 연결된 캐릭터의 상태변화 + 필드값을 정리.
     * [com.novelcharacter.app.util.EventStateChangeSync]의 역방향 처리.
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
