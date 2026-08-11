package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 필드 key를 바꿀 때 미분류 캐릭터의 상태변화 이력을 함께 옮길 것인가 (B-13).
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 셋이고, 셋 다 시험이 유일한 검출이다:**
 * ① *"이 세계관만 가리킬 때만 옮긴다"* — 무너지면 **A의 필드를 고쳤는데 B의 연표가 조용히
 *    어긋난다.** 키는 세계관 안에서만 유일해서 두 세계관이 같은 `성별`을 들 수 있고,
 *    이력에는 세계관이 없어 되짚을 근거도 남지 않는다. 사용자에게는 *"어느 날 갑자기 옛
 *    캐릭터의 상태변화가 아무 필드에도 안 걸린다"*로만 보인다.
 * ② *"단서가 없으면 건드리지 않는다"* — 모르는 것을 앱이 정하면 편의가 아니라 유실이다
 *    (B-128이 덮어쓰기를 열지 않은 것과 같은 이유). 그래도 **세어서 말한다** — 조용히
 *    넘기면 사용자는 자기 연표 일부가 옛 키에 남은 것을 영영 모른다.
 * ③ *"남의 세계관 것은 세지 않는다"* — [UnassignedHistoryScope.Skip.FOREIGN]까지 고지에
 *    담으면 A의 필드를 고친 사용자가 **자기 조작과 무관한 숫자**를 받는다. 그 숫자는 손댈
 *    수도 없는 것이라, 말하는 것이 도움이 아니라 소음이다(원칙 04).
 *
 * 표본은 미분류가 생기는 두 길을 다 세운다 — 사용자가 작품을 비워 둔 캐릭터(단서 없음)와
 * **작품 삭제(`onDelete = SET_NULL`)로 미분류가 된 캐릭터**(보관 값이 그 세계관을 가리킨다).
 * 둘째가 이 항목이 실제로 겨냥하는 쪽이다.
 */
class UnassignedHistoryScopeTest {

    private val universeA = 10L
    private val universeB = 20L

    // ── ① 이 세계관만 가리킬 때만 옮긴다 ──

    @Test
    fun `보관 값이 이 세계관만 가리키면 옮긴다`() {
        // 작품 삭제로 미분류가 된 캐릭터 — 값은 여전히 A의 정의를 가리킨다.
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(1L),
            attribution = mapOf(1L to setOf(universeA)),
            targetUniverseId = universeA
        )
        assertEquals(listOf(1L), plan.migrate)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `다른 세계관만 가리키면 옮기지 않는다`() {
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(2L),
            attribution = mapOf(2L to setOf(universeB)),
            targetUniverseId = universeA
        )
        assertTrue(plan.migrate.isEmpty())
        assertEquals(UnassignedHistoryScope.Skip.FOREIGN, plan.skipped[2L])
    }

    @Test
    fun `두 세계관을 거친 캐릭터는 갈리지 않으므로 옮기지 않는다`() {
        // A의 작품 → B의 작품으로 옮겨 다니며 양쪽 보관 값을 든 캐릭터. 이력의 '성별'이
        // 어느 쪽 것인지 알 방법이 없다 — 옮기면 절반이 틀린다.
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(3L),
            attribution = mapOf(3L to setOf(universeA, universeB)),
            targetUniverseId = universeA
        )
        assertTrue(plan.migrate.isEmpty())
        assertEquals(UnassignedHistoryScope.Skip.AMBIGUOUS, plan.skipped[3L])
    }

    // ── ② 단서가 없으면 건드리지 않되, 세어서 말한다 ──

    @Test
    fun `단서가 아예 없으면 옮기지 않는다`() {
        // 처음부터 미분류로 만들어져 전역 구역 필드에만 이력을 쓴 캐릭터.
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(4L),
            attribution = emptyMap(),
            targetUniverseId = universeA
        )
        assertTrue(plan.migrate.isEmpty())
        assertEquals(UnassignedHistoryScope.Skip.UNATTRIBUTED, plan.skipped[4L])
    }

    @Test
    fun `전역 구역만 가리키는 것은 단서가 아니다`() {
        // 질의가 `universeId IS NULL`을 빼므로 이 캐릭터의 귀속은 빈 집합으로 들어온다.
        // 그것을 '단서 있음'으로 세면 모든 미분류 캐릭터가 잘못 분류된다.
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(5L),
            attribution = mapOf(5L to emptySet()),
            targetUniverseId = universeA
        )
        assertTrue(plan.migrate.isEmpty())
        assertEquals(UnassignedHistoryScope.Skip.UNATTRIBUTED, plan.skipped[5L])
    }

    @Test
    fun `가리지 못한 것은 고지 대상이다`() {
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(3L, 4L),
            attribution = mapOf(3L to setOf(universeA, universeB)),
            targetUniverseId = universeA
        )
        assertEquals(listOf(3L, 4L), plan.reportable)
    }

    // ── ③ 남의 세계관 것은 세지 않는다 ──

    @Test
    fun `남의 세계관 이력은 고지에서 빠진다`() {
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(2L, 3L),
            attribution = mapOf(2L to setOf(universeB), 3L to setOf(universeA, universeB)),
            targetUniverseId = universeA
        )
        // 2번(FOREIGN)은 손댈 것이 아니므로 말하지 않는다. 3번(AMBIGUOUS)만 남는다.
        assertEquals(listOf(3L), plan.reportable)
    }

    @Test
    fun `가릴 것이 하나도 없으면 고지도 없다`() {
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(1L, 2L),
            attribution = mapOf(1L to setOf(universeA), 2L to setOf(universeB)),
            targetUniverseId = universeA
        )
        assertEquals(listOf(1L), plan.migrate)
        // 정상 사용에 잔소리를 달지 않는다 — 옮길 것은 옮겼고 나머지는 남의 것이다.
        assertTrue(plan.reportable.isEmpty())
    }

    // ── 나머지 계약 ──

    @Test
    fun `후보가 없으면 계획도 비어 있다`() {
        val plan = UnassignedHistoryScope.plan(emptyList(), emptyMap(), universeA)
        assertTrue(plan.migrate.isEmpty())
        assertTrue(plan.skipped.isEmpty())
        assertTrue(plan.reportable.isEmpty())
    }

    @Test
    fun `네 갈래가 한 입력에서 서로 섞이지 않는다`() {
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(1L, 2L, 3L, 4L),
            attribution = mapOf(
                1L to setOf(universeA),              // 옮긴다
                2L to setOf(universeB),              // 남의 것
                3L to setOf(universeA, universeB),   // 갈리지 않는다
                4L to emptySet()                     // 단서 없음
            ),
            targetUniverseId = universeA
        )
        assertEquals(listOf(1L), plan.migrate)
        assertEquals(
            mapOf(
                2L to UnassignedHistoryScope.Skip.FOREIGN,
                3L to UnassignedHistoryScope.Skip.AMBIGUOUS,
                4L to UnassignedHistoryScope.Skip.UNATTRIBUTED
            ),
            plan.skipped
        )
        assertEquals(listOf(3L, 4L), plan.reportable)
    }

    @Test
    fun `입력 순서를 보존한다`() {
        // 고지가 세는 대상과 실제로 이관되는 대상이 같은 차례여야 다음 사람이 대조할 수 있다.
        val plan = UnassignedHistoryScope.plan(
            candidates = listOf(9L, 7L, 8L),
            attribution = mapOf(9L to setOf(universeA), 7L to setOf(universeA), 8L to setOf(universeA)),
            targetUniverseId = universeA
        )
        assertEquals(listOf(9L, 7L, 8L), plan.migrate)
    }
}
