package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelImageRoster] — 이미지 축이 **캐릭터마다 따로 논다**는 것의 실행 (B-104 이미지 축).
 *
 * **이 판의 방어선은 셋이고 셋 다 시험이 유일한 검출이다:**
 *
 * 1. *"캐릭터를 넘는 판은 세지 않는다"* — 세면 그 캐릭터의 진행률이 100%를 넘고, 넘은 진행률은
 *    화면에서 *"다 했는데 안 끝난다"*로 보인다. 이 화면은 그런 판을 만들지 않지만 엑셀·백업이
 *    만들 수 있다.
 * 2. *"두 장이 없으면 목록에 없다"* — 올려 두면 눌렀을 때 빈 화면이고, 그것은 고장과 구별되지
 *    않는다. 대신 **세어서 말한다**.
 * 3. *"대조는 정규 경로로 한다"* — 개명 추종이 원본 표기를 지키므로 판의 표기와 목록의 표기가
 *    갈릴 수 있다. 여기서 대조에 실패하면 멀쩡한 판이 통째로 안 세어진다.
 */
class DuelImageRosterTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    private fun character(id: Long, name: String, vararg paths: String) = Character(
        id = id,
        name = name,
        code = "C-$id",
        imagePaths = paths.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    )

    private fun match(a: String, b: String, winner: String? = null) =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner, code = "M-$a-$b")

    @Test
    fun `이미지가 둘 이상인 캐릭터만 목록에 오르고 나머지는 세어진다`() {
        val roster = DuelImageRoster.build(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg"),
                character(3L, "다"),
                character(4L, "라", "$dir/d1.jpg", "$dir/d2.jpg", "$dir/d3.jpg")
            ),
            emptyList()
        )
        assertEquals(listOf(1L, 4L), roster.entries.map { it.characterId })
        assertEquals(1, roster.skippedSingleImage)
        assertEquals(1, roster.skippedNoImage)
        assertTrue(roster.hasSkipped)
        // 한 장과 0장을 갈라 세는 것은 할 말이 다르기 때문이다.
        assertFalse(roster.skippedSingleImage == roster.skippedNoImage + 1 && roster.skippedNoImage == 0)
    }

    @Test
    fun `짝 전수는 그 캐릭터의 이미지 수만으로 정해진다`() {
        val roster = DuelImageRoster.build(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(4L, "라", "$dir/d1.jpg", "$dir/d2.jpg", "$dir/d3.jpg")
            ),
            emptyList()
        )
        assertEquals(1, roster.entryOf(1L)!!.totalPairs)
        assertEquals(3, roster.entryOf(4L)!!.totalPairs)
        // 캐릭터 둘을 합쳐 10짝(5장 전수)이 되지 않는다는 것이 이 시험의 요점이다.
        assertEquals(4, roster.entries.sumOf { it.totalPairs })
    }

    @Test
    fun `판은 그 캐릭터 몫으로만 세어진다`() {
        val roster = DuelImageRoster.build(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            listOf(
                match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
                match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a2.jpg"),
                match("$dir/b1.jpg", "$dir/b2.jpg")
            )
        )
        assertEquals(2, roster.entryOf(1L)!!.played)
        // 같은 짝을 두 번 붙였으므로 판은 둘이고 **덮은 짝은 하나**다.
        assertEquals(1, roster.entryOf(1L)!!.coveredPairs)
        assertEquals(0, roster.entryOf(1L)!!.remainingPairs)
        assertEquals(1, roster.entryOf(2L)!!.played)
        assertFalse(roster.entryOf(1L)!!.untouched)
    }

    @Test
    fun `캐릭터를 넘는 판은 세지 않는다`() {
        // 엑셀·백업으로 들어올 수 있는 모양이다. 어느 한쪽에 세면 진행률이 100%를 넘는다.
        val roster = DuelImageRoster.build(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            listOf(match("$dir/a1.jpg", "$dir/b1.jpg", "$dir/a1.jpg"))
        )
        assertEquals(0, roster.entryOf(1L)!!.played)
        assertEquals(0, roster.entryOf(2L)!!.played)
        assertTrue(roster.entries.all { it.untouched })
    }

    @Test
    fun `이제 없는 이미지의 판도 세지 않는다`() {
        // 이미지를 지우면 그 판은 고아다 — 적합이 세어서 알리는 자리이고, 진행률은 지금
        // 붙일 수 있는 짝만 말해야 한다.
        val roster = DuelImageRoster.build(
            listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg")),
            listOf(match("$dir/a1.jpg", "$dir/사라진.jpg", "$dir/a1.jpg"))
        )
        assertEquals(0, roster.entryOf(1L)!!.played)
    }

    @Test
    fun `표기가 갈려도 정규 경로로 대조된다`() {
        val roster = DuelImageRoster.build(
            listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg")),
            listOf(match("$dir/sub/../a1.jpg", "$dir/./a2.jpg"))
        )
        assertEquals(1, roster.entryOf(1L)!!.played)
    }

    @Test
    fun `참가자 코드는 목록의 표기 그대로다`() {
        // 정규화한 경로를 참가자로 쓰면 저장에 정규 표기가 들어가고, 정규화가 실패하는 날
        // 코드가 죽는다(DuelImageParticipants가 지키는 규칙과 한 벌이다).
        val roster = DuelImageRoster.build(
            listOf(character(1L, "가", "$dir/./a1.jpg", "$dir/a2.jpg")),
            emptyList()
        )
        assertEquals(listOf("$dir/./a1.jpg", "$dir/a2.jpg"), roster.entryOf(1L)!!.paths)
    }

    @Test
    fun `빈 세계관은 예외가 아니라 빈 목록이다`() {
        val roster = DuelImageRoster.build(emptyList(), emptyList())
        assertFalse(roster.any)
        assertFalse(roster.hasSkipped)
        assertNull(roster.entryOf(1L))
    }

    // ── 캐릭터 몫으로 나누기 (B-104 소비처 ⓑ·ⓒ · 설계 13-5) ──
    //
    // 이 함수의 산출물로 **적합이 캐릭터마다 따로 돈다.** 잘못 나누면 남의 판이 섞여
    // 대표 추첨과 걸러낼 후보가 아무 판도 근거하지 않은 순위를 따른다.

    private fun verdict(vararg members: String) = com.novelcharacter.app.data.model.DuelCounterVerdict(
        axisId = 1L,
        kind = com.novelcharacter.app.data.model.DuelCounterVerdict.KIND_COUNTER,
        shape = DuelRecords.shapeOf(members.toList())!!,
        memberCodes = DuelRecords.encodeMembers(members.toList()),
        memberKey = DuelRecords.memberKey(members.toList())
    )

    @Test
    fun `판은 두 참가자가 같은 캐릭터일 때만 그 캐릭터 몫이다`() {
        val splits = DuelImageRoster.split(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            listOf(
                match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
                match("$dir/b1.jpg", "$dir/b2.jpg", "$dir/b2.jpg"),
                // 캐릭터를 넘는 판 — 엑셀·백업이 만들 수 있다. 어느 쪽에도 세지 않는다.
                match("$dir/a1.jpg", "$dir/b1.jpg", "$dir/a1.jpg")
            ),
            emptyList()
        )
        assertEquals(listOf(1L, 2L), splits.map { it.characterId })
        assertEquals(1, splits[0].matches.size)
        assertEquals(1, splits[1].matches.size)
    }

    @Test
    fun `처분은 참가자가 전부 같은 캐릭터일 때만 따라간다`() {
        val splits = DuelImageRoster.split(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            emptyList(),
            listOf(
                verdict("$dir/a1.jpg", "$dir/a2.jpg"),
                // 반쪽이 남의 그림이다 — 반쪽만 세면 짝이 맞지 않는 제외가 생긴다.
                verdict("$dir/a3.jpg", "$dir/b1.jpg"),
                verdict("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg")
            )
        )
        assertEquals(2, splits[0].verdicts.size)
        assertTrue(splits[1].verdicts.isEmpty())
    }

    @Test
    fun `두 장이 없는 캐릭터는 나눌 몫이 없다 — build와 같은 규칙이다`() {
        val splits = DuelImageRoster.split(
            listOf(
                character(1L, "가", "$dir/a1.jpg"),
                character(2L, "나")
            ),
            listOf(match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg")),
            emptyList()
        )
        assertTrue(splits.isEmpty())
    }

    /**
     * **나누는 데까지만** 증명하는 시험이다 — 여기서 더 나가지 않는 것이 요점이다.
     *
     * `split`의 주인 찾기는 정규 경로로 하지만 그 산출물을 받는 `DuelRecords.resolve`는
     * 참가자를 **글자 그대로** 대조한다. 그래서 표기가 갈린 판은 **여기서는 나뉘고 적합에서는
     * 고아가 된다.** 이 시험이 *"그러니 점수에도 반영된다"*까지 주장하면 **없는 보장을 증명한
     * 것처럼 보인다** — 그 자리는 `split`의 KDoc이 사실대로 적고 백로그 **B-174**가 든다.
     */
    @Test
    fun `판의 표기가 목록과 달라도 나뉜다 — 다만 나누기까지다`() {
        val splits = DuelImageRoster.split(
            listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg")),
            listOf(match("$dir/./a1.jpg", "$dir/a2.jpg", "$dir/a2.jpg")),
            emptyList()
        )
        assertEquals(1, splits.single().matches.size)
        // **판의 코드는 손대지 않고 그대로 넘긴다** — 여기서 정규 표기로 고쳐 적으면
        // 저장된 값과 다른 문자열이 적합으로 흘러가 `build`와 또 다른 방식으로 어긋난다.
        assertEquals("$dir/./a1.jpg", splits.single().matches.single().aCode)
    }
}
