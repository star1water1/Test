package com.novelcharacter.app.util

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.google.gson.Gson
import com.novelcharacter.app.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 저장된 이미지를 **사용자 지시로** 돌린다(인앱 회전).
 *
 * ## 왜 필요한가
 *
 * `BitmapFactory`가 EXIF 방향을 무시하던 동안 압축을 켜고 들여온 사진은 **방향 정보가 사라진 채
 * 픽셀만 누워** 저장됐다. 그 부류는 무엇이 원래 방향인지 알 방법이 없어 **자동으로는 못 고친다** —
 * 사람만 보고 정할 수 있다. 그런데 앱에는 회전 수단이 하나도 없었다.
 *
 * ## 두 갈래 — 무손실을 먼저 시도한다
 *
 * 1. **EXIF 태그만 고친다.** JPEG면 픽셀을 다시 쓰지 않으므로 화질이 한 톨도 줄지 않고,
 *    네 번 돌려 제자리로 와도 원본 그대로다. 그리는 자리가 EXIF를 존중하므로 결과는 즉시 보인다.
 * 2. **못 쓰면 픽셀을 돌려 다시 쓴다.** 투명도가 있어 PNG로 인코딩된 파일이 그것이다
 *    (옛 파일은 내용과 무관하게 `.jpg` 이름이라 확장자를 믿을 수 없다 — 그래서
 *    판정을 확장자가 아니라 *1번의 결과*로 한다).
 *
 * ## 경로는 대개 바뀌지 않는다
 *
 * 같은 파일에 그대로 쓴다. 그래서 **정리 폴더 왕복의 토큰이 그대로 살고** 캐릭터·작품·세계관의
 * 경로 참조도 손댈 필요가 없다. PNG·WebP는 같은 형식으로 다시 쓰므로 이름과 내용이 갈리지 않는다.
 *
 * **예외 — 같은 형식으로 다시 쓸 수 없을 때.** 안드로이드는 GIF·BMP·HEIF·AVIF를 쓰지 못해 그
 * 원본은 PNG(투명도)나 JPEG가 된다. 옛 파일 중 이름은 `.jpg`인데 내용이 PNG인 장도 같다. 그대로
 * 덮으면 이름과 내용이 갈리므로, 그때는 **같은 UUID에 확장자만 맞춘 새 이름**으로 쓰고 모든 참조
 * (캐릭터·대표 포인터·작품·세계관·라이브러리 메타·대결 기록·정리 폴더 별칭)를 한 번에 옮긴다
 * ([relocate]). 토큰은 확장자 앞 구간이라 정리 폴더 왕복도 그대로 알아본다.
 */
object ImageRotation {

    /**
     * 회전 결과. [newPath]는 이름이 바뀌었을 때만 있다 — 화면이 들고 있는 경로를 갈아 끼우라는 뜻이다.
     */
    data class Outcome(val ok: Boolean, val newPath: String? = null)

    /**
     * [path]의 이미지를 시계 방향 [degrees]만큼 돌린다.
     *
     * 실패하면 **원본은 한 바이트도 바뀌지 않는다**(임시 파일에 다 쓴 뒤에야 갈아 끼우고, 이름을
     * 바꾸는 갈래는 참조 교체가 커밋된 뒤에야 옛 파일을 정리한다).
     */
    suspend fun rotate(context: Context, path: String, degrees: Int): Outcome =
        ImageExportGate.run { rotateImage(context.applicationContext, path, degrees) }

    private suspend fun rotateImage(context: Context, path: String, degrees: Int): Outcome = withContext(Dispatchers.IO) {
        val failed = Outcome(false)
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext failed

        val current = ImageOrientationIo.readOrientation(path)
        val next = ExifOrientation.rotatedBy(current, degrees)

        // ① 무손실 — 태그만 고쳐 본다.
        if (ImageOrientationIo.writeOrientation(path, next)) return@withContext Outcome(true)

        // ② 폴백 — 픽셀을 돌려 다시 쓴다.
        //
        // **줄이지 않는다.** 여기서 표본 축소를 걸면 사용자가 방향만 고치려다 조용히 화질을
        // 잃는다. 큰 이미지에서 메모리가 모자라면 회전을 **하지 않고 실패로 알린다** —
        // 조용한 손실보다 "안 됐습니다"가 낫다.
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext failed
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        } ?: return@withContext failed

        // 픽셀은 EXIF가 적용되지 않은 상태다 — 지금 방향과 이번 회전을 **합쳐서** 한 번에 적용하고,
        // 결과 파일은 방향 정보 없이(= NORMAL) 남는다.
        val rotated = ImageOrientationIo.applyTo(decoded, next)
        if (rotated === decoded && next != ExifOrientation.NORMAL) {
            // 적용에 실패했는데(메모리 부족 등) 원본을 그대로 다시 쓰면 방향만 잃는다.
            decoded.recycle()
            return@withContext failed
        }

        // 표식을 단다 — 인코딩과 개명 사이에서 죽으면 이 파일이 남는데, 표식이 없으면
        // 목록·통계·쓸어내기·앱 초기화 어디에도 안 걸려 영영 남는다([ImageImportHelper.isTempArtifact]).
        val temp = File(file.parentFile, "img${ImageImportHelper.ROTATE_TEMP_MARKER}${UUID.randomUUID()}.tmp")
        // 원본 형식 그대로 다시 쓴다 — 경로(=이름)를 유지하므로, PNG·WebP를 JPEG로 바꾸면 이름과 내용이 갈린다.
        val ok = ImageImportHelper.encodeBitmapTo(rotated, temp, ImageFileFormat.sniff(bytes))
        rotated.recycle()
        if (!ok) { temp.delete(); return@withContext failed }

        // 임시 파일이 온전할 때만 갈아 끼운다.
        if (temp.length() <= 0L) { temp.delete(); return@withContext failed }

        // 쓴 내용이 지금 이름과 맞지 않으면(GIF·BMP·HEIF·AVIF 원본, `.jpg` 이름의 옛 PNG) 이름을 맞춘다.
        val fittingName = ImageFileFormat.withExtensionFor(file.name, ImageFileFormat.sniff(temp))
        if (fittingName != file.name) {
            val dest = File(file.parentFile, fittingName)
            return@withContext relocate(context, file, dest, temp)
        }
        val replaced = runCatching { temp.renameTo(file) }.getOrDefault(false)
        if (!replaced) { temp.delete(); return@withContext failed }
        Outcome(true)
    }

    /**
     * 회전본을 [dest]에 두고 [old]를 가리키던 참조를 전부 옮긴다.
     *
     * 순서가 안전장치다: ① 새 파일을 놓는다(옛 파일은 그대로) ② DB 참조를 **한 트랜잭션**으로 옮긴다
     * — 실패하면 새 파일을 지우고 끝(원본 무손상) ③ 트랜잭션 밖 장부(대결 기록·정리 폴더 별칭)를
     * 따라가게 한다 ④ 옛 파일은 **아직 쥔 곳(휴지통 스냅샷·작성 중 초안)이 없을 때만** 지운다 —
     * 쥔 곳이 있으면 회전 전 원본으로 남는다(그 스냅샷을 되살리면 그 원본이 돌아온다).
     */
    private suspend fun relocate(context: Context, old: File, dest: File, temp: File): Outcome {
        val failed = Outcome(false)
        val filesDir = context.filesDir
        if (dest.exists() || !ImagePathMatch.isDeletableImage(dest.path, filesDir)) { temp.delete(); return failed }
        val placed = runCatching { temp.renameTo(dest) }.getOrDefault(false)
        if (!placed) { temp.delete(); return failed }

        val oldPath = old.absolutePath
        val newPath = dest.absolutePath
        val oldCanon = ImagePathMatch.canonical(oldPath).ifEmpty { oldPath }
        val renames = mapOf(oldPath to newPath, oldCanon to newPath)
        val db = AppDatabase.getDatabase(context)
        val gson = Gson()
        fun swap(json: String): String {
            val list = CharacterRepresentativeImage.paths(json)
            if (list.none { it == oldPath || ImagePathMatch.canonical(it) == oldCanon }) return json
            return gson.toJson(list.map { if (it == oldPath || ImagePathMatch.canonical(it) == oldCanon) newPath else it })
        }
        val moved = runCatching {
            db.withTransaction {
                val owners = ImageDeletionService.buildOwnerIndex(db, gson)[oldCanon] ?: ImageDeletionService.Owners.NONE
                for (id in owners.characterIds) {
                    db.characterDao().getCharacterById(id)?.let { c ->
                        db.characterDao().update(c.withImagePaths(swap(c.imagePaths), renames))
                    }
                }
                for (id in owners.novelIds) {
                    db.novelDao().getNovelById(id)?.let { n -> db.novelDao().update(n.copy(imagePaths = swap(n.imagePaths))) }
                }
                for (id in owners.universeIds) {
                    db.universeDao().getUniverseById(id)?.let { u -> db.universeDao().update(u.copy(imagePaths = swap(u.imagePaths))) }
                }
                db.imageMetaDao().updatePath(oldPath, newPath)
                if (oldCanon != oldPath) db.imageMetaDao().updatePath(oldCanon, newPath)
            }
        }.isSuccess
        if (!moved) { dest.delete(); return failed }

        // 트랜잭션 밖 장부 — 실패해도 회전 자체는 성공이다(재압축 확정과 같은 처분).
        runCatching { FolderRoundtripPrefs.recordRenames(context, mapOf(oldPath to newPath)) }
        runCatching { com.novelcharacter.app.data.repository.DuelRepository(db).followImageRenames(mapOf(oldPath to newPath)) }
        runCatching { ImageOwnershipGuard.deleteIfUnprotected(db, context, listOf(oldPath), filesDir) }
        return Outcome(true, newPath)
    }
}
