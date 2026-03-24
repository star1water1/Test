package com.novelcharacter.app.ui.supplement

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SupplementViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db = app.database
    private val prefs = application.getSharedPreferences("supplement_ui_state", Context.MODE_PRIVATE)

    // UI 상태
    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _targets = MutableLiveData<List<SupplementTarget>>(emptyList())
    val targets: LiveData<List<SupplementTarget>> = _targets

    private val _totalCount = MutableLiveData(0)
    val totalCount: LiveData<Int> = _totalCount

    private val _universeList = MutableLiveData<List<Universe>>(emptyList())
    val universeList: LiveData<List<Universe>> = _universeList

    private val _novelList = MutableLiveData<List<Novel>>(emptyList())
    val novelList: LiveData<List<Novel>> = _novelList

    // 필터 상태 (SharedPreferences에서 복원)
    var selectedUniverseId: Long? = if (prefs.contains("universe_id")) prefs.getLong("universe_id", -1L) else null
        private set
    var selectedNovelId: Long? = if (prefs.contains("novel_id")) prefs.getLong("novel_id", -1L) else null
        private set
    var issueFilter: SupplementIssue? = null
        private set
    var sortMode: SortMode = try {
        SortMode.valueOf(prefs.getString("sort_mode", null) ?: SortMode.ISSUES_DESC.name)
    } catch (_: Exception) { SortMode.ISSUES_DESC }
        private set

    // 전체 데이터 (필터 전)
    private var allTargets: List<SupplementTarget> = emptyList()
    private var allCharacters: List<Character> = emptyList()

    enum class SortMode { ISSUES_DESC, NAME_ASC, COMPLETION_ASC }

    fun loadData(criteria: SupplementCriteria) {
        viewModelScope.launch {
            _isLoading.value = true
            withContext(Dispatchers.IO) {
                val characters = db.characterDao().getAllCharactersList()
                val novels = db.novelDao().getAllNovelsList()
                val universes = db.universeDao().getAllUniversesList()
                val allTags = db.characterTagDao().getAllTagsList()
                val allFieldValues = db.characterFieldValueDao().getAllValuesList()
                val allFieldDefs = db.fieldDefinitionDao().getAllFieldsList()
                val allRelationships = db.characterRelationshipDao().getAllRelationships()
                val allCrossRefs = db.timelineDao().getAllCrossRefs()
                val allMemberships = db.factionMembershipDao().getAllMembershipsList()
                val allFactions = db.factionDao().getAllFactionsList()

                // 인덱스 맵 구축
                val novelMap = novels.associateBy { it.id }
                val tagsByCharacter = allTags.groupBy { it.characterId }
                val fieldValuesByCharacter = allFieldValues.groupBy { it.characterId }
                val fieldDefsByUniverse = allFieldDefs.groupBy { it.universeId }
                val relationCharIds = mutableSetOf<Long>()
                allRelationships.forEach {
                    relationCharIds.add(it.characterId1)
                    relationCharIds.add(it.characterId2)
                }
                val eventCharIds = allCrossRefs.map { it.characterId }.toSet()
                val membershipCharIds = allMemberships.map { it.characterId }.toSet()
                val factionsByUniverse = allFactions.groupBy { it.universeId }

                val targets = mutableListOf<SupplementTarget>()

                for (char in characters) {
                    val issues = mutableListOf<SupplementIssue>()

                    // 이미지 체크
                    if (criteria.checkImages) {
                        if (char.imagePaths.isBlank() || char.imagePaths == "[]") {
                            issues.add(SupplementIssue.NO_IMAGE)
                        }
                    }

                    // 메모 체크
                    if (criteria.checkMemo && char.memo.isBlank()) {
                        issues.add(SupplementIssue.NO_MEMO)
                    }

                    // 별명 체크
                    if (criteria.checkAliases && char.anotherName.isBlank()) {
                        issues.add(SupplementIssue.NO_ALIASES)
                    }

                    // 작품 배정 체크
                    if (criteria.checkNovel && char.novelId == null) {
                        issues.add(SupplementIssue.NO_NOVEL)
                    }

                    // 태그 체크
                    if (criteria.checkTags) {
                        val tags = tagsByCharacter[char.id] ?: emptyList()
                        if (tags.isEmpty()) {
                            issues.add(SupplementIssue.NO_TAGS)
                        }
                    }

                    // 커스텀 필드 체크
                    var fieldCompletion = -1f
                    if (criteria.checkCustomFields && char.novelId != null) {
                        val novel = novelMap[char.novelId]
                        val universeId = novel?.universeId
                        if (universeId != null) {
                            val fieldDefs = fieldDefsByUniverse[universeId] ?: emptyList()
                            // CALCULATED 필드 제외
                            val checkableDefs = fieldDefs.filter {
                                FieldType.fromName(it.type) != FieldType.CALCULATED
                            }
                            if (checkableDefs.isNotEmpty()) {
                                val values = fieldValuesByCharacter[char.id] ?: emptyList()
                                val filledDefIds = values
                                    .filter { it.value.isNotBlank() }
                                    .map { it.fieldDefinitionId }
                                    .toSet()
                                val filled = checkableDefs.count { it.id in filledDefIds }
                                fieldCompletion = filled.toFloat() / checkableDefs.size * 100f
                                if (fieldCompletion < criteria.fieldCompletionThreshold) {
                                    issues.add(SupplementIssue.INCOMPLETE_FIELDS)
                                }
                            }
                        }
                    }

                    // 관계 체크
                    if (criteria.checkRelationships && char.id !in relationCharIds) {
                        issues.add(SupplementIssue.NO_RELATIONSHIPS)
                    }

                    // 사건 체크
                    if (criteria.checkEvents && char.id !in eventCharIds) {
                        issues.add(SupplementIssue.NO_EVENTS)
                    }

                    // 세력 체크 (세계관에 세력이 있는 경우만)
                    if (criteria.checkFactions && char.novelId != null) {
                        val novel = novelMap[char.novelId]
                        val universeId = novel?.universeId
                        if (universeId != null) {
                            val factions = factionsByUniverse[universeId] ?: emptyList()
                            if (factions.isNotEmpty() && char.id !in membershipCharIds) {
                                issues.add(SupplementIssue.NO_FACTIONS)
                            }
                        }
                    }

                    if (issues.isNotEmpty()) {
                        targets.add(SupplementTarget(char, issues, fieldCompletion))
                    }
                }

                allTargets = targets
                allCharacters = characters

                withContext(Dispatchers.Main) {
                    _universeList.value = universes
                    _novelList.value = novels
                    _totalCount.value = characters.size
                    applyFiltersInternal()
                    _isLoading.value = false
                }
            }
        }
    }

    fun setUniverseFilter(universeId: Long?) {
        selectedUniverseId = universeId
        prefs.edit().apply {
            if (universeId != null) putLong("universe_id", universeId) else remove("universe_id")
        }.apply()
        applyFilters()
    }

    fun setNovelFilter(novelId: Long?) {
        selectedNovelId = novelId
        prefs.edit().apply {
            if (novelId != null) putLong("novel_id", novelId) else remove("novel_id")
        }.apply()
        applyFilters()
    }

    fun setIssueFilter(issue: SupplementIssue?) {
        issueFilter = issue
        applyFilters()
    }

    fun setSortMode(mode: SortMode) {
        sortMode = mode
        prefs.edit().putString("sort_mode", mode.name).apply()
        applyFilters()
    }

    fun getNovelsForUniverse(universeId: Long): List<Novel> {
        return _novelList.value?.filter { it.universeId == universeId } ?: emptyList()
    }

    private fun applyFilters() {
        viewModelScope.launch {
            applyFiltersInternal()
        }
    }

    private fun applyFiltersInternal() {
        var filtered = allTargets.toList()

        // 세계관 필터
        val uId = selectedUniverseId
        if (uId != null) {
            val novelIds = _novelList.value
                ?.filter { it.universeId == uId }
                ?.map { it.id }
                ?.toSet() ?: emptySet()
            filtered = filtered.filter { it.character.novelId in novelIds }
        }

        // 작품 필터
        val nId = selectedNovelId
        if (nId != null) {
            filtered = filtered.filter { it.character.novelId == nId }
        }

        // 이슈 필터
        val issue = issueFilter
        if (issue != null) {
            filtered = filtered.filter { issue in it.issues }
        }

        // 정렬
        filtered = when (sortMode) {
            SortMode.ISSUES_DESC -> filtered.sortedByDescending { it.issues.size }
            SortMode.NAME_ASC -> filtered.sortedBy { it.character.displayName }
            SortMode.COMPLETION_ASC -> filtered.sortedBy {
                if (it.fieldCompletion < 0) Float.MAX_VALUE else it.fieldCompletion
            }
        }

        _targets.value = filtered
    }

    fun getIssuesForCharacter(characterId: Long): List<SupplementIssue> {
        return allTargets.find { it.character.id == characterId }?.issues ?: emptyList()
    }
}
