package com.novelcharacter.app.ui.image

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.ImageImportHelper
import com.novelcharacter.app.util.ImageSettingsStore
import com.novelcharacter.app.util.StorageAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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

    /** 재압축 대상 1건의 커밋 계획(임시 산출물 준비 완료). */
    data class RecompressPlan(
        val item: ManagedImage,
        val tempPath: String,
        val newSize: Long,
        val prefix: String
    )

    /** 재압축에서 제외된 1건 + 사유. */
    data class RecompressSkip(
        val item: ManagedImage,
        val reason: ImageImportHelper.SkipReason
    )

    /** 재압축 미리보기 — 확인 다이얼로그에 실제 전/후 크기와 스킵 내역을 보여주기 위한 결과. */
    data class RecompressPreview(
        val plans: List<RecompressPlan>,
        val skips: List<RecompressSkip>,
        val totalBefore: Long,
        val totalAfter: Long
    ) {
        val savings: Long get() = (totalBefore - totalAfter).coerceAtLeast(0L)
    }

    /** 재압축 커밋 결과. failed = 준비됐으나 커밋(개명·경로교체)에 실패해 반영 못 한 건수(조용한 증발 방지). */
    data class RecompressResult(val recompressed: Int, val freed: Long, val skipped: Int, val failed: Int = 0)

    /** 일괄 삭제 결과. */
    data class BulkDeleteResult(val deleted: Int, val freed: Long, val failed: Int)

    // 사용자 확인을 기다리는 재압축 미리보기(임시 파일 보유). 커밋/취소 시 소비.
    private var pendingPreview: RecompressPreview? = null

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

        // 중단된 재압축 미리보기의 잔여 임시 파일 청소(활성 미리보기가 없을 때만 — 대기 중인 커밋 보호).
        if (pendingPreview == null) sweepRecompressTempFiles()

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

        val files = filesDir.listFiles { f ->
            f.isFile && StorageAnalyzer.isImageFile(f.name) &&
                !f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER)
        } ?: emptyArray()
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

    /**
     * 여러 이미지를 일괄 삭제한다. 각 건은 [deleteInternal] 재사용(참조본이면 소유 엔티티 경로도 함께 제거).
     * 개별 결과를 집계해 돌려주고 목록을 재로딩한다.
     */
    fun deleteImages(items: List<ManagedImage>, onDone: (BulkDeleteResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var deleted = 0
                var freed = 0L
                var failed = 0
                for (item in items) {
                    val f = deleteInternal(item)
                    if (f != null) { deleted++; freed += f } else failed++
                }
                BulkDeleteResult(deleted, freed, failed)
            }
            load()
            onDone(result)
        }
    }

    /**
     * 재압축 미리보기 준비 — 대상들을 현재 압축 설정으로 **임시 파일**에 재인코드해 정확한 전/후 크기를 계산한다.
     * 이 단계는 원본을 건드리지 않으며, 결과([RecompressPreview])를 [pendingPreview]에 보관해 확인 뒤 커밋한다.
     * 참조본이 아닌 것(고아/휴지통)과 임계값 미만·손상·이득 없음은 스킵(사유 포함).
     */
    fun prepareRecompress(items: List<ManagedImage>, onReady: (RecompressPreview) -> Unit) {
        _loading.value = true
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { buildPreview(items) }
            pendingPreview = preview
            _loading.value = false
            onReady(preview)
        }
    }

    private suspend fun buildPreview(items: List<ManagedImage>): RecompressPreview {
        sweepRecompressTempFiles()  // 직전에 중단된 미리보기 잔여물 정리
        val settings = ImageSettingsStore(getApplication()).getSettings()
        val plans = ArrayList<RecompressPlan>()
        val skips = ArrayList<RecompressSkip>()
        for (item in items) {
            if (item.status != Status.REFERENCED) {
                skips.add(RecompressSkip(item, ImageImportHelper.SkipReason.NOT_REFERENCED))
                continue
            }
            val prefix = prefixOf(item.path)
            val outcome = ImageImportHelper.recompressToTemp(
                getApplication(), File(item.path), prefix, settings
            )
            val temp = outcome.tempFile
            if (temp != null) {
                plans.add(RecompressPlan(item, temp.absolutePath, temp.length(), prefix))
            } else {
                skips.add(RecompressSkip(item, outcome.skip ?: ImageImportHelper.SkipReason.ERROR))
            }
        }
        val totalBefore = plans.sumOf { it.item.sizeBytes }
        val totalAfter = plans.sumOf { it.newSize }
        return RecompressPreview(plans, skips, totalBefore, totalAfter)
    }

    /** 재압축 취소 — 준비된 임시 파일을 삭제하고 미리보기를 폐기한다. */
    fun discardRecompress() {
        val preview = pendingPreview ?: return
        pendingPreview = null
        viewModelScope.launch(Dispatchers.IO) {
            preview.plans.forEach { runCatching { File(it.tempPath).delete() } }
        }
    }

    /**
     * 재압축 커밋 — 준비된 임시 파일을 정식 이름으로 개명하고, 소유 엔티티들의 경로를 교체(트랜잭션)한 뒤
     * 원본을 삭제한다. 파일 개명(1) → DB 경로 교체(2) → 원본 삭제(3) 순서라, 도중 실패해도
     * 원본은 살아 있고 새 파일만 고아로 남아(안전) 이후 정리 대상이 된다.
     */
    fun commitRecompress(onDone: (RecompressResult) -> Unit) {
        val preview = pendingPreview
        pendingPreview = null
        if (preview == null || preview.plans.isEmpty()) {
            onDone(RecompressResult(0, 0L, preview?.skips?.size ?: 0))
            return
        }
        viewModelScope.launch {
            // 백스톱: 어떤 예외에도 크래시 대신 '전량 실패'로 통보하고 목록을 재로딩한다(변수 제어).
            val result = try {
                withContext(Dispatchers.IO) { commitInternal(preview) }
            } catch (e: Exception) {
                RecompressResult(0, 0L, preview.skips.size, preview.plans.size)
            }
            load()
            onDone(result)
        }
    }

    private suspend fun commitInternal(preview: RecompressPreview): RecompressResult {
        val filesDir = getApplication<Application>().filesDir

        // 1단계: 임시 파일 → 정식 이름 개명(파일 시스템). 사라졌거나 실패한 건은 스킵.
        val committed = ArrayList<Triple<RecompressPlan, String, String>>() // plan, finalPath, oldCanon
        for (plan in preview.plans) {
            val temp = File(plan.tempPath)
            if (!temp.exists()) continue
            val finalFile = File(filesDir, "${plan.prefix}_${UUID.randomUUID()}.jpg")
            val guardOk = runCatching {
                finalFile.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)
            }.getOrDefault(false)
            if (!guardOk) { temp.delete(); continue }
            val moved = temp.renameTo(finalFile) || runCatching {
                finalFile.writeBytes(temp.readBytes()); temp.delete(); true
            }.getOrDefault(false)
            if (!moved) { temp.delete(); continue }
            val oldCanon = runCatching { File(plan.item.path).canonicalPath }.getOrNull() ?: plan.item.path
            committed.add(Triple(plan, finalFile.absolutePath, oldCanon))
        }

        // 2단계: 소유 엔티티 경로 교체(트랜잭션). 실패 시 개명한 정식 파일은 아직 미참조이므로 롤백 삭제하고
        // (원본은 그대로 살아 있음) 전량 실패로 통보 — 크래시·조용한 증발 방지(변수 제어).
        try {
            db.withTransaction {
                for ((plan, finalPath, oldCanon) in committed) {
                    for (owner in plan.item.owners) {
                        when (owner.type) {
                            OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { e ->
                                db.characterDao().update(e.copy(imagePaths = replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath)))
                            }
                            OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { e ->
                                db.novelDao().update(e.copy(imagePaths = replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath)))
                            }
                            OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { e ->
                                db.universeDao().update(e.copy(imagePaths = replacePath(e.imagePaths, plan.item.path, oldCanon, finalPath)))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            committed.forEach { runCatching { File(it.second).delete() } }  // 미참조 개명본 롤백 삭제
            return RecompressResult(0, 0L, preview.skips.size, preview.plans.size)
        }

        // 3단계: 원본 삭제(교체 완료분). freed = 원본 − 재압축본(음수 방지).
        var freed = 0L
        for ((plan, finalPath, _) in committed) {
            val orig = File(plan.item.path)
            val origLen = orig.length()
            if (orig.delete()) {
                val newLen = File(finalPath).length()
                freed += (origLen - newLen).coerceAtLeast(0L)
            }
        }
        // 준비됐으나 커밋 못 한 건(개명·이동 실패 등) = 계획 − 반영. 조용히 증발하지 않도록 집계·통보.
        val failed = preview.plans.size - committed.size
        return RecompressResult(committed.size, freed, preview.skips.size, failed)
    }

    /** filesDir의 재압축 임시 파일(표식 포함)을 모두 삭제. */
    private fun sweepRecompressTempFiles() {
        val filesDir = getApplication<Application>().filesDir
        filesDir.listFiles { f ->
            f.isFile && f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER)
        }?.forEach { runCatching { it.delete() } }
    }

    /** 파일명 접두(char/novel/universe)를 추출 — 재압축 산출물 명명 규약 유지. 그 외는 img. */
    private fun prefixOf(path: String): String {
        val base = File(path).name.substringBefore('_', "")
        return when (base) {
            "char", "novel", "universe" -> base
            else -> "img"
        }
    }

    /** imagePaths JSON에서 [oldPath]/[oldCanon]에 해당하는 항목을 [newPath]로 교체해 재직렬화. */
    private fun replacePath(json: String, oldPath: String, oldCanon: String, newPath: String): String {
        val updated = parsePaths(json).map {
            val c = runCatching { File(it).canonicalPath }.getOrNull() ?: it
            if (it == oldPath || c == oldCanon) newPath else it
        }
        return gson.toJson(updated)
    }
}
