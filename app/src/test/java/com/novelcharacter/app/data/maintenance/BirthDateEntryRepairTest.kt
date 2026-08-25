package com.novelcharacter.app.data.maintenance

import com.novelcharacter.app.data.maintenance.BirthDateEntryRepair.Action
import com.novelcharacter.app.data.maintenance.BirthDateEntryRepair.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BirthDateEntryRepair] — **값 라이브러리에 남은 규격 밖 생일 표기의 처분**.
 *
 * 실측이 세운 계약이다(2026.08.25 사용자가 내보낸 파일, '스텔라 크로니클'):
 * 캐릭터는 `05-30`·`06-07`을 쓰는데 라이브러리에는 그 행이 없고 `5-30`·`6-7`이
 * 사용횟수 0으로 남아 있었다 — ③이 캐릭터 값만 올리고 짝을 두고 간 자리다.
 */
class BirthDateEntryRepairTest {

    private fun entry(id: Long, value: String, vararg aliases: String) =
        Entry(id, value, aliases.toList())

    // ── 실측이 낸 그 모양 ──

    @Test
    fun `저장 모양의 행이 없으면 값을 올리고 옛 표기를 별칭으로 남긴다`() {
        val plan = BirthDateEntryRepair.plan(listOf(entry(7, "5-30")))
        assertEquals(listOf(Action.Rename(7, "05-30", listOf("5-30"))), plan)
    }

    @Test
    fun `실측 세 행이 전부 올라간다`() {
        // 파일에 있던 `4-28`·`5-30`·`6-7` — 사용 여부와 무관하게 표기는 하나여야 한다.
        val plan = BirthDateEntryRepair.plan(
            listOf(entry(1, "4-28"), entry(2, "5-30"), entry(3, "6-7"))
        )
        assertEquals(
            listOf(
                Action.Rename(1, "04-28", listOf("4-28")),
                Action.Rename(2, "05-30", listOf("5-30")),
                Action.Rename(3, "06-07", listOf("6-7"))
            ),
            plan
        )
    }

    // ── 병합: 유니크 색인이 두 행에 같은 값을 허락하지 않는다 ──

    @Test
    fun `저장 모양의 행이 이미 있으면 그쪽으로 접는다`() {
        val plan = BirthDateEntryRepair.plan(
            listOf(entry(3, "05-30"), entry(9, "5-30", "오오오"))
        )
        // 대상은 이미 저장 모양인 3번. 9번의 정체와 별칭이 전부 그리로 간다.
        assertEquals(listOf(Action.Merge(9, 3, listOf("오오오", "5-30"))), plan)
    }

    @Test
    fun `같은 저장 모양으로 접히는 행이 둘이면 먼저 온 쪽이 대상이 된다`() {
        val plan = BirthDateEntryRepair.plan(
            listOf(entry(2, "5-30"), entry(5, "2026-05-30"))
        )
        assertEquals(
            listOf(
                Action.Rename(2, "05-30", listOf("5-30")),
                Action.Merge(5, 2, listOf("5-30", "2026-05-30"))
            ),
            plan
        )
    }

    // ── 건드리지 않는 것 ──

    @Test
    fun `이미 저장 모양이면 아무것도 하지 않는다`() {
        assertTrue(BirthDateEntryRepair.plan(listOf(entry(1, "05-30"), entry(2, "12-01"))).isEmpty())
    }

    @Test
    fun `읽을 수 없는 값은 건드리지 않는다`() {
        // 사용자가 적어 둔 것을 우리가 못 읽는다고 해서 바꾸지 않는다(개발 의도 2번).
        val plan = BirthDateEntryRepair.plan(
            listOf(entry(1, "봄쯤"), entry(2, "13-40"), entry(3, ""))
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `별칭에 정규값이나 빈 글자를 남기지 않는다`() {
        val plan = BirthDateEntryRepair.plan(listOf(entry(4, "5-30", "05-30", "  ", "5-30")))
        assertEquals(listOf(Action.Rename(4, "05-30", listOf("5-30"))), plan)
    }

    // ── 멱등 ──

    @Test
    fun `계획을 적용한 뒤 다시 세우면 빈 목록이다`() {
        val before = listOf(entry(2, "5-30"), entry(5, "2026-05-30"), entry(8, "12-01"))
        val after = apply(before, BirthDateEntryRepair.plan(before))
        assertEquals(listOf("05-30", "12-01"), after.map { it.value })
        assertTrue(BirthDateEntryRepair.plan(after).isEmpty())
    }

    @Test
    fun `한 값이 두 행에 남지 않는다`() {
        // `(fieldDefinitionId, value)`가 유니크라, 계획이 같은 값을 둘로 만들면 쓰기가 죽는다.
        val before = listOf(entry(1, "6-7"), entry(2, "06-07"), entry(3, "2020-06-07"))
        val after = apply(before, BirthDateEntryRepair.plan(before))
        assertEquals(after.map { it.value }.distinct().size, after.size)
    }

    /** 계획을 실제로 적용한다 — [LegacyValueFormats]의 쓰기와 같은 처분이어야 한다. */
    private fun apply(entries: List<Entry>, actions: List<Action>): List<Entry> {
        val rows = entries.associateBy { it.id }.toMutableMap()
        for (action in actions) {
            when (action) {
                is Action.Rename -> rows[action.id] =
                    rows.getValue(action.id).copy(value = action.newValue, aliases = action.aliases)
                is Action.Merge -> {
                    rows[action.targetId] =
                        rows.getValue(action.targetId).copy(aliases = action.targetAliases)
                    rows.remove(action.sourceId)
                }
            }
        }
        return rows.values.sortedBy { it.id }
    }
}
