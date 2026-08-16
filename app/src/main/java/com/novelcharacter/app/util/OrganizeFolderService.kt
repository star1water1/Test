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
        val ambiguousCandidates: Map<String, List<FolderCandidate>> = emptyMap(),
        /**
         * `_삭제승인/`이 지울 이미지를 **대표로 지정한** 캐릭터 이름들(B-107 D6 · B-103 ㄷ1).
         *
         * 계획만으로는 알 수 없다 — 대표 포인터는 캐릭터 쪽에 있고 플래너는 순수 계층이다.
         * 확인창이 "지우면 대표 지정이 풀린다"를 말하려면 여기 실어야 한다. 되돌릴 수 없는
         * 처분이므로 **결과를 먼저 말하는 것**이 R-4의 요구다.
         */
        val deleteRepresentativeOf: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = plan.isEmpty

        /** 계획이 든 canonical 경로를 저장형으로 되돌린다. 모르는 경로는 그대로 쓴다. */
        fun stored(canonicalPath: String): String = storedPathByCanonical[canonicalPath] ?: canonicalPath
    }

    /** 반영 결과 — 모든 수치는 고지 대상이다. */
    data class ApplyResult(
        val imported: Int = 0,
        val moved: Int = 0,
        /** 캐릭터 배정을 실제로 뗀 수. 묶음만 푼 항목은 여기 들어가지 않는다. */
        val detached: Int = 0,
        /**
         * 링크 묶음을 푼 수. [detached]와 **겹치지만 부분집합은 아니다** — 되돌리는 자리에서는
         * 배정이 없고 묶음만 있는 이미지도 풀리기 때문이다(그런 항목은 여기에만 잡힌다).
         */
        val unlinked: Int = 0,
        /**
         * 서랍에 넣었으나 **캐릭터 자동 링크라 묶인 채로 남은** 수. 배정이 그대로인 자리라
         * 재동기화가 도로 묶으므로, 푼 척하고 세는 대신 여기 담아 사유와 함께 고지한다.
         */
        val autoLinkedKept: Int = 0,
        /**
         * `_삭제승인/`으로 **앱에서 지운** 이미지 수(B-107 D6). 되돌릴 수 없으므로 실행 전에
         * 확인창을 거쳤고, 여기 수는 그 확인이 약속한 수와 같아야 한다.
         */
        val deleted: Int = 0,
        /** 지워서 확보한 바이트 — 파괴의 대가를 수로 보여 준다. */
        val deletedBytes: Long = 0,
        /** 묶음이 쪼개져 혼자 남은 이미지 수 — 어시스턴트 카드에 뜰 수와 같다(장부 ③). */
        val scattered: Int = 0,
        /**
         * 신규 편입 항목 id → 최종 저장 경로. AI 태그가 "이번에 그 폴더에서 온 이미지"를
         * 짚으려면 이것이 필요하다(D-4) — 계획은 항목 id만 알고 경로는 편입 뒤에 정해진다.
         */
        val importedPathById: Map<String, String> = emptyMap(),
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
            // 행 전체를 한 번만 읽는다 — 토큰 사전(경로)과 되돌리는 자리 판정(linkGroupId),
            // 아래 병합 고지가 **같은 스냅샷**을 쓰게 하려는 것이다. 종전에는 경로만 읽고
            // 병합 고지에서 같은 표를 한 번 더 읽어, 질의 두 번에 시점도 갈렸다.
            val metas = db.imageMetaDao().getAllList()
            val metaPaths = metas.map { it.path }
            // 묶음에 속한 경로 — 배정이 없어도 되돌리는 자리에서는 할 일이 있다는 판정의 근거.
            val linkedCanonPaths = metas.mapNotNullTo(HashSet()) {
                if (it.linkGroupId != null) canonical(it.path) else null
            }
            // 뗀 표식이 붙은 경로 — `linkedCanonPaths`와 같은 이유로 필요하다(B-107 D5).
            // 서랍에 있던 파일을 `_미배정/`으로 옮기는 것은 "다시 쓸 것으로 되돌림"이라 할 일이
            // 있는데, 그 파일은 배정도 묶음도 없어 이 집합 없이는 '할 일 없음'으로 보인다.
            // 같은 `metas`에서 뽑는다 — 질의를 한 번 더 하면 시점이 갈린다(바로 위 주석의 전례).
            val detachedCanonPaths = metas.mapNotNullTo(HashSet()) {
                if (it.isDetached) canonical(it.path) else null
            }
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
                items, idsByName, dictionary.pathByToken, idsByCanonPath, resolver,
                linkedCanonPaths, detachedCanonPaths
            )

            // 링크 세트가 흡수하는 기존 그룹 — 사전 확인에 한 줄로 싣는다(설계 9장 C-8).
            var mergedGroups = 0
            var mergedOutsiders = 0
            if (plan.linkSets.isNotEmpty()) {
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
            // `_삭제승인/`이 대표 이미지를 지우면 확인창이 그 사실을 함께 말해야 한다
            // (B-107 D6 · B-103 ㄷ1 — 파괴는 결과를 먼저 말한다). 캐릭터는 위에서 이미 읽었다.
            val deleteTargets = plan.deletes.mapTo(HashSet()) { canonical(it.path) }
            val representativeOf = if (deleteTargets.isEmpty()) emptyList() else {
                characters.filter {
                    ImagePathMatch.canonical(it.representativeImagePath).takeIf { p -> p.isNotEmpty() }
                        ?.let { p -> p in deleteTargets } == true
                }.map { it.name }
            }
            PlanBundle(
                plan, scan, mergedGroups, mergedOutsiders, storedByCanon.toMap(), candidates,
                deleteRepresentativeOf = representativeOf
            )
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
        // 배정 해제 중 실제로 링크까지 푼 수 — 결과에 따로 싣는다(무엇이 일어났는지 숫자로 말한다).
        var unlinked = 0
        // 서랍에 넣었으나 자동 링크라 묶인 채 남은 수(③-b 참조).
        var autoLinkedKept = 0
        var deleted = 0
        var deletedBytes = 0L
        // 이번 반영이 멤버를 뺀 링크 그룹 — 누가 혼자 남았는지는 ④가 끝난 뒤에 판정한다.
        val touchedGroups = LinkedHashSet<String>()
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
                            db.characterDao().update(c.withImagePaths(gson.toJson(paths)))
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
                                c.withImagePaths(removePath(gson, c.imagePaths, action.path))
                            )
                        }
                    }
                    db.characterDao().getCharacterById(action.toCharacterId)?.let { c ->
                        val current = parsePaths(gson, c.imagePaths)
                        if (current.none { canonical(it) == action.path }) {
                            db.characterDao().update(
                                c.withImagePaths(gson.toJson(current + storedPath))
                            )
                        }
                    }
                }
            }.isSuccess
            if (ok) { moved++; processedFiles.add(file) } else failures.add(file.name)
            step()
        }

        // ③ 배정 해제 — 파일은 남기고 라이브러리로 승격한다(인앱 '배정 해제'와 같은 규약).
        //
        // **링크 묶음도 함께 푼다.** 종전에는 캐릭터 배정만 떼고 `linkGroupId`는 남겨서, 같은
        // 폴더가 신규 파일과 기존 파일에게 다른 뜻이 됐다 — 신규는 "미배정 + 링크 없음"인데
        // 기존은 "배정 해제 + 링크 유지"였다. 사용자가 되돌리려고 `_미배정/`에 넣어도 묶음이
        // 남으니 "초기화했는데 그대로"가 된다. 정리 폴더 직속·`_미배정/` 직속은 **되돌리는
        // 자리**여야 하므로 뜻을 하나로 맞춘다(사용자 판정 A안).
        //
        // 해제 규약은 인앱 `unlinkImages`와 **같은 두 걸음**을 탄다 — 그룹을 비우고, 1장만
        // 남은 그룹은 정리한다. 규칙이 두 곳에 갈리면 같은 조작이 화면마다 다른 결과를 낸다.
        // 자동 링크(`char:`)는 따로 다루지 않는다 — 캐릭터 배정을 함께 떼므로 ⑤의 재동기화가
        // 도로 묶지 않는다(인앱 해제가 `autoRelinkable`을 고지해야 했던 것과 갈리는 지점이다).
        //
        // **묶음을 푸는지는 계획이 정한다**([DetachAction.unlinks]). 여기서 "그룹이 있으면
        // 푼다"로 판정하면 `_미배정/<세트명>/`의 해제까지 풀어 버려서, 뒤이은 ④가 기존 그룹을
        // 흡수하지 못한다 — 규약상 **병합**이어야 할 것이 조용히 **이동**이 되고, 사전 확인이
        // 이미 약속한 "폴더에 없던 이미지 M장이 함께 묶입니다"와 어긋난다.
        //
        // **경로마다 묻던 `getByPath`를 이 루프 앞의 일괄 조회 한 번으로 내렸다 (B-241).**
        // 답이 같은 근거: 이 루프의 쓰기는 **제 행만** 건드리고(`setGroup(listOf(imageId), null)`),
        // 뗀 표식·캐릭터 갱신은 `linkGroupId`를 보지 않는다 — 한 항목의 쓰기가 다른 항목의 답을
        // 바꾸지 않는다. 예외는 **같은 경로가 두 번 실리는 경우**뿐이라 겹으로 받아 반영한다
        // (겹은 트랜잭션이 성공했을 때만 갱신한다 — 롤백된 쓰기를 반영하면 겹이 DB보다 앞선다).
        // `adopt`가 새로 만드는 행은 `linkGroupId`가 null이라 "행이 없다"와 답이 같다.
        //
        // **실패를 null로 떨어뜨리지 않는다 — `null`이 여기서 두 뜻이기 때문이다.** 종전에는 이
        // 읽기가 항목 트랜잭션 *안*이라 실패가 곧 그 항목의 실패였고 `failures`에 실렸다. 앞으로
        // 모으면서 삼키면 *묶음을 풀지 않고 성공이라 말하는* 상태가 된다(개발 의도 2번 — 조용히
        // 틀린 고지도 유실이다). 그래서 실패는 `null` 지도로 남기고, **그것을 쓰는 항목만**
        // 트랜잭션 안에서 던져 종전과 같은 자리에서 같은 실패를 낸다(묶음을 안 푸는 항목은
        // 그 읽기를 애초에 하지 않았으므로 그대로 성공한다).
        val detachGroups: MutableMap<String, String?>? =
            if (cancelled || plan.detaches.isEmpty()) HashMap()
            else runCatching {
                val m = HashMap<String, String?>()
                val storedPaths = plan.detaches.map { bundle.stored(it.path) }.distinct()
                SqlInChunks.flat(storedPaths) { db.imageMetaDao().getByPaths(it) }
                    .forEach { m[it.path] = it.linkGroupId }
                m
            }.getOrNull()
        if (!cancelled) for (action in plan.detaches) {
            if (isCancelled()) { cancelled = true; break }
            val file = fileById[action.item.id] ?: continue
            // 트랜잭션이 롤백되면 아무 일도 없었던 것이다 — 계수는 성공을 확인한 뒤에 올린다.
            var didUnlink = false
            val ok = runCatching {
                db.withTransaction {
                    for (id in action.fromCharacterIds) {
                        db.characterDao().getCharacterById(id)?.let { c ->
                            db.characterDao().update(
                                c.withImagePaths(removePath(gson, c.imagePaths, action.path))
                            )
                        }
                    }
                    val now = System.currentTimeMillis()
                    // 저장형으로 입양한다 — canonical로 넣으면 같은 파일의 meta 행이 하나 더 생긴다.
                    val storedPath = bundle.stored(action.path)
                    val imageId = db.imageMetaDao().adopt(storedPath, now)
                    // 뗀 표식의 처분은 **계획이 정한다**(B-107 D5) — 여기서 폴더 이름을 다시
                    // 보고 판정하면 규칙이 둘로 갈라진다. `_분리됨/`은 "아직 판단 안 함"이라
                    // 남기고, `_미배정/`·직속은 "다시 쓸 것으로 되돌림"이라 지운다.
                    if (action.keepsDetachedMark) {
                        DetachedImageMarker.markDetached(
                            db, listOf(storedPath),
                            action.fromCharacterIds.firstOrNull()
                                ?.let { db.characterDao().getCharacterById(it)?.code },
                            now
                        )
                    } else {
                        DetachedImageMarker.clearMark(db, listOf(storedPath))
                    }
                    if (action.unlinks) {
                        val groups = detachGroups
                            ?: throw IllegalStateException("이미지 묶음 조회 실패")
                        val oldGroup = groups[storedPath]
                        if (oldGroup != null) {
                            db.imageMetaDao().setGroup(listOf(imageId), null)
                            // 정리(singleton 해제)는 **모든 해제가 끝난 뒤** 한 번에 한다 —
                            // 여기서 바로 하면 "누가 혼자 남았는가"를 중간 상태로 판정하게 되어,
                            // 2장짜리 묶음을 통째로 푸는 동안 첫 장을 뗀 순간의 나머지가 잘못
                            // 잡힌다(그 나머지도 곧 풀리므로 흩어진 것이 아니다).
                            touchedGroups.add(oldGroup)
                            didUnlink = true
                        }
                    }
                }
            }.isSuccess
            if (ok) {
                // 배정이 없고 묶음만 풀린 항목은 '배정 해제'로 세지 않는다 — 단위가 다른 두 수를
                // 한 칸에 담으면 결과가 사용자에게 거짓말을 한다.
                if (action.fromCharacterIds.isNotEmpty()) detached++
                if (didUnlink) unlinked++
                // 겹 갱신 — 커밋된 쓰기만 반영한다(같은 경로가 두 번 실릴 때의 답을 지킨다).
                if (didUnlink) detachGroups?.put(bundle.stored(action.path), null)
                processedFiles.add(file)
            } else failures.add(file.name)
            step()
        }

        // ③-a `_삭제승인/` — **앱에서 지운다**(B-107 D6). 예약 폴더 처분 중 유일하게 파괴적이라
        // 호출부가 확인창을 이미 거쳤다(R-4). 여기서 다시 묻지 않는다.
        //
        // 처분은 `ImageDeletionService`가 단일 소스다 — 이미지 탭의 명시적 삭제와 **같은 함수**를
        // 부른다. 규칙을 한 벌 더 적으면 링크 정리·대표 포인터·롤백 조건이 갈라진다.
        //
        // 소유 색인을 여기서 한 번 만드는 이유: 계획은 **캐릭터 축만** 안다(`characterIdsByPath`).
        // 작품·세계관이 쓰는 이미지를 그대로 지우면 그쪽 참조가 끊긴 채 남는다.
        if (!cancelled && plan.deletes.isNotEmpty()) {
            val ownerIndex = ImageDeletionService.buildOwnerIndex(db, gson)
            for (action in plan.deletes) {
                if (isCancelled()) { cancelled = true; break }
                val file = fileById[action.item.id] ?: continue
                val storedPath = bundle.stored(action.path)
                val canon = ImagePathMatch.canonical(storedPath)
                val freed = ImageDeletionService.delete(
                    db = db,
                    path = storedPath,
                    owners = ownerIndex[canon] ?: ImageDeletionService.Owners.NONE,
                    // 이 값을 먹는 `delete`가 제 안에서 `clearGroupIfSingleton`으로 **다른 행의**
                    // linkGroupId를 바꾼다 — 앞으로 모으면 낡으므로 겹이 필요하고, 그것은 별도
                    // 설계 판정이라 등재만 했다.
                    // 단발 허용(B-243 — 읽는 값을 소비처가 되쓴다. 겹이 필요하다)
                    linkGroupId = db.imageMetaDao().getByPath(storedPath)?.linkGroupId,
                    gson = gson
                )
                if (freed != null) {
                    deleted++
                    deletedBytes += freed
                    // 원본은 `_처리됨/`으로 옮긴다 — **앱이 사용자의 폴더 파일을 지우지는 않는다.**
                    // 되돌리기가 없는 처분이라 폴더에 남는 원본이 마지막 안전망이다(D6).
                    processedFiles.add(file)
                } else failures.add(file.name)
                step()
            }
        }

        // ③-b 서랍의 묶음만 해제 — 캐릭터 배정은 건드리지 않는다(설계 D-2).
        //
        // **자동 링크(`char:`) 묶음은 풀지 않고 센다.** 배정이 그대로 남는 자리이므로 아래 ⑤의
        // 재동기화가 곧바로 도로 묶는다 — 풀었다고 세면 결과 요약이 "묶음 N개를 풀었습니다"라고
        // 말하는데 화면은 그대로인, 사용자에게 거짓말하는 수가 된다. 대신 개수를 고지하고
        // 인앱 해제와 같은 빠져나갈 길(자동 링크를 끄거나 캐릭터에서 이미지를 빼기)을 안내한다.
        // 설정이 꺼져 있으면 재동기화가 돌지 않으므로 그때는 정상적으로 푼다.
        val autoLinkOn = ImageSettingsStore(context).getAutoLinkByCharacter()
        // 위 ③과 같은 일괄 조회 — 근거도 같다(제 행만 쓴다 · 같은 경로 재등장은 겹으로 받는다).
        // **③의 것을 물려 쓰지 않고 여기서 다시 뜨는 이유:** 사이에 ③-a(삭제)가 돌았고 그쪽은
        // `ImageDeletionService.delete` 안에서 `clearGroupIfSingleton`을 불러 **다른 행의**
        // `linkGroupId`까지 바꾼다. 한 번만 떴다면 그 변화를 못 본 값으로 판정하게 된다.
        // **실패 처분도 ③과 같다** — 여기서는 항목마다 그 읽기를 하므로 실패하면 전부 실패하고,
        // 그것이 종전과 정확히 같은 결과다.
        val unlinkGroups: MutableMap<String, String?>? =
            if (cancelled || plan.unlinkOnly.isEmpty()) HashMap()
            else runCatching {
                val m = HashMap<String, String?>()
                val storedPaths = plan.unlinkOnly.map { bundle.stored(it.path) }.distinct()
                SqlInChunks.flat(storedPaths) { db.imageMetaDao().getByPaths(it) }
                    .forEach { m[it.path] = it.linkGroupId }
                m
            }.getOrNull()
        if (!cancelled) for (action in plan.unlinkOnly) {
            if (isCancelled()) { cancelled = true; break }
            val file = fileById[action.item.id] ?: continue
            var didUnlink = false
            var keptAuto = false
            val ok = runCatching {
                db.withTransaction {
                    val storedPath = bundle.stored(action.path)
                    val oldGroup = (unlinkGroups ?: throw IllegalStateException("이미지 묶음 조회 실패"))[storedPath]
                    if (oldGroup != null) {
                        if (autoLinkOn && AutoLinkPlanner.isAutoToken(oldGroup)) {
                            keptAuto = true
                        } else {
                            val imageId = db.imageMetaDao().adopt(storedPath, System.currentTimeMillis())
                            db.imageMetaDao().setGroup(listOf(imageId), null)
                            touchedGroups.add(oldGroup)   // 정리·판정은 아래에서 한 번에
                            didUnlink = true
                        }
                    }
                }
            }.isSuccess
            if (ok) {
                if (didUnlink) unlinked++
                if (keptAuto) autoLinkedKept++
                // 겹 갱신 — 커밋된 쓰기만 반영한다(③과 같은 이유).
                if (didUnlink) unlinkGroups?.put(bundle.stored(action.path), null)
                processedFiles.add(file)
            } else failures.add(file.name)
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
                    // 경로마다·토큰마다 묻던 조회 둘을 **일괄 조회 둘**로 내린다 (B-241).
                    // **겹이 필요 없는 자리다** — 이 블록의 읽기는 전부 아래 `setGroup` 앞에서
                    // 끝나므로 제가 읽는 것을 쓰지 않는다(가져오기 쪽 루프와 갈리는 지점 — 그쪽
                    // 처방이 `ImageLinkGroupPlanner`다. B-239). 세트마다 트랜잭션이 따로라
                    // 앞 세트의 쓰기는 이 조회가 이미 보고 있다.
                    val metaByPath = SqlInChunks.flat(paths.distinct()) { db.imageMetaDao().getByPaths(it) }
                        .associateBy { it.path }
                    // 순서를 `paths`로 훑는 것이 규약이다 — 아래 `token`이 **첫 경로의 묶음**이라
                    // 조회가 돌려준 순서를 그대로 쓰면 흡수 대상이 달라진다.
                    val existingGroups = paths
                        .mapNotNull { metaByPath[it]?.linkGroupId }
                        .filterNot { AutoLinkPlanner.isAutoToken(it) }
                        .distinct()
                    SqlInChunks.flat(existingGroups) { db.imageMetaDao().getByGroups(it) }
                        .forEach { ids.add(it.id) }
                    val token = existingGroups.firstOrNull() ?: UUID.randomUUID().toString()
                    SqlInChunks.each(ids.toList()) { db.imageMetaDao().setGroup(it, token) }
                }
            }.isSuccess
            if (ok) linkedSets++
        }

        // ④-b 묶음 결산 — 이번 반영이 멤버를 뺀 그룹에서 **누가 혼자 남았는가**를 정한다.
        //
        // ④ 뒤에 두는 이유: 세트가 다시 묶을 수 있다. 앞에서 판정하면 곧 다시 묶일 이미지를
        // '흩어진 것'으로 적는다. 그리고 항목마다가 아니라 여기서 한 번에 하는 이유는 위
        // ③의 주석에 있다 — 중간 상태로 판정하면 통째로 푸는 묶음의 첫 나머지가 잘못 잡힌다.
        //
        // 그룹이 통째로 풀려 0장이 되면 아무도 남지 않으므로 기록되지 않는다. 이것이 곧
        // "의도한 잡동사니는 적지 않는다"의 구현이다 — 별도 분기가 필요 없다.
        //
        // **토큰마다 왕복 둘(조회 + 정리)을 각각 한 번으로 내렸다 (B-241).** 답이 같은 근거는
        // 묶음이 행을 나눠 가진다는 것이다 — 행 하나의 `linkGroupId`는 하나이므로 토큰 X를 푸는
        // 것이 토큰 Y의 인원을 못 바꾸고, 그래서 판정도 정리도 순서에 걸리지 않는다
        // (정본은 `clearSingletonGroups`의 주석 — B-239). `scattered`의 순서는 `touchedGroups`
        // 순서를 그대로 훑어 지킨다.
        //
        // **여기서는 실패를 삼켜도 된다 — 종전에도 그랬고, 위 ③과 달리 사용자에게 할 말이 없다.**
        // 이 블록이 내는 것은 어시스턴트 카드가 읽는 *장부*뿐이고 반영 자체는 이미 끝났다.
        // 다만 삼키는 **범위**가 토큰 하나에서 전체로 넓어진 것은 사실이라 적어 둔다.
        val remainingByGroup = runCatching {
            SqlInChunks.flat(touchedGroups) { db.imageMetaDao().getByGroups(it) }
        }.getOrDefault(emptyList()).groupBy { it.linkGroupId }
        val scattered = ArrayList<String>()
        for (group in touchedGroups) {
            val remaining = remainingByGroup[group].orEmpty()
            if (remaining.size == 1) scattered.add(remaining[0].path)
        }
        runCatching { SqlInChunks.each(touchedGroups) { db.imageMetaDao().clearSingletonGroups(it) } }
        if (scattered.isNotEmpty()) {
            runCatching { FolderRoundtripPrefs.addScatteredPaths(context, scattered) }
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
            unlinked = unlinked,
            autoLinkedKept = autoLinkedKept,
            deleted = deleted,
            deletedBytes = deletedBytes,
            scattered = scattered.size,
            importedPathById = importedPathById,
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

        // 폴백: **복사 → 크기 검증 → 그다음에만 원본 삭제** (B-78).
        //
        // 검증 없이 지우면 사용자 폴더의 파일이 깨진 사본만 남기고 사라진다 — R-4가 금지한
        // 파괴적 조작이다. 그리고 **어느 단계에서 실패하든 사본을 지운다**: 같은 토큰이 두 위치에
        // 남으면 다음 스캔이 "같은 토큰 두 위치 → 반영 보류"를 **앱 스스로 유발**한다.
        // 두 위치에 남는 갈래를 만들지 않는 것이 처방이므로 `_처리됨/` 스캔 제외와의
        // 상호작용을 따로 정의할 필요가 없다(B-78 판정).
        val created = runCatching {
            DocumentsContract.createDocument(resolver, targetParentUri, mimeOf(file.name), file.name)
        }.getOrNull() ?: return false
        fun discardCopy() {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
        }

        val copiedBytes = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(created)?.use { output -> input.copyTo(output) }
            }
        }.getOrNull()
        if (copiedBytes == null) { discardCopy(); return false }

        // 나열이 크기를 못 읽으면 0으로 들어온다(`COLUMN_SIZE`가 null인 provider) — 그때는
        // 이 대조를 건너뛴다. 걸어 버리면 그런 기기에서 이동이 **전부** 실패한다.
        if (file.size > 0L && copiedBytes != file.size) { discardCopy(); return false }
        // 스트림이 센 바이트와 목적지에 실제로 쓰인 길이는 다를 수 있다(provider 버퍼링·중단).
        // 목적지 크기를 못 읽으면(null) 확인할 방법이 없으므로 통과시킨다 — 위 대조가 남는다.
        val destSize = documentSize(resolver, created)
        if (destSize != null && destSize != copiedBytes) { discardCopy(); return false }

        val deleted = runCatching {
            DocumentsContract.deleteDocument(resolver, sourceUri)
        }.getOrDefault(false)
        if (!deleted) { discardCopy(); return false }
        return true
    }

    /** 목적지 문서의 실제 크기. 읽을 수 없으면 null(= 확인 불가이지 0이 아니다). */
    private fun documentSize(resolver: android.content.ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        }.getOrNull()

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic", "heif" -> "image/heif"
        else -> "image/jpeg"
    }

    // ── 공용 유틸 ──

    // 경로 정규화의 단일 소스 (B-106 ⓐ) — 실패 시 원본을 들고 간다.
    private fun canonical(path: String): String = ImagePathMatch.canonical(path)

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
