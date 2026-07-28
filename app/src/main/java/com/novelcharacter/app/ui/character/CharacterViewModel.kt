package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.novelcharacter.app.util.toastAndLogResult
import com.novelcharacter.app.data.repository.EventFieldValueMerge
import com.novelcharacter.app.util.EpochMemo
import com.novelcharacter.app.util.FieldFilterHelper
import com.novelcharacter.app.util.FieldValueSorter
import com.novelcharacter.app.util.FormulaEvaluator
import com.novelcharacter.app.util.SortComparators
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.BirthdayCharacterItem
import com.novelcharacter.app.util.BirthdayHelper
import com.novelcharacter.app.ui.timeline.EventEditDialogFragment.ShiftDirection
import com.novelcharacter.app.util.SemanticFieldSyncHelper
import com.novelcharacter.app.util.StandardYearSyncHelper
import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 캐릭터 목록 정렬 사양. [kind]는 CharacterListPreset.SORT_* 상수. */
data class CharacterSort(
    val kind: String = CharacterListPreset.SORT_MANUAL,
    val fieldKey: String? = null,
    val ascending: Boolean = true,
    val bodySizePartIndex: Int? = null
)

/** 정렬 UI가 제시할 정렬 가능 필드(세계관 간 (key,type) 병합 결과). */
data class SortableField(
    val key: String,
    val name: String,
    val type: String,
    val bodySizePartLabels: List<String> = emptyList()
)

class CharacterViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val novelRepository = app.novelRepository
    private val timelineRepository = app.timelineRepository
    private val universeRepository = app.universeRepository
    private val recentActivityDao = app.recentActivityDao
    private val semanticSyncHelper = SemanticFieldSyncHelper(characterRepository, universeRepository, novelRepository)

    val allCharacters: LiveData<List<Character>> = characterRepository.allCharacters
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    // 데이터 처리 결과 알림 채널 (관계·관계변화·상태변화·삭제 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    private val _currentNovelId = MutableLiveData<Long?>()

    // ===== 다가오는 생일 (반응형: DB 변경 시 자동 갱신) =====
    private val birthChangesLive: LiveData<List<CharacterStateChange>> =
        app.database.characterStateChangeDao().observeChangesWithDate(CharacterStateChange.KEY_BIRTH)

    private val _birthdayTrigger = MediatorLiveData<Unit>().apply {
        addSource(birthChangesLive) { value = Unit }
        addSource(_currentNovelId) { value = Unit }
    }

    val upcomingBirthdays: LiveData<List<BirthdayCharacterItem>> = _birthdayTrigger.switchMap {
        liveData {
            val changes = birthChangesLive.value ?: emptyList()
            val upcoming = BirthdayHelper.filterUpcoming(changes)
            if (upcoming.isEmpty()) {
                emit(emptyList())
                return@liveData
            }
            val charIds = upcoming.map { it.characterId }
            val characters = characterRepository.getCharactersByIds(charIds)
            val charMap = characters.associateBy { it.id }

            val novelId = _currentNovelId.value
            val items = upcoming.mapNotNull { b ->
                val char = charMap[b.characterId] ?: return@mapNotNull null
                // 작품 필터 적용
                if (novelId != null && novelId != -1L && char.novelId != novelId) return@mapNotNull null
                BirthdayCharacterItem(
                    character = char,
                    birthMonth = b.birthMonth,
                    birthDay = b.birthDay,
                    daysUntil = b.daysUntil
                )
            }
            emit(items)
        }
    }

    val filteredCharacters: LiveData<List<Character>> = _currentNovelId.switchMap { novelId ->
        if (novelId == null || novelId == -1L) {
            characterRepository.allCharacters
        } else {
            characterRepository.getCharactersByNovel(novelId)
        }
    }

    private val _searchQuery = MutableLiveData("")
    private val _searchTrigger = MediatorLiveData<Unit>().apply {
        addSource(_searchQuery) { value = Unit }
        addSource(_currentNovelId) { value = Unit }
    }

    /** 작품 스코프 + 텍스트 검색만 적용한 기반 목록 (필터·정렬 이전 단계). */
    private val baseCharacters: LiveData<List<Character>> = _searchTrigger.switchMap {
        val query = _searchQuery.value
        if (query.isNullOrBlank()) {
            filteredCharacters
        } else {
            val novelId = _currentNovelId.value
            if (novelId == null || novelId == -1L) {
                characterRepository.searchCharacters(query)
            } else {
                characterRepository.searchCharacters(query).map { characters ->
                    characters.filter { it.novelId == novelId }
                }
            }
        }
    }

    // ===== 필터 / 정렬 상태 (SharedPreferences 영속) =====
    private val prefs = app.getSharedPreferences("character_list_ui", android.content.Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _sortSpec = MutableLiveData(loadSavedSort())
    val sortSpec: LiveData<CharacterSort> = _sortSpec
    private val _fieldFilters = MutableLiveData(loadSavedFieldFilters())
    val fieldFilters: LiveData<List<FieldFilter>> = _fieldFilters
    private val _tagFilters = MutableLiveData(loadSavedTagFilters())
    val tagFilters: LiveData<Set<String>> = _tagFilters
    // 작품 필터 차원 — 전역 목록에서 작품(novelId)으로 거른다. 내비게이션 스코프(_currentNovelId)와 별개.
    private val _novelFilters = MutableLiveData(loadSavedNovelFilters())
    val novelFilters: LiveData<Set<Long>> = _novelFilters

    // ===== 재계산 캐시 무효화 (Room 테이블 변경 관측) =====
    // baseCharacters는 characters 테이블 LiveData만 관측하므로 필드값/태그/필드정의 편집을 감지하지 못한다
    // (예: saveAllFieldValues는 characters 로우를 건드리지 않음). 아래 에폭은 해당 테이블 변경을 관측해
    //  (1) 메모 캐시가 stale해지는 것을 막고 (2) 편집이 목록에 즉시 반영되도록 recompute를 재트리거한다.
    //  characters 변경/삭제·핀·재정렬은 baseCharacters가 이미 커버하므로 여기서 관측하지 않는다.
    private val fieldValueEpoch = AtomicInteger(0)  // character_field_values
    private val tagEpoch = AtomicInteger(0)         // character_tags
    private val structEpoch = AtomicInteger(0)      // field_definitions / novels / universes (필드정의·작품→세계관 귀속)

    /** 테이블 변경을 메인 스레드로 옮겨 recompute를 재트리거하는 토큰(onInvalidated가 오프메인이므로 postValue). */
    private val _tableInvalidation = MutableLiveData<Unit>()

    private val invalidationObserver = object : InvalidationTracker.Observer(
        "character_field_values", "character_tags", "field_definitions", "novels", "universes",
        "field_value_entries"
    ) {
        // Room 쿼리 실행자(오프메인)에서 호출된다. 에폭 bump는 원자적이라 즉시 안전, 재계산은 postValue로 메인 위임.
        override fun onInvalidated(tables: Set<String>) {
            val fieldDefChanged = "field_definitions" in tables
            // field_definitions 삭제는 FK 캐스케이드로 character_field_values를 지우지만 recursive_triggers=OFF라
            // 자식 테이블 무효화 트리거가 안 울린다 → 필드필터·정렬키 캐시가 stale되지 않게 fieldValueEpoch도 함께 올린다.
            // field_value_entries: 별칭 추가/병합은 값 테이블 변경 없이 exact 필터 결과를 바꾼다 (검토 A15).
            if ("character_field_values" in tables || fieldDefChanged ||
                "field_value_entries" in tables) fieldValueEpoch.incrementAndGet()
            if ("character_tags" in tables) tagEpoch.incrementAndGet()
            if (fieldDefChanged || "novels" in tables || "universes" in tables) structEpoch.incrementAndGet()
            _tableInvalidation.postValue(Unit)  // listTrigger → recompute()의 120ms 디바운스가 버스트를 흡수
        }
    }

    init {
        app.database.invalidationTracker.addObserver(invalidationObserver)
    }

    // 필드 필터 → 매칭 캐릭터 id 집합. (필터 리스트, character_field_values 에폭) 동일 시 재조회 없이 재사용.
    private val fieldFilterMemo = EpochMemo<List<FieldFilter>, Set<Long>> { filters ->
        FieldFilterHelper.applyFieldFilters(
            app.database.characterFieldValueDao(), app.database.fieldDefinitionDao(),
            app.database.fieldValueEntryDao(), filters)
    }
    // 태그 필터 → 매칭 캐릭터 id 집합(OR). 타깃 쿼리 사용(전체 스캔 제거). (태그 집합, character_tags 에폭) 키.
    private val tagFilterMemo = EpochMemo<Set<String>, Set<Long>> { tags ->
        val result = HashSet<Long>()
        // 900개씩 청크 — SQLite IN(...) 변수 상한(999) 방어(batchRemoveTags와 동일 정책).
        for (chunk in tags.toList().chunked(900)) {
            result.addAll(app.database.characterTagDao().getCharacterIdsByTags(chunk))
        }
        result
    }

    private val listTrigger = MediatorLiveData<Unit>().apply {
        addSource(baseCharacters) { value = Unit }
        addSource(_sortSpec) { value = Unit }
        addSource(_fieldFilters) { value = Unit }
        addSource(_tagFilters) { value = Unit }
        addSource(_novelFilters) { value = Unit }
        addSource(_tableInvalidation) { value = Unit }  // DB 필드값/태그/필드정의 변경 → 재계산
    }

    private var computeJob: Job? = null

    private val _searchResults = MediatorLiveData<List<Character>>().apply {
        addSource(listTrigger) { recompute() }
    }
    /** 최종 목록: 작품 스코프 + 검색 + 필터 + 정렬. Fragment가 관측한다. */
    val searchResults: LiveData<List<Character>> = _searchResults

    /** 필터/정렬 재계산 — 디바운스 후 백그라운드에서 필터·정렬을 적용해 emit. */
    private fun recompute() {
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            delay(120) // 연속 변경 시 마지막 상태만 계산
            val base = baseCharacters.value ?: emptyList()
            val filters = _fieldFilters.value ?: emptyList()
            val tags = _tagFilters.value ?: emptySet()
            val novelIds = effectiveNovelFilters()
            val sort = _sortSpec.value ?: CharacterSort()
            val result = withContext(Dispatchers.Default) {
                applyFiltersAndSort(base, filters, tags, novelIds, sort)
            }
            _searchResults.value = result
        }
    }

    override fun onCleared() {
        super.onCleared()
        // InvalidationTracker는 옵저버를 강한 참조로 보관하고 AppDatabase는 프로세스 싱글턴 → 반드시 해제(누수 방지).
        app.database.invalidationTracker.removeObserver(invalidationObserver)
        computeJob?.cancel()
    }

    fun setNovelFilter(novelId: Long?) {
        _currentNovelId.value = novelId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** 현재 검색어 반환 (배치 작업 후 갱신 트리거용) */
    fun getSearchQuery(): String = _searchQuery.value ?: ""

    /** LiveData 강제 재평가 트리거 (배치 작업 후 리스트 갱신용) */
    fun refreshList() {
        _searchQuery.value = _searchQuery.value
    }

    // ===== 필터 / 정렬 세터 =====

    fun setSortSpec(sort: CharacterSort) {
        _sortSpec.value = sort
        saveSort(sort)
    }

    fun setFieldFilters(filters: List<FieldFilter>) {
        _fieldFilters.value = filters
        prefs.edit().putString("field_filters_json", FieldFilterHelper.filtersToJson(filters)).apply()
    }

    fun addFieldFilter(filter: FieldFilter) {
        val cur = _fieldFilters.value?.toMutableList() ?: mutableListOf()
        cur.removeAll { it.fieldId == filter.fieldId }  // 같은 필드 기존 필터 교체
        cur.add(filter)
        setFieldFilters(cur)
    }

    fun removeFieldFilter(fieldId: Long) {
        setFieldFilters((_fieldFilters.value ?: emptyList()).filter { it.fieldId != fieldId })
    }

    fun setTagFilters(tags: Set<String>) {
        _tagFilters.value = tags
        prefs.edit().putString("tag_filters_json", gson.toJson(tags.toList())).apply()
    }

    fun setNovelFilters(novels: Set<Long>) {
        _novelFilters.value = novels
        prefs.edit().putString("novel_filters_json", gson.toJson(novels.toList())).apply()
    }

    /**
     * 실제 적용되는 작품 필터. 작품 필터는 설계상 **전역 목록 전용** 차원이므로, 이미 한 작품에 스코프된
     * 화면(_currentNovelId != -1)에선 무시한다. 이 가드가 없으면 전역에서 [A]로 필터한 상태로 작품 B 화면에
     * 들어갔을 때 `B in [A]`=false로 목록이 통째로 비고, 스코프 화면은 작품 섹션을 숨겨 필터를 끌 수도 없다.
     * 프리셋 적용 경로도 _novelFilters를 거쳐 recompute로 흐르므로 이 한 곳에서 함께 차단된다.
     */
    private fun effectiveNovelFilters(): Set<Long> {
        val scope = _currentNovelId.value
        val scoped = scope != null && scope != -1L
        return if (scoped) emptySet() else (_novelFilters.value ?: emptySet())
    }

    fun clearAllFilters() {
        setFieldFilters(emptyList())
        setTagFilters(emptySet())
        setNovelFilters(emptySet())
    }

    fun hasActiveFilters(): Boolean =
        !_fieldFilters.value.isNullOrEmpty() || !_tagFilters.value.isNullOrEmpty() ||
            effectiveNovelFilters().isNotEmpty()

    // ===== 필터/정렬 적용 (백그라운드) =====

    // 메모 캐시(fieldFilterMemo·tagFilterMemo·fieldSortCache)는 공유 가변 상태다. recompute는 이전 job을
    // 취소 후 새 job을 띄우는데(cancel-then-relaunch), 취소된 job이 정지점에서 아직 안 풀린 사이 새 job이
    // 같은 캐시(HashMap)를 건드리면 Dispatchers.Default(멀티스레드)에서 경쟁이 생긴다. 이 Mutex로 캐시 접근을
    // 직렬화한다. (락 보유 중 취소되면 다음 정지점에서 CancellationException → withLock이 락 해제, 데드락 없음.)
    private val cacheMutex = Mutex()

    private suspend fun applyFiltersAndSort(
        base: List<Character>,
        filters: List<FieldFilter>,
        tags: Set<String>,
        novelIds: Set<Long>,
        sort: CharacterSort
    ): List<Character> = cacheMutex.withLock {
        var chars = base
        if (novelIds.isNotEmpty()) {
            // 작품 필터(OR) — 선택 작품 중 하나에 속한 캐릭터. 전역 목록에서만 유의미하며 삭제된 id는 무해.
            // NO_NOVEL_ID sentinel은 "작품 미배정"(novelId null) 캐릭터를 선택한다.
            chars = chars.filter {
                com.novelcharacter.app.util.UnassignedFilter.matchesNovel(it.novelId, novelIds)
            }
        }
        if (filters.isNotEmpty()) {
            // 필드 필터 id셋: (필터, character_field_values 에폭) 캐시 — 검색어/정렬만 바뀌면 재조회 없음.
            // stale 잔여 id(삭제 캐릭터 등)는 살아있는 base와의 교집합으로 무해.
            val ids = fieldFilterMemo.get(filters, fieldValueEpoch.get())
            chars = chars.filter { it.id in ids }
        }
        if (tags.isNotEmpty()) {
            // 태그 필터(OR): (태그, character_tags 에폭) 캐시 + 타깃 쿼리(전체 스캔 제거).
            val taggedIds = tagFilterMemo.get(tags, tagEpoch.get())
            chars = chars.filter { it.id in taggedIds }
        }
        sortCharacters(chars, sort)
    }

    private suspend fun sortCharacters(chars: List<Character>, sort: CharacterSort): List<Character> {
        return when (sort.kind) {
            CharacterListPreset.SORT_NAME ->
                chars.sortedWithDir(sort.ascending) { it.name.lowercase() }
            CharacterListPreset.SORT_CREATED ->
                chars.sortedWithDir(sort.ascending) { it.createdAt }
            CharacterListPreset.SORT_RECENT ->
                chars.sortedWithDir(sort.ascending) { it.updatedAt }
            CharacterListPreset.SORT_FIELD ->
                sortByField(chars, sort.fieldKey, sort.ascending, sort.bodySizePartIndex)
            else ->
                // SORT_MANUAL: 핀 최상단 + displayOrder + 이름 (검색 결과도 동일 순서로 정규화)
                chars.sortedWith(
                    compareByDescending<Character> { it.isPinned }
                        .thenBy { it.displayOrder }
                        .thenBy { it.name.lowercase() }
                )
        }
    }

    private fun <R : Comparable<R>> List<Character>.sortedWithDir(
        ascending: Boolean, selector: (Character) -> R
    ): List<Character> = if (ascending) sortedBy(selector) else sortedByDescending(selector)

    /**
     * 커스텀 필드값 정렬. 값 없음/파싱불가는 방향과 무관하게 최후순.
     *
     * 정렬키(DB 조회 + CALCULATED 수식 평가)는 per-id로 [fieldSortCache]에 증분 저장한다. 정체성 키
     * =(fieldKey, partIndex, 작품 스코프, character_field_values 에폭, structEpoch)가 같으면 방향 반전·
     * 검색어·필터 변경에서 키를 재사용(재조회·재평가 0). 후보 중 캐시에 없는 **살아있는 id**는 그 자리에서
     * 계산해 채우므로(도메인 완전성), 검색 해제·필터 제거·스코프 확대·복원으로 보이는 집합이 커져도 새 id가
     * null 키로 최후순에 처박히지 않는다.
     */
    private suspend fun sortByField(
        chars: List<Character>, fieldKey: String?, ascending: Boolean, partIndex: Int?
    ): List<Character> {
        if (fieldKey == null || chars.isEmpty()) return chars

        val scopeSig = _currentNovelId.value ?: -1L
        val fvE = fieldValueEpoch.get()
        val stE = structEpoch.get()

        var cache = fieldSortCache
        if (cache == null || cache.fieldKey != fieldKey || cache.partIndex != partIndex ||
            cache.scopeSig != scopeSig || cache.fvEpoch != fvE || cache.structEpoch != stE
        ) {
            val fieldDefs = getCharacterFieldsForKey(fieldKey)
            if (fieldDefs.isEmpty()) { fieldSortCache = null; return chars }  // 정렬 필드 없음 → base 순서 유지
            val useNumeric = fieldDefs.values.any { FieldValueSorter.isNumericSortType(it.type) }
            val allCalc = fieldDefs.values.all { it.type == "CALCULATED" }
            cache = FieldSortKeyCache(fieldKey, partIndex, scopeSig, fvE, stE, useNumeric, allCalc, fieldDefs)
            fieldSortCache = cache
        }

        // 캐시에 없는 후보 id만 계산(증분). 값 없음은 null로 명시 저장하고 computed에 등록 → 재계산 방지·null 구분.
        // allCalc 키는 c.novelId→세계관→수식에 의존한다. 필드값을 안 건드리는 작품 변경(미배정/무세계관 작품으로의
        // 일괄 이동)은 fvEpoch를 올리지 않으므로, 캐시 계산 시점과 novelId가 달라진 id도 재계산 대상에 포함한다.
        val missing = chars.filter { c ->
            c.id !in cache.computed || (cache.allCalc && cache.novelIdAtCompute[c.id] != c.novelId)
        }
        if (missing.isNotEmpty()) {
            if (cache.useNumeric) {
                val km: Map<Long, Double?> =
                    if (cache.allCalc) computeCalculatedValues(missing, fieldKey)
                    else computeStoredNumeric(missing, cache.fieldDefs, partIndex)
                for (c in missing) {
                    cache.numeric[c.id] = km[c.id]
                    if (cache.allCalc) cache.novelIdAtCompute[c.id] = c.novelId
                    cache.computed.add(c.id)
                }
            } else {
                val stored = characterRepository.getValuesForCharacters(missing.map { it.id })
                    .filter { it.fieldDefinitionId in cache.fieldDefs.keys }
                    .associateBy { it.characterId }
                for (c in missing) {
                    val fv = stored[c.id]
                    val ownerFd = fv?.let { cache.fieldDefs[it.fieldDefinitionId] }
                    cache.text[c.id] =
                        if (fv != null && ownerFd != null) FieldValueSorter.textValue(ownerFd, fv.value) else null
                    cache.computed.add(c.id)
                }
            }
        }

        return if (cache.useNumeric) chars.sortedWith(nullsLastComparator(ascending) { cache.numeric[it.id] })
        else chars.sortedWith(nullsLastComparator(ascending) { cache.text[it.id] })
    }

    /** 필드 정렬키 증분 캐시. 정체성(fieldKey/partIndex/scope/fv·struct 에폭)이 바뀌면 통째로 교체된다. */
    private class FieldSortKeyCache(
        val fieldKey: String,
        val partIndex: Int?,
        val scopeSig: Long,
        val fvEpoch: Int,
        val structEpoch: Int,
        val useNumeric: Boolean,
        val allCalc: Boolean,
        val fieldDefs: Map<Long, FieldDefinition>
    ) {
        val numeric = HashMap<Long, Double?>()
        val text = HashMap<Long, String?>()
        val computed = HashSet<Long>()
        /** allCalc 전용 — 각 id의 키를 계산할 때 쓴 novelId. 이후 novelId가 바뀌면 그 id만 재계산한다. */
        val novelIdAtCompute = HashMap<Long, Long?>()
    }
    private var fieldSortCache: FieldSortKeyCache? = null

    /** 값 있는 항목은 방향대로, null(값 없음)은 항상 뒤로. 동값은 이름 오름차순 안정화. (SortComparators 위임 — 테스트 가능) */
    private fun <R : Comparable<R>> nullsLastComparator(
        ascending: Boolean, selector: (Character) -> R?
    ): Comparator<Character> = SortComparators.nullsLast(ascending, { it.name.lowercase() }, selector)

    private suspend fun computeStoredNumeric(
        chars: List<Character>, fieldDefs: Map<Long, FieldDefinition>, partIndex: Int?
    ): Map<Long, Double?> {
        val stored = characterRepository.getValuesForCharacters(chars.map { it.id })
            .filter { it.fieldDefinitionId in fieldDefs.keys }
            .associateBy { it.characterId }
        return chars.associate { c ->
            val fv = stored[c.id]
            val ownerFd = fv?.let { fieldDefs[it.fieldDefinitionId] }
            c.id to (if (fv != null && ownerFd != null) FieldValueSorter.numericValue(ownerFd, fv.value, partIndex) else null)
        }
    }

    /** CALCULATED 필드: 캐릭터별 세계관 필드맵으로 라이브 평가(ExcelExporter/Stats와 동일 방식). */
    private suspend fun computeCalculatedValues(chars: List<Character>, fieldKey: String): Map<Long, Double?> {
        val valuesByChar = characterRepository.getValuesForCharacters(chars.map { it.id }).groupBy { it.characterId }
        val universeFieldsCache = mutableMapOf<Long, List<FieldDefinition>>()
        val novelUniverseCache = mutableMapOf<Long, Long?>()
        val result = mutableMapOf<Long, Double?>()
        for (c in chars) {
            val novelId = c.novelId
            val uid = if (novelId == null) null
                else novelUniverseCache.getOrPut(novelId) { novelRepository.getNovelById(novelId)?.universeId }
            if (uid == null) { result[c.id] = null; continue }
            val uFields = universeFieldsCache.getOrPut(uid) { universeRepository.getFieldsByUniverseList(uid) }
            val calcFd = uFields.firstOrNull { it.key == fieldKey && it.type == "CALCULATED" }
            if (calcFd == null) { result[c.id] = null; continue }
            val formula = try { org.json.JSONObject(calcFd.config).optString("formula", "") } catch (_: Exception) { "" }
            if (formula.isBlank()) { result[c.id] = null; continue }
            val idToKey = uFields.associate { it.id to it.key }
            val fieldMap = valuesByChar[c.id].orEmpty()
                .mapNotNull { fv -> idToKey[fv.fieldDefinitionId]?.let { k -> k to fv.value } }
                .toMap()
            val v = try { FormulaEvaluator(fieldMap, uFields).evaluate(formula) } catch (_: Exception) { Double.NaN }
            result[c.id] = if (v.isFinite()) v else null
        }
        return result
    }

    /** 정렬 대상 fieldKey에 해당하는 캐릭터 필드정의(세계관별) 수집. */
    private suspend fun getCharacterFieldsForKey(fieldKey: String): Map<Long, FieldDefinition> {
        val result = mutableMapOf<Long, FieldDefinition>()
        for (uid in scopedUniverseIds()) {
            universeRepository.getFieldsByUniverseList(uid).filter { it.key == fieldKey }.forEach { result[it.id] = it }
        }
        return result
    }

    private suspend fun scopedUniverseIds(): List<Long> {
        val novelId = _currentNovelId.value
        return if (novelId != null && novelId != -1L) {
            listOfNotNull(novelRepository.getNovelById(novelId)?.universeId)
        } else {
            universeRepository.getAllUniversesList().map { it.id }
        }
    }

    // ===== 필터/정렬 UI 제공 데이터 =====

    /** 현재 스코프의 정렬 가능 필드 (전체 스코프는 (key,type) 병합). CALCULATED 포함. */
    suspend fun getSortableFields(): List<SortableField> {
        val seen = LinkedHashMap<String, SortableField>()
        for (uid in scopedUniverseIds()) {
            for (fd in universeRepository.getFieldsByUniverseList(uid)) {
                val mk = "${fd.key}|${fd.type}"
                if (mk !in seen) {
                    val parts = if (fd.type == "BODY_SIZE") {
                        val sic = StructuredInputConfig.fromConfig(fd.config)
                        if (sic.enabled && sic.parts.isNotEmpty()) sic.parts.map { it.label }
                        else listOf("가슴(B)", "허리(W)", "엉덩이(H)")
                    } else emptyList()
                    seen[mk] = SortableField(fd.key, fd.name, fd.type, parts)
                }
            }
        }
        return seen.values.toList()
    }

    /** 필터 UI용 세계관 목록 (작품 선택 시 그 세계관만, 전체면 모두). */
    suspend fun getScopedUniverses(): List<Universe> {
        val novelId = _currentNovelId.value
        return if (novelId != null && novelId != -1L) {
            val uid = novelRepository.getNovelById(novelId)?.universeId
            if (uid != null) listOfNotNull(universeRepository.getUniverseById(uid)) else emptyList()
        } else {
            universeRepository.getAllUniversesList()
        }
    }

    /** 필터 UI용 필드 목록 (CALCULATED 제외 — DB 값이 없어 필터 불가). */
    suspend fun getFilterableFields(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId).filter { it.type != "CALCULATED" }

    /** 필터 UI용 특정 필드의 유니크 값 목록 — 토큰화·trim·별칭 접기 (GlobalSearch와 동일 규칙, 검토 A16). */
    suspend fun getFieldValues(fieldDefId: Long): List<String> {
        val fd = app.database.fieldDefinitionDao().getFieldById(fieldDefId)
        val rows = app.database.characterFieldValueDao().getValuesByFieldDef(fieldDefId)
        if (fd == null) {
            return rows.filter { it.value.isNotBlank() }.map { it.value.trim() }.distinct().sorted()
        }
        val resolver = com.novelcharacter.app.util.FieldValueResolver(
            app.database.fieldValueEntryDao().getByField(fieldDefId))
        return rows.flatMap { com.novelcharacter.app.util.FieldValueTokenizer.tokenize(fd, it.value) }
            .map { resolver.canonical(it) }
            .distinct()
            .sorted()
    }

    // ===== 프리셋 =====

    val presets: LiveData<List<CharacterListPreset>> = app.characterListPresetRepository.allPresets

    fun saveAsPreset(name: String) = viewModelScope.launch {
        try {
            app.characterListPresetRepository.insertPreset(currentPreset(name))
            reportResult(_result, OpResult.success(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_saved, name)))
        } catch (e: IllegalStateException) {
            reportResult(_result, OpResult.failure(OpResult.CAT_PRESET,
                app.getString(R.string.search_preset_limit_reached, CharacterListPreset.MAX_PRESETS)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to save preset", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_save_failed), e.message))
        }
    }

    fun applyPreset(preset: CharacterListPreset) {
        val tags = try {
            (gson.fromJson(preset.tagsJson, Array<String>::class.java) ?: arrayOf()).toSet()
        } catch (_: Exception) { emptySet() }
        setTagFilters(tags)
        setNovelFilters(parseNovelIds(preset.novelIdsJson))
        setFieldFilters(FieldFilterHelper.filtersFromJson(preset.fieldFiltersJson))
        setSortSpec(CharacterSort(preset.sortKind, preset.sortFieldKey, preset.sortAscending, preset.bodySizePartIndex))
    }

    /** 이름만 변경 — 저장된 필터/정렬 내용은 유지. */
    fun renamePreset(preset: CharacterListPreset, newName: String) = viewModelScope.launch {
        try {
            app.characterListPresetRepository.updatePreset(preset.copy(name = newName))
            reportResult(_result, OpResult.success(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_updated, newName)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to rename preset", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_update_failed), e.message))
        }
    }

    /** 현재 필터/정렬 상태로 프리셋 내용을 덮어쓰기 — 이름 유지. */
    fun overwritePreset(preset: CharacterListPreset) = viewModelScope.launch {
        try {
            app.characterListPresetRepository.updatePreset(
                currentPreset(preset.name).copy(id = preset.id, isDefault = preset.isDefault)
            )
            reportResult(_result, OpResult.success(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_updated, preset.name)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to overwrite preset", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_update_failed), e.message))
        }
    }

    fun deletePreset(id: Long, name: String) = viewModelScope.launch {
        try {
            app.characterListPresetRepository.deletePreset(id)
            reportResult(_result, OpResult.success(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_deleted, name)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete preset", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_PRESET,
                app.getString(R.string.result_preset_delete_failed), e.message))
        }
    }

    private fun currentPreset(name: String): CharacterListPreset {
        val sort = _sortSpec.value ?: CharacterSort()
        return CharacterListPreset(
            name = name,
            tagsJson = gson.toJson((_tagFilters.value ?: emptySet()).toList()),
            novelIdsJson = gson.toJson((_novelFilters.value ?: emptySet()).toList()),
            fieldFiltersJson = FieldFilterHelper.filtersToJson(_fieldFilters.value ?: emptyList()),
            sortKind = sort.kind,
            sortFieldKey = sort.fieldKey,
            sortAscending = sort.ascending,
            bodySizePartIndex = sort.bodySizePartIndex
        )
    }

    // ===== 상태 영속화 =====

    private fun loadSavedSort(): CharacterSort {
        val kind = prefs.getString("sort_kind", CharacterListPreset.SORT_MANUAL) ?: CharacterListPreset.SORT_MANUAL
        return CharacterSort(
            kind = kind,
            fieldKey = prefs.getString("sort_field_key", null),
            ascending = prefs.getBoolean("sort_ascending", true),
            bodySizePartIndex = prefs.getInt("sort_body_part", -1).takeIf { it >= 0 }
        )
    }

    private fun saveSort(sort: CharacterSort) {
        prefs.edit()
            .putString("sort_kind", sort.kind)
            .putString("sort_field_key", sort.fieldKey)
            .putBoolean("sort_ascending", sort.ascending)
            .putInt("sort_body_part", sort.bodySizePartIndex ?: -1)
            .apply()
    }

    private fun loadSavedFieldFilters(): List<FieldFilter> =
        FieldFilterHelper.filtersFromJson(prefs.getString("field_filters_json", "{}") ?: "{}")

    private fun loadSavedTagFilters(): Set<String> {
        val json = prefs.getString("tag_filters_json", null) ?: return emptySet()
        return try {
            (gson.fromJson(json, Array<String>::class.java) ?: arrayOf()).toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun loadSavedNovelFilters(): Set<Long> = parseNovelIds(prefs.getString("novel_filters_json", null))

    /** JSON 배열 문자열 → 작품 id 집합. 손상/누락 시 빈 집합. */
    private fun parseNovelIds(json: String?): Set<Long> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            (gson.fromJson(json, LongArray::class.java) ?: LongArray(0)).toSet()
        } catch (_: Exception) { emptySet() }
    }

    fun getCharacterById(id: Long): LiveData<Character?> = characterRepository.getCharacterByIdLive(id)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsForCharacter(characterId)

    suspend fun getEventsForCharacterSuspend(characterId: Long): List<TimelineEvent> =
        timelineRepository.getEventsForCharacterList(characterId)

    suspend fun getCharacterByIdSuspend(id: Long): Character? = characterRepository.getCharacterById(id)

    /** 삭제 확인 다이얼로그용 — 함께 영구 삭제되는 데이터 범위 집계 */
    data class CharacterDeleteImpact(
        val relationships: Int,
        val stateChanges: Int,
        val factionMemberships: Int,
        val images: Int
    )

    suspend fun getCharacterDeleteImpact(character: Character): CharacterDeleteImpact {
        val relationships = characterRepository.getRelationshipsForCharacterList(character.id).size
        val stateChanges = characterRepository.getChangesByCharacterList(character.id).size
        val memberships = app.database.factionMembershipDao().getMembershipsByCharacterList(character.id).size
        val images = try { org.json.JSONArray(character.imagePaths).length() } catch (_: Exception) { 0 }
        return CharacterDeleteImpact(relationships, stateChanges, memberships, images)
    }

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    suspend fun getNovelById(id: Long): Novel? = novelRepository.getNovelById(id)

    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()

    fun insertCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.insertCharacter(character)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert character", e)
        }
    }

    fun updateCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.updateCharacter(character)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update character", e)
        }
    }

    fun deleteCharacter(character: Character) = viewModelScope.launch {
        try {
            characterRepository.deleteCharacter(character)
            // 삭제 후 switchMap 재평가를 강제하여 캐시된 LiveData 갱신
            _searchQuery.value = _searchQuery.value
            reportResult(_result, OpResult.success(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_character_deleted, character.name)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete character", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_character_delete_failed), e.message))
        }
    }

    // ===== FieldDefinition =====
    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)

    /** 세계관 밖 정의를 가리키는 보관 값을 화면에 드러내기 위한 조회 (N2) */
    suspend fun getFieldsByIds(ids: List<Long>): List<FieldDefinition> =
        universeRepository.getFieldsByIds(ids)

    // ===== CharacterFieldValue =====
    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        characterRepository.getValuesByCharacterList(characterId)

    suspend fun getFieldValuesForNovel(novelId: Long, fieldDefId: Long): List<String> =
        characterRepository.getFieldValuesForNovel(novelId, fieldDefId)

    suspend fun getFieldValuesForUniverse(universeId: Long, fieldDefId: Long): List<String> =
        characterRepository.getFieldValuesForUniverse(universeId, fieldDefId)

    /** 여러 캐릭터의 전체 필드값 일괄 조회 (백분위 배치 계산용) */
    suspend fun getValuesForCharacters(characterIds: List<Long>): List<CharacterFieldValue> =
        characterRepository.getValuesForCharacters(characterIds)

    /** 세계관 전체 필드값 일괄 조회 (자동완성 배치 로드용) */
    suspend fun getAllFieldValuesForUniverse(universeId: Long): List<CharacterFieldValue> =
        characterRepository.getAllFieldValuesForUniverse(universeId)

    // ===== AI 필드 추천 컨텍스트 (CharacterFieldAiSuggester) =====

    /**
     * 첨부 이미지 경로들의 라이브러리 태그 합집합 — 경로는 저장 규약대로 absolutePath 그대로 조회.
     * 조회 실패는 null — '태그 없음'(빈 목록)과 구별해 호출측이 결손을 고지한다 (변수 제어).
     */
    suspend fun getImageTagsForPaths(paths: List<String>): List<String>? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (paths.isEmpty()) return@withContext emptyList()
                val metaIds = paths.chunked(900)
                    .flatMap { db.imageMetaDao().getByPaths(it) }
                    .map { it.id }
                if (metaIds.isEmpty()) return@withContext emptyList()
                metaIds.chunked(900)
                    .flatMap { db.imageTagDao().getDistinctTagsForImages(it) }
                    .distinct()
            } catch (e: Exception) {
                Log.e("CharacterViewModel", "Failed to load image tags for AI context", e)
                null
            }
        }

    /** 활성(미탈퇴) 소속 세력명 목록. 조회 실패는 null (빈 목록과 구별 — 결손 고지용) */
    suspend fun getFactionNamesForCharacter(characterId: Long): List<String>? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val activeFactionIds = db.factionMembershipDao().getMembershipsByCharacterList(characterId)
                    .filter { it.leaveType == null }
                    .map { it.factionId }
                    .distinct()
                if (activeFactionIds.isEmpty()) return@withContext emptyList()
                val nameById = activeFactionIds.chunked(900)
                    .flatMap { db.factionDao().getByIds(it) }
                    .associate { it.id to it.name }
                activeFactionIds.mapNotNull { nameById[it] }
            } catch (e: Exception) {
                Log.e("CharacterViewModel", "Failed to load factions for AI context", e)
                null
            }
        }

    /** "상대이름 – 관계유형" 요약 목록. 조회 실패는 null (빈 목록과 구별 — 결손 고지용) */
    suspend fun getRelationshipSummariesForCharacter(characterId: Long): List<String>? =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val relationships = db.characterRelationshipDao().getRelationshipsForCharacterList(characterId)
                if (relationships.isEmpty()) return@withContext emptyList()
                val otherIds = relationships
                    .map { if (it.characterId1 == characterId) it.characterId2 else it.characterId1 }
                    .distinct()
                val nameById = otherIds.chunked(900)
                    .flatMap { db.characterDao().getCharactersByIds(it) }
                    .associate { it.id to it.displayName }
                relationships.mapNotNull { rel ->
                    val otherId = if (rel.characterId1 == characterId) rel.characterId2 else rel.characterId1
                    val otherName = nameById[otherId] ?: return@mapNotNull null
                    "$otherName – ${rel.relationshipType}"
                }
            } catch (e: Exception) {
                Log.e("CharacterViewModel", "Failed to load relationships for AI context", e)
                null
            }
        }

    // ===== AI 필드 추천 실행 (AiFieldSuggestSheet → 여기서 수행) =====
    // 실행을 VM 스코프에 두는 이유: 뷰 수명 스코프에서는 회전이 요청을 취소해
    // 유료 응답이 폐기되고, 파괴된 액티비티의 진행 다이얼로그 dismiss가 크래시를 냈다.
    // VM은 회전을 생존하므로 요청·결과가 화면 재생성을 넘어 전달된다.

    /** AI 추천 1회 실행분 — 결과 표시(검토 다이얼로그)에 필요한 전부 */
    data class AiSuggestRun(
        val targets: List<com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec>,
        val singleMode: Boolean,
        val outcome: com.novelcharacter.app.ai.CharacterFieldAiSuggester.SuggestOutcome,
        /**
         * 요청 시점의 대상 캐릭터 id (미저장 신규는 -1) — A-3 오적용 차단의 검출 축.
         * 보충(랜덤) 탭은 요청 도중 캐릭터가 바뀔 수 있어, 결과 표시 전에 지금 화면의
         * 캐릭터와 비교해 다르면 적용 대신 선택지(돌아가 적용/버리기)를 연다.
         * 편집 화면은 캐릭터가 화면 수명 동안 고정이라 언제나 일치한다(동작 불변).
         */
        val targetCharacterId: Long = -1L
    )

    val aiSuggestRunning = MutableLiveData(false)
    val aiSuggestResult = MutableLiveData<AiSuggestRun?>()
    fun clearAiSuggestResult() { aiSuggestResult.value = null }

    /**
     * AI 필드 추천 실행. 이미 실행 중이면 false를 반환한다 — 호출측이 반드시 사용자에게
     * 알릴 것(무통보 무시 금지). 결과는 [aiSuggestResult]로 전달되며, 소비(clear)는
     * 결과 다이얼로그의 액션 시점에 하므로 검토 중 회전에도 결과가 생존한다.
     */
    fun runAiSuggest(
        aiContext: com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext,
        targets: List<com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec>,
        singleMode: Boolean,
        /**
         * 근거 강도 하한을 적용할지. **지시를 달아 다시 요청한 경우는 false** —
         * 사용자가 이 필드를 콕 집어 다시 물은 것이므로, 설정이 그 답을 가로채면
         * 돈만 내고 아무것도 못 받는다(자율성: 구체적 요청이 일반 설정을 이긴다).
         */
        applyConfidenceFilter: Boolean = true,
        /** 요청 대상 캐릭터 id — [AiSuggestRun.targetCharacterId]로 그대로 실린다 */
        targetCharacterId: Long = -1L
    ): Boolean {
        if (aiSuggestRunning.value == true) return false
        aiSuggestRunning.value = true
        viewModelScope.launch {
            try {
                val suggester = com.novelcharacter.app.ai.CharacterFieldAiSuggester(
                    com.novelcharacter.app.ai.AiService(getApplication())
                )
                val settings = com.novelcharacter.app.ai.AiPromptSettings(getApplication())
                val floor = if (applyConfidenceFilter) settings.minConfidence else null
                val outcome = suggester.suggest(
                    aiContext, withFieldUsage(targets), floor, settings.creativity
                ) { failure ->
                    com.novelcharacter.app.ai.AiErrorMessages.of(getApplication(), failure)
                }
                aiSuggestResult.value = AiSuggestRun(targets, singleMode, outcome, targetCharacterId)
            } finally {
                aiSuggestRunning.value = false
            }
        }
        return true
    }

    /**
     * 대상 스펙에 값 라이브러리의 **기존 사용값**을 싣는다 — 모델이 이 작품의 표기 기조를
     * 모른 채 어긋난 값을 만들어 내는 문제(일관성)의 교정 경로다.
     *
     * 캐릭터 전체를 훑지 않고 `field_value_entries`를 필드 단위로 1쿼리 배치 조회한다:
     * 라이브러리가 이미 필드별 중복 없는 카탈로그이고 사용 빈도까지 들고 있어,
     * 캐릭터 수백 명에서도 비용이 값 종수에만 비례한다(받쳐주는 확장성).
     * 조회 실패는 추천 자체를 막지 않는다 — 용례 없이 종전대로 진행한다.
     */
    private suspend fun withFieldUsage(
        targets: List<com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec>
    ): List<com.novelcharacter.app.ai.CharacterFieldAiSuggester.FieldSpec> {
        val ids = targets.filter { it.libraryEligible && it.fieldId > 0 }.map { it.fieldId }
        if (ids.isEmpty()) return targets
        val byField = try {
            app.fieldValueLibraryRepository.entriesForFields(ids)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to load field value library for AI suggest", e)
            return targets
        }
        // 예시 개수는 사용자 설정. 0이어도 조회를 건너뛰지 않는다 — 별칭 접기·restricted 검증은
        // 토큰 절약 설정이 끌 수 있는 것이 아니다(정확성).
        val exampleLimit = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).usageExampleCount
        return targets.map { spec ->
            val entries = byField[spec.fieldId] ?: return@map spec
            com.novelcharacter.app.ai.CharacterFieldAiSuggester.withLibraryUsage(spec, entries, exampleLimit)
        }
    }

    // ===== 서술형 필드 AI 작성 (NarrativeWriteSheet → 여기서 수행) =====
    // 추천 경로와 같은 이유로 VM 스코프에서 실행한다 — 긴 글은 응답이 더 오래 걸려
    // 회전으로 유료 응답이 폐기될 확률이 오히려 높다.

    /** 서술형 작성 1회 실행분 — 결과 검토에 필요한 전부 */
    data class AiNarrativeRun(
        val fieldId: Long,
        val fieldName: String,
        val mode: com.novelcharacter.app.ai.NarrativeFieldAiWriter.Mode,
        /** 실행 시점의 원문 — 이어쓰기 결과를 이어 붙이고, 원문과 나란히 보여주는 데 쓴다. */
        val originalValue: String,
        val outcome: com.novelcharacter.app.ai.NarrativeFieldAiWriter.WriteOutcome
    )

    val aiNarrativeRunning = MutableLiveData(false)
    val aiNarrativeResult = MutableLiveData<AiNarrativeRun?>()
    fun clearAiNarrativeResult() { aiNarrativeResult.value = null }

    /**
     * 서술형 작성 실행. 이미 실행 중이면 false — 호출측이 반드시 사용자에게 알린다.
     * 결과 소비(clear)는 검토 다이얼로그의 액션 시점이라 검토 중 회전에도 결과가 생존한다.
     */
    fun runAiNarrative(
        aiContext: com.novelcharacter.app.ai.CharacterFieldAiSuggester.CharacterAiContext,
        fieldId: Long,
        characterId: Long,
        spec: com.novelcharacter.app.ai.NarrativeFieldAiWriter.FieldSpec,
        mode: com.novelcharacter.app.ai.NarrativeFieldAiWriter.Mode,
        length: com.novelcharacter.app.ai.NarrativeFieldAiWriter.Length,
        variants: Int
    ): Boolean {
        if (aiNarrativeRunning.value == true) return false
        aiNarrativeRunning.value = true
        viewModelScope.launch {
            try {
                val writer = com.novelcharacter.app.ai.NarrativeFieldAiWriter(
                    com.novelcharacter.app.ai.AiService(getApplication())
                )
                val enriched = withStyleSamples(spec, fieldId, characterId)
                val creativity = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).creativity
                val outcome = writer.write(aiContext, enriched, mode, length, variants, creativity) { failure ->
                    com.novelcharacter.app.ai.AiErrorMessages.of(getApplication(), failure)
                }
                aiNarrativeResult.value =
                    AiNarrativeRun(fieldId, spec.name, mode, spec.currentValue, outcome)
            } finally {
                aiNarrativeRunning.value = false
            }
        }
        return true
    }

    /**
     * 서술형 스펙에 **문체 참고**를 싣는다 — 짧은 값 추천의 '기존 사용값'에 대응하는 서술형판.
     * 값 목록으로는 어투·시점·문장 길이 같은 문체를 전할 수 없어서 같은 필드에 다른 캐릭터가
     * 쓴 글을 소수 싣는다.
     *
     * 자기 자신은 제외한다 — 원문은 이미 [원문] 절에 실리므로 참고로 또 넣으면 토큰만 쓰고
     * 후보가 원문 쪽으로 쏠린다. 개수는 사용자 설정이며 0이면 조회 자체를 건너뛴다
     * (여기서는 정확성에 걸린 것이 없어 짧은 값 경로와 달리 완전히 끌 수 있다).
     * 조회 실패는 작성 자체를 막지 않는다.
     */
    private suspend fun withStyleSamples(
        spec: com.novelcharacter.app.ai.NarrativeFieldAiWriter.FieldSpec,
        fieldId: Long,
        characterId: Long
    ): com.novelcharacter.app.ai.NarrativeFieldAiWriter.FieldSpec {
        val limit = com.novelcharacter.app.ai.AiPromptSettings(getApplication()).styleSampleCount
        if (limit <= 0 || fieldId <= 0) return spec
        val values = try {
            app.database.characterFieldValueDao().getValuesByFieldDef(fieldId)
                .filter { it.characterId != characterId && it.value.isNotBlank() }
                .map { it.value }
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to load style samples for AI narrative", e)
            return spec
        }
        return spec.copy(
            styleSamples = com.novelcharacter.app.ai.NarrativeFieldAiWriter.selectStyleSamples(values, limit)
        )
    }

    /** restricted 입력 모드 위반 검출 — (필드, 위반 토큰) 목록 (검토 A8: 코디네이터 공통 가드용) */
    suspend fun findRestrictedViolations(
        values: List<CharacterFieldValue>
    ): List<Pair<FieldDefinition, List<String>>> {
        val result = mutableListOf<Pair<FieldDefinition, List<String>>>()
        for (v in values) {
            if (v.value.isBlank()) continue
            val fd = app.database.fieldDefinitionDao().getFieldById(v.fieldDefinitionId) ?: continue
            if (!com.novelcharacter.app.util.FieldValueTokenizer.supportsLibrary(fd)) continue
            if (!com.novelcharacter.app.data.model.FieldValueLibraryConfig.fromConfig(fd.config).isRestricted) continue
            val entries = app.database.fieldValueEntryDao().getByField(fd.id)
            val violations = com.novelcharacter.app.data.repository.FieldValueLibraryRepository
                .validateRestricted(fd, v.value, entries)
            if (violations.isNotEmpty()) result.add(fd to violations)
        }
        return result
    }

    /** restricted 위반 토큰을 라이브러리에 수동 등재 — '추가하고 저장' 교정 경로 */
    suspend fun addRestrictedValuesToLibrary(violations: List<Pair<FieldDefinition, List<String>>>) {
        for ((fd, tokens) in violations) {
            for (token in tokens) {
                app.fieldValueLibraryRepository.addEntry(fd.id, token)
            }
        }
    }

    /** restricted 필드의 허용 값 미리보기 (다이얼로그 안내용) */
    suspend fun allowedValuesPreview(fieldDefId: Long, limit: Int = 10): List<String> =
        app.database.fieldValueEntryDao().getByField(fieldDefId).map { it.value }.take(limit)

    /** 값 라이브러리 제안 배치 조회 — 필드별 비숨김 엔트리 (usageCount 내림차순) */
    suspend fun getLibrarySuggestionsForUniverse(
        universeId: Long,
        entityType: String = com.novelcharacter.app.data.model.FieldDefinition.ENTITY_CHARACTER
    ): Map<Long, List<com.novelcharacter.app.data.model.FieldValueEntry>> =
        app.fieldValueLibraryRepository.suggestionsForUniverse(universeId, entityType)

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) {
        characterRepository.saveAllFieldValues(characterId, values)
        val universeId = getUniverseIdForCharacter(characterId)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(characterId, universeId, values)
        }
    }

    /** @return 폼이 커버하지 않아 보존된 필드값 개수 (N2 — 호출부가 고지에 쓴다) */
    suspend fun updateCharacterWithFields(
        character: Character,
        values: List<CharacterFieldValue>,
        coveredFieldDefinitionIds: Set<Long>? = null
    ): Int {
        val preserved = characterRepository.updateCharacterWithFields(
            character, values, coveredFieldDefinitionIds
        )
        val universeId = getUniverseIdForCharacter(character.id)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(character.id, universeId, values)
        }
        return preserved
    }

    private suspend fun getUniverseIdForCharacter(characterId: Long): Long? {
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        return novel.universeId
    }

    /** 작품 id → 세계관 id (없으면 null). 편집화면 세계관 이동 감지용. */
    suspend fun universeIdForNovel(novelId: Long?): Long? {
        val id = novelId ?: return null
        return novelRepository.getNovelById(id)?.universeId
    }

    /** 세계관 이동 시 유실될 필드값·세력 소속 수를 미리 센다(확인 다이얼로그·고지용, 파괴 없음). */
    suspend fun countCrossUniverseLoss(characterId: Long, newUniverseId: Long) =
        characterRepository.countCrossUniverseLoss(characterId, newUniverseId)

    /**
     * 편집화면에서 다른 세계관으로 이동 저장. 같은 이름 필드는 이관, 대응 없는 값·세력은 스냅샷(되돌리기) 후 제거.
     * 저장된 최종 값으로 시맨틱 필드 동기화까지 정규 경로로 수행한다.
     */
    suspend fun updateCharacterAcrossUniverse(
        character: Character,
        values: List<CharacterFieldValue>,
        newUniverseId: Long
    ): com.novelcharacter.app.data.repository.UniverseMoveCounts {
        val counts = characterRepository.updateCharacterAcrossUniverse(character, values, newUniverseId)
        val stored = characterRepository.getValuesByCharacterList(character.id)
        semanticSyncHelper.syncFieldToStateChange(character.id, newUniverseId, stored)
        return counts
    }

    // ===== 나이-출생연도 불일치 감지 =====

    data class AgeLinkageConflict(
        val inputAge: Int,
        val inputBirthYear: Int,
        val currentStdYear: Int,
        val expectedAge: Int,
        val suggestedBirthYear: Int,
        val suggestedStdYear: Int,
        val affectedCharacterCount: Int,
        val ageFieldId: Long,
        val birthYearFieldId: Long,
        val novelId: Long
    )

    /**
     * 저장 전 나이-출생연도 불일치를 감지한다.
     * 둘 다 값이 있고 standardYear와 맞지 않을 때만 conflict 반환.
     * @param novelId 캐릭터가 속한 작품 ID (신규 캐릭터용 — DB 조회 없이 직접 전달)
     * @param characterId 기존 캐릭터 ID (연동 확인용, 신규면 -1)
     */
    suspend fun detectAgeLinkageConflict(
        novelId: Long?,
        characterId: Long,
        fieldValues: List<CharacterFieldValue>
    ): AgeLinkageConflict? {
        if (novelId == null) return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        val stdYear = novel.standardYear ?: return null
        val universeId = novel.universeId ?: return null

        // 연동 활성 확인 (신규 캐릭터는 기본 활성)
        if (characterId > 0 && !semanticSyncHelper.isLinked(characterId)) return null

        val fields = universeRepository.getFieldsByUniverseList(universeId)
        val ageField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE } ?: return null
        val birthYearField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR } ?: return null

        val inputAge = fieldValues.find { it.fieldDefinitionId == ageField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null
        val inputBirthYear = fieldValues.find { it.fieldDefinitionId == birthYearField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null

        val expectedAge = stdYear - inputBirthYear
        if (inputAge == expectedAge) return null

        // 기준연도 변경 시 영향받는 다른 캐릭터 수
        val linkedCount = characterRepository.getCharactersByNovelList(novelId)
            .filter { it.id != characterId }
            .count { semanticSyncHelper.isLinked(it.id) }

        return AgeLinkageConflict(
            inputAge = inputAge,
            inputBirthYear = inputBirthYear,
            currentStdYear = stdYear,
            expectedAge = expectedAge,
            suggestedBirthYear = stdYear - inputAge,
            suggestedStdYear = inputBirthYear + inputAge,
            affectedCharacterCount = linkedCount,
            ageFieldId = ageField.id,
            birthYearFieldId = birthYearField.id,
            novelId = novelId
        )
    }

    /**
     * 기준연도 변경 옵션 선택 시: novel.standardYear 업데이트 + 다른 캐릭터 일괄 재계산.
     */
    suspend fun applyStandardYearChange(novelId: Long, oldStdYear: Int, newStdYear: Int) {
        val novel = novelRepository.getNovelById(novelId) ?: return
        val updatedNovel = novel.copy(standardYear = newStdYear)
        novelRepository.updateNovel(updatedNovel)
        standardYearSyncHelper.onStandardYearChanged(updatedNovel, oldStdYear, newStdYear)
    }

    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)

    // ===== 어시스턴트 교정(Phase A2) — 저장된 캐릭터를 정규 경로로 그 자리에서 고친다 =====

    /** 나이-출생연도 교정 선택지(편집화면 다이얼로그의 라디오 순서와 동일: 0/1/2). */
    enum class AgeResolution { BIRTH_YEAR, AGE, STANDARD_YEAR }

    /**
     * 저장된 캐릭터의 나이-출생연도 모순을 라이브로 재검출한다(스냅샷이 낡았을 수 있으므로).
     * 이미 해결됐으면 null.
     */
    suspend fun detectAgeLinkageConflictForCharacter(characterId: Long): AgeLinkageConflict? {
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val values = characterRepository.getValuesByCharacterList(characterId)
        return detectAgeLinkageConflict(character.novelId, characterId, values)
    }

    /**
     * 모순을 사용자가 고른 방식으로 교정한다. 편집화면과 동일 규칙을 정규 메서드로만 반영:
     * 출생/나이 변경은 [saveAllFieldValues](내부에서 semanticSync 실행), 기준연도 변경은
     * [applyStandardYearChange](작품 전체 재계산). 직접 sync를 손대지 않아 편집 경로와 로직이 일치한다.
     */
    suspend fun resolveAgeLinkage(characterId: Long, conflict: AgeLinkageConflict, choice: AgeResolution) {
        // 단일 트랜잭션 — 필드 저장/시맨틱 동기화, 기준연도 변경 시 작품 전체 재계산이 원자적으로 커밋된다.
        // 어시스턴트 교정이 프래그먼트 스코프에서 실행돼 중간 취소돼도 부분 적용으로 캐스트가 어긋나지 않게(P1-B).
        app.database.withTransaction {
            when (choice) {
                AgeResolution.BIRTH_YEAR -> {
                    val values = characterRepository.getValuesByCharacterList(characterId).toMutableList()
                    upsertFieldValueInList(values, characterId, conflict.birthYearFieldId, conflict.suggestedBirthYear.toString())
                    saveAllFieldValues(characterId, values)
                }
                AgeResolution.AGE -> {
                    val values = characterRepository.getValuesByCharacterList(characterId).toMutableList()
                    upsertFieldValueInList(values, characterId, conflict.ageFieldId, conflict.expectedAge.toString())
                    saveAllFieldValues(characterId, values)
                }
                AgeResolution.STANDARD_YEAR -> {
                    applyStandardYearChange(conflict.novelId, conflict.currentStdYear, conflict.suggestedStdYear)
                }
            }
        }
    }

    private fun upsertFieldValueInList(
        values: MutableList<CharacterFieldValue>, characterId: Long, fieldDefId: Long, value: String
    ) {
        val idx = values.indexOfFirst { it.fieldDefinitionId == fieldDefId }
        if (idx >= 0) {
            values[idx] = values[idx].copy(value = value)
        } else {
            values.add(CharacterFieldValue(characterId = characterId, fieldDefinitionId = fieldDefId, value = value))
        }
    }

    /**
     * 작품 미배정 캐릭터를 작품에 배정한다. 정규 경로(`batchChangeNovel`)를 통해 세계관이
     * 바뀌는 경우 고아 필드값·세력 소속 정리 + `updatedAt` 갱신까지 일관 처리한다.
     * 캐릭터가 이미 삭제됐으면 false(호출부가 "성공" 오알림을 피하도록).
     */
    suspend fun assignNovel(characterId: Long, novelId: Long): Boolean {
        if (characterRepository.getCharacterById(characterId) == null) return false
        characterRepository.batchChangeNovel(listOf(characterId), novelId)
        return true
    }

    /** 캐릭터가 속한 세계관의 관계 유형 목록을 반환 (세계관 미배정 시 기본 유형) */
    suspend fun getRelationshipTypesForCharacter(characterId: Long): List<String> {
        val universeId = getUniverseIdForCharacter(characterId) ?: return Universe.DEFAULT_RELATIONSHIP_TYPES
        val universe = universeRepository.getUniverseById(universeId) ?: return Universe.DEFAULT_RELATIONSHIP_TYPES
        return universe.getRelationshipTypes()
    }

    suspend fun insertCharacterSuspend(character: Character): Long =
        characterRepository.insertCharacter(character)

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterRepository.getChangesByCharacter(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        characterRepository.getChangesUpToYear(characterId, year)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterRepository.getChangesByCharacterList(characterId)

    fun insertStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.insertStateChange(change)
            syncStateChangeToField(change)
            reportResult(_result, OpResult.success(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_added)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert state change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_add_failed), e.message))
        }
    }

    fun updateStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.updateStateChange(change)
            syncStateChangeToField(change)
            reportResult(_result, OpResult.success(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_updated)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update state change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_update_failed), e.message))
        }
    }

    private suspend fun syncStateChangeToField(change: CharacterStateChange) {
        if (change.fieldKey != CharacterStateChange.KEY_BIRTH && change.fieldKey != CharacterStateChange.KEY_DEATH) return
        val universeId = getUniverseIdForCharacter(change.characterId) ?: return
        semanticSyncHelper.syncStateChangeToField(change.characterId, universeId, change)
    }

    fun deleteStateChange(change: CharacterStateChange) = viewModelScope.launch {
        try {
            characterRepository.deleteStateChange(change)
            reportResult(_result, OpResult.success(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_deleted)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete state change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_state_change_delete_failed), e.message))
        }
    }

    // ===== Relationships =====
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>> =
        characterRepository.getRelationshipsForCharacter(characterId)

    fun insertRelationship(relationship: CharacterRelationship) = viewModelScope.launch {
        try {
            characterRepository.insertRelationship(relationship)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_added)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert relationship", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_add_failed), e.message))
        }
    }

    fun updateRelationship(relationship: CharacterRelationship) = viewModelScope.launch {
        try {
            characterRepository.updateRelationship(relationship)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_updated)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_update_failed), e.message))
        }
    }

    fun deleteRelationshipById(id: Long) = viewModelScope.launch {
        try {
            characterRepository.deleteRelationshipById(id)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_deleted)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete relationship", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_delete_failed), e.message))
        }
    }

    suspend fun getRelationshipsForCharacterList(characterId: Long): List<CharacterRelationship> =
        characterRepository.getRelationshipsForCharacterList(characterId)

    suspend fun getRelationshipById(id: Long): CharacterRelationship? =
        characterRepository.getRelationshipById(id)

    fun updateRelationshipOrders(relationships: List<CharacterRelationship>) = viewModelScope.launch {
        try {
            characterRepository.updateRelationshipOrders(relationships)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship orders", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_reorder_failed), e.message))
        }
    }

    suspend fun getAllCharactersList(): List<Character> =
        characterRepository.getAllCharactersList()

    suspend fun getAllCharactersByName(name: String): List<Character> =
        characterRepository.getAllCharactersByName(name)

    suspend fun getCharactersByNovelList(novelId: Long): List<Character> =
        characterRepository.getCharactersByNovelList(novelId)

    suspend fun getCharactersByUniverseList(universeId: Long): List<Character> =
        characterRepository.getCharactersByUniverseList(universeId)

    // ===== RelationshipChanges =====
    fun getRelationshipChanges(relationshipId: Long): LiveData<List<CharacterRelationshipChange>> =
        characterRepository.getRelationshipChanges(relationshipId)

    suspend fun getRelationshipChangesList(relationshipId: Long): List<CharacterRelationshipChange> =
        characterRepository.getRelationshipChangesList(relationshipId)

    fun insertRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.insertRelationshipChange(change)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_added)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert relationship change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_add_failed), e.message))
        }
    }

    fun updateRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.updateRelationshipChange(change)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_updated)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update relationship change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_update_failed), e.message))
        }
    }

    fun deleteRelationshipChange(change: CharacterRelationshipChange) = viewModelScope.launch {
        try {
            characterRepository.deleteRelationshipChange(change)
            reportResult(_result, OpResult.success(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_deleted)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete relationship change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_RELATIONSHIP,
                app.getString(R.string.result_relationship_change_delete_failed), e.message))
        }
    }

    suspend fun getEventsForNovelList(novelId: Long): List<TimelineEvent> =
        timelineRepository.getEventsByNovelList(novelId)

    // ===== Tags =====
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>> =
        characterRepository.getTagsByCharacter(characterId)

    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag> =
        characterRepository.getTagsByCharacterList(characterId)

    suspend fun getAllDistinctTags(): List<String> =
        characterRepository.getAllDistinctTags()

    fun deleteAllTagsByCharacter(characterId: Long) = viewModelScope.launch {
        try {
            characterRepository.deleteAllTagsByCharacter(characterId)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete tags", e)
        }
    }

    fun insertTags(tags: List<CharacterTag>) = viewModelScope.launch {
        try {
            characterRepository.insertTags(tags)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert tags", e)
        }
    }

    suspend fun deleteAllTagsByCharacterSuspend(characterId: Long) =
        characterRepository.deleteAllTagsByCharacter(characterId)

    suspend fun insertTagsSuspend(tags: List<CharacterTag>) =
        characterRepository.insertTags(tags)

    suspend fun replaceAllTagsSuspend(characterId: Long, tags: List<CharacterTag>) =
        characterRepository.replaceAllTagsForCharacter(characterId, tags)

    fun updateCharacterDisplayOrders(characters: List<Character>) = viewModelScope.launch {
        try {
            characterRepository.updateCharacterDisplayOrders(characters)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update display orders", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_character_reorder_failed), e.message))
        }
    }

    fun togglePin(character: Character) = viewModelScope.launch {
        try {
            characterRepository.setPinned(character.id, !character.isPinned)
            // 핀 토글은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to toggle pin", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_CHARACTER,
                app.getString(R.string.result_character_pin_failed), e.message))
        }
    }

    fun recordRecentActivity(characterId: Long, name: String) = viewModelScope.launch {
        try {
            recentActivityDao.upsert(
                RecentActivity(entityType = RecentActivity.TYPE_CHARACTER, entityId = characterId, title = name)
            )
            recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to record recent activity", e)
        }
    }

    // ===== 편집창 추천 이미지 (G3) =====

    /** 추천 후보 묶음: 미배정 라이브러리 이미지 + 링크 확장용 전체 메타. */
    data class RecommendationData(
        val candidates: List<com.novelcharacter.app.util.ImageRecommendationHelper.Candidate>,
        val metas: List<com.novelcharacter.app.util.ImageLinkResolver.Meta>
    )

    /**
     * 추천 후보 1회 페치(D9): 전 엔티티(캐릭터·작품·세계관)의 imagePaths 소유 집합을 1회 구성한 뒤,
     * 소유자 0이면서 파일이 존재하는 라이브러리(meta) 이미지에 태그를 조인해 돌려준다.
     * 이후 태그 변경·attach 시 재매칭은 인메모리로만 수행한다(호출부 계약 — 오픈 동기 비용 0).
     */
    suspend fun getRecommendationCandidates(): RecommendationData = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            fun canonical(p: String): String =
                try { java.io.File(p).canonicalPath } catch (_: Exception) { p }

            val gson = com.google.gson.Gson()
            val owned = HashSet<String>()
            fun collect(json: String) {
                if (json.isBlank() || json == "[]") return
                val parsed = try { gson.fromJson(json, Array<String>::class.java) } catch (_: Exception) { null }
                parsed?.forEach { owned.add(canonical(it)) }
            }
            for (c in db.characterDao().getAllCharactersList()) collect(c.imagePaths)
            for (n in db.novelDao().getAllNovelsList()) collect(n.imagePaths)
            for (u in db.universeDao().getAllUniversesList()) collect(u.imagePaths)

            // 존재 필터를 확장용 metas에도 적용 — 링크 그룹에 소실 파일이 있어도
            // 첨부 확장이 죽은 경로를 캐릭터에 끌어들이지 않는다
            val existingMetas = db.imageMetaDao().getAllList().filter { java.io.File(it.path).exists() }
            val tagsByImage = db.imageTagDao().getAllList().groupBy({ it.imageId }, { it.tag })
            val candidates = existingMetas.mapNotNull { meta ->
                if (canonical(meta.path) in owned) return@mapNotNull null
                com.novelcharacter.app.util.ImageRecommendationHelper.Candidate(
                    path = meta.path,
                    tags = tagsByImage[meta.id]?.toSet() ?: emptySet(),
                    linkGroupId = meta.linkGroupId,
                    importedAt = meta.importedAt
                )
            }
            RecommendationData(
                candidates = candidates,
                metas = existingMetas.map { com.novelcharacter.app.util.ImageLinkResolver.Meta(it.path, it.linkGroupId) }
            )
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to load recommendation candidates", e)
            RecommendationData(emptyList(), emptyList())
        }
    }

    // ===== Event CRUD (캐릭터 화면에서 사건 생성용) =====
    private val db = app.database

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
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForEvent(newEventId)
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert event", e)
        }
    }

    fun updateEvent(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long> = emptyList(),
        fieldSubmission: EventFieldValueMerge.Submission? = null
    ) = viewModelScope.launch {
        try {
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
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to update event", e)
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

                    for (s in shifted) {
                        if (s.eventType == TimelineEvent.TYPE_BIRTH || s.eventType == TimelineEvent.TYPE_DEATH) {
                            val charIds = timelineRepository.getCharacterIdsForEvent(s.id)
                            syncEventTypeToStateChanges(s, charIds)
                        }
                    }
                }
            }
            // 고지는 커밋 직후, 수확보다 먼저 — 보존은 이미 사실이 됐으므로 수확 실패에 연좌되면 안 된다
            notifyPreservedEventFieldValues(preservedFieldValues)
            if (fieldSubmission != null) app.fieldValueLibraryRepository.harvestForEvent(event.id)
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to shift events", e)
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
    suspend fun getEventFieldsForUniverse(universeId: Long) =
        db.fieldDefinitionDao().getFieldsByUniverseList(universeId, com.novelcharacter.app.data.model.FieldDefinition.ENTITY_EVENT)
    suspend fun getEventFieldValuesForEvent(eventId: Long) =
        db.eventFieldValueDao().getValuesByEventList(eventId)
    suspend fun getEventsByNovelList(novelId: Long) = timelineRepository.getEventsByNovelList(novelId)
    suspend fun getEventsByUniverseList(universeId: Long) = timelineRepository.getEventsByUniverseList(universeId)
    suspend fun getAllEventsList() = timelineRepository.getAllEventsList()

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
                    existing.copy(year = event.year, month = event.month, day = event.day, newValue = event.year.toString())
                        .also { characterRepository.updateStateChange(it) }
                } else {
                    CharacterStateChange(
                        characterId = charId, year = event.year,
                        month = event.month, day = event.day,
                        fieldKey = fieldKey, newValue = event.year.toString()
                    ).also { characterRepository.insertStateChange(it) }
                }
                val character = characterRepository.getCharacterById(charId) ?: continue
                val novel = character.novelId?.let { novelRepository.getNovelById(it) } ?: continue
                val uId = novel.universeId ?: continue
                semanticSyncHelper.syncStateChangeToField(charId, uId, change)
            } catch (e: Exception) {
                Log.w("CharacterViewModel", "Failed to sync event type for character $charId", e)
            }
        }
    }

    /**
     * 사건 삭제 — 캐릭터 화면에서도 연표 탭과 **같은 경로**로 지운다.
     *
     * 종전에는 사건을 지우려면 반드시 연표 탭으로 가야 했다(원칙 04 — 조작 마찰).
     * 저장소가 휴지통 스냅샷을 남기고, 출생·사망 사건이면 캐릭터 쪽 상태변화 정리를
     * **스냅샷 뒤·삭제 앞에 같은 트랜잭션 안에서** 실행한다(TimelineViewModel과 동일 규약).
     * 순서가 바뀌면 스냅샷이 이미 지워진 이력을 담지 못해 사건만 되살아난다.
     */
    fun deleteEvent(event: TimelineEvent) = viewModelScope.launch {
        try {
            timelineRepository.deleteEvent(event) {
                if (event.eventType == TimelineEvent.TYPE_BIRTH ||
                    event.eventType == TimelineEvent.TYPE_DEATH
                ) {
                    cleanupStateChangesForDeletedEvent(event)
                }
            }
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_deleted)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to delete event", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_delete_failed), e.message))
        }
    }

    /** 출생/사망 사건 삭제 전, 연결된 캐릭터의 상태변화 + 파생 필드값 정리 (TimelineViewModel과 동일). */
    private suspend fun cleanupStateChangesForDeletedEvent(event: TimelineEvent) {
        for (charId in timelineRepository.getCharacterIdsForEvent(event.id)) {
            try {
                val character = characterRepository.getCharacterById(charId) ?: continue
                val novel = character.novelId?.let { novelRepository.getNovelById(it) } ?: continue
                val universeId = novel.universeId ?: continue
                when (event.eventType) {
                    TimelineEvent.TYPE_BIRTH -> semanticSyncHelper.onBirthEventDeleted(charId, universeId)
                    TimelineEvent.TYPE_DEATH -> semanticSyncHelper.onDeathEventDeleted(charId, universeId)
                }
            } catch (e: Exception) {
                Log.w("CharacterViewModel", "Failed to cleanup state changes for character $charId", e)
            }
        }
    }

    /**
     * 이 캐릭터와 사건의 **연결만** 끊는다 — 사건 자체는 남는다.
     *
     * 사건은 여러 캐릭터가 공유하므로 캐릭터 화면의 '삭제'가 곧 전역 삭제가 되면
     * 다른 캐릭터의 연표에서도 사라진다. 둘을 다른 동작으로 갈라 둔다.
     */
    fun unlinkEventFromCharacter(eventId: Long, characterId: Long) = viewModelScope.launch {
        try {
            timelineRepository.unlinkCharacterFromEvent(eventId, characterId)
            reportResult(_result, OpResult.success(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_unlinked)))
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to unlink event", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_EVENT,
                app.getString(R.string.result_event_unlink_failed), e.message))
        }
    }

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
}
