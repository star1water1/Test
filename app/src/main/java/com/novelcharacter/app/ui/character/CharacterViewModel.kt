package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
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

    private val _searchQuery = MutableLiveData("")
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

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        repository.getCharactersForEvent(eventId)

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

    // ===== FieldDefinition =====
    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        repository.getFieldsByUniverseList(universeId)

    // ===== CharacterFieldValue =====
    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        repository.getValuesByCharacterList(characterId)

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) =
        repository.saveAllFieldValues(characterId, values)

    suspend fun insertCharacterSuspend(character: Character): Long =
        repository.insertCharacter(character)

    fun insertCharacterAndGetId(character: Character, onResult: (Long) -> Unit) = viewModelScope.launch {
        val id = repository.insertCharacter(character)
        onResult(id)
    }

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        repository.getChangesByCharacter(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        repository.getChangesUpToYear(characterId, year)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        repository.getChangesByCharacterList(characterId)

    fun insertStateChange(change: CharacterStateChange) = viewModelScope.launch {
        repository.insertStateChange(change)
    }

    fun updateStateChange(change: CharacterStateChange) = viewModelScope.launch {
        repository.updateStateChange(change)
    }

    fun deleteStateChange(change: CharacterStateChange) = viewModelScope.launch {
        repository.deleteStateChange(change)
    }
}
