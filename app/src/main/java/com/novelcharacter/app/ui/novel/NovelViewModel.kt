package com.novelcharacter.app.ui.novel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.RecentActivity
import com.novelcharacter.app.util.StandardYearSyncHelper
import com.novelcharacter.app.util.OpResult
import com.novelcharacter.app.util.reportResult
import com.google.gson.Gson
import android.util.Log
import kotlinx.coroutines.launch

class NovelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val novelRepository = app.novelRepository
    private val universeRepository = app.universeRepository
    private val characterRepository = app.characterRepository
    private val recentActivityDao = app.recentActivityDao
    private val standardYearSyncHelper = StandardYearSyncHelper(characterRepository, universeRepository)
    private val gson = Gson()
    val allNovels: LiveData<List<Novel>> = novelRepository.allNovels

    // 데이터 처리 결과 알림 채널 (변수 제어: 모든 의미있는 조작 결과 통보)
    private val _result = MutableLiveData<OpResult?>()
    val result: LiveData<OpResult?> = _result
    fun clearResult() { _result.value = null }

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
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_saved, novel.title)))
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to insert novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_save_failed), e.message))
        }
    }

    fun updateNovel(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.updateNovel(novel)
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_updated, novel.title)))
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_update_failed), e.message))
        }
    }

    fun deleteNovel(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.deleteNovel(novel)
            reportResult(_result, OpResult.success(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_deleted, novel.title)))
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to delete novel", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_delete_failed), e.message))
        }
    }

    fun updateDisplayOrders(novels: List<Novel>) = viewModelScope.launch {
        try {
            novelRepository.updateNovelDisplayOrders(novels)
            // 재정렬은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to update display orders", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_reorder_failed), e.message))
        }
    }

    fun togglePin(novel: Novel) = viewModelScope.launch {
        try {
            novelRepository.setPinned(novel.id, !novel.isPinned)
            // 핀 토글은 초고빈도 조작 — 성공 무통보, 실패만 알림 (원칙 04)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to toggle pin", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_pin_failed), e.message))
        }
    }

    /** 작품에 속한 캐릭터 중 이미지가 있는 랜덤/지정 캐릭터의 첫 이미지 경로 반환 */
    fun resolveCharacterImage(novelId: Long, characterId: Long?, callback: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val characters = characterRepository.getCharactersByNovelList(novelId)
                val withImages = characters.filter { char ->
                    val paths = char.imagePaths
                    paths.isNotBlank() && paths != "[]"
                }
                if (withImages.isEmpty()) {
                    callback(null)
                    return@launch
                }

                val target = if (characterId != null) {
                    withImages.find { it.id == characterId } ?: withImages.random()
                } else {
                    withImages.random()
                }

                // imagePaths는 JSON 배열 형태 — 랜덤 경로 추출
                val pathsStr = target.imagePaths
                val firstPath = try {
                    gson.fromJson(pathsStr, Array<String>::class.java)?.randomOrNull()
                } catch (_: Exception) {
                    null
                }
                callback(firstPath)
            } catch (e: Exception) {
                Log.e("NovelViewModel", "Failed to resolve character image", e)
                callback(null)
            }
        }
    }

    /** 작품에 속한 이미지 있는 캐릭터 목록 반환 (선택 모드용) */
    suspend fun getCharactersWithImages(novelId: Long): List<com.novelcharacter.app.data.model.Character> {
        return characterRepository.getCharactersByNovelList(novelId).filter { char ->
            char.imagePaths.isNotBlank() && char.imagePaths != "[]"
        }
    }

    fun onStandardYearChanged(novel: Novel, oldStdYear: Int?, newStdYear: Int?) = viewModelScope.launch {
        try {
            standardYearSyncHelper.onStandardYearChanged(novel, oldStdYear, newStdYear)
            // 성공은 작품 저장 알림에 포함 — 실패만 별도 통보 (캐릭터 나이 파급 실패 방지)
        } catch (e: Exception) {
            Log.e("NovelViewModel", "Failed to sync standard year change", e)
            reportResult(_result, OpResult.failure(OpResult.CAT_NOVEL,
                app.getString(R.string.result_novel_stdyear_sync_failed), e.message))
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
