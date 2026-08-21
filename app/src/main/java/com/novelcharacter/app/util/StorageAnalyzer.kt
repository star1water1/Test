package com.novelcharacter.app.util

import android.content.Context
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.excel.ImageZipHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 앱 저장 공간을 분류별로 실측하는 분석기.
 *
 * 사용자가 "앱 용량이 왜 이렇게 큰가"를 인앱에서 파악하고(변수 제어),
 * 안전한 정리 액션을 자율적으로 실행할 수 있게 하는 데이터 소스.
 * 모든 계산은 실제 파일 크기 + DB 참조 대조 기반이며 IO 스레드에서 수행한다.
 */
object StorageAnalyzer {

    /** filesDir 루트에 저장되는 이미지 파일 접두 규칙 (char_/universe_/novel_/img_ + UUID). img_는 이미지 탭 직접 임포트. */
    // internal: 엑셀 복원(buildImageRemap)이 파일명 프리픽스 보존에 같은 목록을 참조한다 (단일 소스)
    internal val IMAGE_PREFIXES = listOf("char_", "universe_", "novel_", "img_")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")

    private const val BACKUP_DIR = "backups"
    private const val DB_NAME = "novel_character_database"

    data class Category(
        val key: String,
        val bytes: Long,
        val fileCount: Int,
    )

    data class StorageReport(
        val referencedImages: Category,   // DB가 참조하는 이미지
        val libraryImages: Category,      // 라이브러리(image_meta) 관리 중인 미배정 이미지
        val orphanImages: Category,       // 디스크에 있으나 DB·휴지통·라이브러리 어디서도 참조 안 함
        val trashHeldImages: Category,    // 휴지통 스냅샷이 복원용으로 보류 중인 이미지
        val autoBackups: Category,        // filesDir/backups/*.enc
        /**
         * `cacheDir` **전체** (재생성 가능한 전송 임시 파일).
         *
         * 종전에는 `cacheDir/exports`의 **최상위 파일만** 셌다. 같은 함수가 `filesDir`에
         * 대해서는 하위 디렉터리까지 '기타'로 합산하는데 `cacheDir`에는 그 대칭이 없어서,
         * `ExportWorkbook.useTempDirectory`가 *"앱이 지울 수도, 용량을 셀 수도 없는 자리에
         * 백업 크기의 임시 파일이 생기지 않도록"* 일부러 cacheDir 밑으로 못박아 둔
         * `poi-temp`조차 **한 바이트도 세지 않았다** — '전체 사용량'이 사실보다 작았다.
         */
        val exportCache: Category,
        val database: Category,           // DB 파일 + WAL/SHM
        val logs: Category,               // error_log.txt + crash_log.txt (상한 있음)
        val other: Category,              // 위에 안 잡힌 filesDir 기타
    ) {
        val totalBytes: Long
            get() = referencedImages.bytes + libraryImages.bytes + orphanImages.bytes + trashHeldImages.bytes +
                autoBackups.bytes + exportCache.bytes + database.bytes + logs.bytes + other.bytes
    }

    suspend fun analyze(context: Context, db: AppDatabase): StorageReport = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val gson = Gson()

        // 참조 집합: DB(캐릭터/세계관/작품) + 휴지통 보류 + 미저장 편집 드래프트 이미지.
        // 드래프트(B-6)는 DB 미커밋이지만 사용자 작업물이므로 '고아'로 오분류하지 않는다.
        // 정규화 실패 경로를 버리지 않는다 (B-106 ⓐ) — 종전 `mapNotNull { getOrNull() }`은
        // **참조된 이미지를 참조 집합에서 떨어뜨려 곧바로 '고아'로 분류**했다. 바로 위 주석이
        // 막겠다고 적어 둔 그 오분류이며, 화면에는 그저 정리 대상 숫자로만 보인다.
        val referencedPaths = (ImageZipHelper.collectAllImagePaths(db, gson) +
            com.novelcharacter.app.util.CharacterDraftPrefs.collectAllDraftImagePaths(context))
            .map { ImagePathMatch.canonical(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        val trashHeldPaths = collectTrashHeldPaths(db, gson)  // suspend — DB 접근
        // 라이브러리(image_meta) 경로 — 미배정 이미지를 고아로 오분류하지 않기 위한 분류 집합
        val libraryPaths = runCatching { db.imageMetaDao().getAllPaths() }.getOrDefault(emptyList())
            .map { ImagePathMatch.canonical(it) }
            .filter { it.isNotEmpty() }
            .toSet()

        // filesDir 루트 파일 순회 (하위 디렉토리는 별도 계산)
        val rootFiles = filesDir.listFiles()?.filter { it.isFile } ?: emptyList()

        var refBytes = 0L; var refCount = 0
        var libBytes = 0L; var libCount = 0
        var orphanBytes = 0L; var orphanCount = 0
        var trashBytes = 0L; var trashCount = 0
        var logBytes = 0L; var logCount = 0
        var otherBytes = 0L; var otherCount = 0

        for (f in rootFiles) {
            // 훑는 쪽은 실제 파일이라 실패가 드물지만, 실패 처분은 위 참조 집합과 같아야 한다
            // (한쪽만 정규화되면 같은 파일이 서로 다른 키가 되어 그대로 고아로 떨어진다).
            val canonical = ImagePathMatch.canonical(f.absolutePath)
            when {
                // 재압축 임시 산출물은 커밋 전 과도기 파일 — 고아(orphan)로 오분류하지 않는다(관리 탭 통계와 일치).
                f.name.contains(ImageImportHelper.RECOMPRESS_TEMP_MARKER) -> { otherBytes += f.length(); otherCount++ }
                isImageFile(f.name) -> when {
                    canonical in referencedPaths -> { refBytes += f.length(); refCount++ }
                    canonical in trashHeldPaths -> { trashBytes += f.length(); trashCount++ }
                    canonical in libraryPaths -> { libBytes += f.length(); libCount++ }
                    else -> { orphanBytes += f.length(); orphanCount++ }
                }
                f.name == "error_log.txt" || f.name == "crash_log.txt" -> { logBytes += f.length(); logCount++ }
                else -> { otherBytes += f.length(); otherCount++ }
            }
        }

        // 자동 백업
        val backupDir = File(filesDir, BACKUP_DIR)
        val backupFiles = backupDir.listFiles { f -> f.isFile && f.name.endsWith(".enc") } ?: emptyArray()
        val backupBytes = backupFiles.sumOf { it.length() }

        // 총량 정확도: 루트의 하위 디렉토리(datastore 등)도 '기타'에 합산 — backups는 별도 계산이므로 제외
        val subDirs = filesDir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in subDirs) {
            if (dir.name == BACKUP_DIR) continue
            val dirBytes = dirSize(dir)
            if (dirBytes > 0) { otherBytes += dirBytes; otherCount++ }
        }

        // 앱 캐시 — **cacheDir 전체**를 재귀로 센다(`filesDir` 쪽과 같은 대칭).
        // 전송 임시 파일은 `exports/` 말고도 여러 자리에 난다(`poi-temp`,
        // `world_import_*`, 복호화 임시 파일…). 특정 이름을 나열하면 임시 파일이
        // 늘 때마다 같은 자리가 또 낡으므로 **자리가 아니라 뿌리**를 센다.
        val cacheRoot = context.cacheDir
        val exportBytes = dirSize(cacheRoot)
        val exportCount = fileCount(cacheRoot)

        // DB 파일 (WAL/SHM 포함)
        val dbFile = context.getDatabasePath(DB_NAME)
        val dbDir = dbFile.parentFile
        val dbFiles = dbDir?.listFiles { f -> f.isFile && f.name.startsWith(DB_NAME) } ?: emptyArray()
        val dbBytes = dbFiles.sumOf { it.length() }

        StorageReport(
            referencedImages = Category("referenced", refBytes, refCount),
            libraryImages = Category("library", libBytes, libCount),
            orphanImages = Category("orphan", orphanBytes, orphanCount),
            trashHeldImages = Category("trash", trashBytes, trashCount),
            autoBackups = Category("backup", backupBytes, backupFiles.size),
            exportCache = Category("export_cache", exportBytes, exportCount),
            database = Category("database", dbBytes, dbFiles.size),
            logs = Category("logs", logBytes, logCount),
            other = Category("other", otherBytes, otherCount),
        )
    }

    /**
     * 휴지통 스냅샷이 복원용으로 보류 중인 이미지의 canonical 경로 집합.
     * 고아 판정에서 반드시 제외해야 복원이 깨지지 않는다.
     */
    suspend fun collectTrashHeldPaths(db: AppDatabase, gson: Gson = Gson()): Set<String> =
        collectTrashHeldPathsWithStatus(db, gson).paths

    /** 위와 동일하되 읽지 못한 항목 수를 함께 보고한다(고아 정리 fail-safe용). */
    suspend fun collectTrashHeldPathsWithStatus(
        db: AppDatabase,
        gson: Gson = Gson()
    ): ImageZipHelper.CollectResult {
        val result = mutableSetOf<String>()
        var unreadable = 0
        // payload는 읽지 않는다 — 휴지통 한도가 '작업 30건'이라 행 수는 수만이 될 수 있다.
        //
        // **조회 실패도 '못 읽음'이다(B-225).** 종전에는 빈 목록으로 갈음하고 실패 표시를
        // 세우지 않아, DB가 답하지 못한 것이 *"보류 중인 이미지가 없다"*와 구분되지 않았다 —
        // 그 상태로 고아 정리를 통과시키면 **복원이 기다리는 이미지를 지운다.** 이 함수가
        // fail-safe라 불리는 이유가 바로 그 갈래이므로, 그 갈래만 침묵인 채로 둘 수 없다.
        val snapshots = runCatching { db.trashSnapshotDao().getAllImages() }
        if (snapshots.isFailure) unreadable++
        for (snap in snapshots.getOrDefault(emptyList())) {
            val json = snap.imagePaths
            if (json.isBlank() || json == "[]") continue
            val paths: List<String?>? = runCatching {
                gson.fromJson<List<String?>>(json, GsonTypes.STRING_LIST)
            }.getOrNull()
            if (paths == null) { unreadable++; continue }
            paths.filterNotNull().forEach { p ->
                ImagePathMatch.canonical(p).takeIf { it.isNotEmpty() }?.let { result.add(it) }
            }
        }
        return ImageZipHelper.CollectResult(result, unreadable)
    }

    /** 디렉토리 재귀 파일 수 — [dirSize]의 짝이다(둘이 같은 범위를 봐야 행이 어긋나지 않는다). */
    private fun fileCount(dir: File): Int {
        var total = 0
        val children = dir.listFiles() ?: return 0
        for (c in children) {
            total += if (c.isDirectory) fileCount(c) else 1
        }
        return total
    }

    /** 디렉토리 재귀 크기 (심링크 순환 방지 위해 실제 파일만 합산) */
    private fun dirSize(dir: File): Long {
        var total = 0L
        val children = dir.listFiles() ?: return 0L
        for (c in children) {
            total += if (c.isDirectory) dirSize(c) else c.length()
        }
        return total
    }

    /**
     * 앱 캐시 비우기 — **세는 범위와 같은 범위**(`cacheDir` 전체)를 비운다.
     *
     * ## 왜 넓혔나 (2026.08.21 사용자 판정)
     *
     * 종전에는 `exports/`의 최상위 파일만 지웠다. 그 좁힘의 근거는 *"돌고 있는 전송의 임시
     * 파일을 앗아갈 수 있다"*였고 그것은 옳았지만, 결과로 **화면이 1.2GB라 말하고 버튼은
     * 120MB를 지우는** 자리가 났다 — 전송 임시 파일은 `exports/` 말고도 `poi-temp`·
     * `world_import_*`·복호화 산출물 등 여러 자리에 나고, 프로세스가 죽어 주인을 잃은
     * 것들은 **인앱에서 회수할 길이 아예 없었다**(원칙 02 — 기능의 '존재'가 아니라 '쓰임').
     *
     * 걸려 있던 것은 [ActiveTransfers]가 풀었다: 도는 전송이 하나라도 있으면 부르는 쪽이
     * **아예 시작하지 않고**(StorageFragment), 도는 것이 없어도 약속된 파일은
     * `protectedPaths`가 지킨다. **비우기는 미룰 수 있지만 깨진 전송은 못 되돌린다** —
     * 그래서 판정은 보수적인 쪽으로 잡았다.
     */
    suspend fun clearTransferCache(context: Context): CacheSweep.Result = withContext(Dispatchers.IO) {
        CacheSweep.sweep(
            context.cacheDir,
            com.novelcharacter.app.excel.ActiveTransfers.protectedPaths(
                com.novelcharacter.app.excel.ExportRetryStore.rawPath(context)
            )
        )
    }

    fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_PREFIXES.any { lower.startsWith(it) } && IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val mb = bytes / 1024.0 / 1024.0
        return when {
            mb >= 1024 -> String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
            mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }
}
