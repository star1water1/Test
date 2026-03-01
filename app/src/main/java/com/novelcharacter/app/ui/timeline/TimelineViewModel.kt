package com.novelcharacter.app.ui.timeline

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent
import kotlinx.coroutines.launch

class TimelineViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as NovelCharacterApp).repository

    val allEvents: LiveData<List<TimelineEvent>> = repository.allEvents
    val allNovels: LiveData<List<Novel>> = repository.allNovels
    val allCharacters: LiveData<List<Character>> = repository.allCharacters

    private val _searchQuery = MutableLiveData<String>()
    val searchResults: LiveData<List<TimelineEvent>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            repository.allEvents
        } else {
            repository.searchEvents(query)
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    suspend fun getAllNovelsList(): List<Novel> = repository.getAllNovelsList()
    suspend fun getAllCharactersList(): List<Character> = repository.getAllCharactersList()
    suspend fun getCharacterIdsForEvent(eventId: Long): List<Long> =
        repository.getCharacterIdsForEvent(eventId)
    suspend fun getCharactersForEvent(eventId: Long): List<Character> =
        repository.getCharactersForEvent(eventId)

    fun insertEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        val eventId = repository.insertEvent(event)
        repository.updateEventCharacters(eventId, characterIds)
    }

    fun updateEvent(event: TimelineEvent, characterIds: List<Long>) = viewModelScope.launch {
        repository.updateEvent(event)
        repository.updateEventCharacters(event.id, characterIds)
    }

    fun deleteEvent(event: TimelineEvent) = viewModelScope.launch {
        repository.deleteEvent(event)
    }
}
