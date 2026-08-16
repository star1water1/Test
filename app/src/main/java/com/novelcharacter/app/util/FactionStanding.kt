package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FactionMembership

/**
 * **'지금 소속'의 단일 소스** (B-171).
 *
 * 대결 축에 세력을 걸 수 있게 하려면 *"이 캐릭터는 지금 어느 세력인가"*에 답해야 하는데,
 * 그 답은 표에 적혀 있지 않고 **파생된다** — `faction_memberships`에는 가입·탈퇴 시점과
 * [FactionMembership.leaveType]이 있고, 어느 조합이 *지금*인지는 규칙이다.
 *
 * ## 왜 새로 세우는가 — 규칙이 이미 여섯 자리에 흩어져 있었다
 * 착수 시점에 이 판정은 DAO의 SQL과 코틀린 `filter`에 각자 적혀 있었다(통계 · 세력 관리 ·
 * 캐릭터 AI 문맥 · 엑셀 가져오기). 대결 카드가 그것을 **일곱째로** 다시 적으면 두 화면이
 * 다른 답을 내는 것이 시간 문제다 — 원칙 05가 막으려는 모양이고, B-117이
 * *"저장하지 않아도 각자 계산하면 갈라진다"*로 배운 것과 같은 부류다.
 * **자리 수를 여기 적지 않는 것은 일부러다** — `tools/check_faction_standing.sh`가 세는 법이고,
 * 적으면 다음 소비처에서 낡는다.
 *
 * ## [isCurrent]와 [FactionMembership.isActiveAtYear]는 **다른 물음이다**
 *
 * | | 묻는 것 | 재료 | 쓰는 곳 |
 * |---|---|---|---|
 * | [isCurrent] | *"지금 소속인가"* | [FactionMembership.leaveType] | 대결·통계·세력 관리·AI 문맥 |
 * | [FactionMembership.isActiveAtYear] | *"그 해에 소속이었나"* | `joinYear`·`leaveYear` | 연표·관계 그래프의 시점 슬라이더 |
 *
 * **대결에는 연도 축이 없다**(카드에 시점을 고르는 자리가 없고, 축은 *지금* 누가 센가를
 * 겨룬다). 그래서 이쪽은 연도를 묻지 않고 **이력의 상태 표식**만 본다 — 그것이 이 앱이
 * 착수 이전부터 *"현재 활성 중인 멤버"*로 세어 온 뜻 그대로이며, 이 파일은 그 뜻을 옮겨
 * 적을 뿐 바꾸지 않는다(같은 커밋에서 통계·세력 관리의 수가 한 건도 달라지지 않는다).
 *
 * ⚠️ **`leaveYear`가 있는데 `leaveType`이 없는 행은 여기서 *지금 소속*이다** — 그 판정은
 * 그대로 두었다(B-206, 확정 15장 7번 = ⓒ). 앱 안에서는 그 조합이 만들어지지 않지만
 * (`departMember`가 둘을 함께 적고 `removeMember`는 행을 지운다) **엑셀은 두 칸이 따로라
 * 사람이 한쪽만 적을 수 있다.** 판정 기준을 바꾸면 **이미 DB에 있는 행들의 통계·AI 문맥
 * 수가 조용히 달라지므로**, 고치는 자리를 *읽는 쪽*이 아니라 **들어오는 자리**로 옮겼다 —
 * [leaveTypeForImportedRow]가 가져오기 시점에 그 파일의 행만 바로잡고 경고로 말한다.
 *
 * ## SQL 쌍둥이
 * Room 질의는 코틀린 함수를 부를 수 없어 같은 술어가 `FactionMembershipDao`에 글자로 한 벌 더
 * 산다([SQL_CURRENT]와 같은 글자여야 한다). **그 한 벌은 기계가 지킨다** —
 * `tools/check_faction_standing.sh`가 둘이 갈리는 순간 실패한다. 주석으로만 묶어 두면
 * 다음 사람이 SQL 쪽에 `leaveYear IS NULL`을 보태고 그것이 조용히 다른 답을 낸다.
 */
object FactionStanding {

    /**
     * DAO가 같은 판정을 SQL로 적을 때 쓰는 술어 — **글자 그대로 하나여야 한다.**
     *
     * 상수를 Room `@Query`에 끼워 넣지 않는 것은 애노테이션 인자가 컴파일 시각 상수라
     * 그 자리에서 문자열을 이어 붙이면 Room의 질의 검증이 무엇을 보는지 보장할 수 없기
     * 때문이다. **그래서 코드가 아니라 검사로 묶는다**(위 KDoc의 마지막 문단).
     */
    const val SQL_CURRENT = "leaveType IS NULL"

    /**
     * 이 상태 표식이 *지금 소속*인가.
     *
     * 값을 받는 갈래를 따로 두는 것은 **아직 엔티티가 아닌 자리**를 위해서다 — 엑셀
     * 가져오기는 칸을 읽어 `leaveType`을 손에 쥔 채 *"이 소속은 활성인가"*를 묻는다.
     * 그 자리가 `== null`을 직접 적으면 판정이 다시 둘이 된다.
     */
    fun isCurrent(leaveType: String?): Boolean = leaveType == null

    /** 이 소속이 *지금*인가. */
    fun isCurrent(membership: FactionMembership): Boolean = isCurrent(membership.leaveType)

    /**
     * 엑셀이 만든 **반쪽 표식**을 들어오는 자리에서 바로잡는다 (B-206 · 확정 15장 7번 ⓒ).
     *
     * '세력 소속' 시트는 `탈퇴연도`와 `탈퇴유형`이 **따로 있는 칸**이라, 사람이 연도만 적고
     * 유형을 비워 둘 수 있다. 그 행은 [isCurrent]에게는 *지금 소속*이고
     * [FactionMembership.isActiveAtYear]에게는 *그 해 이후로는 아니다*라 — 둘은 다른 물음이라
     * 모순은 아니지만, **사용자가 적어 둔 `1200년 탈퇴`가 대결·통계·세력 관리에서 아무 뜻이
     * 없다.** 적은 것이 없던 일이 되는 자리다.
     *
     * 그래서 **연도만 온 행은 '설정상 탈퇴'로 읽는다.** 부르는 쪽은 이 교정을 경고로 말해야
     * 한다(개발 의도 2번 — 검증 → 알림 → 바로잡을 경로). 값을 지우는 방향이 아니라
     * 채우는 방향이라 유실은 없다.
     *
     * **소급하지 않는다** — 이 함수는 *파일에서 읽은 행*에만 쓴다. 이미 DB에 든 모순 행은
     * 손대지 않는다(손대려면 그때 따로 묻는다 — 확정의 착수 조건 ⓐ).
     *
     * @param leaveTypeColumnPresent '탈퇴유형' **열 자체가 있는가.** 없으면 사용자가 칸을
     *   비운 것이 아니라 *말하지 않은 것*이라 채우지 않는다(F1-A — 열 없음은 기존 유지).
     */
    fun leaveTypeForImportedRow(
        leaveYear: Int?,
        leaveType: String?,
        leaveTypeColumnPresent: Boolean
    ): String? =
        if (leaveTypeColumnPresent && leaveType == null && leaveYear != null) {
            FactionMembership.LEAVE_DEPARTED
        } else {
            leaveType
        }

    /** *지금*인 소속만 — **차례는 넘긴 그대로다**(부르는 쪽의 정렬을 뒤집지 않는다). */
    fun current(memberships: List<FactionMembership>): List<FactionMembership> =
        memberships.filter { isCurrent(it) }

    /** *지금*인 소속이 하나라도 있는가 — 재가입한 캐릭터는 옛 탈퇴 행도 함께 갖는다. */
    fun hasCurrent(memberships: List<FactionMembership>): Boolean =
        memberships.any { isCurrent(it) }

    /** *지금*인 소속의 수. */
    fun countCurrent(memberships: List<FactionMembership>): Int =
        memberships.count { isCurrent(it) }

    /**
     * *지금* 속한 세력의 id — **중복은 걷어내고 차례는 지킨다.**
     *
     * 겸직·복수 소속이 여기서 여럿으로 나온다(그것이 정상이다). 같은 세력에 재가입 이력이
     * 여러 줄이어도 세력은 하나이므로 [distinct]가 필요하다 — 그러지 않으면 카드가 같은
     * 세력 이름을 두 번 띄운다.
     */
    fun currentFactionIds(memberships: List<FactionMembership>): List<Long> =
        current(memberships).map { it.factionId }.distinct()
}
