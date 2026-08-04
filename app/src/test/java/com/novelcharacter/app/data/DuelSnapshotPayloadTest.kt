package com.novelcharacter.app.data

import com.google.gson.Gson
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelAxisSnapshot
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.DuelMatchesSnapshot
import com.novelcharacter.app.data.model.EntityRefs
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.util.DuelRecords
import com.novelcharacter.app.util.ResetPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결(B-104) 휴지통 payload의 Gson 왕복·하위호환 계약과 복원 순서 (규약 R-2).
 *
 * 여기서 지키는 것은 **"지운 것은 되돌릴 수 있다"는 약속이 대결에도 적용되는가**이다.
 * 축 하나를 지우면 수만 번의 누름이 FK CASCADE로 함께 사라지므로, payload가 한 번이라도
 * 깨지면 그 데이터는 되돌릴 길이 없다.
 */
class DuelSnapshotPayloadTest {

    private val gson = Gson()

    private fun axis(name: String = "강함") = DuelAxis(
        id = 3, universeId = 1, name = name, targetType = DuelAxis.TARGET_CHARACTER,
        displayOrder = 0, createdAt = 100, code = "AX-1"
    )

    // ──────────────────────────────────────────────────────────────────────
    // 왕복과 하위호환 (R-2)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `축 스냅샷은 처분과 함께 왕복한다`() {
        val original = DuelAxisSnapshot(
            axis = axis(),
            verdicts = listOf(
                DuelCounterVerdict(
                    id = 9, axisId = 3,
                    kind = DuelCounterVerdict.KIND_COUNTER,
                    shape = DuelCounterVerdict.SHAPE_DIRECT,
                    memberCodes = DuelRecords.encodeMembers(listOf("CHR-1", "CHR-2")),
                    memberKey = DuelRecords.memberKey(listOf("CHR-1", "CHR-2")),
                    decidedAt = 200, code = "VD-1"
                )
            ),
            universeCode = "UNI-1",
            matchCount = 12_000,
            refs = EntityRefs(universeCode = "UNI-1")
        )
        val restored = gson.fromJson(gson.toJson(original), DuelAxisSnapshot::class.java)
        assertEquals("AX-1", restored.axis.code)
        assertEquals(DuelAxis.TARGET_CHARACTER, restored.axis.targetType)
        assertEquals(1, restored.verdicts.orEmpty().size)
        assertEquals("UNI-1", restored.universeCode)
        assertEquals(12_000, restored.matchCount)
        assertEquals(
            listOf("CHR-1", "CHR-2"),
            DuelRecords.decodeMembers(restored.verdicts!![0].memberCodes)
        )
    }

    @Test
    fun `처분 키가 없는 구버전 payload도 읽힌다`() {
        // Gson은 생성자 기본값을 실행하지 않으므로 없는 키에는 null이 주입된다 — 선언이
        // non-null이면 읽는 쪽이 순회하는 순간 NPE로 죽고, 사용자가 가진 휴지통 항목
        // **전량**이 복원 불가가 된다.
        val bare = """{"axis": {"id": 3, "universeId": 1, "name": "강함",
                       "targetType": "character", "displayOrder": 0, "createdAt": 0, "code": "AX-1"}}"""
        val old = gson.fromJson(bare, DuelAxisSnapshot::class.java)
        assertNull(old.verdicts)
        assertNull(old.universeCode)
        assertNull(old.matchCount)
        assertEquals(emptyList<DuelCounterVerdict>(), old.verdicts.orEmpty())
    }

    /**
     * v50 이전(층 C 이전)에 만들어진 축 payload — **연결 칸 둘이 없다.**
     *
     * 선언은 non-null인데 Gson이 null을 주입하므로, 복원이 `copy()`를 부르는 순간 그 칸에서
     * NPE가 난다(B-103이 실제로 밟은 지뢰다. `copy`는 넘기지 않은 인자를 `this`에서 읽어
     * 생성자에 넘기고, 생성자가 null 검사를 한다). **그래서 복원 경로가 두 칸을 명시적으로
     * 접는다** — 여기서는 그 접기가 성립하는지, 그리고 해석이 빈 연결로 읽히는지를 잰다.
     */
    @Test
    fun `연결 칸이 없는 구버전 축 payload도 되살아난다`() {
        val bare = """{"axis": {"id": 3, "universeId": 1, "name": "강함",
                       "targetType": "character", "displayOrder": 0, "createdAt": 0, "code": "AX-1"}}"""
        val old = gson.fromJson(bare, DuelAxisSnapshot::class.java).axis

        @Suppress("SENSELESS_COMPARISON")
        val missing = old.influenceFieldKeys == null && old.outcomeFieldKeys == null
        assertTrue("구버전 payload에는 연결 칸이 없어 null이 주입된다", missing)

        // 복원이 하는 접기 그대로 — 이 두 줄이 곧 TrashRepository.applyDuelAxis의 계약이다.
        val restored = old.copy(
            influenceFieldKeys = old.influenceFieldKeys.orEmpty().ifEmpty { "[]" },
            outcomeFieldKeys = old.outcomeFieldKeys.orEmpty().ifEmpty { "[]" }
        )
        assertEquals("[]", restored.influenceFieldKeys)
        assertEquals("[]", restored.outcomeFieldKeys)
        assertTrue("옛 축은 연결이 없는 축이다", !restored.fieldLinks.hasAny)
    }

    @Test
    fun `판 조각은 참가자를 코드로 담아 왕복한다`() {
        val original = DuelMatchesSnapshot(
            axisCode = "AX-1",
            matches = listOf(
                DuelMatch(
                    id = 1, axisId = 3, aCode = "CHR-1", bCode = "CHR-2",
                    winnerCode = "CHR-1", groupId = "grp-7", decidedAt = 300, code = "MT-1"
                ),
                DuelMatch(
                    id = 2, axisId = 3, aCode = "CHR-1", bCode = "CHR-3",
                    winnerCode = null, decidedAt = 301, code = "MT-2"
                )
            ),
            universeCode = "UNI-1",
            refs = EntityRefs(universeCode = "UNI-1")
        )
        val restored = gson.fromJson(gson.toJson(original), DuelMatchesSnapshot::class.java)
        assertEquals("AX-1", restored.axisCode)
        assertEquals(2, restored.matches.orEmpty().size)
        assertEquals("CHR-1", restored.matches!![0].winnerCode)
        assertTrue("무승부는 null로 왕복한다", restored.matches!![1].isDraw)
        assertEquals("grp-7", restored.matches!![0].groupId)

        val bare = """{"axisCode": "AX-1"}"""
        val old = gson.fromJson(bare, DuelMatchesSnapshot::class.java)
        assertNull(old.matches)
        assertEquals(emptyList<DuelMatch>(), old.matches.orEmpty())
    }

    @Test
    fun `판 payload는 캐릭터의 생사와 무관하게 온전하다`() {
        // 참가자가 id였다면 복원 시점의 id 재발급을 payload가 따라갈 수 없어, 되살아난 판이
        // 남의 캐릭터에 붙거나(오배정) 아무에게도 안 붙는다. 코드라서 그 문제가 없다.
        val snapshot = DuelMatchesSnapshot(
            axisCode = "AX-1",
            matches = listOf(DuelMatch(axisId = 3, aCode = "CHR-1", bCode = "CHR-2", winnerCode = "CHR-2"))
        )
        val restored = gson.fromJson(gson.toJson(snapshot), DuelMatchesSnapshot::class.java)
        val row = restored.matches!![0]
        assertTrue(row.isWellFormed)
        assertEquals("CHR-2", row.winnerCode)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 복원 순서
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `축은 세계관보다 나중에 - 판은 축보다 나중에 복원된다`() {
        assertTrue(
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_DUEL_AXIS) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_UNIVERSE)
        )
        assertTrue(
            "판은 붙을 축이 이미 서 있어야 한다(FK)",
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_DUEL_MATCHES) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_DUEL_AXIS)
        )
    }

    @Test
    fun `알 수 없는 타입은 여전히 맨 뒤다`() {
        // 대결 둘을 뒤에 붙이면서 else 값을 올리는 것을 잊으면, 미래의 신규 타입이 대결보다
        // 먼저 복원된다 — 순서가 조용히 틀어지는 부류의 결함이다.
        val known = listOf(
            TrashSnapshot.TYPE_UNIVERSE, TrashSnapshot.TYPE_FIELD_DEFINITION,
            TrashSnapshot.TYPE_UNIVERSE_DATA, TrashSnapshot.TYPE_GRADE_SYSTEM,
            TrashSnapshot.TYPE_NOVEL, TrashSnapshot.TYPE_FACTION, TrashSnapshot.TYPE_EVENT,
            TrashSnapshot.TYPE_CHARACTER, TrashSnapshot.TYPE_STATE_CHANGE,
            TrashSnapshot.TYPE_DUEL_AXIS, TrashSnapshot.TYPE_DUEL_MATCHES
        )
        val unknown = TrashSnapshot.restorePriority("어떤_미래_타입")
        assertTrue(known.all { TrashSnapshot.restorePriority(it) < unknown })
        // 아는 타입끼리 우선순위가 겹치지 않는다 — 겹치면 정렬이 삽입 순서에 좌우된다.
        assertEquals(known.size, known.map { TrashSnapshot.restorePriority(it) }.distinct().size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 초기화 처분 (S-13)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `대결 세 표가 전부 초기화 계획에 있다`() {
        val tables = ResetPlan.plan.associateBy { it.table }
        assertNotNull("축이 빠지면 초기화 뒤에도 살아남는다", tables["duel_axes"])
        assertNotNull(tables["duel_matches"])
        assertNotNull(tables["duel_counter_verdicts"])
        assertEquals("universes", tables["duel_axes"]!!.via)
        assertEquals("duel_axes", tables["duel_matches"]!!.via)
        assertEquals("duel_axes", tables["duel_counter_verdicts"]!!.via)
        // 부모가 비워지지 않는 CASCADE는 "비워진다고 적혀 있지만 실제로는 남는" 표다.
        assertTrue(ResetPlan.brokenCascades().isEmpty())
    }
}
