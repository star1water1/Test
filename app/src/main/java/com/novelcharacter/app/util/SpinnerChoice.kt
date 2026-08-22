package com.novelcharacter.app.util

/**
 * 스피너가 **저장된 값을 표현할 수 있게** 만든다 (순수 로직 — JVM 시험으로 검증).
 *
 * ## 무엇을 막는가 — *위젯이 못 그린 것*이 *사용자가 지운 것*으로 읽히는 일
 *
 * 스피너의 항목은 대개 **지금 고를 수 있는 것들**로 채운다(세계관의 관계 유형 목록,
 * 이 작품의 사건 목록, 필드의 선택지). 그런데 저장된 값이 그 목록에 없을 수 있다 —
 * 엑셀로 들여온 유형, 목록에서 지운 선택지, 다른 작품의 사건.
 *
 * 그때 아무것도 안 하면 스피너는 안드로이드 기본값인 **0번**에 서고, 저장은 그 자리를
 * 그대로 읽는다. 그래서 **설명만 고치려고 창을 열었다 저장한 사용자가 관계 유형을 잃고**,
 * 사건 연결이 통째로 끊긴다. 고지도 없고, 목록 화면은 저장 전까지 원래 값을 보여 주므로
 * 바뀐 줄도 모른다.
 *
 * ## 처분 — 끼워 넣고, 고르는 것은 사용자가 한다
 *
 * 목록에 없으면 **저장된 값을 맨 뒤에 끼워 넣고 그 자리를 고른다.** 그러면
 * *"건드리지 않으면 그대로"*가 성립하고, 그 값을 유지할지 새 목록의 것으로 바꿀지는
 * 사용자가 스스로 고른다(자율성). 이 저장소가 동적 필드 폼에서 이미 쓰던 규칙이고,
 * 여기 한 자리로 모아 다른 스피너들도 같은 규칙을 쓰게 한다.
 */
object SpinnerChoice {

    /**
     * @param items 스피너에 실을 항목 — 저장된 값이 목록에 없었으면 **맨 뒤에 그것이 붙어 있다**
     * @param index 고를 자리. 목록이 비어 있고 저장된 값도 없으면 `-1`
     * @param appended 끼워 넣었는가 — 부르는 쪽이 그 사실을 사용자에게 알릴 수 있다
     */
    data class Resolved<T>(val items: List<T>, val index: Int, val appended: Boolean)

    /**
     * @param options 지금 고를 수 있는 것들(맨 앞의 '없음' 항목도 포함해서 넘긴다)
     * @param stored 저장된 값. `null`이면 첫 자리를 고른다(대개 '없음')
     */
    fun <T> resolve(options: List<T>, stored: T?): Resolved<T> {
        if (stored == null) {
            return Resolved(options, if (options.isEmpty()) -1 else 0, false)
        }
        val index = options.indexOf(stored)
        if (index >= 0) return Resolved(options, index, false)
        return Resolved(options + stored, options.size, true)
    }
}
