package com.novelcharacter.app.ui.image

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.maintenance.SystemMaintenanceService
import com.novelcharacter.app.util.GsonTypes
import com.novelcharacter.app.util.ImageFilterHelper
import com.novelcharacter.app.util.ImageImportHelper
import com.novelcharacter.app.util.ImageLinkResolver
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
class ImageManagerViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val app = application as NovelCharacterApp
    private val db: AppDatabase = app.database
    private val gson = Gson()

    // 기본 필터·태그·정렬은 세션을 넘는 작업 컨텍스트 — SharedPreferences로 영속(재방문 시 복원).
    // 검색어는 일회성 조회라 세션(SavedStateHandle) 한정 — 낡은 검색어가 재방문 시
    // 이미지를 조용히 숨기지 않게 한다(변수 제어).
    private val prefs = application.getSharedPreferences(
        "image_manager_ui_state", android.content.Context.MODE_PRIVATE
    )

    enum class Sort { SIZE, NAME, DATE }

    init {
        // 콜드 스타트 시딩 — SavedStateHandle이 이미 살아있으면(회전/저메모리 복원) 그쪽이 우선
        if (!savedState.contains("filter_base")) {
            savedState["filter_base"] =
                prefs.getString("filter_base", null) ?: ImageFilterHelper.BaseFilter.ALL.name
        }
        if (!savedState.contains("filter_tags")) {
            savedState["filter_tags"] = ArrayList(loadPersistedTags())
        }
        if (!savedState.contains("sort")) {
            savedState["sort"] = prefs.getString("sort", null) ?: Sort.SIZE.name
        }
    }

    private fun loadPersistedTags(): List<String> = runCatching {
        gson.fromJson<List<String?>>(
            prefs.getString("filter_tags", null) ?: "[]", GsonTypes.STRING_LIST
        )
    }.getOrNull()?.filterNotNull() ?: emptyList()

    /**
     * 필터/검색/정렬 상태 — SavedStateHandle 영속. ViewPager가 원거리 탭의 프래그먼트(와 이 VM)를
     * 파기해도 FragmentStateAdapter의 상태 저장을 타고 복원된다(탭 전환 시 필터 리셋 방지, D10).
     * 기본 필터·태그·정렬은 prefs에도 기록해 콜드 스타트를 넘는다(위 주석 참조).
     */
    var criteria: ImageFilterHelper.Criteria
        get() = ImageFilterHelper.Criteria(
            base = runCatching {
                ImageFilterHelper.BaseFilter.valueOf(savedState["filter_base"] ?: ImageFilterHelper.BaseFilter.ALL.name)
            }.getOrDefault(ImageFilterHelper.BaseFilter.ALL),
            tags = savedState.get<ArrayList<String>>("filter_tags")?.toSet() ?: emptySet(),
            query = savedState["filter_query"] ?: ""
        )
        set(value) {
            savedState["filter_base"] = value.base.name
            savedState["filter_tags"] = ArrayList(value.tags)
            savedState["filter_query"] = value.query
            prefs.edit()
                .putString("filter_base", value.base.name)
                .putString("filter_tags", gson.toJson(ArrayList(value.tags)))
                .apply()
        }

    var sort: Sort
        get() = runCatching { Sort.valueOf(savedState["sort"] ?: Sort.SIZE.name) }.getOrDefault(Sort.SIZE)
        set(value) {
            savedState["sort"] = value.name
            prefs.edit().putString("sort", value.name).apply()
        }

    enum class OwnerType { CHARACTER, NOVEL, UNIVERSE }
    data class Owner(val type: OwnerType, val name: String, val id: Long)
    /** UNASSIGNED = 라이브러리(image_meta) 관리 중이나 아직 어떤 엔티티에도 배정되지 않은 이미지(고아 아님·보호됨). */
    enum class Status { REFERENCED, ORPHAN, TRASH_HELD, UNASSIGNED }
    /** 라이브러리 메타(있으면 라이브러리 관리 이미지): 태그·링크 그룹. */
    data class MetaInfo(val imageId: Long, val linkGroupId: String?, val tags: List<String>)
    data class ManagedImage(
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val owners: List<Owner>,
        val status: Status,
        val meta: MetaInfo? = null
    )
    data class Summary(
        val totalBytes: Long,
        val totalCount: Int,
        val referencedCount: Int,
        val orphanCount: Int,
        val trashCount: Int,
        val unassignedCount: Int = 0
    )

    /** 탭 임포트 결과. */
    data class ImportResult(val imported: Int, val failed: Int)

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
    // @Volatile: buildItems()는 IO 스레드에서 이 값을 읽고 prepare/commit은 메인에서 쓰므로 가시성 보장.
    @Volatile private var pendingPreview: RecompressPreview? = null

    // ===== 재압축 되돌리기 — 재압축 시 원본을 영구 삭제하지 않고 백업 디렉터리로 옮겨 스낵바 "실행취소"로 복원 가능하게 함 =====
    private data class RecompressUndoEntry(
        val originalPath: String,   // 재압축 전 원본 경로(복원 목적지)
        val backupPath: String,     // recompress_backup/ 로 옮긴 백업 파일
        val finalPath: String,      // 재압축 산출물(복원 시 삭제)
        val owners: List<Owner>
    )
    @Volatile private var pendingUndo: List<RecompressUndoEntry>? = null
    private val recompressBackupDirName = "recompress_backup"
    private val backupRetentionMs = 7L * 24 * 60 * 60 * 1000  // 7일

    fun hasRecompressUndo(): Boolean = !pendingUndo.isNullOrEmpty()

    private fun recompressBackupDir(): File =
        File(getApplication<Application>().filesDir, recompressBackupDirName).apply { if (!exists()) mkdirs() }

    // 미리보기 준비 중(임시 파일 생성 중) 플래그. pendingPreview는 buildPreview가 끝난 뒤에야 세팅되므로,
    // 그 사이 창에서 동시 load()의 sweep가 준비 중 임시 파일을 지워 재압축이 조용히 무효화되는 것을 막는다.
    @Volatile private var preparingRecompress = false

    private val _images = MutableLiveData<List<ManagedImage>>(emptyList())
    val images: LiveData<List<ManagedImage>> = _images

    private val _summary = MutableLiveData(Summary(0, 0, 0, 0, 0))
    val summary: LiveData<Summary> = _summary

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            val (items, summary) = withContext(Dispatchers.IO) {
                sweepOldRecompressBackups()  // 오래된 재압축 백업 정리(수명 관리)
                buildItems()
            }
            _images.value = items
            _summary.value = summary
            _loading.value = false
        }
    }

    private suspend fun buildItems(): Pair<List<ManagedImage>, Summary> {
        val filesDir = getApplication<Application>().filesDir

        // 중단된 재압축 미리보기의 잔여 임시 파일 청소(활성 미리보기가 없고, 준비 중도 아닐 때만 —
        // 대기 중인 커밋과 '준비 중 생성되는 임시 파일'을 모두 보호. 준비 창 sweep는 재압축 무효화 버그였다).
        if (pendingPreview == null && !preparingRecompress) sweepRecompressTempFiles()

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

        // 라이브러리 메타 + 태그 로드 → canonical 경로 → MetaInfo 역맵.
        // 스테일 정리: 파일이 사라졌고(24h 경과) 엔티티 참조도 없는 meta 행은 지워 집계를 진실하게 유지한다
        // (임포트/복원 in-flight 보호 — 24h 가드는 고아 정리와 동일 기준).
        val metas = runCatching { db.imageMetaDao().getAllList() }.getOrDefault(emptyList())
        val allTags = runCatching { db.imageTagDao().getAllList() }.getOrDefault(emptyList())
        val tagsByImage = allTags.groupBy({ it.imageId }, { it.tag })
        val now = System.currentTimeMillis()
        val staleMetaPaths = ArrayList<String>()
        val metaMap = HashMap<String, MetaInfo>()
        for (m in metas) {
            val exists = runCatching { File(m.path).exists() }.getOrDefault(false)
            val canon = runCatching { File(m.path).canonicalPath }.getOrNull() ?: m.path
            if (!exists) {
                if (now - m.importedAt > 24L * 60 * 60 * 1000 && !ownerMap.containsKey(canon)) {
                    staleMetaPaths.add(m.path)
                }
                continue
            }
            metaMap[canon] = MetaInfo(m.id, m.linkGroupId, tagsByImage[m.id]?.sorted() ?: emptyList())
        }
        if (staleMetaPaths.isNotEmpty()) {
            runCatching { db.imageMetaDao().deleteByPaths(staleMetaPaths) }
        }

        val files = filesDir.listFiles { f ->
            f.isFile && StorageAnalyzer.isImageFile(f.name) &&
                !f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER)
        } ?: emptyArray()
        val items = ArrayList<ManagedImage>(files.size)
        var totalBytes = 0L
        var refCount = 0
        var orphanCount = 0
        var trashCount = 0
        var unassignedCount = 0
        for (f in files) {
            val canon = runCatching { f.canonicalPath }.getOrNull() ?: f.absolutePath
            val owners = ownerMap[canon] ?: emptyList()
            val meta = metaMap[canon]
            // 우선순위: 참조 > 휴지통 보류 > 라이브러리 미배정 > 고아
            val status = when {
                owners.isNotEmpty() -> Status.REFERENCED
                trashHeld.contains(canon) -> Status.TRASH_HELD
                meta != null -> Status.UNASSIGNED
                else -> Status.ORPHAN
            }
            val size = f.length()
            totalBytes += size
            when (status) {
                Status.REFERENCED -> refCount++
                Status.ORPHAN -> orphanCount++
                Status.TRASH_HELD -> trashCount++
                Status.UNASSIGNED -> unassignedCount++
            }
            items.add(ManagedImage(f.absolutePath, size, f.lastModified(), owners, status, meta))
        }
        items.sortByDescending { it.sizeBytes }  // 기본 정렬: 큰 것부터(정리 우선)
        return items to Summary(totalBytes, items.size, refCount, orphanCount, trashCount, unassignedCount)
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
        val existed = file.exists()
        val size = if (existed) file.length() else 0L
        val groupId = item.meta?.linkGroupId
        // 원자성: 소유 엔티티 imagePaths 제거를 단일 트랜잭션으로 묶고, 파일 삭제를 그 안에서 마지막에 시도한다.
        // 파일 삭제 실패 시 예외를 던져 링크 제거를 롤백 → 파일·링크가 함께 보존된다(캐릭터가 이미지를 조용히 잃지 않음).
        // 파일이 이미 없는(고아) 경우엔 링크만 정리한다. 라이브러리 meta·태그(CASCADE)도 함께 제거(명시적 파괴).
        db.withTransaction {
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
            db.imageMetaDao().deleteByPaths(listOf(item.path, canon))
            // 삭제로 링크 그룹이 1장만 남으면 잔존 "링크" 표식이 오해를 부르므로 자동 해제.
            if (groupId != null) db.imageMetaDao().clearGroupIfSingleton(groupId)
            if (existed && !file.delete()) throw java.io.IOException("파일 삭제 실패: ${item.path}")
        }
        size
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
        // pendingPreview가 세팅되기 전 창을 보호(동시 load()의 sweep가 준비 중 임시 파일을 지우지 않도록).
        preparingRecompress = true
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) { buildPreview(items) }
                pendingPreview = preview
                onReady(preview)
            } finally {
                // 성공/실패 모두 스피너 해제 — buildPreview 예외 시 로딩이 멈춰 걸리던 문제 방지(변수 제어).
                // pendingPreview 세팅 이후이므로 sweep 게이트는 여전히 보호됨(pendingPreview != null).
                _loading.value = false
                preparingRecompress = false
            }
        }
    }

    private suspend fun buildPreview(items: List<ManagedImage>): RecompressPreview {
        sweepRecompressTempFiles()  // 직전에 중단된 미리보기 잔여물 정리
        val settings = ImageSettingsStore(getApplication()).getSettings()
        val plans = ArrayList<RecompressPlan>()
        val skips = ArrayList<RecompressSkip>()
        for (item in items) {
            // 참조본과 라이브러리 미배정 모두 재압축 가능한 사용자 자산. 고아·휴지통 보류만 스킵.
            if (item.status == Status.ORPHAN || item.status == Status.TRASH_HELD) {
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
        // 직전 되돌리기 창은 지났으므로 이전 백업을 정리하고(무한 누적 방지), 오래된 백업(7일)도 함께 쓸어낸다.
        pendingUndo?.forEach { runCatching { File(it.backupPath).delete() } }
        pendingUndo = null
        sweepOldRecompressBackups()
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
                    // 라이브러리 meta 경로도 함께 갱신(태그·링크는 imageId에 붙어 있어 자동 승계).
                    // 저장형이 canonical 변형일 가능성 대비 두 표기 모두 시도 — 행은 최대 1개라 중복 갱신 없음.
                    db.imageMetaDao().updatePath(plan.item.path, finalPath)
                    if (oldCanon != plan.item.path) db.imageMetaDao().updatePath(oldCanon, finalPath)
                }
            }
        } catch (e: Exception) {
            committed.forEach { runCatching { File(it.second).delete() } }  // 미참조 개명본 롤백 삭제
            return RecompressResult(0, 0L, preview.skips.size, preview.plans.size)
        }

        // 3단계: 원본을 영구 삭제하지 않고 백업 디렉터리로 옮긴다(되돌리기용). freed = 원본 − 재압축본(백업 정리 후 실제 확보).
        val backupDir = recompressBackupDir()
        val undo = ArrayList<RecompressUndoEntry>()
        var freed = 0L
        for ((plan, finalPath, _) in committed) {
            val orig = File(plan.item.path)
            val origLen = orig.length()
            val newLen = File(finalPath).length()
            val backup = File(backupDir, "${UUID.randomUUID()}_${orig.name}")
            val backedUp = orig.renameTo(backup) || runCatching {
                backup.writeBytes(orig.readBytes()); orig.delete(); true
            }.getOrDefault(false)
            if (backedUp) {
                runCatching { backup.setLastModified(System.currentTimeMillis()) }  // 수명은 백업 생성 시점 기준
                undo.add(RecompressUndoEntry(plan.item.path, backup.absolutePath, finalPath, plan.item.owners))
                freed += (origLen - newLen).coerceAtLeast(0L)
            } else if (orig.delete()) {
                // 백업 이동 실패 → 공간 확보 우선으로 원본 삭제(이 건만 되돌리기 불가). 커밋 자체는 유효.
                freed += (origLen - newLen).coerceAtLeast(0L)
            }
        }
        pendingUndo = undo.ifEmpty { null }
        // 준비됐으나 커밋 못 한 건(개명·이동 실패 등) = 계획 − 반영. 조용히 증발하지 않도록 집계·통보.
        val failed = preview.plans.size - committed.size
        return RecompressResult(committed.size, freed, preview.skips.size, failed)
    }

    /** 방금 재압축한 원본을 복원한다: 백업 → 원위치, DB 경로를 재압축본 → 원본으로 되돌리고(트랜잭션) 재압축본 삭제. */
    fun undoLastRecompress(onDone: (Boolean) -> Unit) {
        val entries = pendingUndo
        if (entries.isNullOrEmpty()) { onDone(false); return }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { restoreRecompress(entries) }
            pendingUndo = null
            load()
            onDone(ok)
        }
    }

    private suspend fun restoreRecompress(entries: List<RecompressUndoEntry>): Boolean {
        var allOk = true
        for (e in entries) {
            val backup = File(e.backupPath)
            val original = File(e.originalPath)
            // 1) 백업 → 원위치 복원
            val restored = backup.exists() && (backup.renameTo(original) || runCatching {
                original.writeBytes(backup.readBytes()); backup.delete(); true
            }.getOrDefault(false))
            if (!restored) { allOk = false; continue }
            // 2) DB 경로를 재압축본 → 원본으로 되돌림(트랜잭션) 후 3) 재압축 산출물 삭제
            val finalCanon = runCatching { File(e.finalPath).canonicalPath }.getOrNull() ?: e.finalPath
            runCatching {
                db.withTransaction {
                    for (owner in e.owners) {
                        when (owner.type) {
                            OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { c ->
                                db.characterDao().update(c.copy(imagePaths = replacePath(c.imagePaths, e.finalPath, finalCanon, e.originalPath)))
                            }
                            OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { n ->
                                db.novelDao().update(n.copy(imagePaths = replacePath(n.imagePaths, e.finalPath, finalCanon, e.originalPath)))
                            }
                            OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { u ->
                                db.universeDao().update(u.copy(imagePaths = replacePath(u.imagePaths, e.finalPath, finalCanon, e.originalPath)))
                            }
                        }
                    }
                    // 라이브러리 meta 경로 역갱신(재압축본 → 원본).
                    db.imageMetaDao().updatePath(e.finalPath, e.originalPath)
                    if (finalCanon != e.finalPath) db.imageMetaDao().updatePath(finalCanon, e.originalPath)
                }
                File(e.finalPath).delete()
            }.onFailure { allOk = false }
        }
        return allOk
    }

    // ===== 라이브러리(임포트·태그) =====

    /**
     * 이미지 탭 직접 임포트 — 시스템 픽커의 URI들을 img_ 접두로 filesDir에 복사(압축 설정 적용)하고
     * **즉시** image_meta에 등록해 미배정 상태로 편입한다(파일 생성 직후 meta 선삽입이라 고아 정리·
     * 24h 가드와 이중으로 보호됨). 실패 건수는 집계해 통보한다(변수 제어).
     */
    fun importImages(uris: List<android.net.Uri>, onDone: (ImportResult) -> Unit) {
        _loading.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val settings = ImageSettingsStore(getApplication()).getSettings()
                var imported = 0
                var failed = 0
                val now = System.currentTimeMillis()
                for (uri in uris) {
                    val path = ImageImportHelper.importImage(getApplication(), uri, "img", settings)
                    if (path != null) {
                        runCatching {
                            db.imageMetaDao().insert(com.novelcharacter.app.data.model.ImageMeta(path = path, importedAt = now))
                        }
                        imported++
                    } else {
                        failed++
                    }
                }
                ImportResult(imported, failed)
            }
            load()
            onDone(result)
        }
    }

    /** 단일 이미지 태그 교체 — 필요 시 라이브러리로 입양(adopt) 후 태그 전체 교체. */
    fun replaceTags(path: String, tags: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val id = db.imageMetaDao().adopt(path, System.currentTimeMillis())
                    db.imageTagDao().replaceAllForImage(
                        id, tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = id, tag = it) }
                    )
                }
            }
            load()
            onDone()
        }
    }

    /** 태그 제안 목록 — 이미지 태그 ∪ 캐릭터 태그(어휘 공유 → 편집창 추천 매칭 정확도 향상). */
    suspend fun getTagSuggestions(): List<String> = withContext(Dispatchers.IO) {
        val imageTags = runCatching { db.imageTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
        val charTags = runCatching { db.characterTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
        (imageTags + charTags).distinct().sorted()
    }

    /** 태그 필터 시트용 — 이미지 태그 distinct 목록. */
    suspend fun getAllImageTags(): List<String> = withContext(Dispatchers.IO) {
        runCatching { db.imageTagDao().getAllDistinctTags() }.getOrDefault(emptyList())
    }

    /** 선택 경로들의 distinct 태그(일괄 태그 제거 시트용) — 현재 목록의 meta id 기준. */
    suspend fun getDistinctTagsForPaths(paths: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        val ids = metaIdsForPaths(paths)
        if (ids.isEmpty()) emptyList()
        else runCatching { db.imageTagDao().getDistinctTagsForImages(ids) }.getOrDefault(emptyList())
    }

    // ===== 배정 / 해제 / 링크 (G2) =====

    data class AssignResult(
        val assigned: Int, val alreadyOwned: Int, val viaLink: Int,
        val modeChanged: Boolean, val failed: Boolean = false
    )
    data class UnassignResult(val cleared: Int, val adopted: Int, val failed: Boolean = false)
    sealed class LinkOutcome {
        data class Done(val linked: Int, val merged: Boolean) : LinkOutcome()
        data class NeedsMerge(val groups: Int) : LinkOutcome()
        object Failed : LinkOutcome()
    }
    /** 배정 대상 목록 행(피커 표시용). */
    data class PickRow(val id: Long, val title: String, val subtitle: String)

    /** 현재 목록 기준 링크 meta(경로↔그룹) — 확장·계획 판정의 입력. */
    private fun currentMetas(): List<ImageLinkResolver.Meta> =
        (_images.value ?: emptyList()).mapNotNull { item ->
            item.meta?.let { ImageLinkResolver.Meta(item.path, it.linkGroupId) }
        }

    private fun currentItemsByPath(): Map<String, ManagedImage> =
        (_images.value ?: emptyList()).associateBy { it.path }

    private fun metaIdsForPaths(paths: Collection<String>): List<Long> {
        val byPath = currentItemsByPath()
        return paths.mapNotNull { byPath[it]?.meta?.imageId }
    }

    /** 선택을 링크 그룹 전체로 확장(사전 확인 다이얼로그용 — D5: 조용한 확대 금지). */
    fun expandWithLinkedGroups(paths: Collection<String>): ImageLinkResolver.Expansion =
        ImageLinkResolver.expand(paths, currentMetas())

    /** 배정 대상 목록 — 캐릭터(부제=작품명)/작품(부제=세계관명)/세계관. */
    suspend fun getAssignTargets(type: OwnerType): List<PickRow> = withContext(Dispatchers.IO) {
        when (type) {
            OwnerType.CHARACTER -> {
                val novels = db.novelDao().getAllNovelsList().associateBy({ it.id }, { it.title })
                db.characterDao().getAllCharactersList().sortedBy { it.name }
                    .map { PickRow(it.id, it.name, it.novelId?.let { n -> novels[n] } ?: "") }
            }
            OwnerType.NOVEL -> {
                val unis = db.universeDao().getAllUniversesList().associateBy({ it.id }, { it.name })
                db.novelDao().getAllNovelsList().sortedBy { it.title }
                    .map { PickRow(it.id, it.title, it.universeId?.let { u -> unis[u] } ?: "") }
            }
            OwnerType.UNIVERSE ->
                db.universeDao().getAllUniversesList().sortedBy { it.name }
                    .map { PickRow(it.id, it.name, "") }
        }
    }

    /**
     * 배정 = 경로 공유(D6): 링크 그룹 확장(D5) 후 대상 엔티티 imagePaths에 append(중복 canonical 스킵).
     * 작품/세계관은 imageMode==none이고 **기존 이미지가 0장이던 경우에만** custom으로 자동 전환+고지
     * (사용자가 의도적으로 none 설정한 카드는 존중).
     */
    fun assignToTarget(paths: List<String>, type: OwnerType, targetId: Long, onDone: (AssignResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val expansion = expandWithLinkedGroups(paths)
                    var assigned = 0
                    var already = 0
                    var modeChanged = false
                    db.withTransaction {
                        when (type) {
                            OwnerType.CHARACTER -> {
                                val c = requireNotNull(db.characterDao().getCharacterById(targetId))
                                val (json, added, skipped) = appendPaths(c.imagePaths, expansion.allPaths)
                                db.characterDao().update(c.copy(imagePaths = json))
                                assigned = added; already = skipped
                            }
                            OwnerType.NOVEL -> {
                                val n = requireNotNull(db.novelDao().getNovelById(targetId))
                                val wasEmpty = parsePaths(n.imagePaths).isEmpty()
                                val (json, added, skipped) = appendPaths(n.imagePaths, expansion.allPaths)
                                var updated = n.copy(imagePaths = json)
                                if (n.imageMode == com.novelcharacter.app.data.model.Novel.IMAGE_MODE_NONE && wasEmpty && added > 0) {
                                    updated = updated.copy(imageMode = com.novelcharacter.app.data.model.Novel.IMAGE_MODE_CUSTOM)
                                    modeChanged = true
                                }
                                db.novelDao().update(updated)
                                assigned = added; already = skipped
                            }
                            OwnerType.UNIVERSE -> {
                                val u = requireNotNull(db.universeDao().getUniverseById(targetId))
                                val wasEmpty = parsePaths(u.imagePaths).isEmpty()
                                val (json, added, skipped) = appendPaths(u.imagePaths, expansion.allPaths)
                                var updated = u.copy(imagePaths = json)
                                if (u.imageMode == com.novelcharacter.app.data.model.Universe.IMAGE_MODE_NONE && wasEmpty && added > 0) {
                                    updated = updated.copy(imageMode = com.novelcharacter.app.data.model.Universe.IMAGE_MODE_CUSTOM)
                                    modeChanged = true
                                }
                                db.universeDao().update(updated)
                                assigned = added; already = skipped
                            }
                        }
                    }
                    AssignResult(assigned, already, expansion.addedByLink.size, modeChanged)
                } catch (e: Exception) {
                    AssignResult(0, 0, 0, false, failed = true)
                }
            }
            load()
            onDone(result)
        }
    }

    /** imagePaths JSON에 경로들을 append(중복 canonical 스킵). @return (새 JSON, 추가 수, 스킵 수) */
    private fun appendPaths(json: String, adds: Collection<String>): Triple<String, Int, Int> {
        val current = parsePaths(json)
        val canonSet = current.mapTo(HashSet()) { runCatching { File(it).canonicalPath }.getOrNull() ?: it }
        val result = current.toMutableList()
        var added = 0
        var skipped = 0
        for (p in adds) {
            val c = runCatching { File(p).canonicalPath }.getOrNull() ?: p
            if (c in canonSet) { skipped++ } else { result.add(p); canonSet.add(c); added++ }
        }
        return Triple(gson.toJson(result), added, skipped)
    }

    /**
     * 배정 해제 — 지정 소유자([ownerFilter], null=전체)에게서 경로를 제거한다(파일 유지).
     * 소유자 0이 된 비-라이브러리 경로는 자동 입양(미배정 복귀) — 명시적 비삭제 행위가
     * 이후 고아 정리에 지워질 고아를 만들지 않게 한다(변수 제어).
     */
    fun unassign(paths: List<String>, ownerFilter: List<Owner>?, onDone: (UnassignResult) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val byPath = currentItemsByPath()
                    var cleared = 0
                    var adopted = 0
                    db.withTransaction {
                        for (path in paths) {
                            val item = byPath[path] ?: continue
                            val targets = ownerFilter?.filter { it in item.owners } ?: item.owners
                            if (targets.isEmpty()) continue
                            val canon = runCatching { File(path).canonicalPath }.getOrNull() ?: path
                            for (owner in targets) {
                                when (owner.type) {
                                    OwnerType.CHARACTER -> db.characterDao().getCharacterById(owner.id)?.let { c ->
                                        db.characterDao().update(c.copy(imagePaths = removePath(c.imagePaths, path, canon)))
                                    }
                                    OwnerType.NOVEL -> db.novelDao().getNovelById(owner.id)?.let { n ->
                                        db.novelDao().update(n.copy(imagePaths = removePath(n.imagePaths, path, canon)))
                                    }
                                    OwnerType.UNIVERSE -> db.universeDao().getUniverseById(owner.id)?.let { u ->
                                        db.universeDao().update(u.copy(imagePaths = removePath(u.imagePaths, path, canon)))
                                    }
                                }
                            }
                            cleared++
                            val remaining = item.owners - targets.toSet()
                            if (remaining.isEmpty() && item.meta == null && File(path).exists()) {
                                db.imageMetaDao().adopt(path, System.currentTimeMillis())
                                adopted++
                            }
                        }
                    }
                    UnassignResult(cleared, adopted)
                } catch (e: Exception) {
                    UnassignResult(0, 0, failed = true)
                }
            }
            load()
            onDone(result)
        }
    }

    /**
     * 링크 생성(D5): ≥2장, 전원 라이브러리 입양. 기존 그룹 1개에 걸치면 그 그룹으로 흡수,
     * 2개 이상 걸치면 [LinkOutcome.NeedsMerge]를 돌려 확인 후 [confirmMerge]=true로 재호출.
     */
    fun linkImages(paths: List<String>, confirmMerge: Boolean, onDone: (LinkOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val plan = ImageLinkResolver.planLink(paths, currentMetas())
                    if (plan.needsMergeConfirm && !confirmMerge) {
                        LinkOutcome.NeedsMerge(plan.groupsInvolved.size)
                    } else {
                        db.withTransaction {
                            val now = System.currentTimeMillis()
                            val ids = paths.map { db.imageMetaDao().adopt(it, now) }.toMutableSet()
                            for (g in plan.groupsInvolved) {
                                db.imageMetaDao().getByGroup(g).forEach { ids.add(it.id) }
                            }
                            val groupId = plan.groupsInvolved.firstOrNull() ?: UUID.randomUUID().toString()
                            db.imageMetaDao().setGroup(ids.toList(), groupId)
                        }
                        LinkOutcome.Done(paths.size, plan.needsMergeConfirm)
                    }
                } catch (e: Exception) {
                    LinkOutcome.Failed
                }
            }
            if (outcome !is LinkOutcome.NeedsMerge) load()
            onDone(outcome)
        }
    }

    /** 링크 해제 — 대상 경로들의 그룹을 지우고, 1장만 남은 그룹은 자동 정리. @return 해제 수 */
    fun unlinkImages(paths: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    val byPath = currentItemsByPath()
                    val targets = paths.mapNotNull { p ->
                        byPath[p]?.meta?.let { m -> if (m.linkGroupId != null) m.imageId to m.linkGroupId else null }
                    }
                    if (targets.isEmpty()) 0
                    else db.withTransaction {
                        db.imageMetaDao().setGroup(targets.map { it.first }, null)
                        targets.mapTo(HashSet()) { it.second }.forEach { db.imageMetaDao().clearGroupIfSingleton(it) }
                        targets.size
                    }
                } catch (e: Exception) { 0 }
            }
            load()
            onDone(count)
        }
    }

    /** 일괄 태그 추가 — 전원 입양 후 태그 삽입(중복 IGNORE). @return 반영 이미지 수 */
    fun addTagsToImages(paths: List<String>, tags: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    db.withTransaction {
                        val now = System.currentTimeMillis()
                        for (p in paths) {
                            val id = db.imageMetaDao().adopt(p, now)
                            db.imageTagDao().insertAll(tags.map { com.novelcharacter.app.data.model.ImageTag(imageId = id, tag = it) })
                        }
                    }
                    paths.size
                } catch (e: Exception) { 0 }
            }
            load()
            onDone(count)
        }
    }

    /** 일괄 태그 제거 — 라이브러리 이미지들에서 지정 태그 제거. @return 대상 이미지 수 */
    fun removeTagsFromImages(paths: List<String>, tags: List<String>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                try {
                    val ids = metaIdsForPaths(paths)
                    if (ids.isNotEmpty()) db.imageTagDao().deleteTagsFromImages(ids, tags)
                    ids.size
                } catch (e: Exception) { 0 }
            }
            load()
            onDone(count)
        }
    }

    /** 7일 지난 재압축 백업 정리 — subdir라 고아 정리 대상이 아니므로 여기서 수명 관리(무한 누적 방지). */
    private fun sweepOldRecompressBackups() {
        val dir = File(getApplication<Application>().filesDir, recompressBackupDirName)
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - backupRetentionMs
        dir.listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() } }
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
