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

    // ── overlaps / validateSpan (탈퇴 기록 편집) ──

    private fun span(j: Int?, l: Int?) = MembershipTimeline.Span(j, l)

    @Test fun adjacentSpansDoNotOverlap() {
        // [1000,1500) 과 [1500,1600) — 1500년에는 앞 소속이 이미 끝났다
        assertEquals(false, MembershipTimeline.overlaps(span(1000, 1500), span(1500, 1600)))
    }

    @Test fun oneYearOfSharingCountsAsOverlap() {
        assertEquals(true, MembershipTimeline.overlaps(span(1000, 1501), span(1500, 1600)))
    }

    @Test fun openEndedSpansSwallowEverything() {
        // 탈퇴하지 않은 소속은 +∞까지 간다
        assertEquals(true, MembershipTimeline.overlaps(span(1000, null), span(9999, null)))
        // 가입 시점을 모르면 -∞부터다
        assertEquals(true, MembershipTimeline.overlaps(span(null, 1000), span(1, 5)))
        assertEquals(true, MembershipTimeline.overlaps(span(null, null), span(1, 2)))
    }

    @Test fun openEndedStillRespectsTheBoundary() {
        // [.., 1000) 과 [1000, ..) 은 맞닿기만 한다
        assertEquals(false, MembershipTimeline.overlaps(span(null, 1000), span(1000, null)))
    }

    @Test fun joinOnOrAfterLeave_isRejected() {
        assertEquals(
            MembershipTimeline.SpanVerdict.JoinNotBeforeLeave(1500, 1500),
            MembershipTimeline.validateSpan(span(1500, 1500), emptyList())
        )
        assertEquals(
            MembershipTimeline.SpanVerdict.JoinNotBeforeLeave(1600, 1500),
            MembershipTimeline.validateSpan(span(1600, 1500), emptyList())
        )
    }

    @Test fun spanIsOkWhenItFitsBetweenOthers() {
        val others = listOf(span(1000, 1200), span(1600, null))
        assertEquals(
            MembershipTimeline.SpanVerdict.Ok,
            MembershipTimeline.validateSpan(span(1200, 1600), others)
        )
    }

    @Test fun spanReportsWhichOtherItCollidesWith() {
        val others = listOf(span(1000, 1200), span(1600, null))
        val v = MembershipTimeline.validateSpan(span(1500, 1700), others)
            as MembershipTimeline.SpanVerdict.OverlapsOther
        assertEquals(span(1600, null), v.other)
    }

    @Test fun editingBackInTimeCollidesWithTheEarlierSpan() {
        // 탈퇴 기록을 앞으로 당기다 이전 소속을 침범하는 경우
        val others = listOf(span(1000, 1200))
        val v = MembershipTimeline.validateSpan(span(1100, 1300), others)
        assertEquals(MembershipTimeline.SpanVerdict.OverlapsOther(span(1000, 1200)), v)
    }

    @Test fun verdictCarriesNumbersForTheMessage() {
        // 문구가 두 숫자를 그대로 실어야 하므로 판정이 값을 들고 다닌다
        val v = MembershipTimeline.validateJoinYear(3, 77) as JoinYearVerdict.BeforePreviousLeave
        assertEquals(3, v.joinYear)
        assertEquals(77, v.previousLeaveYear)
    }
}
