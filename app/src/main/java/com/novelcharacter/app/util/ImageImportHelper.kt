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
 */
object ImageImportHelper {

    /** 입력 상한(기존 픽커 규약 유지). 초과 시 저장하지 않고 null 반환. */
    const val MAX_INPUT_BYTES = 20L * 1024 * 1024

    /** capDimension이 꺼져 있어도 적용하는 OOM 안전 상한(긴변 픽셀). */
    private const val HARD_MAX_LONG_EDGE = 8192

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
        val guardOk = runCatching {
            file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!guardOk) return@withContext null

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
                    FileOutputStream(file).use { out ->
                        // 투명도가 있으면 PNG(무손실)로 보존 — JPEG는 알파를 버려 배경이 검게 됨.
                        if (bmp.hasAlpha()) {
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } else {
                            bmp.compress(Bitmap.CompressFormat.JPEG, settings.qualityPercent, out)
                        }
                    }
                    bmp.recycle()
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

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
