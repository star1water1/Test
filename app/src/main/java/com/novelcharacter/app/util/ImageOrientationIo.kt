package com.novelcharacter.app.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File

/**
 * EXIF 방향의 **읽는 손·그리는 손** — 규칙은 [ExifOrientation](순수)이 든다.
 *
 * 여기 있는 것은 전부 Android 의존이라 하네스가 못 돈다. 그래서 **판단은 한 줄도 두지 않는다** —
 * 여덟 값의 뜻·회전 누적·모르는 값의 처분은 저쪽 표가 정하고, 이 파일은 그 답을 실행만 한다.
 *
 * ## 두 갈래를 갈라 쓰는 이유
 *
 * - **편입·재압축**은 어차피 픽셀을 다시 쓰므로 [applyTo]로 **세워서 저장**한다. 그러면 저장된
 *   픽셀이 곧 보이는 것이 되어 EXIF에 기대는 자리가 하나 줄고, 내보낸 사본도 외부에서 바로 선다.
 * - **무손실 경로**는 원본 바이트를 그대로 두므로(설정이 약속한 것이다) EXIF가 살아 있고,
 *   **그리는 자리**가 [readOrientation]+[applyTo]로 세운다. 저장을 안 건드리니 약속이 안 깨진다.
 *
 * 둘이 함께여야 빈 곳이 없다 — 하나만 하면 다른 갈래의 사진이 눕는다.
 */
object ImageOrientationIo {

    /** 바이트에서 EXIF 방향을 읽는다. 못 읽으면 [ExifOrientation.NORMAL](= 손대지 않는다). */
    fun readOrientation(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL
        )
    }.getOrDefault(ExifOrientation.NORMAL)

    /** 파일에서 EXIF 방향을 읽는다. 못 읽으면 [ExifOrientation.NORMAL]. */
    fun readOrientation(path: String): Int = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifOrientation.NORMAL
        )
    }.getOrDefault(ExifOrientation.NORMAL)

    /**
     * 방향을 비트맵에 적용한다. 적용할 것이 없으면 **[bmp] 그대로** 돌려준다(새로 만들지 않는다).
     *
     * 새 비트맵을 만들었을 때만 원본을 회수하므로, **갓 디코드한 비트맵에만** 쓸 것 —
     * 캐시에 든 비트맵을 넘기면 그 캐시가 회수된 비트맵을 들게 된다.
     *
     * 메모리가 모자라면 **돌리지 않고 원본을 돌려준다.** 회전은 비트맵 한 장을 새로 뜨는 일이라
     * 큰 이미지에서 최대 사용량이 두 배가 되는데, 여기서 죽는 것보다 누운 채 보이는 편이 낫다
     * (`OutOfMemoryError`만 좁게 잡는다 — 넓게 잡으면 진짜 오류를 삼킨다. R-59 ⓒ와 같은 취지).
     */
    fun applyTo(bmp: Bitmap, exifOrientation: Int): Bitmap {
        val transform = ExifOrientation.transformOf(exifOrientation)
        if (transform.isIdentity) return bmp
        return try {
            val matrix = Matrix()
            // 반전을 먼저, 회전을 나중에 — 순서가 바뀌면 반전을 겸하는 넷(2·4·5·7)이 틀린다.
            if (transform.mirrored) matrix.postScale(-1f, 1f)
            if (transform.degrees != 0) matrix.postRotate(transform.degrees.toFloat())
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated !== bmp) bmp.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bmp
        } catch (e: Exception) {
            bmp
        }
    }

    /** 파일을 열어 방향까지 적용한 비트맵을 준다 — 그리는 자리가 부르는 통로. */
    fun applyFileOrientation(path: String, bmp: Bitmap): Bitmap = applyTo(bmp, readOrientation(path))

    /**
     * 파일의 EXIF 방향 태그를 [exifOrientation]으로 **다시 쓴다**(픽셀은 건드리지 않는다).
     *
     * 성공하면 true. JPEG가 아니면 대개 실패하므로 호출부가 픽셀 회전으로 폴백해야 한다 —
     * 이 저장소는 파일명을 언제나 `.jpg`로 짓지만 **실제 포맷은 내용이 정하고**(투명도가 있으면
     * PNG로 인코딩된다) 확장자로는 알 수 없다. 그래서 판정을 확장자가 아니라 **결과**로 한다.
     */
    fun writeOrientation(path: String, exifOrientation: Int): Boolean = runCatching {
        val file = File(path)
        if (!file.exists() || !file.isFile) return false
        val exif = ExifInterface(path)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
        exif.saveAttributes()
        // 되읽어 확인한다 — `saveAttributes`가 조용히 아무것도 안 하는 포맷이 있다.
        readOrientation(path) == exifOrientation
    }.getOrDefault(false)
}
