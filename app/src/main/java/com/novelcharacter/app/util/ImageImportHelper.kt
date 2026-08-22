package com.novelcharacter.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 외부에서 픽한 이미지를 앱 내부 저장소(filesDir)로 저장하는 **단일 공용 유틸**.
 *
 * 기존엔 캐릭터·작품·세계관 픽커가 각자 원본 바이트를 그대로 복사(3벌 중복)했다. 이 유틸이 그 셋을 대체하며,
 * [ImageSettingsStore.ImageSettings]에 따라 선택적으로 압축한다(용량↔화질 조절). 압축이 꺼져 있거나
 * "일정 이하" 임계값 미만이면 원본 무손실 경로를 그대로 쓴다.
 *
 * 파일명 규약은 기존과 동일: `<prefix>_<UUID>.jpg` (filesDir 루트), 저장 후 절대경로 반환.
 * (확장자는 관례적 표기 — 저장 포맷은 내용으로 결정되며 디코더가 자동 판별한다.)
 *
 * 관리 탭의 **기존 이미지 재압축**([recompressToTemp])도 같은 인코딩 코어를 재사용한다(중복 금지).
 */
object ImageImportHelper {

    /** 입력 상한(기존 픽커 규약 유지). 초과 시 저장하지 않고 null 반환. */
    const val MAX_INPUT_BYTES = 20L * 1024 * 1024

    /**
     * 재압축 입력 상한. 픽 입력(20MB)보다 넉넉히 잡는다 — 압축 도입 전 원본이 이미 저장돼 있을 수 있어서다.
     * 이보다 큰 파일은 OOM 방지를 위해 스킵(사유 고지).
     */
    private const val RECOMPRESS_MAX_INPUT_BYTES = 64L * 1024 * 1024

    /** capDimension이 꺼져 있어도 적용하는 OOM 안전 상한(긴변 픽셀). */
    private const val HARD_MAX_LONG_EDGE = 8192

    /**
     * 재압축 임시 파일 이름에 넣는 표식. 커밋 전까지의 임시 산출물이며, 관리 탭 목록/정리에서 이 표식으로
     * 식별해 숨기거나(활성 미리보기) 쓸어낸다(중단된 미리보기 잔여물). 커밋 시 정식 `<prefix>_<UUID>.jpg`로 개명.
     */
    const val RECOMPRESS_TEMP_MARKER = "_recompress_"

    /**
     * 인앱 회전의 **픽셀 폴백** 임시 파일 이름에 넣는 표식(`ImageRotation`).
     *
     * EXIF 태그만 고쳐 쓸 수 없는 파일은 픽셀을 다시 써서 갈아 끼우는데, 그 사이(인코딩 후
     * 개명 전)에 프로세스가 죽으면 임시 파일이 남는다. 종전에는 이름이 `rotate_<UUID>.tmp`라
     * **어떤 목록에도, 어떤 쓸어내기에도, 앱 초기화에도 걸리지 않았다** — 이미지 확장자가
     * 아니라 목록·통계에서 빠지고, 쓸어내기는 재압축 표식만 봤다. 영영 남는 자리였다.
     */
    const val ROTATE_TEMP_MARKER = "_rotate_"

    /**
     * 커밋 전 **임시 산출물**인가 — 목록·통계·쓸어내기·앱 초기화가 **이 한 판정**을 쓴다.
     *
     * 표식을 늘릴 때 이 함수만 고치면 네 자리가 함께 따라온다(명단을 네 벌 적으면 반드시 갈린다 — R-43).
     */
    fun isTempArtifact(name: String): Boolean =
        name.contains(RECOMPRESS_TEMP_MARKER) || name.contains(ROTATE_TEMP_MARKER)

    /** 재압축 스킵 사유 — 사용자에게 "왜 안 했는지" 고지하기 위한 분류(변수 제어: 조용한 무시 금지). */
    enum class SkipReason { NOT_REFERENCED, TOO_SMALL, TOO_LARGE, CORRUPT, NO_BENEFIT, ERROR }

    /** 재압축 결과 — 성공 시 임시 파일, 아니면 스킵 사유. */
    class RecompressOutcome private constructor(val tempFile: File?, val skip: SkipReason?) {
        companion object {
            fun ok(file: File) = RecompressOutcome(file, null)
            fun skip(reason: SkipReason) = RecompressOutcome(null, reason)
        }
    }

    /**
     * [uri]의 이미지를 filesDir에 저장하고 절대경로를 반환한다(실패/초과 시 null).
     * IO 디스패처에서 실행. [prefix]는 `char`/`novel`/`universe`.
     */
    suspend fun importImage(
        context: Context,
        uri: Uri,
        prefix: String,
        settings: ImageSettingsStore.ImageSettings
    ): String? = withContext(Dispatchers.IO) {
        val bytes = readBounded(context, uri, MAX_INPUT_BYTES) ?: return@withContext null
        // **0바이트는 편입하지 않는다.** 내용이 없는 파일에는 지킬 데이터가 없으므로, 아래
        // "디코드 실패 → 원본 폴백"(무단 유실 금지)의 근거가 여기엔 걸리지 않는다.
        // 그대로 들이면 라이브러리에 **열리지 않는 이미지 행**이 생기고, 정리 폴더 왕복은 그것을
        // 성공으로 세어 사용자의 원본을 `_처리됨/`으로 치운다 — 실패로 돌려야 원본이 폴더에 남는다.
        if (bytes.isEmpty()) return@withContext null

        val fileName = "${prefix}_${UUID.randomUUID()}.jpg"
        val file = File(context.filesDir, fileName)
        // 경로 우회 방지(작품/세계관 기존 가드와 동일)
        if (!isInsideFilesDir(context, file)) return@withContext null

        try {
            val shouldCompress = settings.enabled &&
                !(settings.skipBelowEnabled && bytes.size < settings.skipBelowBytes)

            if (!shouldCompress) {
                // 원본 그대로(무손실) — **방향도 원본이 든 EXIF가 그대로 든다.**
                // 여기서 픽셀을 세우면 그 순간 재인코딩이라 설정이 약속한 무손실이 깨진다.
                // 대신 **그리는 자리**가 EXIF를 읽어 세운다([ImageOrientationIo] 머리말의 두 갈래).
                file.writeBytes(bytes)
            } else {
                val targetLongEdge = if (settings.capDimension) settings.maxLongEdgePx else HARD_MAX_LONG_EDGE
                val decoded = decodeScaled(bytes, targetLongEdge)
                if (decoded == null) {
                    // 디코드 실패(손상·미지원 포맷) → 원본 폴백(변수 제어: 무단 유실 금지)
                    file.writeBytes(bytes)
                } else {
                    // **픽셀을 세워서 저장한다.** 이 갈래는 어차피 재인코딩이라 세우는 데
                    // 추가 손실이 없고, 세우지 않으면 EXIF가 사라진 채 픽셀만 누운 파일이 남아
                    // **원래 방향을 알 방법이 영영 없어진다**(되돌릴 수 없는 유실).
                    val orientation = ImageOrientationIo.readOrientation(bytes)
                    val bmp = ImageOrientationIo.applyTo(decoded, orientation)
                    val ok = encodeBitmap(bmp, file, settings.qualityPercent)
                    bmp.recycle()
                    // **이득이 없으면 원본을 쓴다** — `recompressToTemp`가 이미 세운 정책([SkipReason.NO_BENEFIT])을
                    // 편입에도 그대로 적용한다. 투명도가 있으면 PNG 100으로 인코딩하므로 사진은
                    // 원본보다 커질 수 있는데, 그때 압축을 켠 사용자가 얻는 것이 손실뿐이다.
                    // **단, 세울 것이 있었으면 결과가 커도 쓴다** — 그쪽은 용량이 아니라 방향을
                    // 바로잡는 일이고, 원본으로 되돌리면 그 교정이 통째로 사라진다.
                    val straightened = !ExifOrientation.transformOf(orientation).isIdentity
                    val encodedSize = if (ok) file.length() else 0L
                    if (!ok || (!straightened && (encodedSize <= 0L || encodedSize >= bytes.size))) {
                        // 인코드 실패 또는 이득 없음 → 원본 폴백(무단 유실 금지)
                        file.writeBytes(bytes)
                    }
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /**
     * 이미 저장된 [sourceFile]을 [settings]로 재압축해 임시 파일에 쓴다(커밋 전 단계).
     *
     * 여기서는 파일을 **교체하지 않는다** — 결과 임시 파일(정식 개명 전)만 만들고, 실제 교체(소유 엔티티 경로 갱신·
     * 원본 삭제)는 호출측이 사용자 확인 뒤 수행한다. 이렇게 나눠야 확인 다이얼로그에 **정확한 전/후 크기**를
     * 보여줄 수 있고, 취소 시 아무것도 손상되지 않는다.
     *
     * 스킵 규칙(사유 고지): 임계값 미만([SkipReason.TOO_SMALL]) · 과대([TOO_LARGE]) · 디코드 실패([CORRUPT]) ·
     * 결과가 더 작지 않음([NO_BENEFIT] — 부풀리기 방지) · 그 외 오류([ERROR]).
     */
    suspend fun recompressToTemp(
        context: Context,
        sourceFile: File,
        prefix: String,
        settings: ImageSettingsStore.ImageSettings
    ): RecompressOutcome = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || !sourceFile.isFile) return@withContext RecompressOutcome.skip(SkipReason.ERROR)

        val originalSize = sourceFile.length()
        if (settings.skipBelowEnabled && originalSize < settings.skipBelowBytes) {
            return@withContext RecompressOutcome.skip(SkipReason.TOO_SMALL)
        }
        if (originalSize > RECOMPRESS_MAX_INPUT_BYTES) {
            return@withContext RecompressOutcome.skip(SkipReason.TOO_LARGE)
        }

        val bytes = runCatching { sourceFile.readBytes() }.getOrNull()
            ?: return@withContext RecompressOutcome.skip(SkipReason.ERROR)
        val targetLongEdge = if (settings.capDimension) settings.maxLongEdgePx else HARD_MAX_LONG_EDGE
        val decoded = decodeScaled(bytes, targetLongEdge)
            ?: return@withContext RecompressOutcome.skip(SkipReason.CORRUPT)
        // 여기도 재인코딩이므로 **세워서 쓴다.** 무손실로 들어온 원본을 나중에 재압축할 때
        // 세우지 않으면, 그 순간 EXIF만 사라지고 픽셀은 누운 채 남아 방향을 영영 잃는다.
        val sourceOrientation = ImageOrientationIo.readOrientation(bytes)
        val bmp = ImageOrientationIo.applyTo(decoded, sourceOrientation)

        val tempName = "${prefix}${RECOMPRESS_TEMP_MARKER}${UUID.randomUUID()}.jpg"
        val temp = File(context.filesDir, tempName)
        if (!isInsideFilesDir(context, temp)) {
            bmp.recycle()
            return@withContext RecompressOutcome.skip(SkipReason.ERROR)
        }

        val ok = encodeBitmap(bmp, temp, settings.qualityPercent)
        bmp.recycle()
        if (!ok) {
            temp.delete()
            return@withContext RecompressOutcome.skip(SkipReason.ERROR)
        }

        val newSize = temp.length()
        // **세울 것이 있었으면 크기가 안 줄어도 이득이다** — 방향을 바로잡는 것이 그 자체로
        // 결과물이고, 원본을 유지하면 그 교정이 사라진다(편입 쪽과 같은 판단).
        val straightened = !ExifOrientation.transformOf(sourceOrientation).isIdentity
        if (newSize <= 0L || (!straightened && newSize >= originalSize)) {
            temp.delete()
            return@withContext RecompressOutcome.skip(SkipReason.NO_BENEFIT)  // 이득 없음 → 원본 유지
        }
        RecompressOutcome.ok(temp)
    }

    /**
     * 디코드된 비트맵을 [file]에 인코드한다(공용 코어). 성공 여부 반환.
     * 투명도가 있으면 PNG(무손실)로 보존 — JPEG는 알파를 버려 배경이 검게 됨.
     */
    /**
     * 인코딩 코어를 **회전에도 빌려준다** — 알파 보존·품질 규칙이 두 벌이 되지 않게(중복 금지).
     * 회전은 사용자가 원본을 손보는 조작이라 품질을 낮추지 않는다(100).
     */
    internal fun encodeBitmapTo(bmp: Bitmap, file: File): Boolean = encodeBitmap(bmp, file, 100)

    private fun encodeBitmap(bmp: Bitmap, file: File, qualityPercent: Int): Boolean = try {
        FileOutputStream(file).use { out ->
            if (bmp.hasAlpha()) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, qualityPercent, out)
            }
        }
    } catch (e: Exception) {
        false
    }

    /**
     * [file]이 filesDir 하위(직속)인지 확인 — 경로 우회 방지.
     * 판정은 [ImagePathMatch.isInside]가 든다 (B-106 ⓐ · R-39) — 실패하면 **막는다.**
     */
    private fun isInsideFilesDir(context: Context, file: File): Boolean =
        ImagePathMatch.isInside(file.path, context.filesDir)

    /** Uri를 [maxBytes] 상한 내에서 바이트로 읽는다. 초과·오류 시 null. */
    private fun readBounded(context: Context, uri: Uri, maxBytes: Long): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0L
            var read: Int
            while (input.read(chunk).also { read = it } != -1) {
                total += read
                if (total > maxBytes) return null
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 바이트 이미지를 긴변 [targetLongEdge] 이하로 디코드한다. inSampleSize로 근접 축소 후
     * createScaledBitmap으로 정밀 축소(과다 축소 흐림 방지). CharacterImageLoader.decodeThumbnail 알고리즘 준용.
     */
    private fun decodeScaled(bytes: ByteArray, targetLongEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        val longEdge = maxOf(w, h)
        var sample = 1
        while (sample < 1024 && (longEdge / (sample * 2)) >= targetLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching null
        val decodedLong = maxOf(decoded.width, decoded.height)
        if (decodedLong <= targetLongEdge) {
            decoded
        } else {
            val scale = targetLongEdge.toFloat() / decodedLong
            val nw = (decoded.width * scale).toInt().coerceAtLeast(1)
            val nh = (decoded.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true)
            if (scaled != decoded) decoded.recycle()
            scaled
        }
    }.getOrNull()
}
