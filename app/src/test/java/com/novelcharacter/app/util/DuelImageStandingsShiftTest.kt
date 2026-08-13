package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **이미지 축 순위표의 수가 얼마나 움직이는가** — B-175의 착수 조건을 실행으로 잰다.
 *
 * 확정 문서 17번이 이 판에 조건을 하나 달았다: *"통일 전후로 판 수가 얼마나 움직이는지 먼저
 * 실측해 4장 행에 적는다 — 사용자가 보는 숫자가 바뀌는 변경이라, 얼마나 바뀌는지 모르고
 * 내보내면 그 자체가 조용한 변경이다."*
 *
 * 실사용 데이터는 사용자 기기에만 있어 여기서 잴 수 없다. **잴 수 있는 것은 "어떤 모양의
 * 데이터에서 어느 수가 얼마나 움직이는가"**이고, 그것을 종전 경로와 새 경로를 **같은 자료에
 * 나란히 돌려** 잰다. 종전 경로를 여기 다시 적어 두는 것이 이 파일의 값이다 — 지워 버리면
 * *"움직였다"*는 주장을 다음 사람이 확인할 길이 없다.
 *
 * ## 종전 경로가 무엇이었는가
 * 순위표는 `stateOf(axis, entry.paths)`를 탔다. 그 함수는 **축의 판을 전부** 넘기고 대조는
 * **글자 그대로**였다. 그래서 두 가지가 함께 틀렸다 — ⓐ 남의 캐릭터 판이 전부 고아로 읽혀
 * *"지워진 참가자 N명의 판"*이라 고지됐고(**아무것도 지워지지 않았다**) ⓑ 표기가 갈린 내 판이
 * 점수에서 빠졌다.
 */
class DuelImageStandingsShiftTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    private fun character(id: Long, name: String, vararg paths: String) = Character(
        id = id,
        name = name,
        code = "C-$id",
        imagePaths = paths.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    )

    private fun match(a: String, b: String, winner: String? = null) =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner, code = "M-$a-$b")

    /** 순위표가 실제로 읽는 수들. */
    private data class Read(
        val used: Int,
        val orphan: Int,
        val missing: Int,
        val malformed: Int,
        val cross: Int,
        val topScore: Int
    )

    /** **종전 경로** — 축의 판 전부 + 글자 대조. 지우지 않고 남겨 둔다(위 KDoc). */
    private fun before(
        paths: List<String>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>
    ): Read {
        val records = DuelRecords.resolve(paths, matches, verdicts)
        val fit = DuelRating.fit(records.participants, records.matches, records.excludedPairs)
        return Read(
            used = fit.usedMatches,
            orphan = fit.orphanMatches,
            missing = records.missingParticipants,
            malformed = fit.malformedMatches,
            cross = 0,
            topScore = fit.ratings.maxOfOrNull { it.score } ?: 0
        )
    }

    /** **새 경로** — 그 캐릭터 몫만 + 정규 경로 대조. */
    private fun after(
        characterId: Long,
        characters: List<Character>,
        matches: List<DuelMatch>,
        verdicts: List<DuelCounterVerdict>
    ): Read {
        val split = DuelImageRoster.splitOf(
            characterId,
            characters.single { it.id == characterId }.imagePathList(),
            matches,
            verdicts,
            DuelImageRoster.owners(characters)
        )
        val records = DuelRecords.resolve(
            split.paths, split.matches, split.verdicts, DuelRecords.CodeMatch.IMAGE_PATH
        )
        val fit = DuelRating.fit(records.participants, records.matches, records.excludedPairs)
        return Read(
            used = fit.usedMatches,
            orphan = fit.orphanMatches,
            missing = records.missingParticipants,
            malformed = fit.malformedMatches,
            cross = split.crossCharacterMatches,
            topScore = fit.ratings.maxOfOrNull { it.score } ?: 0
        )
    }

    /**
     * **실사용 모양에서는 점수가 한 점도 안 움직인다** — 고지만 바로잡힌다.
     *
     * 이것이 이 변경의 위험 상한이다. 판을 적는 자리가 목록의 문자열을 그대로 쓰므로
     * 표기가 갈리는 것은 **손편집된 엑셀뿐**이고(4장 B-175 행이 🔵인 근거), 그런 자료가 없으면
     * 달라지는 것은 *"지워진 참가자 N명의 판 M개"*라는 **거짓 문장이 사라지는 것**뿐이다.
     */
    @Test
    fun `표기가 갈리지 않은 자료에서는 점수가 그대로이고 거짓 고지만 사라진다`() {
        val characters = listOf(
            character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"),
            character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg", "$dir/b3.jpg"),
            character(3L, "다", "$dir/c1.jpg", "$dir/c2.jpg", "$dir/c3.jpg")
        )
        val matches = listOf(
            match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
            match("$dir/a2.jpg", "$dir/a3.jpg", "$dir/a2.jpg"),
            match("$dir/a1.jpg", "$dir/a3.jpg", "$dir/a1.jpg"),
            match("$dir/b1.jpg", "$dir/b2.jpg", "$dir/b1.jpg"),
            match("$dir/b2.jpg", "$dir/b3.jpg", "$dir/b2.jpg"),
            match("$dir/c1.jpg", "$dir/c2.jpg", "$dir/c1.jpg")
        )
        val mine = characters[0].imagePathList()

        val old = before(mine, matches, emptyList())
        val new = after(1L, characters, matches, emptyList())

        // 산출은 ASCII로 낸다 — 하네스 출력이 UTF-8이 아닌 자리가 있어 한글이 물음표로 깨진다.
        println("B-175 shift (typical) | before=$old | after=$new")

        assertEquals("점수에 든 판은 그대로다", old.used, new.used)
        assertEquals("점수도 그대로다", old.topScore, new.topScore)
        // 종전에는 **남의 캐릭터 판 셋**이 고아였고, 그 판에 적힌 그림 다섯이 *"지워진 참가자"*였다
        // (`c3`은 어느 판에도 없어 세어지지 않는다 — 세는 것은 그림이 아니라 **판에 남은 코드**다).
        assertEquals(3, old.orphan)
        assertEquals(5, old.missing)
        assertEquals("아무것도 지워지지 않았으므로 0이 옳다", 0, new.orphan)
        assertEquals(0, new.missing)
        assertEquals(0, new.cross)
    }

    /**
     * **한 캐릭터만 대결한 축에서는 종전과 지금이 글자 그대로 같다.**
     *
     * 이 시험이 위험의 바닥을 정한다 — 이미지 축을 한 캐릭터에만 쓴 사용자에게는
     * 이 판이 아무 수도 움직이지 않는다.
     */
    @Test
    fun `한 캐릭터만 대결한 축은 종전과 지금이 같다`() {
        val characters = listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"))
        val matches = listOf(
            match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
            match("$dir/a2.jpg", "$dir/a3.jpg", "$dir/a2.jpg")
        )
        assertEquals(
            before(characters[0].imagePathList(), matches, emptyList()),
            after(1L, characters, matches, emptyList())
        )
    }

    /**
     * **표기가 갈린 자료에서 실제로 얼마나 움직이는가** — 이 판이 고친 것의 크기다.
     *
     * 손편집된 엑셀이 만들 수 있는 넷을 한 자리에 모았다: 표기가 갈린 내 판 · 지워진 그림이
     * 낀 판 · 남의 그림이 낀 판 · 남의 캐릭터 판. **점수가 움직이는 것은 첫째 하나 때문**이고,
     * 나머지 셋은 고지가 움직인다.
     */
    @Test
    fun `표기가 갈린 자료에서 점수에 든 판이 늘고 거짓 고지가 걷힌다`() {
        val characters = listOf(
            character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"),
            character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg", "$dir/b3.jpg"),
            character(3L, "다", "$dir/c1.jpg", "$dir/c2.jpg", "$dir/c3.jpg")
        )
        val matches = listOf(
            match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
            match("$dir/a2.jpg", "$dir/a3.jpg", "$dir/a2.jpg"),
            match("$dir/a1.jpg", "$dir/a3.jpg", "$dir/a1.jpg"),
            // ① 표기가 갈린 내 판 — 종전에는 점수에서 빠졌다.
            match("$dir/./a1.jpg", "$dir/sub/../a2.jpg", "$dir/./a1.jpg"),
            // ② 지워진 그림이 낀 판 — 종전에도 고아였고 지금도 고아다(**그것이 옳다**).
            match("$dir/a1.jpg", "$dir/사라진.jpg", "$dir/a1.jpg"),
            // ③ 남의 그림이 낀 판 — 종전에는 '지워진 참가자의 판'이라 했다.
            match("$dir/a2.jpg", "$dir/b1.jpg", "$dir/a2.jpg"),
            // ④ 남의 캐릭터 판 — 내 화면과 아무 상관이 없다.
            match("$dir/b1.jpg", "$dir/b2.jpg", "$dir/b1.jpg"),
            match("$dir/b2.jpg", "$dir/b3.jpg", "$dir/b2.jpg"),
            match("$dir/c1.jpg", "$dir/c2.jpg", "$dir/c1.jpg")
        )
        val mine = characters[0].imagePathList()

        val old = before(mine, matches, emptyList())
        val new = after(1L, characters, matches, emptyList())

        println("B-175 shift (mixed notation) | before=$old | after=$new")

        // 점수에 든 판: 3 → 4. **늘어난 하나가 ①이다.**
        assertEquals(3, old.used)
        assertEquals(4, new.used)
        // 고아로 고지되던 판: 6 → 1. 남은 하나가 ②이고 **그 하나만이 참이었다.**
        assertEquals(6, old.orphan)
        assertEquals(1, new.orphan)
        // *"지워진 참가자"*: 8 → 1. 종전 여덟은 남의 그림 다섯 + 갈린 표기 둘 + 지워진 그림 하나다.
        assertEquals(8, old.missing)
        assertEquals(1, new.missing)
        // 갈라서 새로 말하는 것: 남의 그림이 낀 판 하나(③).
        assertEquals(1, new.cross)
        // 판이 늘었으므로 **점수도 움직인다** — 그것이 이 판의 요점이고, 실기기 확인이 붙는 이유다.
        assertNotEquals(old.topScore, new.topScore)
    }

    /**
     * **승자 코드도 같은 잣대로 견준다** — 갈리면 멀쩡한 판이 *"깨진 판"*으로 세어진다.
     *
     * 종전에는 참가자 둘의 표기는 맞는데 승자만 갈린 판이 `malformedMatches`에 들어
     * *"승자가 두 참가자 중 어느 쪽도 아닙니다"*로 고지됐다. 승자는 그 둘 중 하나였다.
     */
    @Test
    fun `승자 표기만 갈린 판이 깨진 판으로 세어지지 않는다`() {
        val characters = listOf(character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg"))
        val matches = listOf(match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/./a1.jpg"))
        val mine = characters[0].imagePathList()

        val old = before(mine, matches, emptyList())
        val new = after(1L, characters, matches, emptyList())

        assertEquals("종전에는 깨진 판으로 세었다", 1, old.malformed)
        assertEquals(0, old.used)
        assertEquals(0, new.malformed)
        assertEquals(1, new.used)
    }

    /**
     * **점수표와 순위표가 같은 수를 말한다** — [DuelScoreIndex]의 계약 1.
     *
     * 이 판이 한 커밋에서 셋을 함께 옮긴 이유가 이것이다. 한쪽만 정규화하면 대표 추첨이
     * 보는 순위와 순위표가 보이는 순위가 갈리고, 그 갈림은 **오류가 나지 않아** 아무도 모른다.
     */
    @Test
    fun `순위표와 점수표가 같은 판을 세고 같은 점수를 낸다`() {
        val characters = listOf(
            character(1L, "가", "$dir/a1.jpg", "$dir/a2.jpg", "$dir/a3.jpg"),
            character(2L, "나", "$dir/b1.jpg", "$dir/b2.jpg")
        )
        val matches = listOf(
            match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg"),
            match("$dir/./a2.jpg", "$dir/a3.jpg", "$dir/./a2.jpg"),
            match("$dir/a1.jpg", "$dir/사라진.jpg", "$dir/a1.jpg"),
            match("$dir/b1.jpg", "$dir/b2.jpg", "$dir/b1.jpg")
        )

        // 순위표가 타는 길과 점수표가 타는 길은 **같은 split·같은 resolve**다.
        val split = DuelImageRoster.splitOf(
            1L,
            characters.single { it.id == 1L }.imagePathList(),
            matches,
            emptyList(),
            DuelImageRoster.owners(characters)
        )
        val records = DuelRecords.resolve(
            split.paths, split.matches, split.verdicts, DuelRecords.CodeMatch.IMAGE_PATH
        )
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )
        val rows = DuelStandings.rows(twoPass.fit, twoPass.report, records)
        val scores = DuelScoreIndex.of(
            axisId = 1L,
            axisCode = "AX-1",
            axisName = "그림",
            universeId = 1L,
            rows = rows,
            fit = twoPass.fit,
            missingParticipants = records.missingParticipants,
            scanned = split.matches.size
        )

        // 순위표 줄의 코드가 **목록의 표기**라 점수표의 열쇠로 그대로 맞는다 — 이것이
        // `RepresentativeWeighting`이 받는 표의 열쇠이기도 하다.
        for (path in split.paths) {
            val row = rows.firstOrNull { it.code == path }
            if (row == null || row.provisional) continue
            assertEquals(row.score, scores.scoreOf(path))
        }
        assertTrue("표기가 갈린 판이 점수에 들었다", rows.any { !it.provisional })
        assertEquals("지워진 그림의 판은 세어서 알린다", 1, scores.orphanMatches)
        assertEquals(1, scores.missingParticipants)
    }

    /** 목록에 적힌 표기 그대로의 경로 — 화면이 참가자로 넘기는 것과 같은 값이다. */
    private fun Character.imagePathList(): List<String> =
        CharacterRepresentativeImage.paths(imagePaths)
}
