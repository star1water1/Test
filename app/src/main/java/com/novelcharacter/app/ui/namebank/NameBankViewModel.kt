package com.novelcharacter.app.ui.namebank

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import android.util.Log
import kotlinx.coroutines.launch

class NameBankViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val nameBankRepository = app.nameBankRepository
    private val prefs = application.getSharedPreferences("namebank_ui_state", Context.MODE_PRIVATE)

    // 데이터 처리 결과 알림 채널 (이름은행 CRUD·사용처리/해제 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

    private val _searchQuery = MutableLiveData("")
    private val _showOnlyAvailable = MutableLiveData(prefs.getBoolean("show_only_available", false))

    val displayedNames: LiveData<List<NameBankEntry>> = MediatorLiveData<List<NameBankEntry>>().apply {
        val allNames = nameBankRepository.allNameBankEntries
        val availableNames = nameBankRepository.availableNameBankEntries

        // Cache the latest values from both sources to avoid stale data
        var latestAll: List<NameBankEntry> = emptyList()
        var latestAvailable: List<NameBankEntry> = emptyList()

        fun update() {
            val query = _searchQuery.value ?: ""
            val onlyAvailable = _showOnlyAvailable.value ?: false
            val currentList = if (onlyAvailable) latestAvailable else latestAll
            value = if (query.isBlank()) {
                currentList
            } else {
                currentList.filter {
                    it.name.contains(query, ignoreCase = true) ||
                    it.notes.contains(query, ignoreCase = true) ||
                    it.origin.contains(query, ignoreCase = true)
                }
            }
        }

        addSource(allNames) { latestAll = it; update() }
        addSource(availableNames) { latestAvailable = it; update() }
        addSource(_searchQuery) { update() }
        addSource(_showOnlyAvailable) { update() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowOnlyAvailable(onlyAvailable: Boolean) {
        _showOnlyAvailable.value = onlyAvailable
        prefs.edit().putBoolean("show_only_available", onlyAvailable).apply()
    }

    fun isShowOnlyAvailable(): Boolean = _showOnlyAvailable.value ?: false

    fun insert(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.insertNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_added, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to insert name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_add_failed), e.message))
        }
    }

    fun update(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.updateNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_updated, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to update name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_update_failed), e.message))
        }
    }

    fun delete(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.deleteNameBankEntry(entry)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_deleted, entry.name)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to delete name bank entry", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_delete_failed), e.message))
        }
    }

    fun markAsUsed(id: Long, characterId: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsUsed(id, characterId)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_marked_used)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as used", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_mark_used_failed), e.message))
        }
    }

    fun markAsAvailable(id: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsAvailable(id)
            reportResult(_result, OpResult.success(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_marked_available)))
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as available", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NAMEBANK,
                app.getString(R.string.result_name_mark_available_failed), e.message))
        }
    }

    suspend fun getAvailableNamesList(): List<NameBankEntry> =
        nameBankRepository.getAvailableNameBankList()
}
