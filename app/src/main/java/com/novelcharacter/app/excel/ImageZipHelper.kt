package com.novelcharacter.app.excel

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
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
     * @param progress 진행 보고·취소 창구(R-26). 취소가 걸리면 [ExportCancelledException]을
     *        던지며, 반쯤 쓴 ZIP은 호출부가 지운다.
     *        null이면 보고도 취소 확인도 없이 끝까지 돈다 — **자동 백업은 더 이상 그 경로가
     *        아니다**(B-96). 이 루프의 `copyTo`는 블로킹 I/O라 코루틴 취소로는 멈추지 않으므로,
     *        stop을 보는 유일한 길이 이 싱크다.
     * @return 래핑 결과 집계([ImageZipReport]). created=false면 담을 이미지가 없어 래핑하지 않은 것이며,
     *         이때도 참조·제외 건수는 채워져 호출부가 사용자에게 사실대로 알릴 수 있다
     *         (이미지가 빠진 백업을 완전한 백업으로 오인하는 것을 막는다).
     */
    suspend fun wrapWithImages(
        xlsxFile: File,
        outputZipFile: File,
        db: AppDatabase,
        appContext: Context,
        progress: ExportProgressSink? = null
    ): ImageZipReport {
        val gson = Gson()
        val appDir = appContext.filesDir

        // 모든 이미지 경로 수집 — 엔티티 참조 + 라이브러리(meta) 미배정 이미지.
        // meta 합류는 이 함수 내부에서만 한다: collectAllImagePaths 자체의 의미(엔티티 참조 집합)는
        // StorageAnalyzer·고아 정리 소비처가 의존하므로 바꾸지 않는다.
        //
        // **읽기 실패를 세어서 들고 나온다(B-225).** 종전에는 이 자리가 손상된 imagePaths를
        // 조용히 건너뛰고(parseImagePaths → null) 라이브러리 조회 실패를 빈 목록으로 삼켰다.
        // 그러면 referencedCount가 그만큼 작아지고, 작아진 만큼 **제외 계수에도 안 들어**
        // hasLoss가 false가 된다 — 그 상태로 '완전한 백업입니다'가 나갔다.
        // 못 읽은 것은 담기지도 않으므로, 이것을 세지 않으면 유실이 무음이 된다.
        val collected = collectAllImagePathsWithStatus(db, gson)
        val metaPaths = runCatching { db.imageMetaDao().getAllPaths() }
        val imagePathSet = buildSet {
            addAll(collected.paths)
            addAll(metaPaths.getOrDefault(emptyList()))
        }
        // 라이브러리 조회는 통째로 성공하거나 통째로 실패한다 — 실패는 '항목 하나'로 센다.
        val unreadableRefCount = collected.unreadableCount + if (metaPaths.isFailure) 1 else 0

        // 담을 수 있는 것 / 파일 없음 / 앱 저장소 밖 — 판정은 ImagePathClassifier 단일 소스
        val appDirCanonical = com.novelcharacter.app.util.ImagePathMatch.canonical(appDir.absolutePath)
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
                unreadableRefCount = unreadableRefCount,
                sampleNames = ImagePathClassifier.sampleNames(classified.broken)
            )
        }

        // ZIP 생성
        val imageMap = mutableMapOf<String, String>()
        // use 블록 밖에 둬야 반환 시점에 읽을 수 있다
        val failedPaths = mutableListOf<String>()

        ZipOutputStream(FileOutputStream(outputZipFile)).use { zip ->
            // data.xlsx 추가 — XML이라 압축이 실효가 있으므로 기본 수준 그대로 쓴다
            zip.putNextEntry(ZipEntry("data.xlsx"))
            xlsxFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            // 이미지 구간은 무압축(설계 D8) — JPEG·PNG는 이미 압축된 형식이라 deflate가
            // 사실상 0%를 벌면서 전량에 CPU를 지불한다(실측: 744.3MB → 740MB, -0.6%).
            // STORED가 아니라 setLevel인 이유: STORED는 항목마다 size·crc를 미리 채워야 해
            // 파일을 두 번 읽거나 통째로 버퍼링해야 한다. 이 방식은 스트리밍(copyTo)이 그대로다.
            // 압축 수준은 zip 리더에게 투명하므로 왕복 규약은 무변경이다.
            zip.setLevel(Deflater.NO_COMPRESSION)

            // 이미지 추가 (파일명 충돌 방지)
            val usedNames = mutableSetOf<String>()
            val imageTotal = existingImages.size
            var imageDone = 0
            progress?.onImages?.invoke(0, imageTotal)
            for (path in existingImages) {
                if (progress?.isCancelled?.invoke() == true) throw ExportCancelledException()
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
                    // **엔트리를 여는 것은 원본을 연 뒤다.** 열기 실패(파일이 방금 지워졌다,
                    // 권한이 없다)가 이 부류의 대부분인데, 순서가 반대면 그 흔한 실패마다
                    // 0바이트 엔트리가 ZIP에 남는다. 이쪽은 그 자리에서 통째로 건너뛴다.
                    imageFile.inputStream().use { input ->
                        zip.putNextEntry(ZipEntry(zipPath))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                    imageMap[path] = zipPath
                } catch (e: Exception) {
                    failedPaths.add(path)
                    try { zip.closeEntry() } catch (_: Exception) { }
                    Log.w(TAG, "Failed to add image to ZIP: $path", e)
                }
                imageDone++
                progress?.onImages?.invoke(imageDone, imageTotal)
            }
            if (failedPaths.isNotEmpty()) {
                Log.w(TAG, "${failedPaths.size} of ${existingImages.size} images failed to add to ZIP")
            }

            // 이미지 구간 종료 — JSON은 압축이 실효가 있으므로 기본 수준으로 원복한다
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)

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
            unreadableRefCount = unreadableRefCount,
            // 표본은 "끊어진 참조 + 압축 실패"를 함께 — 사용자가 어느 파일인지 짚을 수 있게 한다
            sampleNames = ImagePathClassifier.sampleNames(classified.broken + failedPaths)
        )
    }

    /**
     * 이미지 포함 내보내기가 담을 총 바이트 견적(설계 D6).
     *
     * **모집단은 [wrapWithImages]와 같은 식으로 구한다** — 엔티티 참조 ∪ 라이브러리 메타
     * 경로를 [ImagePathClassifier]로 거른 '담을 수 있는 것'이다. 다른 식으로 세면 견적과
     * 실물이 갈리므로 이 함수와 래핑이 같은 두 단계를 밟는 것이 요점이다.
     * (`ImageMeta`에는 크기 컬럼이 없어 파일 하나씩 재는 것 말고는 방법이 없다 — 검토 F2.)
     *
     * 견적은 **편의이지 게이트가 아니다.** 실패하면 0을 돌려주고 호출부는 그 줄을 생략한다 —
     * 견적 때문에 내보내기를 막지 않는다.
     */
    suspend fun estimateImageBytes(db: AppDatabase, appContext: Context): Long {
        return runCatching {
            val gson = Gson()
            val appDir = appContext.filesDir
            val imagePathSet = buildSet {
                addAll(collectAllImagePaths(db, gson))
                addAll(runCatching { db.imageMetaDao().getAllPaths() }.getOrDefault(emptyList()))
            }
            val appDirCanonical = com.novelcharacter.app.util.ImagePathMatch.canonical(appDir.absolutePath)
            ImagePathClassifier.classify(imagePathSet, appDirCanonical).includable
                .sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
        }.getOrDefault(0L)
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

    /**
     * 이미지 경로 수집 결과 — 읽지 못한 항목이 있으면 참조 집합이 불완전함을 알린다.
     *
     * **건수로 든다(B-225).** 종전에는 `anyParseFailed: Boolean`이었고 소비처가 고아 정리
     * 하나뿐이라 그것으로 족했다("하나라도 실패했으면 중단"). 내보내기·백업은 중단하지 않고
     * **사용자에게 규모를 말해야** 하므로 몇 항목이 그랬는지가 필요하다.
     * 옛 이름은 파생값으로 남겨 둔다 — 뜻이 같고, 그쪽 소비처가 묻는 것은 여전히 유무다.
     */
    data class CollectResult(val paths: Set<String>, val unreadableCount: Int) {
        val anyParseFailed: Boolean get() = unreadableCount > 0
    }

    /**
     * collectAllImagePaths의 fail-safe 변형.
     *
     * imagePaths JSON이 손상되어 읽지 못하면 그 항목의 이미지가 참조 집합에서 누락된다.
     * 이 상태로 고아 정리를 강행하면 손상 1건이 실사용 이미지 전부의 삭제로 번질 수 있으므로,
     * 실패 항목 수를 별도로 보고해 호출부가 안전하게 중단하거나(고아 정리)
     * 사용자에게 알릴 수 있게 한다(내보내기·백업 — B-225).
     * (빈 값 "[]"·blank는 실패가 아니라 "이미지 없음"으로 구분한다.)
     */
    suspend fun collectAllImagePathsWithStatus(db: AppDatabase, gson: Gson = Gson()): CollectResult {
        val paths = mutableSetOf<String>()
        var unreadable = 0

        fun consume(json: String) {
            if (json.isBlank() || json == "[]") return
            val parsed = try {
                gson.fromJson(json, Array<String>::class.java)
            } catch (_: Exception) {
                unreadable++
                return
            }
            if (parsed == null) { unreadable++; return }
            paths.addAll(parsed)
        }

        for (c in db.characterDao().getAllCharactersList()) consume(c.imagePaths)
        for (u in db.universeDao().getAllUniversesList()) consume(u.imagePaths)
        for (n in db.novelDao().getAllNovelsList()) consume(n.imagePaths)

        return CollectResult(paths, unreadable)
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
