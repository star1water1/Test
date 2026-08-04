package com.novelcharacter.app.util

/**
 * **"대결을 어디로 열 것인가"의 단일 소스** — 홈 진입점의 판단 (B-104 화면 계층).
 *
 * 대결은 이 앱의 도구 중 유일하게 **반복 활동**이다. 다른 도구는 "열어서 본다"이지만 대결은
 * "이어서 한다"라, 진입점이 **재개**를 못 하면 매번 세계관 → 축 → 대결로 세 걸음을 다시 밟는다
 * (원칙 04). 그래서 마지막에 연 축을 기억했다가 한 번에 그 자리로 돌려보낸다.
 *
 * **판단을 여기 두는 이유는 언제나 같다** — 「세션 착수 규칙」 4번(자동 검증은 화면을 못 본다).
 * 이 판단이 틀리면 나는 증상은 *"눌렀더니 엉뚱한 축이 열린다"* 또는 *"지운 축을 열려고 한다"*인데,
 * 둘 다 컴파일도 통과하고 정적 검사도 통과한다.
 */
object DuelEntry {

    /** 홈에서 대결을 눌렀을 때 갈 곳. */
    sealed class Target {
        /** 마지막에 하던 축이 살아 있다 — 곧바로 대결 화면으로. */
        data class Resume(val axisId: Long, val universeId: Long) : Target()

        /** 이어할 축이 없고 세계관이 하나뿐이다 — 고르게 하지 않고 그 세계관의 축 목록으로. */
        data class AxisList(val universeId: Long) : Target()

        /** 세계관이 여럿이라 어느 것인지 물어야 한다. */
        data class PickUniverse(val universeIds: List<Long>) : Target()

        /** 세계관이 하나도 없다 — 대결 이전에 만들 것이 있다. */
        object NeedUniverse : Target()
    }

    /**
     * @param lastAxisId 마지막에 연 축. 0 이하면 기억이 없다는 뜻이다.
     * @param lastAxisUniverseId 그 축이 매달린 세계관. **축이 지워졌으면 null을 넘길 것** —
     *   그 판정은 저장소가 하고(조회해 보면 안다) 이 함수는 결과만 받는다.
     * @param universeIds 지금 있는 세계관. 순서는 화면이 보여 줄 순서 그대로.
     */
    fun targetOf(
        lastAxisId: Long,
        lastAxisUniverseId: Long?,
        universeIds: List<Long>
    ): Target {
        // 지운 축의 id가 남아 있을 수 있다(휴지통으로 갔거나 세계관째 사라졌거나).
        // 그때 그 id로 화면을 열면 빈 화면이 뜨므로, **살아 있음이 확인된 경우에만** 재개한다.
        if (lastAxisId > 0L && lastAxisUniverseId != null) {
            return Target.Resume(lastAxisId, lastAxisUniverseId)
        }
        return when (universeIds.size) {
            0 -> Target.NeedUniverse
            1 -> Target.AxisList(universeIds.first())
            else -> Target.PickUniverse(universeIds)
        }
    }
}
