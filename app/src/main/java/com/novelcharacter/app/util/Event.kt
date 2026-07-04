package com.novelcharacter.app.util

/**
 * SingleLiveEvent 래퍼 — 값을 한 번만 소비하게 해 회전/재관측 시 중복 처리(중복 토스트 등)를 막는다.
 *
 * 기존에 UniverseViewModel·GlobalSearchViewModel에 중복 정의돼 있던 것을 top-level로 승격했다.
 */
class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    /** 아직 소비되지 않았으면 내용을 반환하고 소비 처리, 이미 소비됐으면 null. */
    fun getContentIfNotHandled(): T? =
        if (hasBeenHandled) null else { hasBeenHandled = true; content }

    /** 소비 여부와 무관하게 내용을 열람(로깅 등). */
    fun peekContent(): T = content
}
