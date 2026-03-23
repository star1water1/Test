package com.novelcharacter.app.excel

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 이미지 파일 수집 및 ZIP 래핑 유틸리티.
 * AutoBackupWorker와 ExcelExporter 양쪽에서 공유.
 */
object ImageZipHelper {

    private const val TAG = "ImageZipHelper"

    /**
     * XLSX 파일에 이미지를 포함하여 ZIP으로 래핑한다.
     *
     * ZIP 구조:
     * - data.xlsx (원본 XLSX)
     * - images/ (이미지 파일들)
     * - image_map.json (원본경로 → ZIP 상대경로 매핑)
     *
     * @return true면 ZIP이 생성됨. false면 이미지가 없어 래핑하지 않음.
     */
    suspend fun wrapWithImages(
        xlsxFile: File,
        outputZipFile: File,
        db: AppDatabase,
        appContext: Context
    ): Boolean {
        val gson = Gson()
        val appDir = appContext.filesDir

        // 모든 이미지 경로 수집
        val imagePathSet = collectAllImagePaths(db, gson)

        // 실제 존재하는 이미지가 있는지 확인
        val existingImages = imagePathSet.filter { path ->
            val file = File(path)
            file.exists() && try {
                file.canonicalPath.startsWith(appDir.canonicalPath)
            } catch (_: Exception) { false }
        }

        if (existingImages.isEmpty()) return false

        // ZIP 생성
        val imageMap = mutableMapOf<String, String>()

        ZipOutputStream(FileOutputStream(outputZipFile)).use { zip ->
            // data.xlsx 추가
            zip.putNextEntry(ZipEntry("data.xlsx"))
            xlsxFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            // 이미지 추가 (파일명 충돌 방지)
            val usedNames = mutableSetOf<String>()
            for (path in existingImages) {
                val imageFile = File(path)
                var name = imageFile.name
                if (name in usedNames) {
                    val ext = name.substringAfterLast('.', "")
                    val base = name.substringBeforeLast('.')
                    name = "${base}_${java.util.UUID.randomUUID().toString().take(8)}.$ext"
                }
                usedNames.add(name)
                val zipPath = "images/$name"
                try {
                    zip.putNextEntry(ZipEntry(zipPath))
                    imageFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    imageMap[path] = zipPath
                } catch (e: Exception) {
                    try { zip.closeEntry() } catch (_: Exception) { }
                    Log.w(TAG, "Failed to add image to ZIP: $path", e)
                }
            }

            // image_map.json 추가
            zip.putNextEntry(ZipEntry("image_map.json"))
            zip.write(gson.toJson(imageMap).toByteArray())
            zip.closeEntry()
        }

        return true
    }

    /**
     * DB의 Character/Universe/Novel에서 모든 이미지 절대 경로를 수집한다.
     */
    suspend fun collectAllImagePaths(db: AppDatabase, gson: Gson = Gson()): Set<String> {
        val paths = mutableSetOf<String>()

        for (c in db.characterDao().getAllCharactersList()) {
            parseImagePaths(c.imagePaths, gson)?.let { paths.addAll(it) }
        }
        for (u in db.universeDao().getAllUniversesList()) {
            parseImagePaths(u.imagePaths, gson)?.let { paths.addAll(it) }
        }
        for (n in db.novelDao().getAllNovelsList()) {
            parseImagePaths(n.imagePaths, gson)?.let { paths.addAll(it) }
        }

        return paths
    }

    private fun parseImagePaths(json: String, gson: Gson): Array<String>? {
        if (json.isBlank() || json == "[]") return null
        return try {
            gson.fromJson(json, Array<String>::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
