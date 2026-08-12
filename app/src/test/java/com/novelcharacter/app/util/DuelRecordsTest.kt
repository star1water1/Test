package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelRecords]의 계약 — **저장(코드)과 순수 계층(id) 사이의 해석**이 여기서 검증된다.
 *
 * 이 슬라이스가 세운 주장은 대부분 *"캐릭터를 지웠다 되살려도 판이 제자리로 돌아온다"*인데,
 * 그 주장이 성립하는 자리가 정확히 이 파일이다(저장소는 Room이 있어야 돌아 실행 검증이 안 된다).
 */
class DuelRecordsTest {

    private fun match(a: String, b: String, winner: String?, code: String = "m-$a-$b") =
        DuelMatch(axisId = 1, aCode = a, bCode = b, winnerCode = winner, code = code)

    private fun verdict(members: List<String>, kind: String) = DuelCounterVerdict(
        axisId = 1,
        kind = kind,
        shape = DuelRecords.shapeOf(members)!!,
        memberCodes = DuelRecords.encodeMembers(members),
        memberKey = DuelRecords.memberKey(members)
    )

    // ── 살아 있는 참가자와 사라진 참가자 ──

    @Test
    fun `살아 있는 참가자만 participants에 든다`() {
        val resolved = DuelRecords.resolve(
            listOf("a", "b"),
            listOf(match("a", "b", "a"))
        )
        assertEquals(2, resolved.participants.size)
        assertEquals(0, resolved.missingParticipants)
    }

    @Test
    fun `지워진 참가자의 판은 지워지지 않고 고아로 센다`() {
        // 'c'가 휴지통에 들어갔다 — 판은 그대로 남아 있고 참가자 목록에만 없다.
        val resolved = DuelRecords.resolve(
            listOf("a", "b"),
            listOf(match("a", "b", "a"), match("a", "c", "c"))
        )
        assertEquals(2, resolved.matches.size)
        assertEquals(1, resolved.missingParticipants)

        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals("지워진 캐릭터의 판은 적합에서만 빠진다", 1, fit.orphanMatches)
        assertEquals(1, fit.usedMatches)
    }

    @Test
    fun `참가자가 돌아오면 그 판이 그대로 되살아난다`() {
        val matches = listOf(match("a", "b", "a"), match("a", "c", "c"))
        val gone = DuelRecords.resolve(listOf("a", "b"), matches)
        val back = DuelRecords.resolve(listOf("a", "b", "c"), matches)

        assertEquals(1, DuelRating.fit(gone.participants, gone.matches).orphanMatches)
        // 같은 저장 행인데 참가자 목록이 돌아온 것만으로 적합에 든다 — 판을 지웠다면 불가능하다.
        val fit = DuelRating.fit(back.participants, back.matches)
        assertEquals(0, fit.orphanMatches)
        assertEquals(2, fit.usedMatches)
    }

    @Test
    fun `id가 바뀌어도 결과는 같다 — 참조가 코드이기 때문`() {
        val matches = listOf(match("a", "b", "a"), match("b", "c", "b"))
        val one = DuelRecords.resolve(listOf("a", "b", "c"), matches)
        // 복원이 id를 재발급한 상황을 흉내낸다: 참가자 순서가 달라 id 배정이 통째로 바뀐다.
        val two = DuelRecords.resolve(listOf("c", "b", "a"), matches)

        val scoreOf = { r: DuelRecords.Resolved, code: String ->
            DuelRating.fit(r.participants, r.matches).of(r.idOf(code)!!)!!.score
        }
        assertEquals(scoreOf(one, "a"), scoreOf(two, "a"))
        assertEquals(scoreOf(one, "b"), scoreOf(two, "b"))
        assertEquals(scoreOf(one, "c"), scoreOf(two, "c"))
    }

    // ── 무승부와 깨진 판 ──

    @Test
    fun `무승부는 winnerCode가 null이고 0점 5승으로 든다`() {
        val resolved = DuelRecords.resolve(listOf("a", "b"), listOf(match("a", "b", null)))
        assertNull(resolved.matches[0].winnerId)

        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals(1, fit.usedMatches)
        assertEquals(0, fit.malformedMatches)
        assertEquals(0.5, fit.of(resolved.idOf("a")!!)!!.wins, 1e-9)
        assertEquals(
            "무승부만 있으면 둘의 점수가 같다",
            fit.of(resolved.idOf("a")!!)!!.score,
            fit.of(resolved.idOf("b")!!)!!.score
        )
    }

    @Test
    fun `승자가 두 참가자 중 어느 쪽도 아니면 깨진 판으로 센다 — 무승부로 둔갑시키지 않는다`() {
        // 외부에서 편집된 엑셀이 만들 수 있는 행이다. 조용히 버리거나 무승부로 바꾸면
        // 사용자는 자기 데이터가 틀어진 것을 영영 모른다(개발 의도 2번).
        val resolved = DuelRecords.resolve(listOf("a", "b", "x"), listOf(match("a", "b", "x")))
        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals(1, fit.malformedMatches)
        assertEquals(0, fit.usedMatches)
    }

    // ── 층 B의 처분 ──

    @Test
    fun `상성 확정만 점수에서 빠지고 미정은 그대로 둔다`() {
        val resolved = DuelRecords.resolve(
            listOf("a", "b", "c"),
            listOf(match("a", "b", "a"), match("b", "c", "b")),
            listOf(
                verdict(listOf("a", "b"), DuelCounterVerdict.KIND_COUNTER),
                verdict(listOf("b", "c"), DuelCounterVerdict.KIND_UNDECIDED)
            )
        )
        assertEquals(1, resolved.excludedPairs.size)
        assertEquals(1, resolved.undecidedPairs.size)

        val fit = DuelRating.fit(resolved.participants, resolved.matches, resolved.excludedPairs)
        assertEquals("③만 뺀다", 1, fit.excludedMatches)
        assertEquals("②는 그대로 점수에 든다", 1, fit.usedMatches)
    }

    @Test
    fun `순환 처분은 이어지는 변 전부를 덮는다`() {
        val resolved = DuelRecords.resolve(
            listOf("a", "b", "c"),
            emptyList(),
            listOf(verdict(listOf("a", "b", "c"), DuelCounterVerdict.KIND_COUNTER))
        )
        assertEquals(3, resolved.excludedPairs.size)
        val ids = listOf("a", "b", "c").map { resolved.idOf(it)!! }
        assertTrue(resolved.excludedPairs.contains(DuelRating.PairKey.of(ids[0], ids[1])))
        assertTrue(resolved.excludedPairs.contains(DuelRating.PairKey.of(ids[1], ids[2])))
        assertTrue(resolved.excludedPairs.contains(DuelRating.PairKey.of(ids[2], ids[0])))
    }

    @Test
    fun `상성 상대가 지워져도 처분은 해석된다`() {
        // 처분에만 남은 코드도 '사라진 참가자'로 센다 — 조용히 없던 일이 되지 않는다.
        val resolved = DuelRecords.resolve(
            listOf("a"),
            emptyList(),
            listOf(verdict(listOf("a", "gone"), DuelCounterVerdict.KIND_COUNTER))
        )
        assertEquals(1, resolved.excludedPairs.size)
        assertEquals(1, resolved.missingParticipants)
    }

    // ── 처분의 저장 형식 ──

    @Test
    fun `정규 키는 순서와 회전에 흔들리지 않는다`() {
        assertEquals(
            DuelRecords.memberKey(listOf("a", "b", "c")),
            DuelRecords.memberKey(listOf("c", "a", "b"))
        )
        assertEquals(
            DuelRecords.memberKey(listOf("a", "b")),
            DuelRecords.memberKey(listOf("b", "a"))
        )
        assertFalse(
            DuelRecords.memberKey(listOf("a", "b")) == DuelRecords.memberKey(listOf("a", "c"))
        )
    }

    @Test
    fun `구분자가 든 경로도 참가자로 쓸 수 있다 — 이미지 축의 코드는 경로다`() {
        // 구분자를 정해 이어 붙이는 방식이었다면 이 두 집합이 같은 키가 됐다.
        val one = listOf("/img/a|b.png", "/img/c.png")
        val two = listOf("/img/a.png", "/img/b|c.png")
        assertFalse(DuelRecords.memberKey(one) == DuelRecords.memberKey(two))
        assertEquals(one.sorted(), DuelRecords.decodeMembers(DuelRecords.memberKey(one)))
    }

    @Test
    fun `깨진 처분 payload는 앱을 죽이지 않고 빈 목록이 된다`() {
        assertEquals(emptyList<String>(), DuelRecords.decodeMembers("이건 JSON이 아니다"))
        assertEquals(emptyList<String>(), DuelRecords.decodeMembers(""))
        // 해석 자체도 살아남는다 — 처분 하나가 깨졌다고 축 전체를 못 읽으면 안 된다.
        val resolved = DuelRecords.resolve(
            listOf("a", "b"),
            listOf(match("a", "b", "a")),
            listOf(
                DuelCounterVerdict(
                    axisId = 1,
                    kind = DuelCounterVerdict.KIND_COUNTER,
                    shape = DuelCounterVerdict.SHAPE_DIRECT,
                    memberCodes = "{망가짐",
                    memberKey = "{망가짐"
                )
            )
        )
        assertTrue(resolved.excludedPairs.isEmpty())
        assertEquals(1, resolved.matches.size)
    }

    @Test
    fun `모양은 참가자 수가 정한다`() {
        assertEquals(DuelCounterVerdict.SHAPE_DIRECT, DuelRecords.shapeOf(listOf("a", "b")))
        assertEquals(DuelCounterVerdict.SHAPE_CYCLE, DuelRecords.shapeOf(listOf("a", "b", "c")))
        assertNull("혼자서는 관계가 아니다", DuelRecords.shapeOf(listOf("a")))
        assertNull(DuelRecords.shapeOf(listOf("a", "a")))
    }

    // ── 코드로 되돌리기 ──

    @Test
    fun `짝을 코드 둘로 되돌릴 수 있다`() {
        val resolved = DuelRecords.resolve(listOf("a", "b"), listOf(match("a", "b", "a")))
        val pair = DuelRating.PairKey.of(resolved.idOf("a")!!, resolved.idOf("b")!!)
        val codes = resolved.codesOf(pair)
        assertNotNull(codes)
        assertEquals(setOf("a", "b"), setOf(codes!!.first, codes.second))
    }

    @Test
    fun `같은 코드가 두 번 들어와도 참가자는 하나다`() {
        val resolved = DuelRecords.resolve(listOf("a", "a", "b"), emptyList())
        assertEquals(2, resolved.participants.size)
        assertEquals(2, resolved.participants.distinct().size)
    }

    // ── 대조 방식 (B-175 — 로드맵 20판) ──
    //
    // 이미지 축의 코드는 **경로**라 같은 파일이 여러 표기를 가질 수 있다. 글자로 견주면 그런 판이
    // 고아가 되고, `DuelImageRoster`는 처음부터 정규 경로로 나눠 왔으므로 **나누기와 적합이
    // 서로 다른 판 수를 갖는다.** 이 절이 그 둘을 한 잣대로 묶은 것을 잠근다.

    private val dir = "/data/user/0/com.novelcharacter.app/files"

    @Test
    fun `이미지 축은 표기가 갈려도 같은 참가자다`() {
        val resolved = DuelRecords.resolve(
            listOf("$dir/a1.jpg", "$dir/a2.jpg"),
            listOf(match("$dir/./a1.jpg", "$dir/sub/../a2.jpg", "$dir/./a1.jpg")),
            matching = DuelRecords.CodeMatch.IMAGE_PATH
        )
        assertEquals(2, resolved.participants.size)
        assertEquals("표기가 셋이어도 참가자는 둘이다", 2, resolved.idByCode.size)
        assertEquals(0, resolved.missingParticipants)

        val fit = DuelRating.fit(resolved.participants, resolved.matches)
        assertEquals(1, fit.usedMatches)
        assertEquals(0, fit.orphanMatches)
        // 승자도 같은 잣대로 견준다 — 갈리면 멀쩡한 판이 '깨진 판'으로 세어진다.
        assertEquals(0, fit.malformedMatches)
    }

    @Test
    fun `글자 대조에서는 같은 파일이 두 참가자로 갈린다 — 이 판이 고친 것이 그것이다`() {
        val exact = DuelRecords.resolve(
            listOf("$dir/a1.jpg", "$dir/a2.jpg"),
            listOf(match("$dir/./a1.jpg", "$dir/a2.jpg", "$dir/a2.jpg"))
        )
        assertEquals("종전 동작 — 표기가 갈리면 없는 참가자가 하나 생긴다", 1, exact.missingParticipants)
        assertEquals(1, DuelRating.fit(exact.participants, exact.matches).orphanMatches)
    }

    @Test
    fun `표시 코드는 목록의 표기가 이긴다`() {
        // 저장에 들어가는 값은 원본 표기다(R-42). 새 판·엑셀·화면이 `codeById`를 그대로 쓰므로
        // 정규 표기가 여기 새면 저장에도 새고, 정규화가 실패하는 날 코드가 죽는다.
        val resolved = DuelRecords.resolve(
            listOf("$dir/./a1.jpg", "$dir/a2.jpg"),
            listOf(match("$dir/a1.jpg", "$dir/a2.jpg", "$dir/a1.jpg")),
            matching = DuelRecords.CodeMatch.IMAGE_PATH
        )
        val id = resolved.idOf("$dir/a1.jpg")!!
        assertEquals("$dir/./a1.jpg", resolved.codeOf(id))
        // 어느 표기로 물어도 같은 대표를 준다 — 열쇠를 문자열로 맞추는 자리가 이것을 쓴다.
        assertEquals("$dir/./a1.jpg", resolved.canonicalCode("$dir/sub/../a1.jpg"))
        // 모르는 코드는 버리지 않고 그대로 돌려준다.
        assertEquals("$dir/없다.jpg", resolved.canonicalCode("$dir/없다.jpg"))
    }

    @Test
    fun `캐릭터 축의 코드는 정규화하지 않는다`() {
        // `Character.code`를 경로로 읽으면 작업 디렉터리가 앞에 붙어 코드가 통째로 달라진다.
        // 기본값이 EXACT인 것이 그래서이고, 이 시험이 그 기본값을 잠근다.
        val resolved = DuelRecords.resolve(listOf("C-7", "C-8"), listOf(match("C-7", "C-8", "C-7")))
        assertEquals(DuelRecords.CodeMatch.EXACT, resolved.matching)
        assertEquals("C-7", resolved.codeOf(resolved.idOf("C-7")!!))
        assertEquals(setOf("C-7", "C-8"), resolved.idByCode.keys)
    }

    @Test
    fun `빈 코드는 참가자가 아니다 — 이미지 축에서도 같다`() {
        val resolved = DuelRecords.resolve(
            listOf("", "   ", "$dir/a1.jpg"),
            emptyList(),
            matching = DuelRecords.CodeMatch.IMAGE_PATH
        )
        assertEquals(1, resolved.participants.size)
    }
}
