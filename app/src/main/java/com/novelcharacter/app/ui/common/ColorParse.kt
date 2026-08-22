package com.novelcharacter.app.ui.common

import android.graphics.Color

/**
 * 색 글자 → 색. **못 읽으면 `null`**(부르는 쪽이 기본색으로 떨어진다).
 *
 * 색은 사용자가 자유롭게 적을 수 있는 값이고(엑셀 셀 · 월드패키지 JSON · 손편집), 그래서
 * `#9E9E9E`가 아닌 글자가 DB에 들어온다. 그 값을 `Color.parseColor`에 **맨몸으로** 넘기면
 * `IllegalArgumentException`이 나고, 그것이 다이얼로그 조립 중이면 **창을 여는 즉시 앱이
 * 죽는다.** 이 저장소의 다른 색 자리들은 이미 감싸고 있었는데 한 자리만 그 관행 밖이었다 —
 * 판정을 두 벌로 적지 않도록 여기 모은다.
 *
 * 저장해도 되는 형식인가는 [com.novelcharacter.app.util.ColorHex.isValidHex]가 든다
 * (그쪽은 순수라 들이는 문의 검사를 시험이 잰다). **읽기는 넓게, 쓰기는 좁게**가 두 함수의
 * 관계다 — 이미 저장된 명명색을 여기서 거부하면 멀쩡히 보이던 색이 사라진다.
 */
fun parseColorOrNull(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    return try {
        Color.parseColor(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }
}
