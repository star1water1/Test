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

    private val _summary = MutableLiveData<SummaryStats>()
    val summary: LiveData<SummaryStats> = _summary

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

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private suspend fun ensureSnapshot(): StatsSnapshot {
        return cachedSnapshot ?: withContext(Dispatchers.IO) {
            provider.loadSnapshot()
        }.also { cachedSnapshot = it }
    }

    fun refreshStats() {
        cachedSnapshot = null
        _summary.value = null
        _characterStats.value = null
        _eventStats.value = null
        _relationshipStats.value = null
        _nameBankStats.value = null
        _dataHealthStats.value = null
        loadAllStats()
    }

    fun loadAllStats() {
        if (_summary.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                val summary = withContext(Dispatchers.IO) { provider.computeSummary(snapshot) }
                val chars = withContext(Dispatchers.IO) { provider.computeCharacterStats(snapshot) }
                val events = withContext(Dispatchers.IO) { provider.computeEventStats(snapshot) }
                val rels = withContext(Dispatchers.IO) { provider.computeRelationshipStats(snapshot) }
                val names = withContext(Dispatchers.IO) { provider.computeNameBankStats(snapshot) }
                val health = withContext(Dispatchers.IO) { provider.computeDataHealth(snapshot) }

                _summary.value = summary
                _characterStats.value = chars
                _eventStats.value = events
                _relationshipStats.value = rels
                _nameBankStats.value = names
                _dataHealthStats.value = health
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadCharacterStats() {
        if (_characterStats.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _characterStats.value = withContext(Dispatchers.IO) { provider.computeCharacterStats(snapshot) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadEventStats() {
        if (_eventStats.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _eventStats.value = withContext(Dispatchers.IO) { provider.computeEventStats(snapshot) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadRelationshipStats() {
        if (_relationshipStats.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _relationshipStats.value = withContext(Dispatchers.IO) { provider.computeRelationshipStats(snapshot) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadNameBankStats() {
        if (_nameBankStats.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _nameBankStats.value = withContext(Dispatchers.IO) { provider.computeNameBankStats(snapshot) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }

    fun loadDataHealthStats() {
        if (_dataHealthStats.value != null) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val snapshot = ensureSnapshot()
                _dataHealthStats.value = withContext(Dispatchers.IO) { provider.computeDataHealth(snapshot) }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _loading.value = false
            }
        }
    }
}
