package com.novelcharacter.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.util.FieldValueMatchSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val provider = StatsDataProvider()

    @Volatile private var cachedSnapshot: StatsSnapshot? = null
    private val snapshotMutex = Mutex()

    // 작품 필터 (SharedPreferences에서 복원)
    private val statsPrefs = application.getSharedPreferences("stats_prefs", 0)
    private val _selectedNovelId = MutableLiveData<Long?>(
        if (statsPrefs.contains("selected_novel_id")) statsPrefs.getLong("selected_novel_id", -1L) else null
    )
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

    /**
     * 예외를 **반드시 보이는** 문구로 바꿔 싣는다 (B-32).
     *
     * 종전에는 catch 21곳이 `_error.value = e.message`였는데 관측자는 전부 `error != null`로
     * 거른다. NPE·IndexOutOfBounds·ConcurrentModification처럼 **message가 null인 예외**가 나면
     * 토스트조차 뜨지 않고 `finally`에서 로딩만 걷혀 화면이 백지로 남았다 — 사용자에게는
     * 고장과 구분되지 않는다. 21곳을 개별로 손보는 것은 그 자체가 방편식이므로 한 곳으로 모은다.
     *
     * 메시지가 없으면 **예외 종류라도** 싣는다. 원인을 못 적는 것과 아무 말도 안 하는 것은 다르다.
     */
    private fun reportError(e: Exception) {
        // 취소는 오류가 아니다. 작품 필터를 로딩 중에 바꾸면 statsJob이 취소되는데,
        // CancellationException도 Exception이라 이 catch에 걸려 **정상 조작이 오류 토스트를
        // 낳았다.** 코루틴 규약대로 되던져 취소가 전파되게 한다.
        //
        // **단, 원인이 딸린 취소는 취소가 아니다.** `loadAllStats`는 계산 10개를 `async`로 돌리는데
        // 그중 하나가 터지면 부모 잡이 취소되고, 다른 `await`는 **원래 예외를 cause로 단**
        // JobCancellationException을 던진다. 그것까지 되던지면 진짜 실패가 아무 고지 없이
        // 사라져 화면이 백지로 남는다 — B-32가 없애려던 바로 그 상태다.
        // 그래서 cause가 있으면 취소가 아니라 **그 원인을 보고**한다.
        val cause = e.cause
        if (e is kotlinx.coroutines.CancellationException && cause == null) throw e
        val real = if (e is kotlinx.coroutines.CancellationException) cause else e
        val detail = real?.message?.takeIf { it.isNotBlank() } ?: (real ?: e).javaClass.simpleName
        _error.value = getApplication<Application>()
            .getString(com.novelcharacter.app.R.string.stats_error_detail, detail)
    }

    /**
     * 오류를 **한 번 보여주고 비운다** (B-32 인접).
     *
     * `_error`는 10개 화면이 `activityViewModels()`로 공유하는 보통의 LiveData이고,
     * 비우는 곳은 전부 로더 진입부인데 로더들은 캐시가 살아 있으면 조기 반환한다.
     * 그래서 화면을 회전하거나 되돌아오면 **이미 지나간 오류**가 sticky 값으로 다시 떴다 —
     * 다른 화면에서 난 오류가 엉뚱한 화면에 나타나기도 했다. 표시한 쪽이 소비한다.
     */
    fun consumeError() {
        if (_error.value != null) _error.value = null
    }

    private suspend fun ensureSnapshot(): StatsSnapshot {
        cachedSnapshot?.let { return it }
        return snapshotMutex.withLock {
            cachedSnapshot ?: withContext(Dispatchers.IO) {
                provider.loadSnapshot(app, CompletionWeightPrefs.weights(getApplication()))
            }.also { cachedSnapshot = it }
        }
    }

    /**
     * 필터본 스냅샷 캐시 — **객체 동일성이 계산 캐시의 키**이기 때문에 필요하다.
     *
     * 종전에는 로더마다 `filterByNovel`을 새로 불러 매번 **다른 객체**를 만들었다. 그래서
     * 계산기의 스냅샷 동일성 캐시(계산 필드 값 등)가 호출 간에 한 번도 적중하지 못하고,
     * 드릴다운·하위 그룹 같은 짧은 조작마다 수식 전량이 다시 평가됐다.
     * 원본 스냅샷이 무효화되거나 필터가 바뀌면 함께 버린다.
     */
    // 키(원본 스냅샷 + 작품 id)와 값(필터본)을 **한 객체로** 게시한다. 따로 쓰면 서로 다른 작품을
    // 동시에 요청한 두 코루틴의 기록이 교차해 "작품 A의 키 + 작품 B의 필터본"이 남고,
    // 그다음부터 모든 화면이 남의 작품 데이터로 계산된다(예외도 고지도 없이).
    private class FilteredCache(
        val source: StatsSnapshot,
        val novelId: Long,
        val filtered: StatsSnapshot
    )

    @Volatile private var filteredCache: FilteredCache? = null

    private suspend fun getFilteredSnapshot(snapshot: StatsSnapshot): StatsSnapshot {
        val novelId = _selectedNovelId.value ?: return snapshot
        filteredCache?.let {
            if (it.source === snapshot && it.novelId == novelId) return it.filtered
        }
        // 필터(약 16개 전체 리스트 순회)를 IO로 — 노벨 필터 활성 시 규모에 비례한 메인 스레드 잰크 방지(P2-5).
        val filtered = withContext(Dispatchers.IO) { provider.filterByNovel(snapshot, novelId) }
        filteredCache = FilteredCache(snapshot, novelId, filtered)
        return filtered
    }

    private fun invalidateSnapshots() {
        cachedSnapshot = null
        filteredCache = null
    }

    /**
     * 완성도 가중 설정이 바뀌었다 (B-100).
     *
     * 가중은 **스냅샷에 실려** 계산되므로(`StatsSnapshot.completionWeights`) 캐시를 버려야
     * 새 배수가 반영된다 — 버리지 않으면 설정을 바꿔도 숫자가 그대로이고, 그것이 R-24가
     * 금지한 '고를 수 있는데 아무 일도 일어나지 않는' 자리다.
     */
    fun onCompletionWeightChanged() {
        invalidateSnapshots()
    }

    @Volatile private var isRefreshing = false
    private var statsJob: Job? = null

    fun setNovelFilter(novelId: Long?) {
        _selectedNovelId.value = novelId
        statsPrefs.edit().apply {
            if (novelId != null) putLong("selected_novel_id", novelId) else remove("selected_novel_id")
        }.apply()
        refreshStats()
    }

    fun refreshStats() {
        invalidateSnapshots()
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

                // 작품 목록 설정
                _novelList.value = snapshot.novels.map { it.id to it.title }

                // 삭제된 소설 참조 정리: 복원된 ID가 현재 데이터에 없으면 필터 해제.
                // "작품 미배정" sentinel은 실제 작품이 아니므로 정리 대상에서 제외한다.
                val currentNovelId = _selectedNovelId.value
                if (currentNovelId != null &&
                    currentNovelId != com.novelcharacter.app.util.UnassignedFilter.NO_NOVEL_ID &&
                    snapshot.novels.none { it.id == currentNovelId }
                ) {
                    _selectedNovelId.value = null
                    statsPrefs.edit().remove("selected_novel_id").apply()
                }

                val filtered = getFilteredSnapshot(snapshot)

                // 모든 통계를 병렬로 계산한 후 한 번에 LiveData에 반영
                val summaryDeferred = async(Dispatchers.IO) { provider.computeSummary(filtered) }
                val insightsDeferred = async(Dispatchers.IO) { provider.computeFieldInsights(filtered) }
                val charsDeferred = async(Dispatchers.IO) { provider.computeCharacterStats(filtered) }
                val eventsDeferred = async(Dispatchers.IO) { provider.computeEventStats(filtered) }
                val relsDeferred = async(Dispatchers.IO) { provider.computeRelationshipStats(filtered) }
                val namesDeferred = async(Dispatchers.IO) { provider.computeNameBankStats(filtered) }
                val healthDeferred = async(Dispatchers.IO) { provider.computeDataHealth(filtered) }
                val fieldAnalysisDeferred = async(Dispatchers.IO) { provider.computeFieldAnalysis(filtered) }
                val enabledPatternTypes = getEnabledPatternTypes()
                val patternsDeferred = async(Dispatchers.IO) { provider.detectPatterns(filtered, enabledPatternTypes) }
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
                reportError(e)
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
                reportError(e)
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    /**
     * 교차분석 실행 (B-4).
     *
     * 축(캐릭터/사건)을 먼저 판정해 해당 축의 계산 함수로 보낸다. 판정이 실패하면
     * 조용히 넘어가지 않고 이유를 [error]로 알린다 — 예전에는 사건 필드를 고르면
     * `computeCrossAnalysis`가 null을 돌려주고 화면에 아무 일도 일어나지 않았다.
     */
    fun loadCrossAnalysis(field1Id: Long, field2Id: Long, filterFieldId: Long? = null, filterValue: String? = null) {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val outcome = withContext(Dispatchers.IO) {
                    when (val axis = provider.resolveCrossAxis(filtered, field1Id, field2Id, filterFieldId)) {
                        is CrossAxisResolution.Mismatch -> axis
                        is CrossAxisResolution.UnknownField -> axis
                        is CrossAxisResolution.Resolved -> when (axis.axis) {
                            CrossAxis.CHARACTER ->
                                provider.computeCrossAnalysis(filtered, field1Id, field2Id, filterFieldId, filterValue)
                            CrossAxis.EVENT ->
                                provider.computeEventCrossAnalysis(filtered, field1Id, field2Id, filterFieldId, filterValue)
                        }
                    }
                }
                when (outcome) {
                    is CrossAnalysisResult -> _crossAnalysis.value = outcome
                    is CrossAxisResolution.Mismatch -> _error.value = getApplication<Application>().getString(
                        com.novelcharacter.app.R.string.stats_cross_axis_mismatch,
                        outcome.characterFieldName, outcome.eventFieldName
                    )
                    // 축 판정은 통과했지만 계산이 필드를 찾지 못한 경우(null)도 같은 사유다.
                    else -> _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_cross_field_missing)
                }
            } catch (e: Exception) {
                reportError(e)
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }

    // ===== 순위 =====
    private val _rankingResult = MutableLiveData<RankingResult?>()
    val rankingResult: LiveData<RankingResult?> = _rankingResult

    /** 순위 화면의 선택지 — 필드 다음에 대결 축이다(B-117. 차례는 `rankingSources`가 정한다). */
    private val _rankingSources = MutableLiveData<List<RankingSource>>()
    val rankingSources: LiveData<List<RankingSource>> = _rankingSources

    private var rankingSourcesJob: Job? = null

    fun loadRankingSources(universeId: Long?) {
        // 이전 로드 취소 — 세계관 전환/복원 시 여러 로드가 겹쳐 마지막 것이 아닌 결과가
        // 스피너를 덮어쓰는 경쟁을 막는다(마지막 요청만 확정).
        rankingSourcesJob?.cancel()
        rankingSourcesJob = viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val sources = withContext(Dispatchers.IO) {
                    provider.rankingSources(snapshot, universeId)
                }
                _rankingSources.value = sources
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /**
     * 대결 점수로 순위를 낸다 (B-117).
     *
     * **적합은 축 전체로 돈다** — 넘기는 참가자는 화면 스코프의 캐릭터가 아니라 그 축이
     * 매달린 세계관의 캐릭터 전부다. BT는 결과 집합의 함수라 참가자를 빼면 점수가 달라지고,
     * 작품 필터를 걸 때마다 점수가 흔들리면 **순위표와 다른 수**가 된다. 스코프는 표에서
     * *무엇을 보이는가*에만 쓴다([StatsDataProvider.computeDuelRanking]).
     */
    fun loadDuelRanking(axisCode: String, ascending: Boolean = false, novelId: Long? = null) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val axis = withContext(Dispatchers.IO) { app.duelRepository.axisByCode(axisCode) }
                if (axis == null) {
                    // 축이 사라졌다(다른 화면에서 지웠다) — 빈 표로 답하고 조용히 죽지 않는다.
                    _rankingResult.value = RankingResult(emptyList(), "", "", ascending, 0, 0)
                    return@launch
                }
                val scores = withContext(Dispatchers.IO) {
                    val participants = app.characterRepository
                        .getCharactersByUniverseList(axis.universeId).map { it.code }
                    app.duelRepository.scoresOf(axis, participants)
                }
                val scoped = if (novelId != null) provider.filterByNovel(snapshot, novelId) else snapshot
                _rankingResult.value = withContext(Dispatchers.IO) {
                    provider.computeDuelRanking(scoped, scores, ascending)
                }
            } catch (e: Exception) {
                reportError(e)
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
                    _rankingResult.value = RankingResult(emptyList(), "", "", ascending, 0, 0)
                    return@launch
                }
                _rankingResult.value = withContext(Dispatchers.IO) {
                    provider.computeRanking(scoped, validIds, ascending, bodySizePartIndex)
                }
            } catch (e: Exception) {
                reportError(e)
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
                reportError(e)
            }
        }
    }

    /**
     * 화면이 "왜 비었는지"를 말할 수 있도록 공개한다 — 감지된 패턴이 없는 것과 사용자가
     * 모든 유형을 꺼 둔 것은 전혀 다른 사실이다(R-17, B-31).
     */
    fun enabledPatternTypes(): Set<PatternType> = getEnabledPatternTypes()

    /** 저장·해석 규칙은 [PatternTypePrefs] 하나다 — 같은 키를 세 곳이 따로 파싱하던 것을 모았다. */
    private fun getEnabledPatternTypes(): Set<PatternType> =
        PatternTypePrefs.enabled(getApplication())

    /** 사용자가 선택한 패턴 유형을 저장한다. */
    fun saveEnabledPatternTypes(enabledTypes: Set<PatternType>) {
        PatternTypePrefs.save(getApplication(), enabledTypes)
    }

    // ===== 개선 6: 차트 탭 → 캐릭터 목록 =====

    private val _chartTapCharacters = MutableLiveData<List<FieldValueCharacter>?>()
    val chartTapCharacters: LiveData<List<FieldValueCharacter>?> = _chartTapCharacters

    /** 사건 필드 카드의 드릴다운 결과 (S-9) — 캐릭터 목록과 단위가 달라 따로 싣는다. */
    private val _chartTapEvents = MutableLiveData<List<FieldValueEvent>?>()
    val chartTapEvents: LiveData<List<FieldValueEvent>?> = _chartTapEvents

    /** 작품 필드 카드의 드릴다운 결과 (확-3) — 같은 이유로 따로 싣는다(셀 단위가 작품 수다). */
    private val _chartTapNovels = MutableLiveData<List<FieldValueNovel>?>()
    val chartTapNovels: LiveData<List<FieldValueNovel>?> = _chartTapNovels

    private val _subgroupAnalysis = MutableLiveData<SubgroupAnalysis?>()
    val subgroupAnalysis: LiveData<SubgroupAnalysis?> = _subgroupAnalysis

    /**
     * [fieldDefIds]에는 인사이트 카드가 합산한 머지 id 전체를 준다 — 대표 id 하나만 주면
     * 전체 세계관 보기에서 차트보다 적은 인원이 나온다(S-7).
     * 필드 정의를 찾지 못하면 빈 목록으로 위장하지 않고 사유를 알린다(변수 제어: 검증→알림).
     */
    fun loadCharactersByFieldValue(fieldDefIds: List<Long>, value: String) =
        loadCharactersByFieldValue(fieldDefIds, FieldValueMatchSpec.Values(value))

    /** 매치 스펙판 — 구간 조각·접힌 '기타' 묶음까지 화면이 보여준 규칙 그대로 조회한다(S-16·S-17). */
    fun loadCharactersByFieldValue(fieldDefIds: List<Long>, spec: FieldValueMatchSpec) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val found = withContext(Dispatchers.IO) {
                    provider.getCharactersByFieldValue(filtered, fieldDefIds, spec)
                }
                if (found == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_drilldown_field_missing)
                } else {
                    _chartTapCharacters.value = found
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /** 사건 필드 카드 드릴다운 (S-9) — 캐릭터 경로와 대칭. */
    fun loadEventsByFieldValue(fieldDefIds: List<Long>, value: String) =
        loadEventsByFieldValue(fieldDefIds, FieldValueMatchSpec.Values(value))

    /** 매치 스펙판 — 캐릭터 축과 대칭이다(R-16의 짝 규칙). */
    fun loadEventsByFieldValue(fieldDefIds: List<Long>, spec: FieldValueMatchSpec) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val found = withContext(Dispatchers.IO) {
                    provider.getEventsByFieldValue(filtered, fieldDefIds, spec)
                }
                if (found == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_drilldown_field_missing)
                } else {
                    _chartTapEvents.value = found
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /** 작품 필드 카드 드릴다운 (확-3) — 캐릭터·사건 경로와 대칭. */
    fun loadNovelsByFieldValue(fieldDefIds: List<Long>, spec: FieldValueMatchSpec) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val found = withContext(Dispatchers.IO) {
                    provider.getNovelsByFieldValue(filtered, fieldDefIds, spec)
                }
                if (found == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_drilldown_field_missing)
                } else {
                    _chartTapNovels.value = found
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    fun loadSubgroupAnalysis(characterIds: Set<Long>, targetFieldDefIds: List<Long>) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val result = withContext(Dispatchers.IO) {
                    provider.computeSubgroupAnalysis(filtered, characterIds, targetFieldDefIds)
                }
                if (result == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_subgroup_field_missing)
                } else {
                    _subgroupAnalysis.value = result
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /** 사건 하위 그룹 분석 (S-9) — 셀 단위가 사건 수라 캐릭터판과 함수를 나눈다(R-13). */
    fun loadEventSubgroupAnalysis(eventIds: Set<Long>, targetFieldDefIds: List<Long>) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val result = withContext(Dispatchers.IO) {
                    provider.computeEventSubgroupAnalysis(filtered, eventIds, targetFieldDefIds)
                }
                if (result == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_subgroup_field_missing)
                } else {
                    _subgroupAnalysis.value = result
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /** 작품 축 하위 그룹 분석 (확-3) — 캐릭터·사건판과 대칭(R-13: 축마다 함수를 나눈다). */
    fun loadNovelSubgroupAnalysis(novelIds: Set<Long>, targetFieldDefIds: List<Long>) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)
                val result = withContext(Dispatchers.IO) {
                    provider.computeNovelSubgroupAnalysis(filtered, novelIds, targetFieldDefIds)
                }
                if (result == null) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_subgroup_field_missing)
                } else {
                    _subgroupAnalysis.value = result
                }
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    fun clearChartTapData() {
        _chartTapCharacters.value = null
        _chartTapEvents.value = null
        _chartTapNovels.value = null
        _subgroupAnalysis.value = null
    }

    /**
     * 하위 그룹 분석 필드 선택용 목록 — 인사이트 카드와 **같은 (key,type) 머지 축**으로 묶어 돌려준다.
     * 머지하지 않으면 전체 세계관 보기에서 같은 필드가 중복 나열되고, 고른 것이 한 세계관 값만
     * 집계해 카드와 다른 답을 준다. 동기 getter라 필터를 인라인 수행한다.
     */
    /**
     * '통계에 포함'이 꺼져 목록에서 빠진 필드 그룹 수 — 화면이 **사실대로** 안내하기 위해 필요하다.
     * "함께 볼 다른 필드가 없습니다"와 "있지만 통계에서 제외돼 있습니다"는 전혀 다른 사실이고,
     * 후자는 되돌리는 경로(필드 편집의 토글)가 있다(R-17).
     */
    fun statsExcludedFieldGroupCount(axis: StatsEntityAxis): Int {
        val snapshot = cachedSnapshot ?: return 0
        val novelId = _selectedNovelId.value
        val filtered = if (novelId != null) provider.filterByNovel(snapshot, novelId) else snapshot
        val defs = axis.definitionsIn(filtered)
        val all = defs.groupBy { it.key to it.type }.size
        return (all - provider.getMergedFieldGroups(defs).size).coerceAtLeast(0)
    }

    fun getMergedFieldGroups(axis: StatsEntityAxis): List<MergedFieldGroup> {
        val snapshot = cachedSnapshot ?: return emptyList()
        val novelId = _selectedNovelId.value
        val filtered = if (novelId != null) provider.filterByNovel(snapshot, novelId) else snapshot
        return provider.getMergedFieldGroups(axis.definitionsIn(filtered))
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
                invalidateSnapshots()
                isRefreshing = true
                loadAllStats()
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /**
     * 머지된 카드의 분석 설정을 **그룹 전체**에 적용한다 (B-34).
     *
     * 인사이트 카드는 같은 (key, type) 필드를 세계관 통합으로 한 장에 보여준다. 그 카드의
     * 톱니가 대표 def 하나만 고치면, 사용자는 "이 카드"의 설정을 바꿨다고 생각하는데 작품
     * 필터를 바꿔 형제 세계관이 대표가 되는 순간 옛 설정이 되살아난다 — 바꾼 것이 안 바뀐
     * 것으로 보인다. R-15("카드에서 뻗는 모든 경로는 같은 축")의 마지막 미적용 경로였다.
     *
     * **형제의 나머지 설정은 건드리지 않는다.** 대표의 config를 통째로 복사하면 세계관별로
     * 일부러 다르게 둔 값 라벨·카테고리·구간·'통계에 포함'까지 덮어써 자율성을 침해한다.
     * 다이얼로그가 실제로 편집한 것은 분석 항목([FieldStatsConfig.AnalysisEntry]) 하나이므로
     * 각 def의 자기 설정 위에 그 항목만 얹는다.
     */
    fun updateMergedFieldAnalysis(fieldDefIds: List<Long>, entry: FieldStatsConfig.AnalysisEntry) {
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val targets = (snapshot.fieldDefinitions + snapshot.eventFieldDefinitions)
                    .filter { it.id in fieldDefIds.toSet() }
                if (targets.isEmpty()) {
                    _error.value = getApplication<Application>()
                        .getString(com.novelcharacter.app.R.string.stats_drilldown_field_missing)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    for (fd in targets) {
                        val own = FieldStatsConfig.fromConfig(fd.config)
                        // **첫 항목만 교체하고 나머지는 남긴다.** 다이얼로그가 보여주고 편집한 것은
                        // 첫 분석 항목 하나뿐인데 `listOf(entry)`로 덮으면, 형제 def에 따로 만들어 둔
                        // 두 번째·세 번째 분석 항목이 차트 종류를 바꾼 것만으로 사라진다(무통보 유실).
                        val merged = if (own.analyses.size <= 1) listOf(entry)
                                     else listOf(entry) + own.analyses.drop(1)
                        val json = FieldStatsConfig.applyToConfig(fd.config, own.copy(analyses = merged))
                        app.universeRepository.updateField(fd.copy(config = json))
                    }
                }
                invalidateSnapshots()
                isRefreshing = true
                loadAllStats()
            } catch (e: Exception) {
                reportError(e)
            }
        }
    }

    /** 머지 그룹의 실제 정의들 — 화면이 "형제들의 설정이 서로 다르다"를 말할 수 있게 한다. */
    fun fieldDefsByIds(ids: List<Long>): List<FieldDefinition> {
        val snapshot = cachedSnapshot ?: return emptyList()
        val idSet = ids.toSet()
        return (snapshot.fieldDefinitions + snapshot.eventFieldDefinitions).filter { it.id in idSet }
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
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
                reportError(e)
            } finally {
                dismissLoadingIfIdle()
            }
        }
    }
}
