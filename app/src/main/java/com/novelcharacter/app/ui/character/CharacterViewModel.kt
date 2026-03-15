package com.novelcharacter.app.ui.character

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.launch

class CharacterViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val characterRepository = app.characterRepository
    private val novelRepository = app.novelRepository
    private val timelineRepository = app.timelineRepository
    private val universeRepository = app.universeRepository

    val allCharacters: LiveData<List<Character>> = characterRepository.allCharacters
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    private val _currentNovelId = MutableLiveData<Long?>()

    val filteredCharacters: LiveData<List<Character>> = _currentNovelId.switchMap { novelId ->
        if (novelId == null || novelId == -1L) {
            characterRepository.allCharacters
        } else {
            characterRepository.getCharactersByNovel(novelId)
        }
    }

    private val _searchQuery = MutableLiveData("")
    val searchResults: LiveData<List<Character>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            filteredCharacters
        } else {
            characterRepository.searchCharacters(query)
        }
    }

    fun setNovelFilter(novelId: Long?) {
        _currentNovelId.value = novelId
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getCharacterById(id: Long): LiveData<Character?> = characterRepository.getCharacterByIdLive(id)

    fun getEventsForCharacter(characterId: Long): LiveData<List<TimelineEvent>> =
        timelineRepository.getEventsForCharacter(characterId)

    suspend fun getCharacterByIdSuspend(id: Long): Character? = characterRepository.getCharacterById(id)

    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        timelineRepository.getCharactersForEvent(eventId)

    suspend fun getNovelById(id: Long): Novel? = novelRepository.getNovelById(id)

    suspend fun getAllNovelsList(): List<Novel> = novelRepository.getAllNovelsList()

    fun insertCharacter(character: Character) = viewModelScope.launch {
        characterRepository.insertCharacter(character)
    }

    fun updateCharacter(character: Character) = viewModelScope.launch {
        characterRepository.updateCharacter(character)
    }

    fun deleteCharacter(character: Character) = viewModelScope.launch {
        characterRepository.deleteCharacter(character)
    }

    // ===== FieldDefinition =====
    suspend fun getFieldsByUniverseList(universeId: Long): List<FieldDefinition> =
        universeRepository.getFieldsByUniverseList(universeId)

    // ===== CharacterFieldValue =====
    suspend fun getValuesByCharacterList(characterId: Long): List<CharacterFieldValue> =
        characterRepository.getValuesByCharacterList(characterId)

    suspend fun saveAllFieldValues(characterId: Long, values: List<CharacterFieldValue>) =
        characterRepository.saveAllFieldValues(characterId, values)

    suspend fun insertCharacterSuspend(character: Character): Long =
        characterRepository.insertCharacter(character)

    // ===== CharacterStateChange =====
    fun getChangesByCharacter(characterId: Long): LiveData<List<CharacterStateChange>> =
        characterRepository.getChangesByCharacter(characterId)

    suspend fun getChangesUpToYear(characterId: Long, year: Int): List<CharacterStateChange> =
        characterRepository.getChangesUpToYear(characterId, year)

    suspend fun getChangesByCharacterList(characterId: Long): List<CharacterStateChange> =
        characterRepository.getChangesByCharacterList(characterId)

    fun insertStateChange(change: CharacterStateChange) = viewModelScope.launch {
        characterRepository.insertStateChange(change)
    }

    fun updateStateChange(change: CharacterStateChange) = viewModelScope.launch {
        characterRepository.updateStateChange(change)
    }

    fun deleteStateChange(change: CharacterStateChange) = viewModelScope.launch {
        characterRepository.deleteStateChange(change)
    }

    // ===== Relationships =====
    fun getRelationshipsForCharacter(characterId: Long): LiveData<List<CharacterRelationship>> =
        characterRepository.getRelationshipsForCharacter(characterId)

    fun insertRelationship(relationship: CharacterRelationship) = viewModelScope.launch {
        characterRepository.insertRelationship(relationship)
    }

    fun deleteRelationshipById(id: Long) = viewModelScope.launch {
        characterRepository.deleteRelationshipById(id)
    }

    suspend fun getAllCharactersList(): List<Character> =
        characterRepository.getAllCharactersList()

    // ===== Tags =====
    fun getTagsByCharacter(characterId: Long): LiveData<List<CharacterTag>> =
        characterRepository.getTagsByCharacter(characterId)

    suspend fun getTagsByCharacterList(characterId: Long): List<CharacterTag> =
        characterRepository.getTagsByCharacterList(characterId)

    suspend fun getAllDistinctTags(): List<String> =
        characterRepository.getAllDistinctTags()

    fun deleteAllTagsByCharacter(characterId: Long) = viewModelScope.launch {
        characterRepository.deleteAllTagsByCharacter(characterId)
    }

    fun insertTags(tags: List<CharacterTag>) = viewModelScope.launch {
        characterRepository.insertTags(tags)
    }

    suspend fun deleteAllTagsByCharacterSuspend(characterId: Long) =
        characterRepository.deleteAllTagsByCharacter(characterId)

    suspend fun insertTagsSuspend(tags: List<CharacterTag>) =
        characterRepository.insertTags(tags)
}
