package com.novelcharacter.app.ui.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.RecentActivity
import android.util.Log
import kotlinx.coroutines.launch

class NovelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val novelRepository = app.novelRepository
    private val universeRepository = app.universeRepository
    private val recentActivityDao = app.recentActivityDao
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    private val _universeId = MutableLiveData<Long?>()

    private val _universeBorder = MutableLiveData<Pair<String, Float>>(Pair("", 1.5f))
    val universeBorder: LiveData<Pair<String, Float>> = _universeBorder

    fun loadUniverseBorder(universeId: Long) = viewModelScope.launch {
        val universe = universeRepository.getUniverseById(universeId)
        if (universe != null) {
            _universeBorder.value = Pair(universe.borderColor, universe.borderWidthDp)
        }
    }

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
        try {
            novelRepository.insertNovel(novel)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to insert novel", e)
        }
    }

    fun updateNovel(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.updateNovel(novel)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update novel", e)
        }
    }

    fun deleteNovel(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.deleteNovel(novel)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to delete novel", e)
        }
    }

    fun updateDisplayOrders(novels: List<Novel>) = viewModelScope.launch {
        try {
            novelRepository.updateNovelDisplayOrders(novels)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update display orders", e)
        }
    }

    fun togglePin(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.setPinned(novel.id, !novel.isPinned)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to toggle pin", e)
        }
    }

    fun recordRecentActivity(novelId: Long, title: String) = viewModelScope.launch {
        try {
            recentActivityDao.upsert(
                RecentActivity(entityType = RecentActivity.TYPE_NOVEL, entityId = novelId, title = title)
            )
            recentActivityDao.trimToMax(RecentActivity.MAX_ENTRIES)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to record recent activity", e)
        }
    }
}
