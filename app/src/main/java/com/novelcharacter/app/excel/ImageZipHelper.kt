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
     * @return 래핑 결과 집계([ImageZipReport]). created=false면 담을 이미지가 없어 래핑하지 않은 것이며,
     *         이때도 참조·제외 건수는 채워져 호출부가 사용자에게 사실대로 알릴 수 있다
     *         (이미지가 빠진 백업을 완전한 백업으로 오인하는 것을 막는다).
     */
    suspend fun wrapWithImages(
        xlsxFile: File,
        outputZipFile: File,
        db: AppDatabase,
        appContext: Context
    ): ImageZipReport {
        val gson = Gson()
        val appDir = appContext.filesDir

        // 모든 이미지 경로 수집 — 엔티티 참조 + 라이브러리(meta) 미배정 이미지.
        // meta 합류는 이 함수 내부에서만 한다: collectAllImagePaths 자체의 의미(엔티티 참조 집합)는
        // StorageAnalyzer·고아 정리 소비처가 의존하므로 바꾸지 않는다.
        val imagePathSet = buildSet {
            addAll(collectAllImagePaths(db, gson))
            addAll(runCatching { db.imageMetaDao().getAllPaths() }.getOrDefault(emptyList()))
        }

        // 담을 수 있는 것 / 파일 없음 / 앱 저장소 밖 — 판정은 ImagePathClassifier 단일 소스
        val appDirCanonical = runCatching { appDir.canonicalPath }.getOrNull() ?: appDir.absolutePath
        val classified = ImagePathClassifier.classify(imagePathSet, appDirCanonical)
        val existingImages = classified.includable

        if (existingImages.isEmpty()) {
            // ZIP을 만들지 않아도 "무엇이 왜 빠졌는지"는 반드시 보고한다 — 무음 유실 금지
            return ImageZipReport(
                requested = true,
                created = false,
                referencedCount = imagePathSet.size,
                includedCount = 0,
                missingCount = classified.missing.size,
                outsideAppDirCount = classified.outsideAppDir.size,
                failedCount = 0,
                sampleNames = ImagePathClassifier.sampleNames(classified.broken)
            )
        }

        // ZIP 생성
        val imageMap = mutableMapOf<String, String>()
        // use 블록 밖에 둬야 반환 시점에 읽을 수 있다
        val failedPaths = mutableListOf<String>()

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
                    val base = if ('.' in name) name.substringBeforeLast('.') else name
                    val suffix = java.util.UUID.randomUUID().toString().take(8)
                    name = if (ext.isNotEmpty()) "${base}_$suffix.$ext" else "${base}_$suffix"
                }
                usedNames.add(name)
                val zipPath = "images/$name"
                try {
                    zip.putNextEntry(ZipEntry(zipPath))
                    imageFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    imageMap[path] = zipPath
                } catch (e: Exception) {
                    failedPaths.add(path)
                    try { zip.closeEntry() } catch (_: Exception) { }
                    Log.w(TAG, "Failed to add image to ZIP: $path", e)
                }
            }
            if (failedPaths.isNotEmpty()) {
                Log.w(TAG, "${failedPaths.size} of ${existingImages.size} images failed to add to ZIP")
            }

            // image_map.json 추가
            zip.putNextEntry(ZipEntry("image_map.json"))
            zip.write(gson.toJson(imageMap).toByteArray())
            zip.closeEntry()
        }

        return ImageZipReport(
            requested = true,
            created = true,
            referencedCount = imagePathSet.size,
            includedCount = imageMap.size,
            missingCount = classified.missing.size,
            outsideAppDirCount = classified.outsideAppDir.size,
            failedCount = failedPaths.size,
            // 표본은 "끊어진 참조 + 압축 실패"를 함께 — 사용자가 어느 파일인지 짚을 수 있게 한다
            sampleNames = ImagePathClassifier.sampleNames(classified.broken + failedPaths)
        )
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

    /** 이미지 경로 수집 결과 — 파싱 실패가 하나라도 있으면 참조 집합이 불완전함을 알린다. */
    data class CollectResult(val paths: Set<String>, val anyParseFailed: Boolean)

    /**
     * collectAllImagePaths의 fail-safe 변형.
     *
     * imagePaths JSON이 손상되어 파싱에 실패하면 그 항목의 이미지가 참조 집합에서 누락된다.
     * 이 상태로 고아 정리를 강행하면 손상 1건이 실사용 이미지 전부의 삭제로 번질 수 있으므로,
     * 파싱 실패 여부를 별도로 보고해 호출부(고아 정리)가 안전하게 중단할 수 있게 한다.
     * (빈 값 "[]"·blank는 실패가 아니라 "이미지 없음"으로 구분한다.)
     */
    suspend fun collectAllImagePathsWithStatus(db: AppDatabase, gson: Gson = Gson()): CollectResult {
        val paths = mutableSetOf<String>()
        var anyFailed = false

        fun consume(json: String) {
            if (json.isBlank() || json == "[]") return
            val parsed = try {
                gson.fromJson(json, Array<String>::class.java)
            } catch (_: Exception) {
                anyFailed = true
                return
            }
            if (parsed == null) { anyFailed = true; return }
            paths.addAll(parsed)
        }

        for (c in db.characterDao().getAllCharactersList()) consume(c.imagePaths)
        for (u in db.universeDao().getAllUniversesList()) consume(u.imagePaths)
        for (n in db.novelDao().getAllNovelsList()) consume(n.imagePaths)

        return CollectResult(paths, anyFailed)
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
