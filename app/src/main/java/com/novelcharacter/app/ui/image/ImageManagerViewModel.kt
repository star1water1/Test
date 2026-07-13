package com.novelcharacter.app.ui.image

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.StorageAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 이미지 관리 탭의 데이터 소스. filesDir의 모든 이미지 파일을 개별 항목으로 조립하고,
 * 각 파일이 어느 엔티티(캐릭터/작품/세계관)에서 참조되는지·고아인지·휴지통 보류인지 분류한다.
 * 참조 집합 조립은 [ImageZipHelper]/[StorageAnalyzer]의 기존 규칙(접두·확장자·canonical)을 재사용한다.
 */
class ImageManagerViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db: AppDatabase = app.database
    private val gson = Gson()

    enum class OwnerType { CHARACTER, NOVEL, UNIVERSE }
    data class Owner(val type: OwnerType, val name: String, val id: Long)
    enum class Status { REFERENCED, ORPHAN, TRASH_HELD }
    data class ManagedImage(
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val owners: List<Owner>,
        val status: Status
    )
    data class Summary(
        val totalBytes: Long,
        val totalCount: Int,
        val referencedCount: Int,
        val orphanCount: Int,
        val trashCount: Int
    )

    private val _images = MutableLiveData<List<ManagedImage>>(emptyList())
    val images: LiveData<List<ManagedImage>> = _images

    private val _summary = MutableLiveData(Summary(0, 0, 0, 0, 0))
    val summary: LiveData<Summary> = _summary

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            val (items, summary) = withContext(Dispatchers.IO) { buildItems() }
            _images.value = items
            _summary.value = summary
            _loading.value = false
        }
    }

    private suspend fun buildItems(): Pair<List<ManagedImage>, Summary> {
        val filesDir = getApplication<Application>().filesDir

        // 소유자 역맵: canonical 경로 → 참조하는 엔티티 목록(같은 이미지를 여러 곳이 쓸 수 있음)
        val ownerMap = HashMap<String, MutableList<Owner>>()
        fun addOwners(imagePathsJson: String, type: OwnerType, name: String, id: Long) {
            for (p in parsePaths(imagePathsJson)) {
                val canon = runCatching { File(p).canonicalPath }.getOrNull() ?: continue
                ownerMap.getOrPut(canon) { mutableListOf() }.add(Owner(type, name, id))
            }
        }
        for (c in db.characterDao().getAllCharactersList()) addOwners(c.imagePaths, OwnerType.CHARACTER, c.name, c.id)
        for (n in db.novelDao().getAllNovelsList()) addOwners(n.imagePaths, OwnerType.NOVEL, n.title, n.id)
        for (u in db.universeDao().getAllUniversesList()) addOwners(u.imagePaths, OwnerType.UNIVERSE, u.name, u.id)

        val trashHeld = StorageAnalyzer.collectTrashHeldPaths(db, gson)

        val files = filesDir.listFiles { f -> f.isFile && StorageAnalyzer.isImageFile(f.name) } ?: emptyArray()
        val items = ArrayList<ManagedImage>(files.size)
        var totalBytes = 0L
        var refCount = 0
        var orphanCount = 0
        var trashCount = 0
        for (f in files) {
            val canon = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
            val owners = ownerMap[canon] ?: emptyList()
            val status = when {
                owners.isNotEmpty() -> Status.REFERENCED
                trashHeld.contains(canon) -> Status.TRASH_HELD
                else -> Status.ORPHAN
            }
            val size = f.length()
            totalBytes += size
            when (status) {
                Status.REFERENCED -> refCount++
                Status.ORPHAN -> orphanCount++
                Status.TRASH_HELD -> trashCount++
            }
            items.add(ManagedImage(f.absolutePath, size, f.lastModified(), owners, status))
        }
        items.sortByDescending { it.sizeBytes }  // 기본 정렬: 큰 것부터(정리 우선)
        return items to Summary(totalBytes, items.size, refCount, orphanCount, trashCount)
    }

    private fun parsePaths(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching { gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST) }
            .getOrNull()?.filterNotNull() ?: emptyList()
    }

    /**
     * 이미지 삭제. 참조본이면 소유 엔티티들의 imagePaths에서도 경로를 제거(정합 유지)한 뒤 파일을 지운다.
     * 완료 시 확보 바이트(성공) 또는 null(실패)을 콜백으로 돌려주고 목록을 재로딩한다.
     */
    fun deleteImage(item: ManagedImage, onDone: (Long?) -> Unit) {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { deleteInternal(item) }
            load()
            onDone(freed)
        }
    }

    private suspend fun deleteInternal(item: ManagedImage): Long? = try {
        val file = File(item.path)
        val canon = runCatching { file.canonicalPath }.getOrNull() ?: item.path
        for (owner in item.owners) {
            when (owner.type) {
                OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { c ->
                    db.characterDao().update(c.copy(imagePaths = removePath(c.imagePaths, item.path, canon)))
                }
                OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { n ->
                    db.novelDao().update(n.copy(imagePaths = removePath(n.imagePaths, item.path, canon)))
                }
                OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { u ->
                    db.universeDao().update(u.copy(imagePaths = removePath(u.imagePaths, item.path, canon)))
                }
            }
        }
        val size = file.length()
        if (file.delete()) size else null
    } catch (e: Exception) {
        null
    }

    private fun removePath(json: String, path: String, canon: String): String {
        val kept = parsePaths(json).filter {
            val c = runCatching { File(it).canonicalPath }.getOrNull() ?: it
            it != path && c != canon
        }
        return gson.toJson(kept)
    }

    /** 고아 이미지 일괄 정리 — 기존 SystemMaintenanceService 재사용(참조 손상 시 안전 중단). */
    fun cleanOrphans(onDone: (SystemMaintenanceService.OrphanCleanupResult) -> Unit) {
        viewModelScope.launch {
            val service = SystemMaintenanceService(getApplication(), db)
            val result = service.cleanOrphanImageFiles()
            load()
            onDone(result)
        }
    }
}
