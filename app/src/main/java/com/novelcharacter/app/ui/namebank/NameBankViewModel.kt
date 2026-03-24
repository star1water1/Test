package com.novelcharacter.app.ui.namebank

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.NameBankEntry
import android.util.Log
import kotlinx.coroutines.launch

class NameBankViewModel(application: Application) : AndroidViewModel(application) {

    private val nameBankRepository = (application as NovelCharacterApp).nameBankRepository
    private val prefs = application.getSharedPreferences("namebank_ui_state", Context.MODE_PRIVATE)

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
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to insert name bank entry", e)
        }
    }

    fun update(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.updateNameBankEntry(entry)
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to update name bank entry", e)
        }
    }

    fun delete(entry: NameBankEntry) = viewModelScope.launch {
        try {
            nameBankRepository.deleteNameBankEntry(entry)
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to delete name bank entry", e)
        }
    }

    fun markAsUsed(id: Long, characterId: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsUsed(id, characterId)
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as used", e)
        }
    }

    fun markAsAvailable(id: Long) = viewModelScope.launch {
        try {
            nameBankRepository.markNameBankAsAvailable(id)
        } catch (e: Exception) {
            Log.e("NameBankViewModel", "Failed to mark as available", e)
        }
    }

    suspend fun getAvailableNamesList(): List<NameBankEntry> =
        nameBankRepository.getAvailableNameBankList()
}
