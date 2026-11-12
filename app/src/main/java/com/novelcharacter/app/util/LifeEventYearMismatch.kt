package com.novelcharacter.app.util

/**
 * 출생·사망 **사건**의 연도와 캐릭터의 `__birth`·`__death` 이력 연도가 가져오기 뒤에도 갈린 경우의 고지 (⑩).
 *
 * 연표 시트는 사건 연도를 이력에 쓰지만(사건이 진실 — 인앱 `EventStateChangeSync`와 같은 규칙),
 * 뒤에 도는 상태 변화 시트와 연도 필드 동기화가 다른 연도로 되돌릴 수 있다. 그러면 사건과 이력이
 * 서로 다른 연도를 든 채 남고, 다음 가져오기에서도 같은 되돌림이 반복된다. 어느 쪽이 맞는지는
 * 사용자만 알므로 가져오기는 고치지 않고 **말한다.**
 */
object LifeEventYearMismatch {

    /** 설명이 길면 고지 한 줄이 넘치므로 자른다. */
    private const val DESCRIPTION_LIMIT = 30

    fun message(
        characterName: String,
        isBirth: Boolean,
        eventDescription: String,
        eventYear: Int,
        stateYear: Int
    ): String {
        val kind = if (isBirth) "출생" else "사망"
        val desc = eventDescription.trim().let {
            if (it.length > DESCRIPTION_LIMIT) it.take(DESCRIPTION_LIMIT) + "…" else it
        }
        val eventLabel = if (desc.isEmpty()) "$kind 사건" else "$kind 사건 '$desc'"
        return "캐릭터 '$characterName': ${eventLabel}의 연도($eventYear)와 $kind 연도($stateYear)가 다릅니다 — " +
            "파일의 '상태 변화' 시트나 ${kind}연도 값이 다른 연도를 말해 사건은 그대로 두었습니다. " +
            "어느 쪽이 맞는지 확인해 한쪽을 고친 뒤 다시 가져오세요"
    }
}
