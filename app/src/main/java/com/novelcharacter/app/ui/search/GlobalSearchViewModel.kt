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
                val q = query.lowercase()
                val items = mutableListOf<SearchResultItem>()

                // Rank characters: exact name > prefix name > exact anotherName > prefix anotherName > contains
                val rankedChars = chars.sortedByDescending { c ->
                    val name = c.name.lowercase()
                    val alias = c.anotherName.lowercase()
                    when {
                        name == q -> 100
                        name.startsWith(q) -> 80
                        alias == q -> 70
                        alias.startsWith(q) -> 60
                        name.contains(q) -> 40
                        alias.contains(q) -> 30
                        else -> 10
                    }
                }

                // Rank novels: exact title > prefix > contains title > contains description
                val rankedNovels = novels.sortedByDescending { n ->
                    val title = n.title.lowercase()
                    when {
                        title == q -> 100
                        title.startsWith(q) -> 80
                        title.contains(q) -> 40
                        else -> 10 // matched via description
                    }
                }

                // Rank events: exact description > prefix > contains
                val rankedEvents = events.sortedByDescending { e ->
                    val desc = e.description.lowercase()
                    when {
                        desc == q -> 100
                        desc.startsWith(q) -> 80
                        desc.contains(q) -> 40
                        else -> 10
                    }
                }

                if (rankedChars.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_characters), rankedChars.size)))
                    items.addAll(rankedChars.map { SearchResultItem.CharacterResult(it) })
                }
                if (rankedEvents.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_timeline), rankedEvents.size)))
                    items.addAll(rankedEvents.map { SearchResultItem.EventResult(it) })
                }
                if (rankedNovels.isNotEmpty()) {
                    items.add(SearchResultItem.SectionHeader(appContext.getString(R.string.section_header_format, appContext.getString(R.string.tab_novels), rankedNovels.size)))
                    items.addAll(rankedNovels.map { SearchResultItem.NovelResult(it) })
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
