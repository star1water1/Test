package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.util.DuelRecords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 월드패키지의 대결 직렬화 (B-118 ⓑ — schemaVersion 6).
 *
 * **이 시험이 지키는 것은 참가자 재배선이다.** 판은 참가자를 코드로 가리키는데 가져오기가
 * 그 코드를 재발급할 수 있어, 표를 따라가지 않으면 판이 **이 기기에 이미 있던 남의 캐릭터에
 * 붙는다**(R-1 — 오배정은 생략보다 나쁘다). 배선은 Room을 물어 순수 JVM이 닿지 않으므로
 * 판정을 전부 순수 계층에 두었고, 그 계약을 여기서 실행으로 잠근다.
 */
class WorldPackageDuelsTest {

    private fun axis(
        id: Long,
        code: String,
        name: String = "강함",
        target: String = DuelAxis.TARGET_CHARACTER,
        basis: Boolean = false,
        order: Int = 0
    ) = DuelAxis(
        id = id,
        universeId = 1L,
        name = name,
        targetType = target,
        displayOrder = order,
        isBasisAxis = basis,
        code = code
    )

    private fun match(
        axisId: Long,
        a: String,
        b: String,
        winner: String? = a,
        code: String = "M-$a-$b",
        decidedAt: Long = 1000L
    ) = DuelMatch(axisId = axisId, aCode = a, bCode = b, winnerCode = winner, decidedAt = decidedAt, code = code)

    private fun verdict(
        axisId: Long,
        members: List<String>,
        kind: String = DuelCounterVerdict.KIND_COUNTER,
        code: String = "V-" + members.joinToString("-")
    ) = DuelCounterVerdict(
        axisId = axisId,
        kind = kind,
        shape = DuelRecords.shapeOf(members) ?: DuelCounterVerdict.SHAPE_DIRECT,
        memberCodes = DuelRecords.encodeMembers(members),
        memberKey = DuelRecords.memberKey(members),
        code = code
    )

    // ── 내보내기 방향 ──

    @Test
    fun `참가자가 범위 안이면 싣고 밖이면 세어 남긴다`() {
        val a = axis(1L, "AX-1")
        val result = WorldPackageDuels.toPortable(
            axes = listOf(a),
            matches = listOf(
                match(1L, "CH-1", "CH-2"),
                match(1L, "CH-1", "CH-9"),   // 상대가 범위 밖(다른 작품)
                match(1L, "CH-8", "CH-9")    // 둘 다 밖
            ),
            verdicts = emptyList(),
            characterCodesInScope = setOf("CH-1", "CH-2"),
            imagePathsInScope = emptySet()
        )
        assertEquals(1, result.matches.size)
        // **양쪽 다 밖도 센다** — 이 세계관 축에 달린 판이라 남겨지는 사용자 데이터다.
        assertEquals(2, result.droppedMatches)
    }

    @Test
    fun `이미지 축의 참가자는 경로로 판정한다`() {
        val charAxis = axis(1L, "AX-1")
        val imageAxis = axis(2L, "AX-2", name = "아름다움", target = DuelAxis.TARGET_IMAGE)
        val result = WorldPackageDuels.toPortable(
            axes = listOf(charAxis, imageAxis),
            matches = listOf(
                match(2L, "/f/a.jpg", "/f/b.jpg", winner = "/f/a.jpg"),
                // 캐릭터 코드가 이미지 축에 들어와도 경로 집합에 없으므로 실리지 않는다
                match(2L, "CH-1", "CH-2"),
                match(1L, "CH-1", "CH-2")
            ),
            verdicts = emptyList(),
            characterCodesInScope = setOf("CH-1", "CH-2"),
            imagePathsInScope = setOf("/f/a.jpg", "/f/b.jpg")
        )
        assertEquals(2, result.matches.size)
        assertEquals(1, result.droppedMatches)
    }

    @Test
    fun `상성은 전원이 있을 때만 싣는다`() {
        val a = axis(1L, "AX-1")
        val result = WorldPackageDuels.toPortable(
            axes = listOf(a),
            matches = emptyList(),
            verdicts = listOf(
                verdict(1L, listOf("CH-1", "CH-2")),
                verdict(1L, listOf("CH-1", "CH-2", "CH-9"))   // 셋 중 하나가 밖 → 순환이 아니다
            ),
            characterCodesInScope = setOf("CH-1", "CH-2"),
            imagePathsInScope = emptySet()
        )
        assertEquals(1, result.verdicts.size)
        assertEquals(1, result.droppedVerdicts)
    }

    @Test
    fun `다른 세계관 축의 판은 세지 않는다`() {
        // 축이 이번 집합에 없으면 이 패키지의 범위 자체가 아니다 — 세면 사용자가 이 세계관에서
        // 무엇을 잃었는지 물을 때 엉뚱한 수를 본다.
        val result = WorldPackageDuels.toPortable(
            axes = listOf(axis(1L, "AX-1")),
            matches = listOf(match(99L, "CH-1", "CH-2")),
            verdicts = listOf(verdict(99L, listOf("CH-1", "CH-2"))),
            characterCodesInScope = setOf("CH-1", "CH-2"),
            imagePathsInScope = emptySet()
        )
        assertTrue(result.matches.isEmpty())
        assertEquals(0, result.droppedMatches)
        assertEquals(0, result.droppedVerdicts)
    }

    @Test
    fun `같은 자료는 같은 순서를 낸다`() {
        val axes = listOf(axis(2L, "AX-B", name = "나", order = 1), axis(1L, "AX-A", name = "가", order = 0))
        val matches = listOf(
            match(1L, "CH-2", "CH-1", code = "M-2", decidedAt = 200L),
            match(1L, "CH-1", "CH-2", code = "M-1", decidedAt = 100L)
        )
        val first = WorldPackageDuels.toPortable(axes, matches, emptyList(), setOf("CH-1", "CH-2"), emptySet())
        val second = WorldPackageDuels.toPortable(
            axes.reversed(), matches.reversed(), emptyList(), setOf("CH-1", "CH-2"), emptySet()
        )
        assertEquals(first.axes.map { it.code }, second.axes.map { it.code })
        assertEquals(listOf("AX-A", "AX-B"), first.axes.map { it.code })
        assertEquals(listOf("M-1", "M-2"), first.matches.map { it.code })
    }

    // ── 기준 축 유일성 ──

    @Test
    fun `기준 축이 둘이면 첫 하나만 남기고 센다`() {
        val result = WorldPackageDuels.normalizeImportedAxes(
            listOf(
                axis(1L, "AX-1", basis = true),
                axis(2L, "AX-2", name = "나", basis = true),
                axis(3L, "AX-3", name = "다", basis = false)
            )
        )
        assertEquals(listOf(true, false, false), result.axes.map { it.isBasisAxis })
        assertEquals(1, result.demotedBasisAxes)
    }

    @Test
    fun `기준 축이 하나뿐이면 손대지 않는다`() {
        val axes = listOf(axis(1L, "AX-1", basis = true), axis(2L, "AX-2", name = "나"))
        val result = WorldPackageDuels.normalizeImportedAxes(axes)
        assertEquals(axes, result.axes)
        assertEquals(0, result.demotedBasisAxes)
    }

    // ── 가져오기 방향 — 이 판의 본론 ──

    @Test
    fun `재발급된 캐릭터 코드를 따라간다`() {
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "CH-1", "CH-2", winner = "CH-2")),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            // CH-1은 이 기기에서 겹쳐 재발급됐고 CH-2는 그대로다(제자리 대응).
            characterCodeRemap = mapOf("CH-1" to "CH-NEW", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(1, result.matches.size)
        val m = result.matches.first()
        assertEquals(77L, m.axisId)
        assertEquals("CH-NEW", m.aCode)
        assertEquals("CH-2", m.bCode)
        // 승자도 같은 표를 따른다 — 안 따라가면 승패가 왕복 한 번에 깨진 판이 된다.
        assertEquals("CH-2", m.winnerCode)
        assertEquals(0L, m.id)
        assertEquals(0, result.unresolvedMatches)
    }

    @Test
    fun `표에 없는 코드는 원문을 남기지 않고 제외한다`() {
        // **이 시험이 R-1을 지킨다.** 원문을 남기면 그 코드가 이 기기에 이미 있던 남의 캐릭터를
        // 가리켜, 남의 전적에 이 판이 얹힌다. 폴백이 없다는 것 자체가 설계다.
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "CH-1", "CH-GHOST")),
            verdicts = listOf(verdict(1L, listOf("CH-1", "CH-GHOST"))),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1"),
            imagePathRemap = emptyMap()
        )
        assertTrue(result.matches.isEmpty())
        assertTrue(result.verdicts.isEmpty())
        assertEquals(1, result.unresolvedMatches)
        assertEquals(1, result.unresolvedVerdicts)
    }

    @Test
    fun `축을 잇지 못한 판도 제외하고 센다`() {
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(5L, "CH-1", "CH-2")),
            verdicts = listOf(verdict(5L, listOf("CH-1", "CH-2"))),
            axisIdByOldId = emptyMap(),
            axisTargetTypeByOldId = emptyMap(),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(1, result.unresolvedMatches)
        assertEquals(1, result.unresolvedVerdicts)
    }

    @Test
    fun `이미지 축은 경로 표를 따라간다`() {
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(
                match(2L, "/old/a.jpg", "/old/b.jpg", winner = "/old/b.jpg"),
                match(2L, "/old/a.jpg", "/gone/c.jpg")   // 복원되지 않은 장
            ),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(2L to 88L),
            axisTargetTypeByOldId = mapOf(2L to DuelAxis.TARGET_IMAGE),
            characterCodeRemap = mapOf("CH-1" to "CH-1"),
            // 하나는 새 자리로 복사됐고, 하나는 같은 기기라 원 경로가 그대로 재사용됐다.
            imagePathRemap = mapOf("/old/a.jpg" to "/new/a.jpg", "/old/b.jpg" to "/old/b.jpg")
        )
        assertEquals(1, result.matches.size)
        assertEquals("/new/a.jpg", result.matches.first().aCode)
        assertEquals("/old/b.jpg", result.matches.first().bCode)
        assertEquals("/old/b.jpg", result.matches.first().winnerCode)
        assertEquals(1, result.unresolvedMatches)
    }

    @Test
    fun `이미지 축과 캐릭터 축이 서로의 표를 쓰지 않는다`() {
        // 같은 문자열이 두 축에서 다른 것을 뜻한다 — 표를 섞으면 이미지 판이 캐릭터에 붙는다.
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "/old/a.jpg", "/old/b.jpg")),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1"),
            imagePathRemap = mapOf("/old/a.jpg" to "/new/a.jpg", "/old/b.jpg" to "/new/b.jpg")
        )
        assertTrue(result.matches.isEmpty())
        assertEquals(1, result.unresolvedMatches)
    }

    @Test
    fun `승자가 참가자 어느 쪽도 아니면 원문을 남긴다`() {
        // 참가자의 미해석과 처분이 다르다: 비우면 **무승부로 되읽혀** 사용자가 고른 승패가
        // 사라진다. 남겨 두면 적합이 깨진 판으로 세어 알린다.
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "CH-1", "CH-2", winner = "CH-ELSE")),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(1, result.matches.size)
        assertEquals("CH-ELSE", result.matches.first().winnerCode)
        assertEquals(0, result.unresolvedMatches)
    }

    @Test
    fun `무승부는 무승부로 온다`() {
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "CH-1", "CH-2", winner = null)),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertNull(result.matches.first().winnerCode)
        assertTrue(result.matches.first().isDraw)
    }

    @Test
    fun `두 참가자가 같은 판은 제외한다`() {
        // 비교가 성립하지 않는다 — 엑셀 가져오기가 같은 결함을 같은 방식으로 거른다.
        val result = WorldPackageDuels.fromPortable(
            matches = listOf(match(1L, "CH-1", "CH-1")),
            verdicts = emptyList(),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1"),
            imagePathRemap = emptyMap()
        )
        assertTrue(result.matches.isEmpty())
        assertEquals(1, result.unresolvedMatches)
    }

    @Test
    fun `정규 키를 다시 낸다`() {
        // 코드가 재발급되면 옛 키는 **옛 코드로 만든 문자열**이라 유니크 인덱스가 다른 관계로
        // 읽는다 — 그러면 같은 관계가 두 번 등재된다.
        val old = verdict(1L, listOf("CH-1", "CH-2"))
        val result = WorldPackageDuels.fromPortable(
            matches = emptyList(),
            verdicts = listOf(old),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-AAA", "CH-2" to "CH-BBB"),
            imagePathRemap = emptyMap()
        )
        val v = result.verdicts.single()
        assertEquals(listOf("CH-AAA", "CH-BBB"), DuelRecords.decodeMembers(v.memberCodes))
        assertEquals(DuelRecords.memberKey(listOf("CH-AAA", "CH-BBB")), v.memberKey)
        assertNotEquals(old.memberKey, v.memberKey)
    }

    @Test
    fun `뜻이 있는 순서를 지킨다`() {
        // 천적은 `[센 쪽, 잡는 쪽]`이다 — 정렬하면 누가 누구를 잡는지가 뒤집힌다.
        val result = WorldPackageDuels.fromPortable(
            matches = emptyList(),
            verdicts = listOf(verdict(1L, listOf("CH-2", "CH-1"))),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(listOf("CH-2", "CH-1"), DuelRecords.decodeMembers(result.verdicts.single().memberCodes))
    }

    @Test
    fun `같은 관계가 두 번이면 하나만 넣고 센다`() {
        // 걸러내지 않으면 `(축, 정규 키)` 유니크 인덱스가 예외로 죽고, 트랜잭션이 하나라
        // **세계관 전체가** 들어오지 못한다.
        val result = WorldPackageDuels.fromPortable(
            matches = emptyList(),
            verdicts = listOf(
                verdict(1L, listOf("CH-1", "CH-2"), code = "V-1"),
                // 회전·반전은 같은 관계다(정규 키가 순서를 지운다).
                verdict(1L, listOf("CH-2", "CH-1"), code = "V-2")
            ),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(1, result.verdicts.size)
        assertEquals(1, result.duplicateVerdicts)
        assertEquals(0, result.unresolvedVerdicts)
    }

    @Test
    fun `다른 축의 같은 관계는 둘 다 넣는다`() {
        val result = WorldPackageDuels.fromPortable(
            matches = emptyList(),
            verdicts = listOf(
                verdict(1L, listOf("CH-1", "CH-2"), code = "V-1"),
                verdict(2L, listOf("CH-1", "CH-2"), code = "V-2")
            ),
            axisIdByOldId = mapOf(1L to 77L, 2L to 88L),
            axisTargetTypeByOldId = mapOf(
                1L to DuelAxis.TARGET_CHARACTER, 2L to DuelAxis.TARGET_CHARACTER
            ),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(2, result.verdicts.size)
        assertEquals(0, result.duplicateVerdicts)
    }

    @Test
    fun `모양은 참가자 수로 다시 낸다`() {
        // 손편집 파일이 shape와 참가자 수를 어긋나게 담을 수 있다 — 저장된 값을 믿으면
        // 순환으로 표시되는 둘짜리가 들어온다.
        val broken = verdict(1L, listOf("CH-1", "CH-2")).copy(shape = DuelCounterVerdict.SHAPE_CYCLE)
        val result = WorldPackageDuels.fromPortable(
            matches = emptyList(),
            verdicts = listOf(broken),
            axisIdByOldId = mapOf(1L to 77L),
            axisTargetTypeByOldId = mapOf(1L to DuelAxis.TARGET_CHARACTER),
            characterCodeRemap = mapOf("CH-1" to "CH-1", "CH-2" to "CH-2"),
            imagePathRemap = emptyMap()
        )
        assertEquals(DuelCounterVerdict.SHAPE_DIRECT, result.verdicts.single().shape)
    }

    @Test
    fun `왕복에서 판이 하나도 줄지 않는다`() {
        val axes = listOf(axis(1L, "AX-1"), axis(2L, "AX-2", name = "아름다움", target = DuelAxis.TARGET_IMAGE))
        val matches = listOf(
            match(1L, "CH-1", "CH-2", winner = "CH-1", code = "M-1"),
            match(1L, "CH-2", "CH-3", winner = null, code = "M-2"),
            match(2L, "/f/a.jpg", "/f/b.jpg", winner = "/f/b.jpg", code = "M-3")
        )
        val verdicts = listOf(verdict(1L, listOf("CH-1", "CH-3")))
        val chars = setOf("CH-1", "CH-2", "CH-3")
        val paths = setOf("/f/a.jpg", "/f/b.jpg")

        val out = WorldPackageDuels.toPortable(axes, matches, verdicts, chars, paths)
        assertEquals(0, out.droppedMatches)
        assertEquals(0, out.droppedVerdicts)

        val back = WorldPackageDuels.fromPortable(
            matches = out.matches,
            verdicts = out.verdicts,
            axisIdByOldId = mapOf(1L to 11L, 2L to 22L),
            axisTargetTypeByOldId = mapOf(
                1L to DuelAxis.TARGET_CHARACTER, 2L to DuelAxis.TARGET_IMAGE
            ),
            characterCodeRemap = chars.associateWith { it },
            imagePathRemap = paths.associateWith { it }
        )
        assertEquals(matches.size, back.matches.size)
        assertEquals(verdicts.size, back.verdicts.size)
        assertEquals(0, back.unresolvedMatches)
        assertEquals(0, back.unresolvedVerdicts)
        // 승패·무승부·묶음이 그대로다
        assertEquals(
            matches.map { it.code to it.winnerCode }.toSet(),
            back.matches.map { it.code to it.winnerCode }.toSet()
        )
    }
}
