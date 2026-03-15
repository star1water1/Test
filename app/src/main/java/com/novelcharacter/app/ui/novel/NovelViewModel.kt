package com.novelcharacter.app.ui.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Novel
import kotlinx.coroutines.launch

class NovelViewModel(application: Application) : AndroidViewModel(application) {

    private val novelRepository = (application as NovelCharacterApp).novelRepository
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    private val _universeId = MutableLiveData<Long?>()

    val filteredNovels: LiveData<List<Novel>> = _universeId.switchMap { uid ->
        if (uid == null || uid == -1L) {
            novelRepository.allNovels
        } else {
            novelRepository.getNovelsByUniverse(uid)
        }
    }

    fun setUniverseFilter(universeId: Long?) {
        _universeId.value = universeId
    }

    fun insertNovel(novel: Novel) = viewModelScope.launch {
        novelRepository.insertNovel(novel)
    }

    fun updateNovel(novel: Novel) = viewModelScope.launch {
        novelRepository.updateNovel(novel)
    }

    fun deleteNovel(novel: Novel) = viewModelScope.launch {
        novelRepository.deleteNovel(novel)
    }
}
