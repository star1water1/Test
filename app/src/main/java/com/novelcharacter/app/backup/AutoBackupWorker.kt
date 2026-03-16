package com.novelcharacter.app.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val BACKUP_DIR_NAME = "backups"
        private const val BACKUP_PREFIX = "NovelCharacter_AutoBackup_"
        private const val BACKUP_EXTENSION = ".enc"
        private const val MAX_BACKUPS = 3
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting auto backup...")
            val db = AppDatabase.getDatabase(appContext)
            val workbook = XSSFWorkbook()
            try {
                val headerStyle = createHeaderStyle(workbook)
                val usedSheetNames = mutableSetOf<String>()

                // Export all data
                exportUniverses(db, workbook, headerStyle, usedSheetNames)
                exportNovels(db, workbook, headerStyle, usedSheetNames)
                exportCharacters(db, workbook, headerStyle, usedSheetNames)
                exportTimeline(db, workbook, headerStyle, usedSheetNames)

                // Write workbook to bytes, encrypt, and save to internal storage
                saveEncryptedBackup(workbook)
            } finally {
                try { workbook.close() } catch (_: Exception) {}
            }

            // Rotate old backups
            rotateBackups()

            Log.i(TAG, "Auto backup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveEncryptedBackup(workbook: XSSFWorkbook) {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "$BACKUP_PREFIX$timestamp$BACKUP_EXTENSION"
        val backupFile = File(backupDir, fileName)

        // Write to temp file first to avoid holding entire workbook in memory
        val tempFile = File(backupDir, "temp_backup.xlsx")
        try {
            tempFile.outputStream().use { fos ->
                workbook.write(fos)
            }
            val rawBytes = tempFile.readBytes()
            val encryptedBytes = BackupEncryptor.encrypt(rawBytes)
            backupFile.writeBytes(encryptedBytes)
        } finally {
            tempFile.delete()
        }

        Log.i(TAG, "Encrypted backup saved: ${backupFile.absolutePath}")
    }

    private fun rotateBackups() {
        val backupDir = File(appContext.filesDir, BACKUP_DIR_NAME)
        if (!backupDir.exists()) return

        val backupFiles = backupDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(BACKUP_EXTENSION)
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backupFiles.size > MAX_BACKUPS) {
            backupFiles.drop(MAX_BACKUPS).forEach { file ->
                if (file.delete()) {
                    Log.i(TAG, "Deleted old backup: ${file.name}")
                }
            }
        }
    }

    private suspend fun exportUniverses(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val universes = db.universeDao().getAllUniversesList()
        if (universes.isEmpty()) return

        val sheetName = sanitizeSheetName("세계관", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "설명")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        universes.forEachIndexed { i, u ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(u.name)
            row.createCell(1).setCellValue(u.description)
        }
    }

    private suspend fun exportNovels(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val novels = db.novelDao().getAllNovelsList()
        val universes = db.universeDao().getAllUniversesList()
        if (novels.isEmpty()) return

        val sheetName = sanitizeSheetName("작품", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("제목", "설명", "세계관")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        novels.forEachIndexed { i, n ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(n.title)
            row.createCell(1).setCellValue(n.description)
            row.createCell(2).setCellValue(universes.find { it.id == n.universeId }?.name ?: "")
        }
    }

    private suspend fun exportCharacters(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val allCharacters = db.characterDao().getAllCharactersList()
        val novels = db.novelDao().getAllNovelsList()
        if (allCharacters.isEmpty()) return

        val sheetName = sanitizeSheetName("캐릭터", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("이름", "작품", "메모")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        allCharacters.forEachIndexed { i, c ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(c.name)
            row.createCell(1).setCellValue(novels.find { it.id == c.novelId }?.title ?: "")
            row.createCell(2).setCellValue(c.memo)
        }
    }

    private suspend fun exportTimeline(
        db: AppDatabase, workbook: XSSFWorkbook,
        headerStyle: XSSFCellStyle, usedSheetNames: MutableSet<String>
    ) {
        val events = db.timelineDao().getAllEventsList()
        if (events.isEmpty()) return

        val sheetName = sanitizeSheetName("사건 연표", usedSheetNames)
        val sheet = workbook.createSheet(sheetName)
        val headers = listOf("연도", "월", "일", "사건 설명")
        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { i, h ->
            headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle }
        }
        events.forEachIndexed { i, e ->
            val row = sheet.createRow(i + 1)
            row.createCell(0).setCellValue(e.year.toDouble())
            e.month?.let { row.createCell(1).setCellValue(it.toDouble()) }
            e.day?.let { row.createCell(2).setCellValue(it.toDouble()) }
            row.createCell(3).setCellValue(e.description)
        }
    }

    private fun sanitizeSheetName(name: String, usedNames: MutableSet<String>): String {
        var sanitized = name.replace(Regex("[\\[\\]*/\\\\?:]"), "").take(31)
        if (sanitized.isBlank()) sanitized = "Sheet"
        var result = sanitized
        var counter = 2
        while (result in usedNames) {
            val suffix = "($counter)"
            result = sanitized.take(31 - suffix.length) + suffix
            counter++
        }
        usedNames.add(result)
        return result
    }

    private fun createHeaderStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        val font = workbook.createFont()
        font.bold = true
        font.color = IndexedColors.WHITE.index
        style.setFont(font)
        style.fillForegroundColor = IndexedColors.DARK_BLUE.index
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        return style
    }
}
