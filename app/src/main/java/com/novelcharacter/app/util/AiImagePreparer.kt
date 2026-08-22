package com.novelcharacter.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.novelcharacter.app.ai.AiImage
import com.novelcharacter.app.ai.AiPromptPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **전송용 이미지 준비** — 파일 경로를 [AiImage]로 바꾼다 (A-7).
 *
 * 저장용 압축([ImageImportHelper])과 **별개다.** 그쪽은 기기 용량이 목적이라 긴 변을
 * 8192px까지 허용하지만, 이쪽은 축소 한 픽셀이 곧 요청 바이트이고 요청 바이트가 곧 돈이다.
 * 크기·품질 상수는 [AiPromptPolicy]가 단일 소스이며 여기에 다시 적지 않는다.
 *
 * **읽지 못한 파일은 조용히 사라지지 않는다** — [Prepared.skipped]로 세어 돌려주고
 * 호출측이 고지한다(R-14). 앱 밖에서 파일이 지워졌거나 폴더 왕복이 경로를 바꾼 자리라
 * 사전 확인이 원리적으로 불가능하고, 말하지 않으면 사용자는 붙인 줄 알았던 그림이
 * 빠진 채 결과를 받는다.
 *
 * **앱 저장소 밖의 파일은 싣지 않는다** (B-141) — 이 파일은 **기기의 바이트를 제3자 서버로
 * 올리는 유일한 경로**다. 그래서 `filesDir`가 **필수 인자**이고 [ImagePathMatch.isInside]가
 * 문지기다. 입력을 믿을 수 없는 근거는 실재한다: 캐릭터의 `imagePaths`는 엑셀 '이미지' 열
 * 편집으로 외부에서 들어오고(`ExcelImportService.remapImagePaths`는 표에 없는 경로를 **원본
 * 그대로 유지**한다), DB 적재 경로 둘은 `validateInternalPaths` 없이 화면에 싣는다.
 * 그 경로를 **첨부 줄 썸네일은 플레이스홀더로 그리는데**(`CharacterImageLoader`가 같은 가드를
 * 든다) 준비기만 성공적으로 읽어 올리고 있었다 — **표시와 전송의 비대칭**이 이 결함의 본체였다.
 *
 * > **왜 인자로 받는가:** 이 object가 `Context`를 들면 가드는 *기억해서 부르는 것*이 되고,
 * > 넷째 소비처가 생길 때 조용히 빠진다. 필수 인자로 두면 **빠뜨릴 자리 자체가 없다**
 * > (B-139가 `imageSystemRule`에서 택한 것과 같은 모양). `tools/check_ai_send_guard.sh`가
 * > 가드의 삭제·우회를 기계로 막는다 — 이 파일은 Android 의존이라 순수 JVM 시험이 닿지 않고,
 * > **판정 자체는** [ImagePathMatch.isInside]로 갈라 두어 그쪽만 시험이 잠근다.
 */
object AiImagePreparer {

    /**
     * @property images 실제로 실을 이미지. 순서는 요청한 경로 순서 그대로다
     *   — 프롬프트의 `이미지 1`, `이미지 2`… 번호가 이 순서를 가리킨다.
     * @property skipped 없거나 읽지 못해 뺀 장수.
     * @property blocked 앱 저장소 밖이라 **보내지 않은** 장수 (B-141).
     *   [skipped]와 갈라 세는 이유는 **처방이 다르기 때문**이다 — 못 읽은 것은 파일을 확인할
     *   일이고, 막힌 것은 그 그림을 앱에 들여야 할 일이다. 하나로 뭉뚱그리면 고지가
     *   *"읽지 못했습니다"*라는 **틀린 사유**를 말하고, 사용자는 멀쩡히 있는 파일을 찾아
     *   헤맨다(`ImageBatchTagSuggester.BatchFailKind.RESPONSE_TRUNCATED`가 세운 선례).
     */
    data class Prepared(val images: List<AiImage>, val skipped: Int, val blocked: Int = 0)

    /**
     * 경로와 **짝지어** 돌려준 결과 (B-121).
     *
     * @property loaded 실제로 실은 (경로, 이미지) 짝. 순서는 요청 순서 그대로다.
     * @property skipped 없거나 읽지 못해 뺀 장수.
     * @property blocked 앱 저장소 밖이라 보내지 않은 장수 (B-141).
     */
    data class PreparedEach(
        val loaded: List<Pair<String, AiImage>>,
        val skipped: Int,
        val blocked: Int
    )

    /**
     * 경로 목록 → 전송용 이미지. IO에서 돈다(디코딩·인코딩이 무겁다).
     *
     * @param filesDir 앱 저장소 루트 — 이 밖의 경로는 싣지 않는다(B-141).
     */
    suspend fun prepare(paths: List<String>, filesDir: File): Prepared {
        val each = prepareEach(paths, filesDir)
        return Prepared(each.loaded.map { it.second }, each.skipped, each.blocked)
    }

    /**
     * 경로와 **짝지어** 돌려준다 — 못 읽거나 막힌 경로는 목록에서 빠진다 (B-121 · B-141).
     *
     * [prepare]가 장수만 세는 것과 갈리는 지점이고, 갈라 둔 이유는 소비처의 요구가 다르기
     * 때문이다: 첨부(B-120)는 "몇 장 빠졌나"만 고지하면 되지만, 일괄 태깅은 응답의 번호가
     * **실제로 실린 목록의 자리**를 가리켜야 한다. 짝을 잃으면 가운데 한 장이 빠졌을 때
     * 태그가 옆 이미지에 붙는다(`ai.ImageBatchTagSuggester.Loaded` 주석이 그 사고를 적는다).
     *
     * **뺀 사유를 여기서 센다** — 호출측이 `요청 수 - 실린 수`로 빼서 구하지 않는다.
     * 뺄셈으로는 두 사유가 한 숫자로 뭉개져 고지가 둘 중 하나를 반드시 틀리게 말한다.
     *
     * @param filesDir 앱 저장소 루트 — 이 밖의 경로는 싣지 않는다(B-141).
     */
    suspend fun prepareEach(paths: List<String>, filesDir: File): PreparedEach =
        withContext(Dispatchers.IO) {
            if (paths.isEmpty()) return@withContext PreparedEach(emptyList(), 0, 0)
            val out = ArrayList<Pair<String, AiImage>>(paths.size)
            var skipped = 0
            var blocked = 0
            for (path in paths) {
                // 봉쇄가 먼저다 — 뒤에 두면 막을 파일을 이미 디코딩한 뒤가 된다.
                if (!ImagePathMatch.isInside(path, filesDir)) {
                    blocked++
                    continue
                }
                val encoded = encodeForSend(path)
                if (encoded == null) skipped++ else out.add(path to encoded)
            }
            PreparedEach(out, skipped, blocked)
        }

    /**
     * 한 장을 긴 변 [AiPromptPolicy.SEND_LONG_EDGE_PX] 이하로 줄여 JPEG base64로 만든다.
     * 실패(파일 없음·손상·메모리)는 예외가 아니라 null이다 — 한 장 때문에 요청 전체를
     * 잃는 것이 이 기능에서 가장 나쁜 결과다.
     */
    private fun encodeForSend(path: String): AiImage? = try {
        val file = File(path)
        if (!file.exists() || !file.isFile || file.length() <= 0L) null
        else {
            val bitmap = decodeScaled(file, AiPromptPolicy.SEND_LONG_EDGE_PX)
            if (bitmap == null) null else {
                val out = ByteArrayOutputStream()
                val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, AiPromptPolicy.SEND_JPEG_QUALITY, out)
                bitmap.recycle()
                if (!ok || out.size() == 0) null
                else AiImage(
                    base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP),
                    mediaType = AiImage.DEFAULT_MEDIA_TYPE
                )
            }
        }
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        // 큰 원본 하나가 요청 전체를 죽이지 않게 한다 — 나머지 장은 그대로 나간다.
        null
    }

    /**
     * `inSampleSize`로 근접 축소한 뒤 정밀 축소한다 — [ImageImportHelper.decodeScaled]와 같은
     * 알고리즘이되 **파일에서 직접** 읽는다(전송용은 원본 바이트를 통째로 메모리에 올릴
     * 이유가 없다. 이 경로는 8192px 원본도 만난다).
     */
    private fun decodeScaled(file: File, targetLongEdge: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching null
        val longEdge = maxOf(w, h)
        var sample = 1
        while (sample < MAX_SAMPLE && (longEdge / (sample * 2)) >= targetLongEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
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
    }.getOrNull()?.let {
        // **AI에 보내는 것도 세워서 보낸다.** 누운 사진을 보내면 모델이 그대로 읽어
        // 엉뚱한 태그를 붙이고, 그 결과가 화면과 어긋난다(화면은 세워 보여 준다).
        ImageOrientationIo.applyFileOrientation(file.absolutePath, it)
    }

    private const val MAX_SAMPLE = 1024
}
