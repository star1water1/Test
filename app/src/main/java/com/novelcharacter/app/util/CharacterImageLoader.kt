package com.novelcharacter.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.google.gson.Gson
import com.novelcharacter.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 캐릭터 이미지(내부 저장소 절대경로 JSON 배열) 로딩 공용 유틸.
 *
 * 앱 곳곳(어댑터 5·프래그먼트 다수·알림)에 복붙돼 있던 `BitmapFactory` 다운샘플 + filesDir 경로 가드를
 * 한 곳으로 모은다. 우선 **신규 사용처가 또 복붙하지 않도록** 제공하며, 기존 중복 통합은 별도 정리 대상.
 */
object CharacterImageLoader {

    /** imagePaths JSON 배열에서 첫 이미지의 절대경로. 없거나 파싱 실패면 null. */
    fun firstImagePath(imagePathsJson: String?): String? {
        if (imagePathsJson.isNullOrBlank()) return null
        val list: List<String?>? = runCatching {
            Gson().fromJson<List<String?>>(imagePathsJson, GsonTypes.STRING_LIST)
        }.getOrNull()
        return list?.firstOrNull { !it.isNullOrBlank() }
    }

    /**
     * filesDir 하위 파일만 허용(경로 우회 방지)하고 [reqPx] 기준으로 다운샘플해 디코드한다.
     * 알고리즘은 앱 표준(Android 공식) `calculateInSampleSize`와 동일 — 디코드 결과가 요청 크기 **이상**을
     * 유지하는 가장 큰 샘플(과다 축소로 인한 흐림 없음). 기존 사이트를 이 유틸로 통합해도 화질이 보존된다.
     * **IO 디스패처에서 호출**할 것(디스크·디코드).
     */
    fun decodeThumbnail(path: String, filesDir: File, reqPx: Int = 128): Bitmap? = runCatching {
        val file = File(path)
        // 봉쇄 판정은 [ImagePathMatch.isInside]가 든다 (B-106 ⓐ · R-39) — 던지지 않고,
        // 정규화가 실패하면 **막는다**(모르면 통과가 곧 유출인 축이다).
        if (!ImagePathMatch.isInside(path, filesDir)) return@runCatching null
        if (!file.exists()) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val h = bounds.outHeight
        val w = bounds.outWidth
        if (w <= 0 || h <= 0) return@runCatching null
        var sample = 1
        if (h > reqPx || w > reqPx) {
            val halfH = h / 2
            val halfW = w / 2
            while (sample < 1024 && halfH / sample >= reqPx && halfW / sample >= reqPx) sample *= 2
        }
        // 극단적 종횡비(파노라마·세로 스크롤)에선 표준 알고리즘이 짧은 변 기준이라 긴 변이 그대로 남아 OOM.
        // 총 픽셀 예산(약 4M ≈ 16MB@ARGB_8888)을 넘으면 추가 다운샘플로 상한을 둔다.
        val maxPixels = 4_000_000L
        while (sample < 1024 && (w.toLong() / sample) * (h.toLong() / sample) > maxPixels) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(path, opts)
    }.getOrNull()
}

/**
 * ImageView에 캐릭터 첫 이미지를 **재활용-안전**하게 로드한다.
 * placeholder를 먼저 세팅 → IO에서 디코드 → [isValid]가 true일 때만 반영.
 * 반환된 [Job]을 리스트 항목이라면 `onViewRecycled`에서 cancel할 것.
 *
 * @param cache **부르는 쪽이 소유하는** 썸네일 캐시(선택). 넘기면 적중 시 코루틴을 띄우지
 *   않고 그 자리에서 그린다 — 스크롤 되돌아올 때의 재디코드와 **플레이스홀더 깜빡임**이
 *   함께 없어진다(B-12). 넘기지 않으면 종전 그대로다.
 *
 *   **여기에 캐시를 두지 않는 것이 요점이다.** 이 파일이 캐시를 소유하면 자기 캐시를 이미
 *   든 어댑터들과 이중이 되고, 그 소유 재편은 아직 미측정·미판정인 B-58의 몫이다(확정 16번
 *   = ⓑ). 인자로 받으면 소유는 어댑터에 그대로 있고, **넘기지 않는 화면은 동작이 한 바이트도
 *   바뀌지 않는다** — 이 판이 이미지 관리 탭 하나만 건드리는 근거가 그것이다.
 *
 *   디코드가 끝나면 [isValid]와 **무관하게** 캐시에 넣는다: 스크롤이 지나가 버려 그릴 곳이
 *   없어진 결과도 다음 바인드에는 쓸모가 있다(버리면 그 장을 두 번 디코드한다).
 *
 *   **열쇠에 [reqPx]가 함께 들어간다** — 담기는 것이 *그 경로의 그림*이 아니라 **그 크기로
 *   디코드한 그림**이기 때문이다. 경로만으로 잠그면 한 캐시를 두 크기가 나눠 쓰는 순간
 *   **먼저 담긴 쪽이 조용히 이긴다**(작은 것이 늘어나 흐리거나, 큰 것이 메모리를 두 배로
 *   먹는다 — 어느 쪽이든 화면에서는 원인이 안 보인다). 지금은 캐시를 넘기는 자리가 하나뿐이라
 *   부딪히지 않지만, **부딪히지 않는 것과 부딪힐 수 없는 것은 다른 명제**이고 이 저장소가
 *   반복해 물린 것이 그 차이다. 셈은 바인드마다 짧은 문자열 하나이고 디코드 앞에서는 없는 값이다.
 */
fun ImageView.loadCharacterThumbnail(
    imagePath: String?,
    scope: CoroutineScope,
    reqPx: Int = 128,
    placeholderRes: Int = R.drawable.ic_character_placeholder,
    cache: LruCache<String, Bitmap>? = null,
    isValid: () -> Boolean = { true }
): Job? {
    val key = if (imagePath.isNullOrBlank()) null else "$reqPx@$imagePath"
    if (key != null) {
        val hit = cache?.get(key)
        if (hit != null) {
            // 적중이면 플레이스홀더를 거치지 않는다 — 거치면 이미 가진 그림을 두고 한 프레임
            // 회색으로 깜빡인다(재활용 셀에서 특히 눈에 띈다).
            setImageBitmap(hit)
            return null
        }
    }
    setImageResource(placeholderRes)
    if (imagePath.isNullOrBlank() || key == null) return null
    val dir = context.filesDir
    return scope.launch {
        val bmp = withContext(Dispatchers.IO) { CharacterImageLoader.decodeThumbnail(imagePath, dir, reqPx) }
        if (bmp != null) {
            cache?.put(key, bmp)
            if (isValid()) setImageBitmap(bmp)
        }
    }
}
