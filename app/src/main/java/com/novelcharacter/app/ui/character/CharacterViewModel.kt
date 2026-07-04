package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.novelcharacter.app.util.FieldFilterHelper
import com.novelcharacter.app.util.FieldValueSorter
import com.novelcharacter.app.util.FormulaEvaluator
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterListPreset
import com.novelcharacter.app.data.model.FieldFilter
import com.novelcharacter.app.data.model.StructuredInputConfig
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import androidx.room.withTransaction
import kotlinx.coroutines.launch

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

    private val listTrigger = MediatorLiveData<Unit>().apply {
        addSource(baseCharacters) { value = Unit }
        addSource(_sortSpec) { value = Unit }
        addSource(_fieldFilters) { value = Unit }
        addSource(_tagFilters) { value = Unit }
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
            val sort = _sortSpec.value ?: CharacterSort()
            val result = withContext(Dispatchers.Default) {
                applyFiltersAndSort(base, filters, tags, sort)
            }
            _searchResults.value = result
        }
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

    fun clearAllFilters() {
        setFieldFilters(emptyList())
        setTagFilters(emptySet())
    }

    fun hasActiveFilters(): Boolean =
        !_fieldFilters.value.isNullOrEmpty() || !_tagFilters.value.isNullOrEmpty()

    // ===== 필터/정렬 적용 (백그라운드) =====

    private suspend fun applyFiltersAndSort(
        base: List<Character>,
        filters: List<FieldFilter>,
        tags: Set<String>,
        sort: CharacterSort
    ): List<Character> {
        var chars = base
        if (filters.isNotEmpty()) {
            val ids = FieldFilterHelper.applyFieldFilters(app.database.characterFieldValueDao(), filters)
            chars = chars.filter { it.id in ids }
        }
        if (tags.isNotEmpty()) {
            // 태그 필터: 선택 태그 중 하나라도 가진 캐릭터(OR)
            val taggedIds = app.database.characterTagDao().getAllTagsList()
                .filter { it.tag in tags }.map { it.characterId }.toSet()
            chars = chars.filter { it.id in taggedIds }
        }
        return sortCharacters(chars, sort)
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

    /** 커스텀 필드값 정렬. 값 없음/파싱불가는 방향과 무관하게 최후순. */
    private suspend fun sortByField(
        chars: List<Character>, fieldKey: String?, ascending: Boolean, partIndex: Int?
    ): List<Character> {
        if (fieldKey == null || chars.isEmpty()) return chars
        val fieldDefs = getCharacterFieldsForKey(fieldKey)
        if (fieldDefs.isEmpty()) return chars

        val useNumeric = fieldDefs.values.any { FieldValueSorter.isNumericSortType(it.type) }
        if (useNumeric) {
            val allCalc = fieldDefs.values.all { it.type == "CALCULATED" }
            val keyMap: Map<Long, Double?> =
                if (allCalc) computeCalculatedValues(chars, fieldKey)
                else computeStoredNumeric(chars, fieldDefs, partIndex)
            return chars.sortedWith(nullsLastComparator(ascending) { keyMap[it.id] })
        } else {
            val stored = characterRepository.getValuesForCharacters(chars.map { it.id })
                .filter { it.fieldDefinitionId in fieldDefs.keys }
                .associateBy { it.characterId }
            val keyMap: Map<Long, String?> = chars.associate { c ->
                val fv = stored[c.id]
                val ownerFd = fv?.let { fieldDefs[it.fieldDefinitionId] }
                c.id to (if (fv != null && ownerFd != null) FieldValueSorter.textValue(ownerFd, fv.value) else null)
            }
            return chars.sortedWith(nullsLastComparator(ascending) { keyMap[it.id] })
        }
    }

    /** 값 있는 항목은 방향대로, null(값 없음)은 항상 뒤로. 동값은 이름 오름차순으로 안정화. */
    private fun <R : Comparable<R>> nullsLastComparator(
        ascending: Boolean, selector: (Character) -> R?
    ): Comparator<Character> = Comparator { a, b ->
        val va = selector(a); val vb = selector(b)
        val primary = when {
            va == null && vb == null -> 0
            va == null -> 1
            vb == null -> -1
            else -> if (ascending) va.compareTo(vb) else vb.compareTo(va)
        }
        if (primary != 0) primary else a.name.lowercase().compareTo(b.name.lowercase())
    }

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

    /** 필터 UI용 특정 필드의 유니크 값 목록. */
    suspend fun getFieldValues(fieldDefId: Long): List<String> =
        app.database.characterFieldValueDao().getValuesByFieldDef(fieldDefId)
            .filter { it.value.isNotBlank() }.map { it.value }.distinct().sorted()

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

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) {
        characterRepository.saveAllFieldValues(characterId, values)
        val universeId = getUniverseIdForCharacter(characterId)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(characterId, universeId, values)
        }
    }

    suspend fun updateCharacterWithFields(character: Character, values: List<CharacterFieldValue>) {
        characterRepository.updateCharacterWithFields(character, values)
        val universeId = getUniverseIdForCharacter(character.id)
        if (universeId != null) {
            semanticSyncHelper.syncFieldToStateChange(character.id, universeId, values)
        }
    }

    private suspend fun getUniverseIdForCharacter(characterId: Long): Long? {
        val character = characterRepository.getCharacterById(characterId) ?: return null
        val novelId = character.novelId ?: return null
        val novel = novelRepository.getNovelById(novelId) ?: return null
        return novel.universeId
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

    // ===== Event CRUD (캐릭터 화면에서 사건 생성용) =====
    private val db = app.database

    fun insertEvent(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long> = emptyList(),
        eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>? = null
    ) = viewModelScope.launch {
        try {
            db.withTransaction {
                val eventId = timelineRepository.insertEvent(event)
                timelineRepository.updateEventCharacters(eventId, characterIds)
                timelineRepository.updateEventNovels(eventId, novelIds)
                if (eventFieldValues != null) {
                    db.eventFieldValueDao().replaceAllByEvent(eventId, eventFieldValues.map { it.copy(eventId = eventId) })
                }
            }
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to insert event", e)
        }
    }

    fun updateEvent(
        event: TimelineEvent,
        characterIds: List<Long>,
        novelIds: List<Long> = emptyList(),
        eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>? = null
    ) = viewModelScope.launch {
        try {
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)
                if (eventFieldValues != null) {
                    db.eventFieldValueDao().replaceAllByEvent(event.id, eventFieldValues.map { it.copy(eventId = event.id) })
                }
            }
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
        eventFieldValues: List<com.novelcharacter.app.data.model.EventFieldValue>? = null
    ) = viewModelScope.launch {
        try {
            val oldYear = event.year - delta
            db.withTransaction {
                timelineRepository.updateEvent(event)
                timelineRepository.updateEventCharacters(event.id, characterIds)
                timelineRepository.updateEventNovels(event.id, novelIds)
                if (eventFieldValues != null) {
                    db.eventFieldValueDao().replaceAllByEvent(event.id, eventFieldValues.map { it.copy(eventId = event.id) })
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
            syncEventTypeToStateChanges(event, characterIds)
        } catch (e: Exception) {
            Log.e("CharacterViewModel", "Failed to shift events", e)
        }
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

    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        timelineRepository.getCharacterIdsForEvent(eventId)
}
