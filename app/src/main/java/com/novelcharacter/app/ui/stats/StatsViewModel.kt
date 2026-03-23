package com.novelcharacter.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val provider = StatsDataProvider(app)

    private var cachedSnapshot: StatsSnapshot? = null

    // 작품 필터
    private val _selectedNovelId = MutableLiveData<Long?>(null)
    val selectedNovelId: LiveData<Long?> = _selectedNovelId

    private val _novelList = MutableLiveData<List<Pair<Long, String>>>()
    val novelList: LiveData<List<Pair<Long, String>>> = _novelList

    private val _summary = MutableLiveData<SummaryStats>()
    val summary: LiveData<SummaryStats> = _summary

    // ===== 신규: 필드 인사이트 =====
    private val _fieldInsights = MutableLiveData<List<FieldInsightResult>>()
    val fieldInsights: LiveData<List<FieldInsightResult>> = _fieldInsights

    // ===== 신규: 교차 분석 =====
    private val _crossAnalysis = MutableLiveData<CrossAnalysisResult?>()
    val crossAnalysis: LiveData<CrossAnalysisResult?> = _crossAnalysis

    // ===== 신규: 관계 네트워크 =====
    private val _relationNetwork = MutableLiveData<RelationshipStats>()
    val relationNetwork: LiveData<RelationshipStats> = _relationNetwork

    // ===== 신규: 작품별 비교 =====
    private val _crossNovelComparison = MutableLiveData<CrossNovelComparison>()
    val crossNovelComparison: LiveData<CrossNovelComparison> = _crossNovelComparison

    // ===== 신규: 데이터 현황 =====
    private val _dataOverview = MutableLiveData<DataOverviewStats>()
    val dataOverview: LiveData<DataOverviewStats> = _dataOverview

    // ===== 레거시 (기존 Fragment 호환용) =====
    private val _characterStats = MutableLiveData<CharacterStats>()
    val characterStats: LiveData<CharacterStats> = _characterStats

    private val _eventStats = MutableLiveData<EventStats>()
    val eventStats: LiveData<EventStats> = _eventStats

    private val _relationshipStats = MutableLiveData<RelationshipStats>()
    val relationshipStats: LiveData<RelationshipStats> = _relationshipStats

    private val _nameBankStats = MutableLiveData<NameBankStats>()
    val nameBankStats: LiveData<NameBankStats> = _nameBankStats

    private val _dataHealthStats = MutableLiveData<DataHealthStats>()
    val dataHealthStats: LiveData<DataHealthStats> = _dataHealthStats

    private val _fieldAnalysisStats = MutableLiveData<FieldAnalysisStats>()
    val fieldAnalysisStats: LiveData<FieldAnalysisStats> = _fieldAnalysisStats

    private val _factionStats = MutableLiveData<FactionStatsResult>()
    val factionStats: LiveData<FactionStatsResult> = _factionStats

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private suspend fun ensureSnapshot(): StatsSnapshot {
        return cachedSnapshot ?: withContext(Dispatchers.IO) {
            provider.loadSnapshot()
        }.also { cachedSnapshot = it }
    }

    private fun getFilteredSnapshot(snapshot: StatsSnapshot): StatsSnapshot {
        val novelId = _selectedNovelId.value ?: return snapshot
        return provider.filterByNovel(snapshot, novelId)
    }

    private var isRefreshing = false
    private var statsJob: Job? = null

    fun setNovelFilter(novelId: Long?) {
        _selectedNovelId.value = novelId
        refreshStats()
    }

    fun refreshStats() {
        cachedSnapshot = null
        isRefreshing = true
        loadAllStats()
    }

    fun loadAllStats() {
        if (!isRefreshing && _summary.value != null) return
        statsJob?.cancel()
        _loading.value = true
        _error.value = null
        statsJob = viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)

                // 작품 목록 설정
                _novelList.value = snapshot.novels.map { it.id to it.title }

                // 모든 통계를 병렬로 계산한 후 한 번에 LiveData에 반영
                val summaryDeferred = async(Dispatchers.IO) { provider.computeSummary(filtered) }
                val insightsDeferred = async(Dispatchers.IO) { provider.computeFieldInsights(filtered) }
                val charsDeferred = async(Dispatchers.IO) { provider.computeCharacterStats(filtered) }
                val eventsDeferred = async(Dispatchers.IO) { provider.computeEventStats(filtered) }
                val relsDeferred = async(Dispatchers.IO) { provider.computeRelationshipStats(filtered) }
                val namesDeferred = async(Dispatchers.IO) { provider.computeNameBankStats(filtered) }
                val healthDeferred = async(Dispatchers.IO) { provider.computeDataHealth(filtered) }
                val fieldAnalysisDeferred = async(Dispatchers.IO) { provider.computeFieldAnalysis(filtered) }
                val patternsDeferred = async(Dispatchers.IO) { provider.detectPatterns(filtered) }
                val factionDeferred = async(Dispatchers.IO) { provider.computeFactionStats(filtered) }

                val summary = summaryDeferred.await()
                val insights = insightsDeferred.await()
                val chars = charsDeferred.await()
                val events = eventsDeferred.await()
                val rels = relsDeferred.await()
                val names = namesDeferred.await()
                val health = healthDeferred.await()
                val fieldAnalysis = fieldAnalysisDeferred.await()
                val patterns = patternsDeferred.await()
                val factions = factionDeferred.await()

                // 세부 통계를 먼저 set하고, summary를 마지막에 set
                _fieldInsights.value = insights
                _characterStats.value = chars
                _eventStats.value = events
                _relationshipStats.value = rels
                _nameBankStats.value = names
                _dataHealthStats.value = health
                _fieldAnalysisStats.value = fieldAnalysis
                _patternInsights.value = patterns
                _factionStats.value = factions
                _summary.value = summary
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                isRefreshing = false
                _loading.value = false
            }
        }
    }

    // ===== 신규 load 메서드 =====

    /**
     * 개별 load 메서드에서 사용: loadAllStats()가 실행 중이면 loading 상태를 건드리지 않는다.
     */
    private fun dismissLoadingIfIdle() {
        if (statsJob?.isActive != true) {
            _loading.value = false
        }
    }

    fun loadFieldInsights() {
        if (_fieldInsights.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _fieldInsights.value = withContext(Dispatchers.IO) { provider.computeFieldInsights(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadCrossAnalysis(field1Id: Long, field2Id: Long, filterFieldId: Long? = null, filterValue: String? = null) {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _crossAnalysis.value = withContext(Dispatchers.IO) {
                    provider.computeCrossAnalysis(filtered, field1Id, field2Id, filterFieldId, filterValue)
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadRelationNetwork() {
        if (_relationNetwork.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _relationNetwork.value = withContext(Dispatchers.IO) { provider.computeRelationshipStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadDataOverview() {
        if (_dataOverview.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _dataOverview.value = withContext(Dispatchers.IO) { provider.computeDataOverview(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadCrossNovelComparison() {
        if (_crossNovelComparison.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                // 작품 비교는 전체 스냅샷으로 계산 (필터 미적용)
                _crossNovelComparison.value = withContext(Dispatchers.IO) {
                    provider.computeCrossNovelComparison(snapshot)
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadFactionStats() {
        if (_factionStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _factionStats.value = withContext(Dispatchers.IO) { provider.computeFactionStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    // ===== 순위 =====
    private val _rankingResult = MutableLiveData<RankingResult?>()
    val rankingResult: LiveData<RankingResult?> = _rankingResult

    private val _rankableFields = MutableLiveData<List<RankableField>>()
    val rankableFields: LiveData<List<RankableField>> = _rankableFields

    fun loadRankableFields(universeId: Long?) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _rankableFields.value = withContext(Dispatchers.IO) {
                    provider.getRankableFields(snapshot, universeId)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun loadRanking(fieldDefIds: List<Long>, ascending: Boolean = false, bodySizePartIndex: Int? = null, novelId: Long? = null) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val scoped = if (novelId != null) provider.filterByNovel(snapshot, novelId) else snapshot
                // filterByNovel 후 다른 세계관의 fieldDefId가 스냅샷에 없을 수 있음 → 교집합
                val validIds = fieldDefIds.filter { id -> scoped.fieldDefinitions.any { it.id == id } }
                if (validIds.isEmpty()) {
                    _rankingResult.value = StatsDataProvider.RankingResult(emptyList(), "", "", ascending, 0, 0)
                    return@launch
                }
                _rankingResult.value = withContext(Dispatchers.IO) {
                    provider.computeRanking(scoped, validIds, ascending, bodySizePartIndex)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun getUniverseList(): List<Pair<Long, String>> {
        val snapshot = cachedSnapshot ?: return emptyList()
        return snapshot.universes.map { it.id to it.name }
    }

    fun getNovelListForUniverse(universeId: Long): List<Pair<Long, String>> {
        val snapshot = cachedSnapshot ?: return emptyList()
        return snapshot.novels.filter { it.universeId == universeId }.map { it.id to it.title }
    }

    // ===== 개선 3: 패턴 인사이트 =====
    private val _patternInsights = MutableLiveData<List<PatternInsight>>()
    val patternInsights: LiveData<List<PatternInsight>> = _patternInsights

    fun loadPatternInsights() {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val enabledTypes = getEnabledPatternTypes()
                _patternInsights.value = withContext(Dispatchers.IO) {
                    provider.detectPatterns(filtered, enabledTypes)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** SharedPreferences에서 사용자가 활성화한 패턴 유형 목록을 읽는다. */
    private fun getEnabledPatternTypes(): Set<PatternType> {
        val prefs = getApplication<Application>().getSharedPreferences("stats_prefs", 0)
        val stored = prefs.getStringSet("pattern_insights_enabled_types", null)
            ?: return PatternType.values().toSet() // 기본값: 전체 활성
        return stored.mapNotNull { name ->
            try { PatternType.valueOf(name) } catch (_: Exception) { null }
        }.toSet()
    }

    /** 사용자가 선택한 패턴 유형을 저장한다. */
    fun saveEnabledPatternTypes(enabledTypes: Set<PatternType>) {
        val prefs = getApplication<Application>().getSharedPreferences("stats_prefs", 0)
        prefs.edit().putStringSet("pattern_insights_enabled_types",
            enabledTypes.map { it.name }.toSet()).apply()
    }

    // ===== 개선 6: 차트 탭 → 캐릭터 목록 =====

    private val _chartTapCharacters = MutableLiveData<List<FieldValueCharacter>?>()
    val chartTapCharacters: LiveData<List<FieldValueCharacter>?> = _chartTapCharacters

    private val _subgroupAnalysis = MutableLiveData<SubgroupAnalysis?>()
    val subgroupAnalysis: LiveData<SubgroupAnalysis?> = _subgroupAnalysis

    fun loadCharactersByFieldValue(fieldDefId: Long, value: String) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _chartTapCharacters.value = withContext(Dispatchers.IO) {
                    provider.getCharactersByFieldValue(filtered, fieldDefId, value)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun loadSubgroupAnalysis(characterIds: Set<Long>, targetFieldDefId: Long) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _subgroupAnalysis.value = withContext(Dispatchers.IO) {
                    provider.computeSubgroupAnalysis(filtered, characterIds, targetFieldDefId)
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun clearChartTapData() {
        _chartTapCharacters.value = null
        _subgroupAnalysis.value = null
    }

    /** 현재 스냅샷의 필드 정의 목록 반환 (하위 그룹 분석 필드 선택용) */
    fun getFieldDefinitions(): List<FieldDefinition> {
        val snapshot = cachedSnapshot ?: return emptyList()
        val filtered = getFilteredSnapshot(snapshot)
        return filtered.fieldDefinitions
    }

    // ===== 인라인 분석 설정 업데이트 =====

    fun updateFieldStatsConfig(fieldDef: FieldDefinition, newConfig: FieldStatsConfig) {
        viewModelScope.launch {
            try {
                val updatedConfigJson = FieldStatsConfig.applyToConfig(fieldDef.config, newConfig)
                val updatedField = fieldDef.copy(config = updatedConfigJson)
                withContext(Dispatchers.IO) {
                    app.universeRepository.updateField(updatedField)
                }
                // 캐시 무효화 후 전체 통계 재로딩
                cachedSnapshot = null
                isRefreshing = true
                loadAllStats()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    // ===== 레거시 load 메서드 (기존 Fragment 호환) =====

    fun loadCharacterStats() {
        if (_characterStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _characterStats.value = withContext(Dispatchers.IO) { provider.computeCharacterStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadEventStats() {
        if (_eventStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _eventStats.value = withContext(Dispatchers.IO) { provider.computeEventStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadRelationshipStats() {
        if (_relationshipStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _relationshipStats.value = withContext(Dispatchers.IO) { provider.computeRelationshipStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadNameBankStats() {
        if (_nameBankStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _nameBankStats.value = withContext(Dispatchers.IO) { provider.computeNameBankStats(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadDataHealthStats() {
        if (_dataHealthStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _dataHealthStats.value = withContext(Dispatchers.IO) { provider.computeDataHealth(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    fun loadFieldAnalysisStats() {
        if (_fieldAnalysisStats.value != null && !isRefreshing) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                _fieldAnalysisStats.value = withContext(Dispatchers.IO) { provider.computeFieldAnalysis(filtered) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }
}
