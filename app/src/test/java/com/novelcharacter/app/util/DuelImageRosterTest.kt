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
     * **나누기에서 점수까지 한 잣대다** (B-175 — 로드맵 20판).
     *
     * 종전에는 여기까지만 성립했다 — `split`의 주인 찾기는 정규 경로인데 그 산출물을 받는
     * `DuelRecords.resolve`가 참가자를 **글자 그대로** 대조해, 표기가 갈린 판은 *나뉘고
     * 적합에서는 고아가* 됐다. 그 자리를 이 판이 닫았으므로 이 시험은 **점수까지** 재고,
     * 아래 한 줄(`usedMatches`)이 종전이면 0이었다.
     */
    @Test
    fun `판의 표기가 목록과 달라도 나뉘고 점수에도 든다`() {
        val splits = DuelImageRoster.split(
            listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg")),
            listOf(match("$dir/./a1.jpg", "$dir/a2.jpg", "$dir/a2.jpg")),
            emptyList()
        )
        assertEquals(1, splits.single().matches.size)
        // **판의 코드는 손대지 않고 그대로 넘긴다** — 여기서 정규 표기로 고쳐 적으면
        // 저장된 값과 다른 문자열이 적합으로 흘러가 `build`와 또 다른 방식으로 어긋난다.
        assertEquals("$dir/./a1.jpg", splits.single().matches.single().aCode)

        val split = splits.single()
        val resolved = DuelRecords.resolve(
            split.paths, split.matches, split.verdicts, DuelRecords.CodeMatch.IMAGE_PATH
        )
        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals("표기가 갈렸어도 점수에 든다", 1, fit.usedMatches)
        assertEquals(0, fit.orphanMatches)
        // 참가자 수가 늘지 않는다 — 같은 파일이 두 참가자로 갈라지면 순위표에 같은 그림이 두 줄이다.
        assertEquals(2, resolved.participants.size)
        assertEquals(0, resolved.missingParticipants)
    }

    // ── 몫을 가르는 잣대 (B-175 — 로드맵 20판) ──
    //
    // 종전에는 *"내 것"*과 *"내 것 아님"* 둘로만 갈라, **성격이 다른 둘이 한 자루에** 있었다:
    // 지워진 그림이 낀 판(내 대결의 일부였다)과 남의 그림이 낀 판(누구의 대결도 아니다)이다.
    // 화면이 할 말이 서로 다르므로 갈라야 하고, 갈리지 않은 동안 순위표는 남의 판을
    // *"지워진 참가자의 판"*이라 말했다 — 지워지지 않았으므로 거짓 고지다.

    @Test
    fun `소유 표는 두 장 미만인 캐릭터의 그림도 담는다`() {
        // 두 장이었다가 한 장이 된 캐릭터가 표에서 빠지면 그 남은 그림이 **주인 없는 그림**으로
        // 보여, 그 그림이 낀 판이 상대 캐릭터의 몫으로 넘어간다(그쪽 순위표가 그것을
        // *"지워진 그림의 판"*이라 잘못 고지한다).
        val owners = DuelImageRoster.owners(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg")
            )
        )
        assertEquals(3, owners.size)
        assertEquals(2L, owners.of("$dir/b1.jpg"))
        // 목록에 올릴지는 build가 따로 가린다 — **누가 가졌는가**와 **대결할 수 있는가**는 다르다.
        val roster = DuelImageRoster.build(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg")
            ),
            emptyList()
        )
        assertNull(roster.entryOf(2L))
        assertEquals(1, roster.skippedSingleImage)
    }

    @Test
    fun `몫 판정은 셋으로 갈린다 — 내 것 · 캐릭터를 넘음 · 주인 없음`() {
        val owners = DuelImageRoster.owners(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            )
        )
        assertEquals(
            DuelImageRoster.Claim.Owned(1L, complete = true),
            DuelImageRoster.claimOf("$dir/a1.jpg", "$dir/a2.jpg", owners)
        )
        // 한쪽이 지워졌어도 **내 몫이다** — 그 판은 내 대결의 일부였다. complete만 false다.
        assertEquals(
            DuelImageRoster.Claim.Owned(1L, complete = false),
            DuelImageRoster.claimOf("$dir/a1.jpg", "$dir/사라진.jpg", owners)
        )
        assertEquals(
            DuelImageRoster.Claim.Cross(setOf(1L, 2L)),
            DuelImageRoster.claimOf("$dir/a1.jpg", "$dir/b1.jpg", owners)
        )
        assertEquals(
            DuelImageRoster.Claim.None,
            DuelImageRoster.claimOf("$dir/없다1.jpg", "$dir/없다2.jpg", owners)
        )
        // 처분은 셋 이상일 수 있다(순환) — 잣대는 판과 **같은 것**이다.
        assertEquals(
            DuelImageRoster.Claim.Cross(setOf(1L, 2L)),
            DuelImageRoster.claimOf(
                listOf("$dir/a1.jpg", "$dir/a2.jpg", "$dir/b1.jpg"), owners
            )
        )
    }

    @Test
    fun `지워진 그림의 판은 내 몫으로 남고 남의 그림이 낀 판은 세어서 갈라 말한다`() {
        val splits = DuelImageRoster.split(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            listOf(
                match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
                // 그림 하나를 지웠다 — 이 판은 여전히 '가'의 대결이고, 적합이 고아로 세어 알린다.
                match("$dir/a1.jpg", "$dir/사라진.jpg", "$dir/a1.jpg"),
                // 캐릭터를 넘는 판 — 어느 몫도 아니지만 **낀 둘이 각자 센다.**
                match("$dir/a2.jpg", "$dir/b1.jpg", "$dir/a2.jpg"),
                // 둘 다 주인이 없다 — 어느 캐릭터 화면의 몫도 아니다(기록 화면이 든다).
                match("$dir/없다1.jpg", "$dir/없다2.jpg")
            ),
            emptyList()
        )
        val mine = splits.single { it.characterId == 1L }
        assertEquals("지워진 그림의 판이 몫에 남는다", 2, mine.matches.size)
        assertEquals(1, mine.crossCharacterMatches)
        assertEquals(1, splits.single { it.characterId == 2L }.crossCharacterMatches)

        // 그 판이 **적합에서 고아로 세어진다** — 종전에는 split이 통째로 버려 어느 화면에서도
        // 세어지지 않았고, 그것이 *"쌓아 둔 판이 조용히 사라졌다"*였다.
        val resolved = DuelRecords.resolve(
            mine.paths, mine.matches, mine.verdicts, DuelRecords.CodeMatch.IMAGE_PATH
        )
        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals(1, fit.usedMatches)
        assertEquals(1, fit.orphanMatches)
        assertEquals(1, resolved.missingParticipants)
    }

    @Test
    fun `남의 그림이 낀 처분도 갈라 센다`() {
        val splits = DuelImageRoster.split(
            listOf(
                character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"),
                character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
            ),
            emptyList(),
            listOf(
                verdict("$dir/a1.jpg", "$dir/a2.jpg"),
                verdict("$dir/a3.jpg", "$dir/b1.jpg"),
                // 지워진 그림이 낀 처분은 **남긴다** — 빼면 사용자가 물릴 수도 없는 처분이 된다.
                verdict("$dir/a1.jpg", "$dir/사라진.jpg")
            )
        )
        val mine = splits.single { it.characterId == 1L }
        assertEquals(2, mine.verdicts.size)
        assertEquals(1, mine.crossCharacterVerdicts)
        assertEquals(1, splits.single { it.characterId == 2L }.crossCharacterVerdicts)
    }

    @Test
    fun `splitOf는 split과 글자 그대로 같은 몫을 낸다`() {
        val characters = listOf(
            character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"),
            character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
        )
        val matches = listOf(
            match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
            match("$dir/a1.jpg", "$dir/b1.jpg", "$dir/a1.jpg")
        )
        val verdicts = listOf(verdict("$dir/a1.jpg", "$dir/a2.jpg"))
        // 두 벌로 적으면 *"순위표가 세는 판"*과 *"점수표가 세는 판"*이 갈린다 — B-175가 등재된 이유다.
        assertEquals(
            DuelImageRoster.split(characters, matches, verdicts).single { it.characterId == 1L },
            DuelImageRoster.splitOf(1L, characters, matches, verdicts)
        )
        assertNull("목록에 없는 캐릭터는 몫도 없다", DuelImageRoster.splitOf(9L, characters, matches, verdicts))
    }

    @Test
    fun `소유 표를 넘기면 정규화를 다시 하지 않는다`() {
        // 한 판 누를 때마다 다시 도는 경로라, 표를 새로 만들면 그림 수만큼의 파일 시스템
        // 호출이 판마다 붙는다. 넘긴 표를 그대로 쓰는지 **잰 문자열 수**로 확인한다.
        val characters = listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"))
        val canon = ImagePathMatch.Canonicalizer()
        assertEquals(0, canon.measured)
        canon.of("$dir/a1.jpg")
        canon.of("$dir/a1.jpg")
        assertEquals("같은 문자열은 한 번만 잰다", 1, canon.measured)

        val owners = DuelImageRoster.owners(characters)
        val matches = List(20) { match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg") }
        assertEquals(
            DuelImageRoster.split(characters, matches, emptyList()),
            DuelImageRoster.split(characters, matches, emptyList(), owners)
        )
    }

    /**
     * **여기서 재는 것과 못 재는 것을 갈라 적는다** — 안 그러면 없는 보장을 증명한 것처럼 보인다.
     *
     * **재는 것:** 몫 가르기가 판을 훑을 때 **새로 재는 문자열이 없다**(같은 경로가 판 수만큼
     * 되풀이돼도 표가 그대로다). 이것이 이 표를 넘겨받는 이유 전부다.
     *
     * **못 재는 것:** *"안에서 `ImagePathMatch.canonical`을 직접 부르지 않는가."* 직접 부르면
     * 이 표를 지나치므로 **여기 담기지도 않고 숫자도 안 움직인다** — 결과가 같고 느려지기만
     * 하므로 어떤 셈으로도 안 걸린다. 이 판의 첫 벌이 실제로 `build`에서 그랬고 **시험도 검사도
     * 전부 초록이었다.** 그 자리는 호출부의 주석이 든다(기계로 볼 축이 없다는 사실을 여기 적어
     * 두는 것이, 다음 사람이 이 시험을 그 보장으로 잘못 읽지 않게 하는 유일한 방법이다).
     */
    @Test
    fun `판이 아무리 쌓여도 넘겨받은 표는 새로 재지 않는다`() {
        val characters = listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"))
        val owners = DuelImageRoster.owners(characters)
        assertEquals("표를 만들며 그림 수만큼 잰다", 3, owners.measured)

        val matches = List(50) { match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg") }
        DuelImageRoster.build(characters, matches, owners)
        DuelImageRoster.split(characters, matches, emptyList(), owners)
        assertEquals("판 50개를 훑어도 새로 잴 문자열이 없다", 3, owners.measured)

        // 표기가 갈린 판이 들어오면 **그때만** 는다 — 목록에 없던 문자열이라 잴 수밖에 없고,
        // 그것도 문자열당 한 번이다(같은 표기가 50번 더 와도 그대로다).
        val odd = List(50) { match("$dir/./a1.jpg", "$dir/a2.jpg", "$dir/./a1.jpg") }
        DuelImageRoster.split(characters, odd, emptyList(), owners)
        assertEquals(4, owners.measured)
    }
}
