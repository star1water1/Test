package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelImagePrune] — 걸러낼 이미지 후보의 판정 (B-104 소비처 ⓒ).
 *
 * **이 판의 방어선은 셋이고 셋 다 시험이 유일한 검출이다** — 잘못 골라도 앱은 죽지 않고
 * *"지우시죠"*라고 엉뚱한 그림을 가리킬 뿐이다:
 *
 * 1. *"판이 적으면 후보가 아니다"* — 흔들리는 점수로 지우기를 권하면 앱이 지어낸 근거다.
 * 2. *"하위권은 비율 그대로다"* — 작은 집합에 억지로 한 장을 세우는 특례를 두면 그림 둘뿐인
 *    캐릭터에서 **절반이 후보**가 된다. 안 두는 것이 판정이고, 안 두었다는 사실은
 *    **읽어서 알 수 없으므로** 못 박는다(B-112가 남긴 관행).
 * 3. *"동점은 함께 든다"* — 같은 점수인데 하나만 후보가 되면 근거를 댈 수 없다.
 */
class DuelImagePruneTest {

    private fun match(a: String, b: String, winner: String?): DuelMatch =
        DuelMatch(axisId = 1L, aCode = a, bCode = b, winnerCode = winner)

    /** 순위표가 서는 것과 **같은 경로**로 한 캐릭터 몫의 점수표를 세운다. */
    private fun scoresOf(codes: List<String>, matches: List<DuelMatch>): DuelScoreIndex.AxisScores {
        val records = DuelRecords.resolve(codes, matches, emptyList())
        val twoPass = DuelCounterRelations.analyzeTwoPass(
            participants = records.participants,
            matches = records.matches,
            confirmedCounters = records.excludedPairs
        )
        return DuelScoreIndex.of(
            axisId = 1L,
            axisCode = "AX",
            axisName = "아름다움",
            universeId = 7L,
            rows = DuelStandings.rows(twoPass.fit, twoPass.report, records),
            fit = twoPass.fit,
            missingParticipants = records.missingParticipants,
            scanned = matches.size
        )
    }

    /**
     * 다섯 장이 한 줄로 갈리는 판 — `a`가 가장 세고 `e`가 가장 약하다.
     * 짝 전수(10)를 한 바퀴 돌려 그림마다 4판씩이다.
     */
    private fun ladderOfFive(): DuelScoreIndex.AxisScores {
        val codes = listOf("a", "b", "c", "d", "e")
        val matches = ArrayList<DuelMatch>()
        for (i in codes.indices) {
            for (j in i + 1 until codes.size) {
                matches.add(match(codes[i], codes[j], codes[i]))  // 앞선 것이 언제나 이긴다
            }
        }
        return scoresOf(codes, matches)
    }

    @Test
    fun `다섯 장이면 하위 20퍼센트에 꼴찌 한 장이 든다`() {
        val candidates = DuelImagePrune.candidates(
            ladderOfFive(),
            DuelImagePrune.Options(bottomPercent = 20, minPlayed = 3)
        )
        assertEquals(listOf("e"), candidates.map { it.path })
        val only = candidates.single()
        assertEquals(5, only.scored)
        assertEquals(5, only.rank)
        assertEquals(4, only.played)
    }

    @Test
    fun `넉 장이면 하위 20퍼센트에 아무도 없다 — 그것이 그 말의 뜻이다`() {
        val codes = listOf("a", "b", "c", "d")
        val matches = ArrayList<DuelMatch>()
        for (i in codes.indices) {
            for (j in i + 1 until codes.size) matches.add(match(codes[i], codes[j], codes[i]))
        }
        val candidates = DuelImagePrune.candidates(
            scoresOf(codes, matches),
            DuelImagePrune.Options(bottomPercent = 20, minPlayed = 1)
        )
        assertTrue("다섯 장이 있어야 한 장이 하위 20%에 든다", candidates.isEmpty())
    }

    @Test
    fun `하위권을 넓히면 넉 장에서도 후보가 나온다`() {
        val codes = listOf("a", "b", "c", "d")
        val matches = ArrayList<DuelMatch>()
        for (i in codes.indices) {
            for (j in i + 1 until codes.size) matches.add(match(codes[i], codes[j], codes[i]))
        }
        val candidates = DuelImagePrune.candidates(
            scoresOf(codes, matches),
            DuelImagePrune.Options(bottomPercent = 50, minPlayed = 1)
        )
        assertEquals(listOf("d", "c"), candidates.map { it.path })
    }

    @Test
    fun `최소 판 수를 못 채우면 하위권이어도 후보가 아니다`() {
        val candidates = DuelImagePrune.candidates(
            ladderOfFive(),
            DuelImagePrune.Options(bottomPercent = 20, minPlayed = 5)  // 실제로는 4판씩이다
        )
        assertTrue("흔들리는 점수로 지우기를 권하지 않는다", candidates.isEmpty())
    }

    @Test
    fun `한 판도 안 친 그림은 점수표에 없어 후보가 될 수 없다`() {
        // f는 참가자 목록에만 있고 판이 없다 — DuelScoreIndex의 계약 2가 표에서 뺀다.
        val codes = listOf("a", "b", "c", "d", "e", "f")
        val matches = ArrayList<DuelMatch>()
        val played = codes.take(5)
        for (i in played.indices) {
            for (j in i + 1 until played.size) matches.add(match(played[i], played[j], played[i]))
        }
        val scores = scoresOf(codes, matches)
        assertEquals("점수는 다섯 장 몫이다", 5, scores.scored)
        val candidates = DuelImagePrune.candidates(
            scores, DuelImagePrune.Options(bottomPercent = 20, minPlayed = 1)
        )
        assertEquals(listOf("e"), candidates.map { it.path })
    }

    @Test
    fun `동점은 함께 들거나 함께 빠진다`() {
        // 여섯 장에서 아래 둘이 서로만 비겨 동점이 된다 — 하위 33%면 둘 다 들어야 한다.
        val codes = listOf("a", "b", "c", "d", "e", "f")
        val matches = ArrayList<DuelMatch>()
        for (i in codes.indices) {
            for (j in i + 1 until codes.size) {
                // e·f는 서로 비기고 나머지에게는 모두 진다 — 둘의 전적이 완전히 같다.
                val winner = if (codes[i] == "e" && codes[j] == "f") null else codes[i]
                matches.add(match(codes[i], codes[j], winner))
            }
        }
        val scores = scoresOf(codes, matches)
        val ranks = DuelScoreIndex.rankWithinScored(scores)
        assertEquals("동점이라 등수가 같다", ranks["e"], ranks["f"])
        val candidates = DuelImagePrune.candidates(
            scores, DuelImagePrune.Options(bottomPercent = 34, minPlayed = 1)
        )
        assertEquals(setOf("e", "f"), candidates.map { it.path }.toSet())
    }

    @Test
    fun `밖에서 편집된 설정값은 접힌다 — 판정이 이상해지지 않는다`() {
        val wild = DuelImagePrune.Options(bottomPercent = 900, minPlayed = -3)
        assertEquals(50, wild.percent)
        assertEquals(1, wild.played)
        val zero = DuelImagePrune.Options(bottomPercent = 0, minPlayed = 0)
        assertEquals(1, zero.percent)
        assertEquals(1, zero.played)
    }

    @Test
    fun `점수가 없는 축에서는 아무도 후보가 아니다`() {
        val empty = DuelScoreIndex.AxisScores.empty(1L, "AX", "아름다움", 7L)
        assertTrue(DuelImagePrune.candidates(empty).isEmpty())
    }
}
