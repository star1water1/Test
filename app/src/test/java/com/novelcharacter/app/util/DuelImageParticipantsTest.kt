package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelImageParticipants] — 이미지 축의 참가자 코드가 파일 개명을 따라가는가 (B-104 ⓑ·ⓒ의 빗장).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이다.** 넷 다 틀려도 **오류가 나지 않고**
 * 조용히 어긋난 판이 남으므로, 시험이 유일한 검출이다:
 *
 * 1. *"저장에 들어가는 것은 원본 표기다"* — 정규화한 값을 적으면 다음 대조가 정규화에
 *    기대게 되고, 정규화가 실패하는 날 코드가 죽는다.
 * 2. *"무승부는 개명으로 승패가 되지 않는다"* — `winnerCode`가 null인 채로 남아야 한다.
 *    옮기는 김에 빈 값을 채우면 사용자가 *비겼다*고 답한 판이 승패로 둔갑한다.
 * 3. *"합쳐진 판은 지우지 않는다"* — 지우면 무음 유실이고, 옮겨 적으면 적합이 세어서 알린다.
 * 4. *"부딪히는 처분은 손대지 않는다"* — `(axisId, memberKey)`가 유니크라 옮겨 적으면
 *    **쓰기 자체가 실패**해 그 트랜잭션의 나머지까지 함께 죽는다.
 */
class DuelImageParticipantsTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    private fun match(a: String, b: String, winner: String? = null) =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner, code = "M-$a-$b")

    private fun verdict(members: List<String>, kind: String = DuelCounterVerdict.KIND_COUNTER) =
        DuelCounterVerdict(
            axisId = 1L,
            kind = kind,
            shape = DuelRecords.shapeOf(members)!!,
            memberCodes = DuelRecords.encodeMembers(members),
            memberKey = DuelRecords.memberKey(members),
            code = "V-${members.joinToString("-")}"
        )

    // ── 따라가기 ──

    @Test
    fun `판의 세 코드가 함께 옮겨진다`() {
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg", "$dir/a.jpg")),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/a2.jpg")
        )
        assertEquals(1, plan.matches.size)
        val moved = plan.matches.first()
        assertEquals("$dir/a2.jpg", moved.aCode)
        assertEquals("$dir/b.jpg", moved.bCode)
        // 승자도 함께 옮겨야 한다 — 안 옮기면 두 참가자 중 어느 쪽도 아닌 값이 되어
        // 적합이 그 판을 **깨진 판**으로 센다(사용자가 고른 승패가 사라진 것과 같다).
        assertEquals("$dir/a2.jpg", moved.winnerCode)
        assertEquals(0, plan.collapsedMatches)
    }

    @Test
    fun `무승부는 개명으로 승패가 되지 않는다`() {
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg", winner = null)),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/a2.jpg")
        )
        assertNull(plan.matches.single().winnerCode)
    }

    @Test
    fun `저장에 들어가는 값은 원본 표기다 — 정규화한 것이 아니다`() {
        // 표의 키는 정규화해 대조하되, 적는 값은 표가 준 그대로여야 한다.
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/./a.jpg", "$dir/b.jpg")),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/새 이름.jpg")
        )
        assertEquals("$dir/새 이름.jpg", plan.matches.single().aCode)
    }

    @Test
    fun `정규 표기와 원본 표기 어느 쪽으로 기록돼도 대조된다`() {
        // 개명을 기록하는 자리가 실제로 둘 다 넘긴다(ImageManagerViewModel.commitInternal).
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg")),
            emptyList(),
            mapOf("$dir/sub/../a.jpg" to "$dir/a2.jpg")
        )
        assertEquals("$dir/a2.jpg", plan.matches.single().aCode)
    }

    @Test
    fun `사슬을 끝까지 따라간다`() {
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg")),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/a2.jpg", "$dir/a2.jpg" to "$dir/a3.jpg")
        )
        // 한 번만 따라가면 a2에서 멈춘다 — 그 파일은 이미 없다.
        assertEquals("$dir/a3.jpg", plan.matches.single().aCode)
    }

    @Test
    fun `고리를 만나도 돌지 않는다`() {
        // `a→b`와 `b→a`가 한 표에 있으면 사슬 걷기가 영원히 돈다 — 걸음 수를 표 크기로 묶는
        // 것이 그 방어이고, **끝난다는 것 자체**가 이 시험이 지키는 전부다.
        val cycle = mapOf("$dir/a.jpg" to "$dir/b.jpg", "$dir/b.jpg" to "$dir/a.jpg")
        val moved = DuelImageParticipants.follow("$dir/a.jpg", cycle)
        assertTrue(moved == "$dir/a.jpg" || moved == "$dir/b.jpg")

        // 고리를 한 바퀴 돌아 제자리로 오면 **바뀐 것이 없다** — 그때 계획이 비는 것이 옳다.
        // (판을 다시 쓰지 않는다. 같은 값을 덮어써 봐야 표만 두드린다.)
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/c.jpg")),
            emptyList(),
            cycle
        )
        assertTrue(plan.matches.isEmpty() || plan.matches.single().aCode == "$dir/b.jpg")
    }

    @Test
    fun `안 바뀐 행은 계획에 담기지 않는다`() {
        // 이 앱에서 가장 큰 표라(수만 행) 안 바뀐 것을 다시 쓰면 그 비용이 행 수만큼 곱해진다.
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg"), match("$dir/c.jpg", "$dir/d.jpg")),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/a2.jpg")
        )
        assertEquals(1, plan.matches.size)
        assertEquals("$dir/a2.jpg", plan.matches.single().aCode)
    }

    @Test
    fun `빈 개명 표는 아무것도 하지 않는다`() {
        assertSame(
            DuelImageParticipants.Plan.EMPTY,
            DuelImageParticipants.plan(listOf(match("a", "b")), emptyList(), emptyMap())
        )
        // 제자리 개명만 든 표도 마찬가지다 — 걷어내지 않으면 사슬 걷기가 헛돈다.
        assertSame(
            DuelImageParticipants.Plan.EMPTY,
            DuelImageParticipants.plan(
                listOf(match("$dir/a.jpg", "$dir/b.jpg")),
                emptyList(),
                mapOf("$dir/a.jpg" to "$dir/a.jpg")
            )
        )
    }

    // ── 합쳐짐 ──

    @Test
    fun `합쳐진 판은 지우지 않고 옮겨 적은 뒤 센다`() {
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a.jpg", "$dir/b.jpg", "$dir/a.jpg")),
            emptyList(),
            mapOf("$dir/a.jpg" to "$dir/same.jpg", "$dir/b.jpg" to "$dir/same.jpg")
        )
        assertEquals(1, plan.matches.size)
        assertEquals(1, plan.collapsedMatches)
        assertTrue(plan.hasCaveat)
        // 지우지 않았으므로 적합이 **깨진 판**으로 세어서 알린다 — 그것이 이 처분의 요점이다.
        assertFalse(plan.matches.single().isWellFormed)
    }

    // ── 처분 ──

    @Test
    fun `처분의 코드와 정규 키가 함께 다시 매겨진다`() {
        val v = verdict(listOf("$dir/a.jpg", "$dir/b.jpg"))
        val plan = DuelImageParticipants.plan(
            emptyList(),
            listOf(v),
            mapOf("$dir/a.jpg" to "$dir/z.jpg")
        )
        val moved = plan.verdicts.single()
        // 순서는 뜻이 있다 — `[센 쪽, 잡는 쪽]`이라 옮겨도 자리를 지켜야 한다.
        assertEquals(listOf("$dir/z.jpg", "$dir/b.jpg"), DuelRecords.decodeMembers(moved.memberCodes))
        // 키를 다시 안 매기면 유니크 인덱스가 **옛 관계**를 지키게 되어, 같은 관계가 두 번 등재된다.
        assertEquals(DuelRecords.memberKey(listOf("$dir/z.jpg", "$dir/b.jpg")), moved.memberKey)
        assertEquals(0, plan.verdictConflicts)
    }

    @Test
    fun `옮기면 부딪히는 처분은 손대지 않고 센다`() {
        // a-b를 z-b로 옮기면 이미 있는 z-b와 같은 키가 된다 → 쓰면 유니크 인덱스가 던진다.
        val moving = verdict(listOf("$dir/a.jpg", "$dir/b.jpg"))
        val standing = verdict(listOf("$dir/z.jpg", "$dir/b.jpg"))
        val plan = DuelImageParticipants.plan(
            emptyList(),
            listOf(moving, standing),
            mapOf("$dir/a.jpg" to "$dir/z.jpg")
        )
        assertTrue(plan.verdicts.isEmpty())
        assertEquals(1, plan.verdictConflicts)
    }

    @Test
    fun `하나로 접힌 처분은 손대지 않고 센다`() {
        val plan = DuelImageParticipants.plan(
            emptyList(),
            listOf(verdict(listOf("$dir/a.jpg", "$dir/b.jpg"))),
            mapOf("$dir/a.jpg" to "$dir/same.jpg", "$dir/b.jpg" to "$dir/same.jpg")
        )
        assertTrue(plan.verdicts.isEmpty())
        assertEquals(1, plan.verdictConflicts)
    }

    @Test
    fun `순환 처분이 둘로 접히면 모양이 달라지므로 손대지 않는다`() {
        // shape는 저장된 값이라 함께 바뀌지 않는다 — 셋이 둘이 되면 `cycle`인데 원소가 둘인
        // 행이 되어, 읽는 쪽이 회전 차례를 기대하다 어긋난다.
        val plan = DuelImageParticipants.plan(
            emptyList(),
            listOf(verdict(listOf("$dir/a.jpg", "$dir/b.jpg", "$dir/c.jpg"))),
            mapOf("$dir/c.jpg" to "$dir/a.jpg")
        )
        assertTrue(plan.verdicts.isEmpty())
        assertEquals(1, plan.verdictConflicts)
    }

    @Test
    fun `구분자가 든 파일 이름도 안전하다`() {
        val plan = DuelImageParticipants.plan(
            listOf(match("$dir/a|b.jpg", "$dir/c.jpg")),
            listOf(verdict(listOf("$dir/a|b.jpg", "$dir/c.jpg"))),
            mapOf("$dir/a|b.jpg" to "$dir/d|e.jpg")
        )
        assertEquals("$dir/d|e.jpg", plan.matches.single().aCode)
        assertEquals(
            listOf("$dir/d|e.jpg", "$dir/c.jpg"),
            DuelRecords.decodeMembers(plan.verdicts.single().memberCodes)
        )
    }

    // ── follow 하나만 ──

    @Test
    fun `follow는 빈 값과 빈 표를 그대로 돌려준다`() {
        assertEquals("", DuelImageParticipants.follow(null, mapOf("a" to "b")))
        assertEquals("", DuelImageParticipants.follow("   ", mapOf("a" to "b")))
        assertEquals("$dir/a.jpg", DuelImageParticipants.follow("$dir/a.jpg", emptyMap()))
        assertEquals("$dir/b.jpg", DuelImageParticipants.follow("$dir/a.jpg", mapOf("$dir/a.jpg" to "$dir/b.jpg")))
    }

    // ── displayName (2026.08.23 — 엑셀 '대결 기록'의 필수 열이 빈칸으로 나가던 자리) ──

    @Test
    fun `displayName은 경로에서 파일 이름만 뗀다`() {
        assertEquals("char_a.jpg", DuelImageParticipants.displayName("$dir/char_a.jpg"))
        assertEquals("char_a.jpg", DuelImageParticipants.displayName("char_a.jpg"))
    }

    @Test
    fun `displayName은 없는 척하지 않는다`() {
        // 파일 이름을 못 떼면 받은 글자 그대로다 — "이름 없음"으로 갈음하면 사용자가
        // 파일에서 그 참가자를 되찾을 단서가 사라진다(개발 의도 2번).
        assertEquals("$dir/", DuelImageParticipants.displayName("$dir/"))
        assertEquals("", DuelImageParticipants.displayName(null))
        assertEquals("", DuelImageParticipants.displayName("   "))
    }

    @Test
    fun `displayName은 앞뒤 공백을 들이지 않는다`() {
        // 엑셀 셀은 공백이 섞여 들어온다 — 이름에 그것이 실리면 화면과 시트가 갈린다.
        assertEquals("char_a.jpg", DuelImageParticipants.displayName("  $dir/char_a.jpg  "))
    }
}
