package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 무소속 구역과 세계관 사이를 오갈 때 필드값을 이어 줄 것인가 (B-128, 2026.08.10 사용자 확정).
 *
 * **이 판의 방어선은 시험 수가 아니라 그 안의 넷이고, 넷 다 시험이 유일한 검출이다:**
 * ① *"대상에 값이 있으면 덮지 않는다"* — 이 판정의 본체다. 무너지면 이관이 **말없는 유실**이
 *    된다(개발 의도 2번). 덮어쓰기는 사용자가 되돌릴 방법이 없고, 무엇이 사라졌는지조차
 *    알 수 없다 — 옛 값이 있던 자리에 다른 값이 서 있을 뿐이다.
 * ② *"타입이 안 맞으면 옮기지 않는다"* — 밀어 넣으면 이관이 곧 오염이다. NUMBER 칸에 '스물'이
 *    들어앉으면 그 필드의 통계·정렬·수식이 전부 그 값에서 무너지는데, **코드는 멀쩡해 보인다.**
 * ③ *"짝짓기는 연결 표식 우선"* — 키만 보면 사용자가 한쪽 필드키를 고치는 순간 짝을 잃고
 *    값이 보관 값으로 굳는다. 두 그림자가 같은 템플릿에서 났다는 사실이 표식에 남아 있는데
 *    그것을 안 보는 것이라, 증상은 *"어떤 캐릭터만 이관이 안 된다"*로 나타난다.
 * ④ *"옮기지 못한 것도 유실이 아니다"* — 판정은 [GlobalScopeFieldMove.Plan.kept]로 사유를
 *    남기고, 부르는 쪽은 그것을 보관 값으로 둔다. 여기서 조용히 버리면 확정이 기각한
 *    *"지금 그대로"*보다 나쁜 상태가 된다.
 */
class GlobalScopeFieldMoveTest {

    private fun field(
        id: Long,
        universeId: Long?,
        key: String,
        type: String = "TEXT",
        code: String? = null
    ) = FieldDefinition(
        id = id,
        universeId = universeId,
        key = key,
        name = key,
        type = type,
        config = if (code == null) "{}" else """{"defaultField":"$code"}"""
    )

    private fun moving(vararg pairs: Pair<FieldDefinition, String>) =
        pairs.map { GlobalScopeFieldMove.Candidate(it.first, it.second) }

    // ── 기본: 안전하면 옮긴다 ─────────────────────────────────────────────────

    @Test
    fun 타입이_맞고_대상이_비었으면_옮긴다() {
        val from = field(1, null, "theme", code = "T1")
        val to = field(10, 5, "theme", code = "T1")
        val plan = GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(to), emptySet())
        assertEquals(mapOf(1L to 10L), plan.transfers)
        assertTrue(plan.kept.isEmpty())
    }

    // ── ① 대상에 값이 있으면 덮지 않는다 ──────────────────────────────────────

    @Test
    fun 대상에_이미_값이_있으면_덮지_않고_보관한다() {
        val from = field(1, null, "theme", code = "T1")
        val to = field(10, 5, "theme", code = "T1")
        val plan = GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(to), setOf(10L))
        assertTrue(plan.transfers.isEmpty())
        assertEquals(GlobalScopeFieldMove.KeptReason.TARGET_OCCUPIED, plan.kept[1L])
    }

    /** 한 대상에 둘이 몰리면 앞선 것만 간다 — 뒤엣것이 앞엣것을 덮으면 그것도 덮어쓰기다. */
    @Test
    fun 한_대상에_둘이_몰리면_앞선_것만_가고_나머지는_보관된다() {
        val a = field(1, null, "theme", code = "T1")
        val b = field(2, null, "theme_alt", code = "T1")
        val to = field(10, 5, "theme", code = "T1")
        val plan = GlobalScopeFieldMove.plan(moving(a to "설원", b to "사막"), listOf(to), emptySet())
        assertEquals(mapOf(1L to 10L), plan.transfers)
        assertEquals(GlobalScopeFieldMove.KeptReason.TARGET_OCCUPIED, plan.kept[2L])
    }

    // ── ② 타입이 안 맞으면 옮기지 않는다 ──────────────────────────────────────

    @Test
    fun 타입이_안_맞으면_밀어_넣지_않고_보관한다() {
        val from = field(1, null, "age", code = "A1")
        val to = field(10, 5, "age", type = "NUMBER", code = "A1")
        val plan = GlobalScopeFieldMove.plan(moving(from to "스물"), listOf(to), emptySet())
        assertTrue(plan.transfers.isEmpty())
        assertEquals(GlobalScopeFieldMove.KeptReason.TYPE_MISMATCH, plan.kept[1L])
    }

    /** 같은 대상이라도 값이 그 타입으로 살아남으면 간다 — 타입만 보고 막지 않는다. */
    @Test
    fun 숫자로_읽히는_값은_NUMBER_칸으로_간다() {
        val from = field(1, null, "age", code = "A1")
        val to = field(10, 5, "age", type = "NUMBER", code = "A1")
        val plan = GlobalScopeFieldMove.plan(moving(from to "20"), listOf(to), emptySet())
        assertEquals(mapOf(1L to 10L), plan.transfers)
    }

    // ── ③ 짝짓기는 연결 표식 우선, 없으면 필드키 ──────────────────────────────

    @Test
    fun 필드키가_달라도_연결_표식이_같으면_짝을_찾는다() {
        val from = field(1, null, "theme", code = "T1")
        val to = field(10, 5, "theme_renamed", code = "T1")   // 사용자가 세계관 쪽 키를 고쳤다
        val plan = GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(to), emptySet())
        assertEquals(mapOf(1L to 10L), plan.transfers)
    }

    @Test
    fun 표식이_없으면_필드키로_짝을_찾는다() {
        val from = field(1, null, "theme")
        val to = field(10, 5, "theme")
        val plan = GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(to), emptySet())
        assertEquals(mapOf(1L to 10L), plan.transfers)
    }

    /** 종류가 다르면 짝이 아니다 — 사건 필드와 캐릭터 필드가 키만 같은 경우는 실재한다. */
    @Test
    fun 종류가_다르면_키가_같아도_짝이_아니다() {
        val from = field(1, null, "theme")
        val to = field(10, 5, "theme").copy(entityType = FieldDefinition.ENTITY_EVENT)
        val plan = GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(to), emptySet())
        assertTrue(plan.transfers.isEmpty())
        assertEquals(GlobalScopeFieldMove.KeptReason.NO_COUNTERPART, plan.kept[1L])
    }

    // ── ④ 옮기지 못한 것도 사유를 들고 남는다 ────────────────────────────────

    @Test
    fun 도착지에_필드가_하나도_없으면_전부_사유와_함께_보관된다() {
        val a = field(1, null, "theme")
        val b = field(2, null, "motif")
        val plan = GlobalScopeFieldMove.plan(moving(a to "설원", b to "귀향"), emptyList(), emptySet())
        assertTrue(plan.transfers.isEmpty())
        assertEquals(
            mapOf(
                1L to GlobalScopeFieldMove.KeptReason.NO_COUNTERPART,
                2L to GlobalScopeFieldMove.KeptReason.NO_COUNTERPART
            ),
            plan.kept
        )
    }

    /**
     * 빈 값은 세지도 옮기지도 않는다 — 세면 고지가 *"N개를 보관했다"*고 말하는데 사용자가
     * 찾아가 보면 아무것도 없다. 빈 값은 어느 타입으로도 빈 값이다.
     */
    @Test
    fun 빈_값은_세지도_옮기지도_않는다() {
        val from = field(1, null, "theme", code = "T1")
        val to = field(10, 5, "theme", code = "T1")
        val plan = GlobalScopeFieldMove.plan(moving(from to "   "), listOf(to), emptySet())
        assertTrue(plan.transfers.isEmpty())
        assertTrue(plan.kept.isEmpty())
    }

    /** 자기 자신이 짝으로 나오면 이동이 아니다 — 옮길 것도 보관할 것도 없다. */
    @Test
    fun 자기_자신이_짝이면_이동이_아니다() {
        val f = field(1, null, "theme", code = "T1")
        val plan = GlobalScopeFieldMove.plan(moving(f to "설원"), listOf(f), emptySet())
        assertTrue(plan.transfers.isEmpty())
        assertTrue(plan.kept.isEmpty())
    }

    /**
     * **반대 방향도 같은 규칙이다** — 확정이 세 물음을 하나로 접은 자리다(13-2).
     * 세계관 필드 → 전역 필드로 옮길 때도 타입·점유 판정이 그대로 선다.
     */
    @Test
    fun 세계관에서_전역으로_나갈_때도_같은_규칙이_선다() {
        val from = field(10, 5, "theme", code = "T1")
        val toGlobal = field(1, null, "theme", code = "T1")
        assertEquals(
            mapOf(10L to 1L),
            GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(toGlobal), emptySet()).transfers
        )
        assertEquals(
            GlobalScopeFieldMove.KeptReason.TARGET_OCCUPIED,
            GlobalScopeFieldMove.plan(moving(from to "설원"), listOf(toGlobal), setOf(1L)).kept[10L]
        )
    }
}
