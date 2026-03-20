package com.novelcharacter.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import kotlinx.coroutines.Dispatchers
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
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val filtered = getFilteredSnapshot(snapshot)

                // 작품 목록 설정
                _novelList.value = snapshot.novels.map { it.id to it.title }

                val summary = withContext(Dispatchers.IO) { provider.computeSummary(filtered) }
                val insights = withContext(Dispatchers.IO) { provider.computeFieldInsights(filtered) }

                _summary.value = summary
                _fieldInsights.value = insights

                // 레거시 호환: 기존 Fragment가 참조하는 값들도 로드
                val chars = withContext(Dispatchers.IO) { provider.computeCharacterStats(filtered) }
                val events = withContext(Dispatchers.IO) { provider.computeEventStats(filtered) }
                val rels = withContext(Dispatchers.IO) { provider.computeRelationshipStats(filtered) }
                val names = withContext(Dispatchers.IO) { provider.computeNameBankStats(filtered) }
                val health = withContext(Dispatchers.IO) { provider.computeDataHealth(filtered) }

                val fieldAnalysis = withContext(Dispatchers.IO) { provider.computeFieldAnalysis(filtered) }

                _characterStats.value = chars
                _eventStats.value = events
                _relationshipStats.value = rels
                _nameBankStats.value = names
                _dataHealthStats.value = health
                _fieldAnalysisStats.value = fieldAnalysis
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                isRefreshing = false
                _loading.value = false
            }
        }
    }

    // ===== 신규 load 메서드 =====

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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
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
                _loading.value = false
            }
        }
    }
}
