package com.novelcharacter.app.util

import android.graphics.BitmapFactory
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
 *    (이 저장소는 파일명을 언제나 `.jpg`로 짓지만 **실제 포맷은 내용이 정한다** — 그래서
 *    판정을 확장자가 아니라 *1번의 결과*로 한다).
 *
 * ## 경로는 바뀌지 않는다
 *
 * 같은 파일에 그대로 쓴다. 그래서 **정리 폴더 왕복의 토큰이 그대로 살고**(파일명이 안 바뀐다)
 * 캐릭터·작품·세계관의 경로 참조도 손댈 필요가 없다. 개명 별칭([FolderRoundtripPrefs])도
 * 남길 일이 없다 — 개명이 아니기 때문이다.
 */
object ImageRotation {

    /**
     * [path]의 이미지를 시계 방향 [degrees]만큼 돌린다. 성공하면 true.
     *
     * 실패하면 **원본은 한 바이트도 바뀌지 않는다**(임시 파일에 다 쓴 뒤에야 갈아 끼운다).
     */
    suspend fun rotate(path: String, degrees: Int): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists() || !file.isFile) return@withContext false

        val current = ImageOrientationIo.readOrientation(path)
        val next = ExifOrientation.rotatedBy(current, degrees)

        // ① 무손실 — 태그만 고쳐 본다.
        if (ImageOrientationIo.writeOrientation(path, next)) return@withContext true

        // ② 폴백 — 픽셀을 돌려 다시 쓴다.
        //
        // **줄이지 않는다.** 여기서 표본 축소를 걸면 사용자가 방향만 고치려다 조용히 화질을
        // 잃는다. 큰 이미지에서 메모리가 모자라면 회전을 **하지 않고 실패로 알린다** —
        // 조용한 손실보다 "안 됐습니다"가 낫다.
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext false
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        } ?: return@withContext false

        // 픽셀은 EXIF가 적용되지 않은 상태다 — 지금 방향과 이번 회전을 **합쳐서** 한 번에 적용하고,
        // 결과 파일은 방향 정보 없이(= NORMAL) 남는다.
        val rotated = ImageOrientationIo.applyTo(decoded, next)
        if (rotated === decoded && next != ExifOrientation.NORMAL) {
            // 적용에 실패했는데(메모리 부족 등) 원본을 그대로 다시 쓰면 방향만 잃는다.
            decoded.recycle()
            return@withContext false
        }

        // 표식을 단다 — 인코딩과 개명 사이에서 죽으면 이 파일이 남는데, 표식이 없으면
        // 목록·통계·쓸어내기·앱 초기화 어디에도 안 걸려 영영 남는다([ImageImportHelper.isTempArtifact]).
        val temp = File(file.parentFile, "img${ImageImportHelper.ROTATE_TEMP_MARKER}${UUID.randomUUID()}.tmp")
        val ok = ImageImportHelper.encodeBitmapTo(rotated, temp)
        rotated.recycle()
        if (!ok) { temp.delete(); return@withContext false }

        // 임시 파일이 온전할 때만 갈아 끼운다.
        if (temp.length() <= 0L) { temp.delete(); return@withContext false }
        val replaced = runCatching { temp.renameTo(file) }.getOrDefault(false)
        if (!replaced) { temp.delete(); return@withContext false }
        true
    }
}
