package com.novelcharacter.app.backup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.novelcharacter.app.data.database.AppDatabase
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

class AutoBackupWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        const val BACKUP_FILE_NAME = "NovelCharacter_AutoBackup.xlsx"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "Starting auto backup...")
            val db = AppDatabase.getDatabase(appContext)
            val workbook = XSSFWorkbook()
            val headerStyle = createHeaderStyle(workbook)
            val usedSheetNames = mutableSetOf<String>()

            // Export all data
            exportUniverses(db, workbook, headerStyle, usedSheetNames)
            exportNovels(db, workbook, headerStyle, usedSheetNames)
            exportCharacters(db, workbook, headerStyle, usedSheetNames)
            exportTimeline(db, workbook, headerStyle, usedSheetNames)

            // Save to Downloads
            saveWorkbook(workbook, BACKUP_FILE_NAME)
            workbook.close()

            Log.i(TAG, "Auto backup completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun saveWorkbook(workbook: XSSFWorkbook, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = appContext.contentResolver
            // Try to find existing file first
            val existingUri = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(fileName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                } else null
            }

            if (existingUri != null) {
                resolver.openOutputStream(existingUri, "wt")?.use { outputStream ->
                    workbook.write(outputStream)
                }
            } else {
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw Exception("Cannot create backup file")
                resolver.openOutputStream(uri)?.use { outputStream ->
                    workbook.write(outputStream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { outputStream ->
                workbook.write(outputStream)
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
