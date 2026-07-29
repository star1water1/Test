package com.novelcharacter.app.util

import android.content.Context
import com.novelcharacter.app.R

/**
 * 소속 기간을 사람이 읽는 말로 — 세력 화면과 캐릭터 화면이 **같은 표기**를 쓰게 하는 자리.
 *
 * 규칙 판정은 [MembershipTimeline]이 하고, 그 결과를 **어떻게 적는가**는 여기서 정한다.
 * 두 화면이 각자 조립하면 한쪽만 고쳐져 같은 소속이 화면마다 다르게 보인다.
 *
 * @return 적을 시점이 하나도 없으면 null — 부르는 쪽이 '아예 안 쓸지, 시점 불명이라 쓸지' 정한다.
 */
fun Context.factionSpanLabel(joinYear: Int?, leaveYear: Int?): String? = when {
    joinYear != null && leaveYear != null -> getString(R.string.faction_span_range, joinYear, leaveYear)
    joinYear != null -> getString(R.string.faction_span_open_end, joinYear)
    leaveYear != null -> getString(R.string.faction_span_unknown_start, leaveYear)
    else -> null
}
