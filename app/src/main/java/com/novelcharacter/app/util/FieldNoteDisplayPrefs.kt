package com.novelcharacter.app.util

import android.content.Context

/**
 * **읽기 화면에도 필드 설명 ⓘ를 보일 것인가** (B-44 — 사용자 확정 17번 '옵션화' · P-8 '기본 꺼짐').
 *
 * ⓘ는 지금 **필드 편집·필드 관리·입력 폼** 셋에 있다. 설명은 *"무엇을 넣어야 하는가"*라
 * 입력 자리에서 가장 값지지만, 다 적어 둔 뒤 **읽을 때 그 정의를 다시 확인하고 싶은 사람**도
 * 있다 — 어느 쪽이 옳은지는 사용자가 가린다(원칙: 자율성 우선).
 *
 * **기본이 꺼짐인 것이 이 설정의 안전판이다.** 켜짐이면 이 항목 하나로 모든 캐릭터의 읽기
 * 화면에 아이콘이 새로 돋는다 — 설명을 써 둔 필드가 많은 사용자일수록 화면이 시끄러워지고,
 * 그것은 이 앱이 준 선택이 아니라 **앱이 마음대로 바꾼 화면**이다. 꺼짐이면 현행 화면이
 * 한 픽셀도 안 바뀌고 켜는 것이 사용자 선택이 된다.
 *
 * **앱 전체 설정인 것도 판단이다** — ⓘ의 다른 세 자리가 전부 앱 전체이고, 여기만 세계관
 * 단위로 두면 *"이 앱에서 설명을 어떻게 보는가"*가 두 축으로 갈린다(P-7이 AI 재료 범위를
 * 앱 전체로 정한 것과 같은 근거).
 *
 * **전용 파일을 쓰는 것은 R-28 때문이다** — 같은 prefs 파일의 같은 키를 두 화면이 다른
 * 타입으로 다루면 읽는 순간 죽는다.
 */
object FieldNoteDisplayPrefs {

    private const val PREFS_NAME = "field_display_prefs"
    private const val KEY_READ_SCREEN_NOTE = "read_field_note_enabled"

    /** P-8 확정 — 기본 꺼짐. 이 값을 바꾸면 옛 기기의 화면이 말없이 달라진다. */
    const val DEFAULT_ENABLED = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isReadScreenNoteEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_READ_SCREEN_NOTE, DEFAULT_ENABLED)

    fun setReadScreenNoteEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_READ_SCREEN_NOTE, enabled).apply()
    }
}
