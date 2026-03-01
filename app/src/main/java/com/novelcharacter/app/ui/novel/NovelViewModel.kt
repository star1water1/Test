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

    private val repository = (application as NovelCharacterApp).repository
    val allNovels: LiveData<List<Novel>> = repository.allNovels

    private val _universeId = MutableLiveData<Long?>()

    val filteredNovels: LiveData<List<Novel>> = _universeId.switchMap { uid ->
        if (uid == null || uid == -1L) {
            repository.allNovels
        } else {
            repository.getNovelsByUniverse(uid)
        }
    }

    fun setUniverseFilter(universeId: Long?) {
        _universeId.value = universeId
    }

    fun insertNovel(novel: Novel) = viewModelScope.launch {
        repository.insertNovel(novel)
    }

    fun updateNovel(novel: Novel) = viewModelScope.launch {
        repository.updateNovel(novel)
    }

    fun deleteNovel(novel: Novel) = viewModelScope.launch {
        repository.deleteNovel(novel)
    }
}
