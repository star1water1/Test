package com.novelcharacter.app.util

import com.novelcharacter.app.util.MembershipTimeline.JoinYearVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 소속 이력의 시간 규칙 — 사용자 보고에서 나왔다.
 * "재소속이 전 소속 연도보다 앞이어도 적용된다" (2026.07.29)
 */
class MembershipTimelineTest {

    // ── latestKnownLeaveYear ──

    @Test fun latestLeave_isNullWhenNothingKnown() {
        assertEquals(null, MembershipTimeline.latestKnownLeaveYear(emptyList()))
        assertEquals(null, MembershipTimeline.latestKnownLeaveYear(listOf(null, null)))
    }

    @Test fun latestLeave_ignoresUnknownAndTakesLatest() {
        assertEquals(1500, MembershipTimeline.latestKnownLeaveYear(listOf(1200, null, 1500, 900)))
    }

    @Test fun latestLeave_handlesNegativeYears() {
        // 기원전 연도를 음수로 쓰는 작품이 있다 — 최댓값 규칙이 그대로 성립해야 한다
        assertEquals(-100, MembershipTimeline.latestKnownLeaveYear(listOf(-500, -100, -300)))
    }

    // ── validateJoinYear ──

    @Test fun noPreviousLeave_acceptsAnything() {
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(1000, null))
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(null, null))
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(-999, null))
    }

    @Test fun emptyJoinYear_isRequiredWhenPreviousLeaveKnown() {
        // 빈 값은 '처음부터'로 읽혀 이전 탈퇴 구간과 통째로 겹친다
        assertEquals(
            JoinYearVerdict.Required(1500),
            MembershipTimeline.validateJoinYear(null, 1500)
        )
    }

    @Test fun joinBeforePreviousLeave_isRejected() {
        // 사용자가 보고한 바로 그 경우
        assertEquals(
            JoinYearVerdict.BeforePreviousLeave(800, 1500),
            MembershipTimeline.validateJoinYear(800, 1500)
        )
    }

    @Test fun joinOnTheLeaveYear_isAccepted() {
        // 활성 판정이 year < leaveYear라 탈퇴 당해에는 이미 나가 있다 — 겹치지 않는다
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(1500, 1500))
    }

    @Test fun joinAfterPreviousLeave_isAccepted() {
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(1600, 1500))
    }

    @Test fun negativeYears_compareByOrderNotMagnitude() {
        // -800년은 -500년보다 앞이다 — 절댓값으로 비교하면 뒤집힌다
        assertEquals(
            JoinYearVerdict.BeforePreviousLeave(-800, -500),
            MembershipTimeline.validateJoinYear(-800, -500)
        )
        assertEquals(JoinYearVerdict.Ok, MembershipTimeline.validateJoinYear(-400, -500))
    }

    @Test fun verdictCarriesNumbersForTheMessage() {
        // 문구가 두 숫자를 그대로 실어야 하므로 판정이 값을 들고 다닌다
        val v = MembershipTimeline.validateJoinYear(3, 77) as JoinYearVerdict.BeforePreviousLeave
        assertEquals(3, v.joinYear)
        assertEquals(77, v.previousLeaveYear)
    }
}
