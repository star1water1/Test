package com.novelcharacter.app.util

/**
 * EXIF 방향 값의 **해석 규칙 단일 소스** (순수 로직 — JVM 테스트로 검증).
 *
 * 안드로이드 `BitmapFactory`는 EXIF 방향을 **무시하고** 센서가 기록한 픽셀을 그대로 준다.
 * 폰 카메라는 대개 픽셀을 세우지 않고 방향만 EXIF에 적으므로, 그 값을 읽어 적용하지 않으면
 * 세로로 찍은 사진이 전부 눕는다. 이 저장소는 그 적용을 **한 곳도** 하지 않고 있었다
 * (`ExifInterface` 참조 0건 · 디코드 네 자리가 전부 `BitmapFactory`).
 *
 * ## 왜 순수부를 따로 두는가
 *
 * 읽는 손([android.media.ExifInterface])과 그리는 손([android.graphics.Bitmap])은 Android라
 * 하네스가 못 돈다. 그런데 **틀리기 쉬운 것은 손이 아니라 표**다 — 여덟 값 중 넷이 좌우 반전을
 * 겸하고, 90과 270을 뒤집으면 사진이 반대로 눕는다. 그래서 표만 여기 두고 시험이 잠근다.
 *
 * ## 값의 뜻 (EXIF 2.3 · TIFF Orientation)
 *
 * 값은 *"원본을 어떻게 놓아야 바로 보이는가"*가 아니라 *"기록된 픽셀이 어떻게 놓여 있는가"*다.
 * 그래서 [transformOf]가 돌려주는 것은 **보이게 만들기 위해 적용할 변환**이고, 그것을 적용한
 * 뒤의 방향은 언제나 [NORMAL]이다.
 */
object ExifOrientation {

    /** 픽셀이 이미 바로 서 있다 — 적용할 변환이 없다. `ExifInterface.ORIENTATION_NORMAL`과 같은 값. */
    const val NORMAL = 1

    /** 방향을 알 수 없을 때 쓰는 값. `ExifInterface.ORIENTATION_UNDEFINED`와 같은 값. */
    const val UNDEFINED = 0

    /**
     * 화면에 바로 세우기 위해 적용할 변환.
     *
     * @param degrees 시계 방향 회전 각(0·90·180·270).
     * @param mirrored 회전 **전에** 좌우로 뒤집는가.
     */
    data class Transform(val degrees: Int, val mirrored: Boolean) {
        /** 아무것도 하지 않아도 되는가 — 참이면 호출부는 비트맵을 새로 만들지 않는다. */
        val isIdentity: Boolean get() = degrees == 0 && !mirrored
    }

    private val IDENTITY = Transform(0, false)

    /**
     * EXIF 방향 값 → 적용할 변환.
     *
     * **모르는 값은 [IDENTITY]다**(0·1·범위 밖 전부). 방향을 못 읽었을 때 임의로 돌리는 것은
     * 멀쩡한 사진을 눕히는 일이라, 모를 때는 손대지 않는 쪽이 언제나 옳다.
     */
    fun transformOf(exifOrientation: Int): Transform = when (exifOrientation) {
        2 -> Transform(0, true)      // FLIP_HORIZONTAL
        3 -> Transform(180, false)   // ROTATE_180
        4 -> Transform(180, true)    // FLIP_VERTICAL (= 180 회전 + 좌우 반전)
        // 5·7은 **반전을 먼저 하는 이 표기에서 각이 서로 반대**다(2026.08.22 정정).
        // 전치(5)는 주대각선 대칭이고, 좌우 반전을 먼저 걸면 남는 것은 **반시계 90**이다.
        // 흔히 쓰는 표기(`회전 뒤 반전`)의 *90 + 좌우반전*과 같은 변환이다 —
        // `R(θ)∘F = F∘R(−θ)`라 순서를 바꾸면 각의 부호가 뒤집힌다. 그 부호를 놓치면
        // 사진이 **정확히 180° 뒤집혀** 보인다(반전은 맞고 각만 반대라 눈치채기 어렵다).
        5 -> Transform(270, true)    // TRANSPOSE  (= 좌우 반전 후 270 시계)
        6 -> Transform(90, false)    // ROTATE_90
        7 -> Transform(90, true)     // TRANSVERSE (= 좌우 반전 후 90 시계)
        8 -> Transform(270, false)   // ROTATE_270
        else -> IDENTITY             // 1(NORMAL) · 0(UNDEFINED) · 미지값
    }

    /**
     * 지금 방향에 [degrees]만큼 **더 돌린** 뒤의 EXIF 방향 값 — 인앱 회전이 쓴다.
     *
     * 픽셀을 다시 쓰지 않고 태그만 갱신할 수 있는 자리(JPEG)에서 이것으로 값을 정한다.
     * 좌우 반전이 걸린 값(2·4·5·7)도 반전을 유지한 채 회전만 누적한다 — 반전을 잃으면
     * 사용자가 돌리기만 했는데 좌우가 뒤집힌다.
     *
     * @param degrees 90의 배수(음수·360 이상도 받는다).
     */
    fun rotatedBy(exifOrientation: Int, degrees: Int): Int {
        val current = transformOf(exifOrientation)
        val added = ((degrees % 360) + 360) % 360
        val total = (current.degrees + added) % 360
        return valueOf(Transform(total, current.mirrored))
    }

    /** [transformOf]의 역함수 — 변환 → EXIF 방향 값. 표를 두 벌 적지 않으려고 되짚는다. */
    fun valueOf(transform: Transform): Int {
        for (value in 1..8) if (transformOf(value) == transform) return value
        return NORMAL
    }
}
