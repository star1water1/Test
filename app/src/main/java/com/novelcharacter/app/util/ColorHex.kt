package com.novelcharacter.app.util

/**
 * 색 글자를 읽는 **단일 소스** — 죽지 않고, 나쁜 값을 조용히 들이지도 않는다.
 *
 * ## 왜 필요한가
 *
 * 색은 사용자가 자유롭게 적을 수 있는 값이고(엑셀 셀 · 월드패키지 JSON · 손편집), 그래서
 * `#9E9E9E`가 아닌 글자가 DB에 들어온다. 그 값을 `Color.parseColor`에 **맨몸으로** 넘기면
 * `IllegalArgumentException`이 나고, 그것이 다이얼로그 조립 중이면 **창을 여는 즉시 앱이
 * 죽는다.** 이 저장소의 다른 자리들은 이미 `try/catch`로 감싸고 있었는데 한 자리만 그
 * 관행 밖이었다 — 판정을 두 벌로 적지 않도록 여기 모은다.
 *
 * 이 파일이 **순수 코틀린**인 것은 의도다 — 안드로이드 의존이 없어야 들이는 쪽(엑셀
 * 가져오기)의 검사를 순수 JVM 시험이 잴 수 있다. 읽는 쪽은 명명색(`red` 등)까지 받는
 * 종전 수용 범위를 그대로 지켜야 해 플랫폼 파서를 쓰므로 화면 계층에 둔다
 * (`ui/common/parseColorOrNull`).
 */
object ColorHex {

    /**
     * 저장해도 되는 색 글자인가 — `#RGB` · `#RRGGBB` · `#AARRGGBB`.
     *
     * 명명색을 받지 않는 것도 의도다: 들이는 문에서는 **저장 형식을 좁히는 쪽**이 안전하고,
     * 이 앱이 스스로 내보내는 값은 언제나 `#RRGGBB`다(색 피커의 출력 형식).
     */
    fun isValidHex(raw: String?): Boolean =
        raw != null && HEX.matches(raw.trim())

    private val HEX = Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
}
