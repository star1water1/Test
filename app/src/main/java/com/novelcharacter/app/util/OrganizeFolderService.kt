package com.novelcharacter.app.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.ImageMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 정리 폴더 **받아오기의 실행 계층** — 계획은 [FolderRoundtripPlanner](순수)가 세우고,
 * 여기서 SAF 나열·파일 편입·DB 반영·`_처리됨/` 이동을 한다.
 *
 * ## SAF를 직접 쓰는 이유
 *
 * `androidx.documentfile`을 넣지 않는다(설계 9장 C-4). 그 의존은 이 저장소의 빌드 환경에서
 * 로컬 해결이 되지 않아 CI에서만 확인되고, `DocumentFile`은 **파일마다 쿼리**를 날려 수백 장
 * 규모에서 느리다. 여기서는 [DocumentsContract]로 폴더당 쿼리 1회만 한다.
 *
 * ## 불변식 (설계 1장)
 *
 * - **앱이 원본을 소유한다.** 폴더의 파일이 없어져도 앱 데이터는 그대로다("폴더에 없음 ≠ 삭제").
 * - **받아오기는 메타데이터와 신규 편입만 쓴다.** 기존 이미지 파일 내용을 덮어쓰지 않는다.
 * - **캐릭터 배정만 다룬다.** 작품·세계관 배정은 읽지도 쓰지도 않는다.
 * - 항목 단위로 완결한다 — 취소하면 중단 시점까지 반영되고 반쪽 항목이 남지 않는다.
 */
object OrganizeFolderService {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** 나열된 파일 1건. */
    data class ScannedFile(
        val documentId: String,
        val parentDocumentId: String,
        val folders: List<String>,
        val name: String,
        val size: Long,
        val modifiedAt: Long
    ) {
        val relativePath: String get() = (folders + name).joinToString("/")
    }

    /**
     * 나열 결과.
     *
     * @param unreadFolders 규약 밖 깊이라 내려가지 않은 폴더 수(무시했다는 사실을 고지한다).
     * @param nonImageIgnored 이미지가 아니라 건너뛴 파일 수.
     * @param skippedByFingerprint 지난번 `_처리됨/` 이동에 실패해 이미 처리한 것으로 아는 파일 수.
     */
    data class ScanResult(
        val files: List<ScannedFile>,
        val unreadFolders: Int = 0,
        val nonImageIgnored: Int = 0,
        val skippedByFingerprint: Int = 0
    )

    /** 계획 + 그 계획을 설명하는 데 필요한 부수 정보(사전 확인 다이얼로그용). */
    /** 동명 폴더의 선택지 1건 — 사용자가 "어느 쪽인가"를 고를 수 있을 만큼만 담는다. */
    data class FolderCandidate(val characterId: Long, val name: String, val novelTitle: String?)

    data class PlanBundle(
        val plan: FolderRoundtripPlanner.Plan,
        val scan: ScanResult,
        /** 링크 세트가 흡수하는 기존 그룹 수. */
        val mergedGroups: Int = 0,
        /** 흡수 때문에 함께 묶이는, 폴더에 없던 이미지 수(조용한 확대 금지). */
        val mergedOutsiders: Int = 0,
        /**
         * canonical 경로 → **DB에 실제로 저장된 표기**.
         *
         * 계획은 canonical로 세우지만(비교의 축), 쓸 때는 반드시 저장형으로 되돌려야 한다 —
         * 코드베이스 관례가 "저장은 absolutePath, 비교만 canonical"이고 `image_meta.path`·
         * `getByPath`는 **정확 일치**다. 기기에 따라 `/data/user/0/…`가 `/data/data/…`의
         * 심볼릭 링크라 둘이 다른 문자열이 되는데, 그때 canonical로 쓰면 같은 파일의 meta 행이
         * 하나 더 생긴다(유니크 인덱스는 문자열 기준이라 막지 못한다).
         */
        val storedPathByCanonical: Map<String, String> = emptyMap(),
        /**
         * 동명이라 물어봐야 하는 폴더명 → 고를 수 있는 후보들.
         *
         * 이름만 N개 나열하면 고를 수가 없으므로 **작품명을 함께** 싣는다
         * (인앱 `DuplicateCharacterDialog`가 쓰는 방식과 같다).
         */
        val ambiguousCandidates: Map<String, List<FolderCandidate>> = emptyMap()
    ) {
        val isEmpty: Boolean get() = plan.isEmpty

        /** 계획이 든 canonical 경로를 저장형으로 되돌린다. 모르는 경로는 그대로 쓴다. */
        fun stored(canonicalPath: String): String = storedPathByCanonical[canonicalPath] ?: canonicalPath
    }

    /** 반영 결과 — 모든 수치는 고지 대상이다. */
    data class ApplyResult(
        val imported: Int = 0,
        val moved: Int = 0,
        val detached: Int = 0,
        val linkedSets: Int = 0,
        val failed: List<String> = emptyList(),
        val heldNames: List<String> = emptyList(),
        val unmovedOriginals: Int = 0,
        val cancelled: Boolean = false
    )

    // ── 나열 ──

    /**
     * 정리 폴더를 나열한다. 접근할 수 없으면 null(권한 소실 — 호출부가 재지정 경로를 안내한다).
     *
     * 깊이·예약 폴더 규칙은 [FolderRoundtripPlanner.shouldDescend]가 단일 소스다.
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        applyFingerprints: Boolean = true
    ): ScanResult? = withContext(Dispatchers.IO) {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext null
        // 내보내기의 이전 사본 정리는 **지문으로 걸러진 파일까지** 봐야 한다 — 사본에는 지문이
        // 찍혀 있어서, 거른 목록으로 정리를 계획하면 이전 사본을 못 보고 남겨 같은 이미지를
        // 가리키는 파일이 폴더에 둘이 된다(받아오기가 그 이미지를 통째로 보류한다).
        val fingerprints = if (applyFingerprints) FolderRoundtripPrefs.fingerprints(context) else emptySet()

        val files = ArrayList<ScannedFile>()
        var unreadFolders = 0
        var nonImage = 0
        var skipped = 0

        // 너비 우선 — 폴더당 쿼리 1회.
        val queue = ArrayDeque<Pair<String, List<String>>>()
        queue.add(rootId to emptyList())
        while (queue.isNotEmpty()) {
            val (documentId, folders) = queue.removeFirst()
            val children = listChildren(context, treeUri, documentId) ?: return@withContext null
            for (child in children) {
                if (child.isDirectory) {
                    val childFolders = folders + child.name
                    when {
                        // `_처리됨/`은 통째로 제외한다 — 계수도 하지 않는다(처리 이력만큼 불어나는
                        // 거짓 신호가 된다).
                        childFolders.firstOrNull() == FolderRoundtripPlanner.FOLDER_PROCESSED -> Unit
                        FolderRoundtripPlanner.shouldDescend(childFolders) ->
                            queue.add(child.documentId to childFolders)
                        else -> unreadFolders++
                    }
                    continue
                }
                if (folders.firstOrNull() == FolderRoundtripPlanner.FOLDER_PROCESSED) continue
                if (!isImageName(child.name)) { nonImage++; continue }
                val fingerprint = FolderRoundtripLedger.fingerprintOf(
                    (folders + child.name).joinToString("/"), child.size, child.modifiedAt
                )
                if (fingerprint in fingerprints) { skipped++; continue }
                files.add(
                    ScannedFile(child.documentId, documentId, folders, child.name, child.size, child.modifiedAt)
                )
            }
        }
        ScanResult(files, unreadFolders, nonImage, skipped)
    }

    private data class Child(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long
    )

    /** 폴더 하나의 자식 목록. 접근 실패 시 null(권한 소실과 빈 폴더를 구분한다). */
    private fun listChildren(context: Context, treeUri: Uri, documentId: String): List<Child>? {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        }.getOrNull() ?: return null
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val result = ArrayList<Child>(cursor.count)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    result.add(
                        Child(
                            documentId = id,
                            name = name,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            modifiedAt = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                        )
                    )
                }
                result
            }
        }.getOrNull()
    }

    private fun isImageName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    // ── 계획 ──

    /** 현재 DB 상태를 읽어 계획을 세운다. */
    /**
     * @param ambiguousChoices 동명 폴더에 대해 사용자가 고른 대상(폴더명 → 캐릭터 id).
     *        비어 있으면 동명 폴더는 [FolderRoundtripPlanner.Plan.ambiguousFolders]에 실려
     *        호출부가 물어보게 된다. 답을 받은 뒤 **같은 스캔으로 계획만 다시 세운다** —
     *        다시 스캔하면 그 사이 바뀐 폴더 때문에 사용자가 답한 것과 다른 계획이 나온다.
     */
    suspend fun buildPlan(
        context: Context,
        db: AppDatabase,
        scan: ScanResult,
        ambiguousChoices: Map<String, Long> = emptyMap()
    ): PlanBundle =
        withContext(Dispatchers.IO) {
            val gson = Gson()
            val characters = db.characterDao().getAllCharactersList()

            // 캐릭터 이름 사전 — 트림 기준. 동명이 둘 이상이면 해소 사다리가 코드·작품으로 좁힌다.
            val idsByName = HashMap<String, MutableList<Long>>()
            // 이미지 경로 → 그 이미지를 등록한 캐릭터 id 목록(비교는 canonical, 저장은 원문).
            val idsByCanonPath = HashMap<String, MutableList<Long>>()
            val storedByCanon = HashMap<String, String>()
            for (c in characters) {
                idsByName.getOrPut(c.name.trim()) { mutableListOf() }.add(c.id)
                for (stored in parsePaths(gson, c.imagePaths)) {
                    val canon = canonical(stored)
                    storedByCanon.putIfAbsent(canon, stored)
                    idsByCanonPath.getOrPut(canon) { mutableListOf() }.add(c.id)
                }
            }

            // 토큰 사전 — 라이브러리 행 + 캐릭터가 쥔 경로 전체가 대상이다.
            val metaPaths = db.imageMetaDao().getAllPaths()
            val livePaths = LinkedHashSet<String>()
            for (p in metaPaths + storedByCanon.values) {
                val canon = canonical(p)
                if (File(canon).exists()) {
                    storedByCanon.putIfAbsent(canon, p)
                    livePaths.add(canon)
                }
            }
            val dictionary = FolderNameToken.buildDictionary(
                livePaths,
                FolderRoundtripPrefs.renameAliases(context).mapKeys { canonical(it.key) }
                    .mapValues { canonical(it.value) }
            )

            val items = scan.files.map {
                FolderRoundtripPlanner.ScanItem(it.documentId, it.folders, it.name)
            }
            // 해소 사다리 — 정확 일치 → `이름#코드` → `이름(작품명)` → 사용자 선택.
            val novels = db.novelDao().getAllNovelsList()
            val novelIdsByTitle = HashMap<String, MutableList<Long>>()
            for (n in novels) novelIdsByTitle.getOrPut(n.title.trim()) { mutableListOf() }.add(n.id)
            val resolver = FolderRoundtripPlanner.CharacterFolderResolver(
                characterIdsByName = idsByName,
                characterIdByCode = characters.associate { it.code to it.id },
                novelIdByCharacterId = characters.mapNotNull { c -> c.novelId?.let { c.id to it } }.toMap(),
                novelIdsByTitle = novelIdsByTitle,
                choices = ambiguousChoices
            )
            val plan = FolderRoundtripPlanner.plan(
                items, idsByName, dictionary.pathByToken, idsByCanonPath, resolver
            )

            // 링크 세트가 흡수하는 기존 그룹 — 사전 확인에 한 줄로 싣는다(설계 9장 C-8).
            var mergedGroups = 0
            var mergedOutsiders = 0
            if (plan.linkSets.isNotEmpty()) {
                val metas = db.imageMetaDao().getAllList()
                val groupByCanon = metas.associate { canonical(it.path) to it.linkGroupId }
                val membersByGroup = HashMap<String, Int>()
                for (m in metas) {
                    val g = m.linkGroupId ?: continue
                    membersByGroup[g] = (membersByGroup[g] ?: 0) + 1
                }
                for (set in plan.linkSets) {
                    val groups = set.existingPaths
                        .mapNotNull { groupByCanon[canonical(it)] }
                        .filterNot { AutoLinkPlanner.isAutoToken(it) }
                        .distinct()
                    mergedGroups += groups.size
                    val inFolder = set.existingPaths.size
                    val pulled = groups.sumOf { membersByGroup[it] ?: 0 }
                    mergedOutsiders += (pulled - inFolder).coerceAtLeast(0)
                }
            }
            // 물어봐야 하는 폴더의 후보 — 이름만으로는 고를 수 없으므로 작품명을 함께 싣는다.
            val titleByNovelId = novels.associate { it.id to it.title }
            val charById = characters.associateBy { it.id }
            val candidates = plan.ambiguousFolders.associateWith { folder ->
                idsByName[resolver.displayName(folder)].orEmpty().mapNotNull { id ->
                    charById[id]?.let { c ->
                        FolderCandidate(c.id, c.name, c.novelId?.let { titleByNovelId[it] })
                    }
                }
            }
            PlanBundle(plan, scan, mergedGroups, mergedOutsiders, storedByCanon.toMap(), candidates)
        }

    // ── 반영 ──

    /**
     * 계획을 반영한다. 항목 하나가 끝날 때마다 [onProgress]를 부르고, [isCancelled]가 true가
     * 되면 **그 항목까지 반영하고** 멈춘다(반쪽 항목 없음).
     */
    suspend fun applyPlan(
        context: Context,
        db: AppDatabase,
        treeUri: Uri,
        bundle: PlanBundle,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): ApplyResult = withContext(Dispatchers.IO) {
        val plan = bundle.plan
        val fileById = bundle.scan.files.associateBy { it.documentId }
        val settings = ImageSettingsStore(context).getSettings()
        val gson = Gson()
        val total = plan.actionCount

        var done = 0
        var imported = 0
        var moved = 0
        var detached = 0
        val failures = ArrayList<String>()
        val processedFiles = ArrayList<ScannedFile>()
        val unmovedFingerprints = ArrayList<String>()
        // 편입 결과 — 링크 세트가 나중에 쓴다(문서 id → 내부 경로).
        val importedPathById = HashMap<String, String>()
        var cancelled = false

        suspend fun step() {
            done++
            onProgress(done, total)
        }

        // ① 신규 편입 — 압축 파이프라인은 이미지 탭 임포트와 같은 것을 쓴다.
        for (action in plan.imports) {
            if (isCancelled()) { cancelled = true; break }
            val file = fileById[action.item.id] ?: continue
            val uri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
            }.getOrNull()
            val path = uri?.let { ImageImportHelper.importImage(context, it, "img", settings) }
            if (path == null) {
                failures.add(file.name)
                step()
                continue
            }
            val now = System.currentTimeMillis()
            runCatching {
                db.withTransaction {
                    db.imageMetaDao().insert(ImageMeta(path = path, importedAt = now))
                    val target = action.assignCharacterId
                    if (target != null) {
                        db.characterDao().getCharacterById(target)?.let { c ->
                            val paths = parsePaths(gson, c.imagePaths) + path
                            db.characterDao().update(c.copy(imagePaths = gson.toJson(paths)))
                        }
                    }
                }
            }
            importedPathById[action.item.id] = path
            imported++
            processedFiles.add(file)
            step()
        }

        // ② 배정 이동 — 옛 캐릭터에서 빼고 새 캐릭터에 넣는다(파일은 그대로).
        if (!cancelled) for (action in plan.moves) {
            if (isCancelled()) { cancelled = true; break }
            val file = fileById[action.item.id] ?: continue
            val storedPath = bundle.stored(action.path)
            val ok = runCatching {
                db.withTransaction {
                    for (id in action.fromCharacterIds) {
                        db.characterDao().getCharacterById(id)?.let { c ->
                            db.characterDao().update(
                                c.copy(imagePaths = removePath(gson, c.imagePaths, action.path))
                            )
                        }
                    }
                    db.characterDao().getCharacterById(action.toCharacterId)?.let { c ->
                        val current = parsePaths(gson, c.imagePaths)
                        if (current.none { canonical(it) == action.path }) {
                            db.characterDao().update(
                                c.copy(imagePaths = gson.toJson(current + storedPath))
                            )
                        }
                    }
                }
            }.isSuccess
            if (ok) { moved++; processedFiles.add(file) } else failures.add(file.name)
            step()
        }

        // ③ 배정 해제 — 파일은 남기고 라이브러리로 승격한다(인앱 '배정 해제'와 같은 규약).
        if (!cancelled) for (action in plan.detaches) {
            if (isCancelled()) { cancelled = true; break }
            val file = fileById[action.item.id] ?: continue
            val ok = runCatching {
                db.withTransaction {
                    for (id in action.fromCharacterIds) {
                        db.characterDao().getCharacterById(id)?.let { c ->
                            db.characterDao().update(
                                c.copy(imagePaths = removePath(gson, c.imagePaths, action.path))
                            )
                        }
                    }
                    val now = System.currentTimeMillis()
                    // 저장형으로 입양한다 — canonical로 넣으면 같은 파일의 meta 행이 하나 더 생긴다.
                    db.imageMetaDao().adopt(bundle.stored(action.path), now)
                }
            }.isSuccess
            if (ok) { detached++; processedFiles.add(file) } else failures.add(file.name)
            step()
        }

        // ④ 링크 세트 — 인앱 수동 링크와 같은 흡수·병합 규약(단, 자동 토큰은 흡수하지 않는다.
        //    자동 그룹을 대상으로 삼으면 다음 재동기화가 그 묶음을 도로 풀어 조용히 사라진다).
        var linkedSets = 0
        for (set in plan.linkSets) {
            val paths = set.newItemIds.mapNotNull { importedPathById[it] } +
                set.existingPaths.map { bundle.stored(it) }
            if (paths.size < 2) continue
            val ok = runCatching {
                db.withTransaction {
                    val now = System.currentTimeMillis()
                    val ids = paths.mapTo(LinkedHashSet()) { db.imageMetaDao().adopt(it, now) }
                    val existingGroups = paths
                        .mapNotNull { db.imageMetaDao().getByPath(it)?.linkGroupId }
                        .filterNot { AutoLinkPlanner.isAutoToken(it) }
                        .distinct()
                    for (g in existingGroups) db.imageMetaDao().getByGroup(g).forEach { ids.add(it.id) }
                    val token = existingGroups.firstOrNull() ?: UUID.randomUUID().toString()
                    db.imageMetaDao().setGroup(ids.toList(), token)
                }
            }.isSuccess
            if (ok) linkedSets++
        }

        // ⑤ 마무리 — 자동 링크 재동기화(배정 변경분 수렴), 처리분 `_처리됨/` 이동.
        runCatching { CharacterImageAutoLinker.resyncIfEnabled(context, db) }

        var unmoved = 0
        val processedRoot = ensureProcessedRoot(context, treeUri)
        for (file in processedFiles) {
            val ok = processedRoot != null && moveToProcessed(context, treeUri, file, processedRoot)
            if (!ok) {
                unmoved++
                unmovedFingerprints.add(
                    FolderRoundtripLedger.fingerprintOf(file.relativePath, file.size, file.modifiedAt)
                )
            }
        }
        if (unmovedFingerprints.isNotEmpty()) {
            FolderRoundtripPrefs.addUnmovedFingerprints(context, unmovedFingerprints)
        }

        ApplyResult(
            imported = imported,
            moved = moved,
            detached = detached,
            linkedSets = linkedSets,
            failed = failures,
            heldNames = plan.holds.map { it.item.fileName },
            unmovedOriginals = unmoved,
            cancelled = cancelled
        )
    }

    // ── 내보내기 (PR-2) ──

    /**
     * 내보내기 계획 + 그 계획을 설명하는 데 필요한 부수 정보(사전 확인 다이얼로그용).
     *
     * @param cleanup 지울 이전 사본. [FolderExportPlanner.Cleanup.rearrangedIds]는 **아직
     *        받아오지 않은 사용자의 배치**라 확인 다이얼로그가 따로 고지한다.
     */
    data class ExportBundle(
        val plan: FolderExportPlanner.Plan,
        val cleanup: FolderExportPlanner.Cleanup = FolderExportPlanner.Cleanup(),
        val scope: FolderExportPlanner.Scope = FolderExportPlanner.Scope.ALL
    ) {
        val isEmpty: Boolean get() = plan.isEmpty
        /** 진행도 총량 — 지우는 것도 사용자를 기다리게 하는 일이다. */
        val workCount: Int get() = plan.files.size + cleanup.staleIds.size
    }

    /** 내보내기 결과 — 모든 수치는 고지 대상이다. */
    data class ExportResult(
        val exported: Int = 0,
        val bytes: Long = 0,
        val removed: Int = 0,
        val removeFailed: Int = 0,
        val failed: List<String> = emptyList(),
        val cancelled: Boolean = false
    )

    /**
     * 현재 라이브러리 상태로 내보내기 계획을 세운다. 폴더에 접근할 수 없으면 null.
     *
     * 계획은 canonical 경로로 세운다(비교의 축) — 내보내기는 **읽기만** 하므로 받아오기와 달리
     * 저장형으로 되돌릴 일이 없다(`File(canonical)`은 같은 파일을 연다).
     */
    suspend fun buildExportPlan(
        context: Context,
        db: AppDatabase,
        treeUri: Uri,
        scope: FolderExportPlanner.Scope
    ): ExportBundle? = withContext(Dispatchers.IO) {
        val existing = scan(context, treeUri, applyFingerprints = false) ?: return@withContext null
        val gson = Gson()

        val characters = db.characterDao().getAllCharactersList()
        val ownersByPath = HashMap<String, MutableList<Long>>()
        for (c in characters) {
            for (stored in parsePaths(gson, c.imagePaths)) {
                ownersByPath.getOrPut(canonical(stored)) { mutableListOf() }.add(c.id)
            }
        }

        val metas = db.imageMetaDao().getAllList()
        val groupByPath = HashMap<String, String?>(metas.size)
        for (m in metas) groupByPath[canonical(m.path)] = m.linkGroupId

        // 작품·세계관 전용 이미지는 v1 범위 밖이지만 **개수는 고지해야** 하므로 후보에 싣는다
        // (캐릭터도 라이브러리도 쥐지 않은 것만 그 부류로 남는다).
        val entityPaths = LinkedHashSet<String>()
        for (json in db.novelDao().getAllNovelsList().map { it.imagePaths } +
            db.universeDao().getAllUniversesList().map { it.imagePaths }) {
            for (stored in parsePaths(gson, json)) entityPaths.add(canonical(stored))
        }

        val candidatePaths = LinkedHashSet<String>().apply {
            addAll(ownersByPath.keys)
            addAll(groupByPath.keys)
            addAll(entityPaths)
        }
        val livePaths = candidatePaths.filterTo(LinkedHashSet()) { File(it).exists() }
        val images = candidatePaths.map { path ->
            FolderExportPlanner.ImageInput(
                path = path,
                sizeBytes = runCatching { File(path).length() }.getOrDefault(0L),
                ownerCharacterIds = ownersByPath[path].orEmpty(),
                linkGroupId = groupByPath[path],
                inLibrary = path in groupByPath
            )
        }

        val dictionary = FolderNameToken.buildDictionary(
            livePaths,
            FolderRoundtripPrefs.renameAliases(context).mapKeys { canonical(it.key) }
                .mapValues { canonical(it.value) }
        )
        val plan = FolderExportPlanner.plan(
            images = images,
            characters = characters.map { FolderExportPlanner.CharacterInput(it.id, it.name, it.code) },
            tokenByPath = dictionary.tokenByPath,
            scope = scope,
            existingPaths = livePaths
        )
        val cleanup = FolderExportPlanner.planCleanup(
            existing.files.map {
                FolderExportPlanner.ExistingCopy(it.documentId, it.folders, it.name)
            },
            plan.files,
            dictionary.pathByToken
        )
        ExportBundle(plan, cleanup, scope)
    }

    /**
     * 계획을 실행한다 — 이전 사본을 지우고, 사본을 새로 쓴다.
     *
     * **원본은 읽기만 한다.** 폴더의 파일은 전부 사본이므로 실패해도 앱 데이터는 그대로다.
     * 파일 하나가 끝날 때마다 [onProgress]를 부르고, [isCancelled]가 true가 되면 그 파일까지
     * 쓰고 멈춘다(반쪽 파일 없음).
     *
     * 마지막에 **쓴 사본의 지문을 남긴다** — 그러지 않으면 다음 진입 감지가 우리가 방금 쓴
     * 사본을 "새 이미지"로 세어 거짓 배너를 띄우고, 받아오기도 할 일 없는 파일을 훑는다.
     * 사본을 다른 폴더로 옮기면 상대경로가 바뀌어 지문이 어긋나므로 그때는 정상적으로 잡힌다.
     */
    suspend fun runExport(
        context: Context,
        treeUri: Uri,
        bundle: ExportBundle,
        onProgress: suspend (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): ExportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext ExportResult()
        val total = bundle.workCount
        var done = 0
        suspend fun step() {
            done++
            onProgress(done, total)
        }

        // ① 이전 사본 정리 — 남기면 같은 이미지를 가리키는 파일이 둘이 되어 받아오기가 보류한다.
        var removed = 0
        var removeFailed = 0
        var cancelled = false
        // 지우지 못한 사본의 이미지는 새로 쓰지 않는다(아래 ②) — 같은 이름이 부딪히면
        // provider가 이름을 바꿔 달아 토큰이 깨지고, 그 사본이 다음 받아오기에서 **새 이미지로**
        // 편입된다. 조용한 중복보다 "이번엔 내보내지 않았다"가 낫다.
        val blockedSources = HashSet<String>()
        for (documentId in bundle.cleanup.staleIds) {
            if (isCancelled()) { cancelled = true; break }
            val uri = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            }.getOrNull()
            val ok = uri != null &&
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
            if (ok) {
                removed++
            } else {
                removeFailed++
                bundle.cleanup.sourcePathById[documentId]?.let { blockedSources.add(it) }
            }
            step()
        }

        // ② 사본 쓰기. 폴더 문서 id는 한 번만 찾는다(폴더당 쿼리 1회).
        val folderIds = HashMap<List<String>, String?>()
        folderIds[emptyList()] = rootId
        fun folderId(folders: List<String>): String? {
            val walked = ArrayList<String>(folders.size)
            var current: String? = rootId
            for (name in folders) {
                walked.add(name)
                val key = ArrayList(walked)
                current = if (folderIds.containsKey(key)) {
                    folderIds[key]
                } else {
                    val made = current?.let { ensureDirectory(context, treeUri, it, name) }
                    folderIds[key] = made
                    made
                }
                if (current == null) return null
            }
            return current
        }

        var exported = 0
        var bytes = 0L
        val failures = ArrayList<String>()
        val writtenFolders = LinkedHashSet<List<String>>()
        val writtenTokens = HashSet<String>()

        if (!cancelled) for (file in bundle.plan.files) {
            if (isCancelled()) { cancelled = true; break }
            if (file.sourcePath in blockedSources) {
                failures.add(file.fileName)
                step()
                continue
            }
            val parentId = folderId(file.folders)
            val parentUri = parentId?.let {
                runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }.getOrNull()
            }
            val ok = parentUri != null && runCatching {
                val created = DocumentsContract.createDocument(
                    resolver, parentUri, mimeOf(file.fileName), file.fileName
                ) ?: return@runCatching false
                resolver.openOutputStream(created)?.use { output ->
                    File(file.sourcePath).inputStream().use { input -> input.copyTo(output) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (ok) {
                exported++
                bytes += file.sizeBytes
                writtenFolders.add(file.folders)
                writtenTokens.add(file.token)
            } else {
                failures.add(file.fileName)
            }
            step()
        }

        // ③ 쓴 사본의 지문 기록 — 이름은 provider가 정하므로(같은 이름이 있으면 바꿔 단다)
        //    폴더를 한 번씩 다시 읽어 **실제로 놓인 파일**의 지문을 남긴다.
        val fingerprints = ArrayList<String>()
        for (folders in writtenFolders) {
            val id = folderIds[folders] ?: continue
            for (child in listChildren(context, treeUri, id).orEmpty()) {
                if (child.isDirectory) continue
                val token = FolderNameToken.tokenCandidateOf(child.name) ?: continue
                if (token !in writtenTokens) continue
                fingerprints.add(
                    FolderRoundtripLedger.fingerprintOf(
                        (folders + child.name).joinToString("/"), child.size, child.modifiedAt
                    )
                )
            }
        }
        // 내보내기 전용 장부에 넣는다 — 이동 실패 지문과 상한을 나눠 쓰면 대량 내보내기가
        // 그쪽을 통째로 밀어내 중복 편입을 만든다(FolderRoundtripPrefs의 두 장부 주석 참조).
        if (fingerprints.isNotEmpty()) FolderRoundtripPrefs.addExportFingerprints(context, fingerprints)

        ExportResult(exported, bytes, removed, removeFailed, failures, cancelled)
    }

    // ── `_처리됨/` 이동 ──

    private fun ensureProcessedRoot(context: Context, treeUri: Uri): String? {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return ensureDirectory(context, treeUri, rootId, FolderRoundtripPlanner.FOLDER_PROCESSED)
    }

    /** 자식 폴더를 찾거나 만든다. 실패 시 null. */
    private fun ensureDirectory(
        context: Context,
        treeUri: Uri,
        parentDocumentId: String,
        name: String
    ): String? {
        listChildren(context, treeUri, parentDocumentId)
            ?.firstOrNull { it.isDirectory && it.name == name }
            ?.let { return it.documentId }
        val parentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
        }.getOrNull() ?: return null
        return runCatching {
            DocumentsContract.createDocument(
                context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
            )?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()
    }

    /**
     * 처리한 원본을 `_처리됨/<원폴더명>/`으로 옮긴다.
     *
     * `moveDocument`는 provider가 지원할 때만 되므로 **복사 후 삭제**를 폴백으로 둔다.
     * 복사가 실패하면 원본을 지우지 않는다 — 이동에 실패한 파일이 사라지는 것이 최악이다.
     */
    private fun moveToProcessed(
        context: Context,
        treeUri: Uri,
        file: ScannedFile,
        processedRootId: String
    ): Boolean {
        val targetParentId = if (file.folders.isEmpty()) {
            processedRootId
        } else {
            ensureDirectory(context, treeUri, processedRootId, file.folders.joinToString("_"))
        } ?: return false

        val resolver = context.contentResolver
        val sourceUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
        }.getOrNull() ?: return false
        val sourceParentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, file.parentDocumentId)
        }.getOrNull() ?: return false
        val targetParentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, targetParentId)
        }.getOrNull() ?: return false

        val movedUri = runCatching {
            DocumentsContract.moveDocument(resolver, sourceUri, sourceParentUri, targetParentUri)
        }.getOrNull()
        if (movedUri != null) return true

        // 폴백: 복사 후 삭제. 복사가 확실히 끝난 뒤에만 원본을 지운다.
        val copied = runCatching {
            val created = DocumentsContract.createDocument(
                resolver, targetParentUri, mimeOf(file.name), file.name
            ) ?: return@runCatching false
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(created)?.use { output ->
                    input.copyTo(output)
                    true
                } ?: false
            } ?: false
        }.getOrDefault(false)
        if (!copied) return false
        return runCatching { DocumentsContract.deleteDocument(resolver, sourceUri) }.getOrDefault(false)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        else -> "image/jpeg"
    }

    // ── 공용 유틸 ──

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrNull() ?: path

    private fun parsePaths(gson: Gson, json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching { gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST) }
            .getOrNull()?.filterNotNull() ?: emptyList()
    }

    private fun removePath(gson: Gson, json: String, path: String): String {
        val canon = canonical(path)
        val remaining = parsePaths(gson, json).filterNot { canonical(it) == canon }
        return gson.toJson(remaining)
    }
}
