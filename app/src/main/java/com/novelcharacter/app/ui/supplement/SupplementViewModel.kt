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
    var issueFilter: SupplementIssue? = try {
        prefs.getString("issue_filter", null)?.let { SupplementIssue.valueOf(it) }
    } catch (_: Exception) { null }
        private set
    var sortMode: SortMode = try {
        SortMode.valueOf(prefs.getString("sort_mode", null) ?: SortMode.ISSUES_DESC.name)
    } catch (_: Exception) { SortMode.ISSUES_DESC }
        private set

    // ===== UI 배치 상태 (정렬·필터·접힘·탭 위치는 작업 컨텍스트 — 재방문 시 복원) =====

    /** 마지막으로 확정된 내부 탭 위치 (0=랜덤 보충, 1=완성도 검사) */
    var lastTabPosition: Int = prefs.getInt("last_tab", 0).coerceIn(0, 1)
        private set

    fun setLastTab(pos: Int) {
        val coerced = pos.coerceIn(0, 1)
        if (lastTabPosition == coerced) return
        lastTabPosition = coerced
        prefs.edit().putInt("last_tab", coerced).apply()
    }

    /** 호스트 필터 카드 접힘 — 기본 접힘: 캐릭터 정보가 화면의 주인공 */
    var isFilterCollapsed: Boolean = prefs.getBoolean("filter_collapsed", true)
        private set

    fun setFilterCollapsed(collapsed: Boolean) {
        isFilterCollapsed = collapsed
        prefs.edit().putBoolean("filter_collapsed", collapsed).apply()
    }

    /** 랜덤 탭 뽑기 옵션(방식 칩·설정) 접힘 — 기본 접힘: 핵심 루프(뽑기·편집)는 상시 노출 */
    var isRandomControlsCollapsed: Boolean = prefs.getBoolean("random_controls_collapsed", true)
        private set

    fun setRandomControlsCollapsed(collapsed: Boolean) {
        isRandomControlsCollapsed = collapsed
        prefs.edit().putBoolean("random_controls_collapsed", collapsed).apply()
    }

    // 전체 데이터 (필터 전)
    private var allTargets: List<SupplementTarget> = emptyList()
    private var allCharacters: List<Character> = emptyList()

    // 전체 캐릭터 감사 결과 — 이슈가 없는 캐릭터도 포함 (랜덤 탭의 완성도 배지·미흡 우선 뽑기용)
    private var allAudits: List<SupplementTarget> = emptyList()

    // 랜덤 탭 뽑기 풀 — 세계관/작품 필터만 적용 (이슈 필터·정렬은 완성도 검사 탭 전용)
    private val _randomPool = MutableLiveData<List<Character>>(emptyList())
    val randomPool: LiveData<List<Character>> = _randomPool

    // ===== 랜덤 뽑기 엔진 (ViewModel 보유 — 회전 생존, 프로세스 종료 시 소멸은 의도된 결정) =====

    /** 랜덤 카드의 단발성 알림 종류 */
    enum class RandomNotice { CYCLE_COMPLETE, FELL_BACK_TO_FULL_POOL, PICK_OUTSIDE_FILTER, CURRENT_DELETED }

    private val pickEngine = RandomPickEngine()

    var randomMode: RandomPickEngine.PickMode = try {
        RandomPickEngine.PickMode.valueOf(
            prefs.getString("random_mode", null) ?: RandomPickEngine.PickMode.PURE_RANDOM.name
        )
    } catch (_: Exception) { RandomPickEngine.PickMode.PURE_RANDOM }
        private set

    init {
        pickEngine.setMode(randomMode)
    }

    /** 현재 뽑힌 캐릭터 — null이면 풀 공백 */
    private val _currentPick = MutableLiveData<Character?>(null)
    val currentPick: LiveData<Character?> = _currentPick

    /** 단발성 알림 — 소비 후 [clearRandomNotice] 호출 */
    private val _randomNotice = MutableLiveData<RandomNotice?>(null)
    val randomNotice: LiveData<RandomNotice?> = _randomNotice

    fun clearRandomNotice() {
        if (_randomNotice.value != null) _randomNotice.value = null
    }

    fun setRandomMode(mode: RandomPickEngine.PickMode) {
        if (randomMode == mode) return
        randomMode = mode
        prefs.edit().putString("random_mode", mode.name).apply()
        pickEngine.setMode(mode)
    }

    val canRandomGoBack: Boolean get() = pickEngine.canGoBack
    val canRandomGoForward: Boolean get() = pickEngine.canGoForward

    /** 다시 뽑기 — 엔진 신호(한 바퀴/폴백)를 알림으로 발행 */
    fun rerollRandom() {
        val id = pickEngine.next()
        if (id == null) {
            _currentPick.value = null
            return
        }
        if (pickEngine.lastDrawCompletedCycle) {
            _randomNotice.value = RandomNotice.CYCLE_COMPLETE
        } else if (pickEngine.lastDrawFellBackToFullPool) {
            _randomNotice.value = RandomNotice.FELL_BACK_TO_FULL_POOL
        }
        _currentPick.value = allCharacters.find { it.id == id }
    }

    fun randomPickBack() {
        val id = pickEngine.goBack() ?: return
        _currentPick.value = allCharacters.find { it.id == id }
    }

    fun randomPickForward() {
        val id = pickEngine.goForward() ?: return
        _currentPick.value = allCharacters.find { it.id == id }
    }

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

                val audits = mutableListOf<SupplementTarget>()

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

                    // 이슈가 없어도 감사 결과를 보관 — 랜덤 탭이 완성도 배지를 표시할 수 있도록
                    audits.add(SupplementTarget(char, issues, fieldCompletion))
                }

                allAudits = audits
                allTargets = audits.filter { it.issues.isNotEmpty() }
                allCharacters = characters

                withContext(Dispatchers.Main) {
                    _universeList.value = universes
                    _novelList.value = novels
                    _totalCount.value = characters.size

                    // 삭제된 엔티티 참조 정리: 복원된 ID가 현재 데이터에 없으면 필터 해제
                    // setter를 사용하지 않고 직접 수정하여 불필요한 비동기 applyFilters() 호출 방지
                    if (selectedUniverseId != null && universes.none { it.id == selectedUniverseId }) {
                        selectedUniverseId = null
                        prefs.edit().remove("universe_id").apply()
                    }
                    if (selectedNovelId != null && novels.none { it.id == selectedNovelId }) {
                        selectedNovelId = null
                        prefs.edit().remove("novel_id").apply()
                    }

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
        prefs.edit().apply {
            if (issue != null) putString("issue_filter", issue.name) else remove("issue_filter")
        }.apply()
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

        // 랜덤 탭 뽑기 풀 갱신 — 세계관/작품 필터만 적용
        var pool = allCharacters.toList()
        if (uId != null) {
            val novelIds = _novelList.value
                ?.filter { it.universeId == uId }
                ?.map { it.id }
                ?.toSet() ?: emptySet()
            pool = pool.filter { it.novelId in novelIds }
        }
        if (nId != null) {
            pool = pool.filter { it.novelId == nId }
        }
        _randomPool.value = pool
        syncPickEngine(pool)
    }

    /** 뽑기 엔진에 새 풀을 반영하고 현재 뽑기의 생존/이탈/삭제를 처리한다 */
    private fun syncPickEngine(pool: List<Character>) {
        val incompleteIds = allAudits.asSequence()
            .filter { it.issues.isNotEmpty() }
            .map { it.character.id }
            .toSet()
        val entries = pool.map {
            RandomPickEngine.Entry(id = it.id, updatedAt = it.updatedAt, isIncomplete = it.id in incompleteIds)
        }

        val prevPick = _currentPick.value
        // 갱신 전에 엔진이 이 뽑기를 들고 있었는지 — '이번에' 이탈/삭제된 것인지 판별 (알림 중복 방지)
        val hadInEngine = prevPick != null && pickEngine.current() == prevPick.id
        val result = pickEngine.updatePool(entries)

        when {
            pool.isEmpty() -> _currentPick.value = null
            result.currentSurvived -> {
                // 저장 등으로 데이터가 바뀌었을 수 있으니 새 객체로 갱신
                val id = pickEngine.current()
                _currentPick.value = pool.find { it.id == id }
            }
            prevPick == null -> {
                // 최초 진입 — 자동으로 첫 캐릭터를 뽑는다 (알림 없음)
                rerollRandom()
                clearRandomNotice()
            }
            allCharacters.any { it.id == prevPick.id } -> {
                // 캐릭터는 살아있지만 현재 필터 범위를 벗어남 — 표시는 유지하고 알린다 (무단 점프 금지)
                _currentPick.value = allCharacters.find { it.id == prevPick.id }
                if (hadInEngine) _randomNotice.value = RandomNotice.PICK_OUTSIDE_FILTER
            }
            else -> {
                // 표시 중이던 캐릭터가 삭제됨 — 새로 뽑고 알린다
                rerollRandom()
                if (hadInEngine) _randomNotice.value = RandomNotice.CURRENT_DELETED
            }
        }
    }

    /** 특정 캐릭터의 감사 결과 (이슈 없는 완성 캐릭터 포함) — 랜덤 탭 배지·미흡 칩용 */
    fun getAuditForCharacter(characterId: Long): SupplementTarget? {
        return allAudits.find { it.character.id == characterId }
    }

    fun getIssuesForCharacter(characterId: Long): List<SupplementIssue> {
        return getAuditForCharacter(characterId)?.issues ?: emptyList()
    }
}
