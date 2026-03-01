package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.launch

class CharacterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NovelCharacterApp).repository

    val allCharacters: LiveData<List<Character>> = repository.allCharacters
    val allNovels: LiveData<List<Novel>> = repository.allNovels

    private val _currentNovelId = MutableLiveData<Long?>()

    val filteredCharacters: LiveData<List<Character>> = _currentNovelId.switchMap { novelId ->
        if (novelId == null || novelId == -1L) {
            repository.allCharacters
        } else {
            repository.getCharactersByNovel(novelId)
        }
    }

    private val _searchQuery = MutableLiveData<String>()
    val searchResults: LiveData<List<Character>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            filteredCharacters
        } else {
            repository.searchCharacters(query)
        }
    }

    fun setNovelFilter(novelId: Long?) {
        _currentNovelId.value = novelId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getCharacterById(id: Long): LiveData<Character?> = repository.getCharacterByIdLive(id)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        repository.getEventsForCharacter(characterId)

    suspend fun getCharacterByIdSuspend(id: Long): Character? = repository.getCharacterById(id)

    suspend fun getNovelById(id: Long): Novel? = repository.getNovelById(id)

    suspend fun getAllNovelsList(): List<Novel> = repository.getAllNovelsList()

    fun insertCharacter(character: Character) = viewModelScope.launch {
        repository.insertCharacter(character)
    }

    fun updateCharacter(character: Character) = viewModelScope.launch {
        repository.updateCharacter(character)
    }

    fun deleteCharacter(character: Character) = viewModelScope.launch {
        repository.deleteCharacter(character)
    }
}
