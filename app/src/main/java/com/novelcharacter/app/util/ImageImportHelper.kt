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

        val fileName = "${prefix}_${UUID.randomUUID()}.jpg"
        val file = File(context.filesDir, fileName)
        // 경로 우회 방지(작품/세계관 기존 가드와 동일)
        if (!isInsideFilesDir(context, file)) return@withContext null

        try {
            val shouldCompress = settings.enabled &&
                !(settings.skipBelowEnabled && bytes.size < settings.skipBelowBytes)

            if (!shouldCompress) {
                file.writeBytes(bytes)  // 원본 그대로(무손실)
            } else {
                val targetLongEdge = if (settings.capDimension) settings.maxLongEdgePx else HARD_MAX_LONG_EDGE
                val bmp = decodeScaled(bytes, targetLongEdge)
                if (bmp == null) {
                    // 디코드 실패(손상·미지원 포맷) → 원본 폴백(변수 제어: 무단 유실 금지)
                    file.writeBytes(bytes)
                } else {
                    val ok = encodeBitmap(bmp, file, settings.qualityPercent)
                    bmp.recycle()
                    if (!ok) {
                        // 인코드 실패 → 원본 폴백(무단 유실 금지)
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
        val bmp = decodeScaled(bytes, targetLongEdge)
            ?: return@withContext RecompressOutcome.skip(SkipReason.CORRUPT)

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
        if (newSize <= 0L || newSize >= originalSize) {
            temp.delete()
            return@withContext RecompressOutcome.skip(SkipReason.NO_BENEFIT)  // 이득 없음 → 원본 유지
        }
        RecompressOutcome.ok(temp)
    }

    /**
     * 디코드된 비트맵을 [file]에 인코드한다(공용 코어). 성공 여부 반환.
     * 투명도가 있으면 PNG(무손실)로 보존 — JPEG는 알파를 버려 배경이 검게 됨.
     */
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

    /** [file]이 filesDir 하위(직속)인지 확인 — 경로 우회 방지. */
    private fun isInsideFilesDir(context: Context, file: File): Boolean = runCatching {
        file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)
    }.getOrDefault(false)

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
