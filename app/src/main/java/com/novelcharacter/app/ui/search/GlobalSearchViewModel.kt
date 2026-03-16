package com.novelcharacter.app.ui.search

import android.app.Application
import androidx.lifecycle.*
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.TimelineEvent

sealed class SearchResultItem {
    data class SectionHeader(val title: String) : SearchResultItem()
    data class CharacterResult(val character: Character) : SearchResultItem()
    data class EventResult(val event: TimelineEvent) : SearchResultItem()
    data class NovelResult(val novel: Novel) : SearchResultItem()
}

class GlobalSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application
    private val app = application as NovelCharacterApp
    private val novelRepository = app.novelRepository
    private val characterRepository = app.characterRepository
    private val timelineRepository = app.timelineRepository

    private val _searchQuery = MutableLiveData("")

    val searchResults: LiveData<List<SearchResultItem>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            MutableLiveData(emptyList())
        } else {
            val mediator = MediatorLiveData<List<SearchResultItem>>()
            val charResults = characterRepository.searchCharacters(query)
            val eventResults = timelineRepository.searchEvents(query)
            val novelResults = novelRepository.searchNovels(query)

            var chars: List<Character> = emptyList()
            var events: List<TimelineEvent> = emptyList()
            var novels: List<Novel> = emptyList()

            fun combine() {
                val items = mutableListOf<SearchResultItem>()
                if (chars.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader("${appContext.getString(R.string.tab_characters)} (${chars.size})"))
                    items.addAll(chars.map { SearchResultItem.CharacterResult(it) })
                }
                if (events.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader("${appContext.getString(R.string.tab_timeline)} (${events.size})"))
                    items.addAll(events.map { SearchResultItem.EventResult(it) })
                }
                if (novels.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader("${appContext.getString(R.string.tab_novels)} (${novels.size})"))
                    items.addAll(novels.map { SearchResultItem.NovelResult(it) })
                }
                mediator.value = items
            }

            mediator.addSource(charResults) { chars = it; combine() }
            mediator.addSource(eventResults) { events = it; combine() }
            mediator.addSource(novelResults) { novels = it; combine() }
            mediator
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
