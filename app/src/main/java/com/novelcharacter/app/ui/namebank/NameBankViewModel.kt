package com.novelcharacter.app.ui.namebank

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.NameBankEntry
import kotlinx.coroutines.launch

class NameBankViewModel(application: Application) : AndroidViewModel(application) {

    private val nameBankRepository = (application as NovelCharacterApp).nameBankRepository

    private val _searchQuery = MutableLiveData("")
    private val _showOnlyAvailable = MutableLiveData(false)

    val displayedNames: LiveData<List<NameBankEntry>> = MediatorLiveData<List<NameBankEntry>>().apply {
        val allNames = nameBankRepository.allNameBankEntries
        val availableNames = nameBankRepository.availableNameBankEntries

        fun update() {
            val query = _searchQuery.value ?: ""
            val onlyAvailable = _showOnlyAvailable.value ?: false
            // We'll use a coroutine to filter if needed
            val source = if (onlyAvailable) availableNames else allNames
            val currentList = source.value ?: emptyList()
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

        addSource(allNames) { update() }
        addSource(availableNames) { update() }
        addSource(_searchQuery) { update() }
        addSource(_showOnlyAvailable) { update() }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setShowOnlyAvailable(onlyAvailable: Boolean) {
        _showOnlyAvailable.value = onlyAvailable
    }

    fun isShowOnlyAvailable(): Boolean = _showOnlyAvailable.value ?: false

    fun insert(entry: NameBankEntry) = viewModelScope.launch {
        nameBankRepository.insertNameBankEntry(entry)
    }

    fun update(entry: NameBankEntry) = viewModelScope.launch {
        nameBankRepository.updateNameBankEntry(entry)
    }

    fun delete(entry: NameBankEntry) = viewModelScope.launch {
        nameBankRepository.deleteNameBankEntry(entry)
    }

    fun markAsUsed(id: Long, characterId: Long) = viewModelScope.launch {
        nameBankRepository.markNameBankAsUsed(id, characterId)
    }

    fun markAsAvailable(id: Long) = viewModelScope.launch {
        nameBankRepository.markNameBankAsAvailable(id)
    }

    suspend fun getAvailableNamesList(): List<NameBankEntry> =
        nameBankRepository.getAvailableNameBankList()
}
