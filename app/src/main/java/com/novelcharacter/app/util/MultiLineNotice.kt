package com.novelcharacter.app.util

import android.content.Context
import android.widget.Toast

/**
 * **여러 줄짜리 고지를 토스트로 낼 때 쓴다 — 시스템 토스트는 두 줄에서 자른다.**
 *
 * ## 왜 있는가
 *
 * 토스트 본문은 시스템이 그리고 **두 줄에서 잘린다**(API 30+). 그래서 `"\n"`으로 이은
 * 세 줄짜리 고지는 **셋째 줄이 아예 보이지 않는다** — 2026.11.07 워크스루가 두 자리에서
 * 이것을 관찰로 잡았고(이동 보관 토스트 · *"허용 값이 없는…"*), 가장 나쁜 자리는 이미지
 * 가져오기였다: 담아 둔 몫·못 담은 몫·시도 못 한 몫·저장 실패를 **네 줄**로 말하는데
 * 사용자는 앞 두 줄만 본다. 말하지 않은 것과 구별되지 않는다.
 *
 * ## 왜 토스트를 다른 것으로 바꾸지 않는가
 *
 * 이 고지가 나가는 시점은 **화면이 이미 사라진 뒤**다(회전·홈 이탈). 그래서
 * - **Snackbar**는 붙일 뷰가 없다(`ResultNotify`가 그 폴백으로 토스트를 쓰는 이유다).
 * - **다이얼로그**는 살아 있는 액티비티가 필요하다.
 *
 * 남는 길은 **줄마다 토스트 하나**다. 토스트는 시스템이 차례로 띄우므로 줄이 사라지지 않고,
 * 뷰도 액티비티도 필요하지 않다. 긴 고지가 몇 초 더 걸리는 것은 **줄이 잘리는 것보다 낫다.**
 *
 * **한 줄이면 종전과 똑같다** — 흔한 경우(회전 한 번)는 줄이 하나다.
 */
object MultiLineNotice {

    /**
     * [lines]를 **줄마다 하나씩** 토스트로 낸다. 빈 줄은 건너뛴다.
     *
     * 반드시 메인 스레드에서 부른다 — 토스트는 다른 스레드에서 띄울 수 없다.
     */
    fun toastEachLine(context: Context, lines: List<String>, duration: Int = Toast.LENGTH_LONG) {
        for (line in lines) {
            if (line.isBlank()) continue
            Toast.makeText(context, line, duration).show()
        }
    }
}
