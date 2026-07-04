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

    /** filesDir 루트에 저장되는 이미지 파일 접두 규칙 (char_/universe_/novel_ + UUID) */
    private val IMAGE_PREFIXES = listOf("char_", "universe_", "novel_")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")

    private const val BACKUP_DIR = "backups"
    private const val EXPORTS_DIR = "exports"
    private const val DB_NAME = "novel_character_database"

    data class Category(
        val key: String,
        val bytes: Long,
        val fileCount: Int,
    )

    data class StorageReport(
        val referencedImages: Category,   // DB가 참조하는 이미지
        val orphanImages: Category,       // 디스크에 있으나 DB·휴지통 어디서도 참조 안 함
        val trashHeldImages: Category,    // 휴지통 스냅샷이 복원용으로 보류 중인 이미지
        val autoBackups: Category,        // filesDir/backups/*.enc
        val exportCache: Category,        // cacheDir/exports (재생성 가능)
        val database: Category,           // DB 파일 + WAL/SHM
        val logs: Category,               // error_log.txt + crash_log.txt (상한 있음)
        val other: Category,              // 위에 안 잡힌 filesDir 기타
    ) {
        val totalBytes: Long
            get() = referencedImages.bytes + orphanImages.bytes + trashHeldImages.bytes +
                autoBackups.bytes + exportCache.bytes + database.bytes + logs.bytes + other.bytes
    }

    suspend fun analyze(context: Context, db: AppDatabase): StorageReport = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val gson = Gson()

        // 참조 집합: DB(캐릭터/세계관/작품) + 휴지통 보류 이미지
        val referencedPaths = ImageZipHelper.collectAllImagePaths(db, gson)
            .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
            .toSet()
        val trashHeldPaths = collectTrashHeldPaths(db, gson)  // suspend — DB 접근

        // filesDir 루트 파일 순회 (하위 디렉토리는 별도 계산)
        val rootFiles = filesDir.listFiles()?.filter { it.isFile } ?: emptyList()

        var refBytes = 0L; var refCount = 0
        var orphanBytes = 0L; var orphanCount = 0
        var trashBytes = 0L; var trashCount = 0
        var logBytes = 0L; var logCount = 0
        var otherBytes = 0L; var otherCount = 0

        for (f in rootFiles) {
            val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            when {
                isImageFile(f.name) -> when {
                    canonical in referencedPaths -> { refBytes += f.length(); refCount++ }
                    canonical in trashHeldPaths -> { trashBytes += f.length(); trashCount++ }
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

        // 내보내기 캐시
        val exportsDir = File(context.cacheDir, EXPORTS_DIR)
        val exportFiles = exportsDir.listFiles { f -> f.isFile } ?: emptyArray()
        val exportBytes = exportFiles.sumOf { it.length() }

        // DB 파일 (WAL/SHM 포함)
        val dbFile = context.getDatabasePath(DB_NAME)
        val dbDir = dbFile.parentFile
        val dbFiles = dbDir?.listFiles { f -> f.isFile && f.name.startsWith(DB_NAME) } ?: emptyArray()
        val dbBytes = dbFiles.sumOf { it.length() }

        StorageReport(
            referencedImages = Category("referenced", refBytes, refCount),
            orphanImages = Category("orphan", orphanBytes, orphanCount),
            trashHeldImages = Category("trash", trashBytes, trashCount),
            autoBackups = Category("backup", backupBytes, backupFiles.size),
            exportCache = Category("export_cache", exportBytes, exportFiles.size),
            database = Category("database", dbBytes, dbFiles.size),
            logs = Category("logs", logBytes, logCount),
            other = Category("other", otherBytes, otherCount),
        )
    }

    /**
     * 휴지통 스냅샷이 복원용으로 보류 중인 이미지의 canonical 경로 집합.
     * 고아 판정에서 반드시 제외해야 복원이 깨지지 않는다.
     */
    suspend fun collectTrashHeldPaths(db: AppDatabase, gson: Gson = Gson()): Set<String> {
        val result = mutableSetOf<String>()
        val snapshots = runCatching { db.trashSnapshotDao().getAllList() }.getOrDefault(emptyList())
        for (snap in snapshots) {
            val paths: List<String?>? = runCatching {
                gson.fromJson<List<String?>>(snap.imagePaths, GsonTypes.STRING_LIST)
            }.getOrNull()
            paths?.filterNotNull()?.forEach { p ->
                runCatching { File(p).canonicalPath }.getOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    /** 내보내기 캐시 비우기 — 재생성 가능한 산출물만 삭제. @return 삭제 바이트 수 */
    suspend fun clearExportCache(context: Context): Long = withContext(Dispatchers.IO) {
        val exportsDir = File(context.cacheDir, EXPORTS_DIR)
        val files = exportsDir.listFiles { f -> f.isFile } ?: return@withContext 0L
        var freed = 0L
        for (f in files) {
            val len = f.length()
            if (f.delete()) freed += len
        }
        freed
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
