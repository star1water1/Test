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
}
