package com.novelcharacter.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
     * filesDir 하위 파일만 허용(경로 우회 방지)하고 [reqPx] 근처로 다운샘플해 디코드한다.
     * **IO 디스패처에서 호출**할 것(디스크·디코드).
     */
    fun decodeThumbnail(path: String, filesDir: File, reqPx: Int = 128): Bitmap? {
        val file = File(path)
        if (!file.canonicalPath.startsWith(filesDir.canonicalPath + File.separator)) return null
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqPx || bounds.outHeight / sample > reqPx) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeFile(path, opts) }.getOrNull()
    }
}

/**
 * ImageView에 캐릭터 첫 이미지를 **재활용-안전**하게 로드한다.
 * placeholder를 먼저 세팅 → IO에서 디코드 → [isValid]가 true일 때만 반영.
 * 반환된 [Job]을 리스트 항목이라면 `onViewRecycled`에서 cancel할 것.
 */
fun ImageView.loadCharacterThumbnail(
    imagePath: String?,
    scope: CoroutineScope,
    reqPx: Int = 128,
    placeholderRes: Int = R.drawable.ic_character_placeholder,
    isValid: () -> Boolean = { true }
): Job? {
    setImageResource(placeholderRes)
    if (imagePath.isNullOrBlank()) return null
    val dir = context.filesDir
    return scope.launch {
        val bmp = withContext(Dispatchers.IO) { CharacterImageLoader.decodeThumbnail(imagePath, dir, reqPx) }
        if (bmp != null && isValid()) setImageBitmap(bmp)
    }
}
