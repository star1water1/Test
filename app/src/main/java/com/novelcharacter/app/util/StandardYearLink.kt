package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterStateChange

/**
 * 표준연도 ↔ 나이·출생연도 **연동의 계약** — 키·값과 *"이 캐릭터가 연동 중인가"*의 판정.
 *
 * ## 왜 [StandardYearSyncHelper]에서 갈라 나왔는가 (B-251 ⓓ · 2026.08.19)
 *
 * 종전에는 이 다섯 상수가 [StandardYearSyncHelper]의 companion에 살았다. 그 클래스는
 * `CharacterRepository`·`UniverseRepository`를 생성자로 받으므로 **Room 없이는 컴파일되지
 * 않는다.** 그래서 *순수한 상수를 쓰려는 순수한 코드*까지 그 불순함을 함께 짊어졌다 —
 * [ConsistencyChecker]가 정확히 그 자리였고, 그 탓에 **순수 하네스가 실을 수 없어
 * 시험이 0건**이었다.
 *
 * **선언(순수)과 배선(불순)을 가르는 것은 이 저장소의 기존 관행이다** —
 * `excel/AppSettingsKeys.kt`(순수, 하네스가 싣는다)와 `excel/AppSettingsBindings.kt`(불순,
 * 증명이 CI뿐이다)가 같은 모양으로 갈려 있다.
 *
 * ## 상수만이 아니라 *판정*을 담는 이유
 *
 * *"연동 해제란 무엇인가"*가 **두 자리에 따로 적혀 있었다** — [StandardYearSyncHelper.isLinked]는
 * `newValue != "none"`으로, [ConsistencyChecker]는 `newValue == "none"`으로. 둘이 지금은
 * 같은 뜻이지만 **한쪽만 고치면 어긋나고, 어긋난 사실을 아무도 모른다**(정합성 검사기가
 * 조용히 연동 해제 캐릭터를 모순으로 신고하게 된다). 그래서 판정을 여기 하나로 둔다.
 *
 * **값이 없으면 연동이다** — 사용자가 명시적으로 끈 적이 없으면 켜져 있는 것이 기본값이고,
 * 그 기본값은 [isUnlinked]의 `null` 처분 하나에만 적혀 있다.
 */
object StandardYearLink {

    /** 연동 여부를 담는 시스템 상태변화의 필드 키. */
    const val KEY = "__std_year_link"

    /** 연동 켬. */
    const val AUTO = "auto"

    /** 연동 끔 — 나이/출생연도가 표준연도와 어긋나도 의도된 것으로 본다. */
    const val NONE = "none"

    /** 표준연도가 바뀔 때 나이를 고정하고 출생연도를 옮긴다. */
    const val RULE_AGE_ANCHOR = "age_anchor"

    /** 표준연도가 바뀔 때 출생연도를 고정하고 나이를 옮긴다. */
    const val RULE_BIRTH_ANCHOR = "birth_anchor"

    /**
     * 상태변화 값 하나가 '연동 해제'를 뜻하는가.
     *
     * `null`(= 그런 상태변화가 없다)은 **연동으로 본다** — 끈 적이 없으면 켜져 있다.
     */
    fun isUnlinked(newValue: String?): Boolean = newValue == NONE

    /** [isUnlinked]의 반대. 읽는 쪽이 부정을 겹치지 않게 둘 다 둔다. */
    fun isLinked(newValue: String?): Boolean = !isUnlinked(newValue)

    /** 토글이 저장할 값. */
    fun valueFor(linked: Boolean): String = if (linked) AUTO else NONE

    /**
     * 한 캐릭터의 상태변화 목록에서 연동 해제 여부를 읽는다.
     *
     * 목록에 [KEY] 행이 없으면 `false`(= 연동 중)다.
     */
    fun isUnlinkedIn(changes: List<CharacterStateChange>): Boolean =
        isUnlinked(changes.find { it.fieldKey == KEY }?.newValue)
}
