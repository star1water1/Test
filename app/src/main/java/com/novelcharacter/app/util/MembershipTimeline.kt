package com.novelcharacter.app.util

/**
 * 소속 이력의 **시간 규칙** — 새로 만드는 소속이 이전 소속과 모순되지 않는지 판정한다.
 *
 * 왜 순수 객체인가: 같은 재가입에 문이 둘이다(탈퇴 멤버의 '다시 소속시키기', 그리고
 * `[멤버 추가]`에서 탈퇴자를 다시 고르는 것). 규칙을 화면마다 쓰면 반드시 갈라지고,
 * 갈라진 쪽이 모순된 이력을 통과시킨다 — 이 저장소가 반복해서 값비싸게 배운 것이다.
 *
 * 판정 기준은 [com.novelcharacter.app.data.model.FactionMembership.isActiveAtYear]의
 * 활성 구간(`joinYear <= year < leaveYear`)이다. 두 소속의 구간이 겹치면 같은 해에
 * **나가 있으면서 동시에 들어와 있는** 상태가 된다.
 */
object MembershipTimeline {

    sealed interface JoinYearVerdict {
        /** 이전 이력과 모순되지 않는다. */
        object Ok : JoinYearVerdict

        /**
         * 이전 탈퇴 시점을 **아는데** 새 가입 연도를 비웠다.
         * 빈 값은 '시점 불명 = 처음부터'로 읽히므로 그 탈퇴 구간과 통째로 겹친다.
         */
        data class Required(val previousLeaveYear: Int) : JoinYearVerdict

        /** 새 가입이 이전 탈퇴보다 앞이다. */
        data class BeforePreviousLeave(val joinYear: Int, val previousLeaveYear: Int) : JoinYearVerdict
    }

    /**
     * 이전 소속들이 끝난 **가장 늦은** 시점. 전부 모르면 null.
     *
     * 가장 늦은 것을 쓰는 이유: 탈퇴가 여러 번이면 사용자가 그중 옛 줄을 눌러 재가입할 수
     * 있는데, 새 소속은 **마지막 탈퇴 뒤**여야 어느 구간과도 겹치지 않는다.
     */
    fun latestKnownLeaveYear(leaveYears: List<Int?>): Int? = leaveYears.filterNotNull().maxOrNull()

    /**
     * 새 소속의 가입 연도 판정.
     *
     * **같은 해 재가입은 허용한다** — 활성 판정이 `year < leaveYear`라 탈퇴 연도 당해에는
     * 이미 나가 있다. 그 해부터 새 소속이 시작하면 겹치는 해가 없다.
     */
    fun validateJoinYear(joinYear: Int?, previousLeaveYear: Int?): JoinYearVerdict {
        // 비교할 것이 없으면 규칙도 없다 — 시점을 모르는 소속에 시점을 강요하지 않는다.
        if (previousLeaveYear == null) return JoinYearVerdict.Ok
        if (joinYear == null) return JoinYearVerdict.Required(previousLeaveYear)
        if (joinYear < previousLeaveYear) {
            return JoinYearVerdict.BeforePreviousLeave(joinYear, previousLeaveYear)
        }
        return JoinYearVerdict.Ok
    }

    // ── 이미 있는 소속을 **고칠** 때의 규칙 ──
    // 만들 때(위)는 '이전 탈퇴 뒤인가' 하나면 되지만, 고칠 때는 앞뒤 양쪽에 다른 소속이
    // 있을 수 있어 구간 겹침으로 봐야 한다.

    /**
     * 한 소속이 차지하는 기간. 활성 판정(`joinYear <= year < leaveYear`)과 같은 반열림 구간이며,
     * [joinYear]가 null이면 처음부터, [leaveYear]가 null이면 끝없이 이어진다.
     */
    data class Span(val joinYear: Int?, val leaveYear: Int?)

    sealed interface SpanVerdict {
        object Ok : SpanVerdict
        /** 가입이 탈퇴와 같거나 뒤다 — 하루도 소속되지 않은 구간이 된다. */
        data class JoinNotBeforeLeave(val joinYear: Int, val leaveYear: Int) : SpanVerdict
        /** 같은 캐릭터의 다른 소속과 겹친다. */
        data class OverlapsOther(val other: Span) : SpanVerdict
    }

    /** 두 구간이 **한 해라도** 겹치는가. null은 각각 -∞ / +∞로 읽는다. */
    fun overlaps(a: Span, b: Span): Boolean {
        val lo = when {
            a.joinYear == null || b.joinYear == null -> a.joinYear ?: b.joinYear  // 한쪽이 -∞면 다른 쪽
            else -> maxOf(a.joinYear, b.joinYear)
        }
        val hi = when {
            a.leaveYear == null || b.leaveYear == null -> a.leaveYear ?: b.leaveYear
            else -> minOf(a.leaveYear, b.leaveYear)
        }
        // lo가 -∞이거나 hi가 +∞면 반드시 겹친다(둘 다 null인 경우 포함)
        if (lo == null || hi == null) return true
        return lo < hi
    }

    /**
     * 고친 구간이 성립하는지, 그리고 같은 캐릭터의 [others]와 겹치지 않는지.
     * [others]에는 **고치는 당사자를 빼고** 넘길 것 — 자기 자신과는 당연히 겹친다.
     */
    fun validateSpan(target: Span, others: List<Span>): SpanVerdict {
        val j = target.joinYear
        val l = target.leaveYear
        if (j != null && l != null && j >= l) return SpanVerdict.JoinNotBeforeLeave(j, l)
        val hit = others.firstOrNull { overlaps(target, it) }
        return if (hit != null) SpanVerdict.OverlapsOther(hit) else SpanVerdict.Ok
    }
}
