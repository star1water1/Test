package com.novelcharacter.app.util

import com.novelcharacter.app.util.DuelFieldLinks.Agreement
import com.novelcharacter.app.util.DuelFieldLinks.Link
import com.novelcharacter.app.util.DuelFieldLinks.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DuelFieldLinks] 하네스 — 축↔필드 연결의 계약(B-104 층 C).
 *
 * **여기서 지키는 것 셋:** ① 저장 형식이 왕복해도 순위가 뒤집히지 않는다 ② 견줄 수 없는 값을
 * 견주지 않는다 ③ 어긋남으로 세는 것은 *실제로 반대인 것* 하나뿐이다.
 */
class DuelFieldLinksTest {

    // ── 저장 형식 ──

    @Test
    fun `json round trip keeps order and direction`() {
        val links = listOf(Link("mana"), Link("age", higherWins = false), Link("attr"))
        val decoded = DuelFieldLinks.decode(DuelFieldLinks.encode(links))
        assertEquals(links, decoded)
    }

    @Test
    fun `excel text round trip keeps order and direction`() {
        val links = listOf(Link("mana"), Link("age", higherWins = false))
        assertEquals("mana, ▼age", DuelFieldLinks.toText(links))
        assertEquals(links, DuelFieldLinks.parseText("mana, ▼age"))
        // 사람이 적은 것이라 공백·줄바꿈이 섞여도 같은 결과여야 한다.
        assertEquals(links, DuelFieldLinks.parseText("  mana ,\n  ▼ age  "))
    }

    // ── B-173: 표식이 `-`에서 `▼`로 바뀌었다 (읽기 셋 · 쓰기 하나) ──

    /**
     * **쓰기는 `▼` 하나다.** 되돌리면 `-`가 다시 나가고, 그러면 사용자가 그 칸을 손으로 고칠 때
     * 엑셀이 수식으로 읽어 `#NAME?`를 돌려준다 — B-173이 고친 것의 전부다.
     *
     * 앞머리를 **키의 일부로 착각하지 않는지**도 여기서 잰다: `▼`가 키에 남으면 축은 있지도
     * 않은 필드를 가리키게 되고, 그 값은 영영 비어 있다.
     */
    @Test
    fun `writes only the new marker`() {
        val text = DuelFieldLinks.toText(listOf(Link("age", higherWins = false)))
        assertEquals("▼age", text)
        assertTrue("옛 표식이 아직 나간다 — 엑셀이 수식으로 읽는다", !text.startsWith("-"))
        assertEquals(listOf(Link("age", higherWins = false)), DuelFieldLinks.parseText(text))
    }

    /**
     * **옛 표식이 그대로 읽힌다** — 이것이 이 판의 방어선이다.
     *
     * 표식을 바꾸면서 마이그레이션을 하지 않았으므로 **이미 내보낸 파일과 DB에 저장된 축이
     * 전부 `-` 표기**다. 이 폴백을 걷으면 그것들의 *작을수록 유리*가 통째로 뜻을 잃고
     * (앞머리가 키에 눌어붙어) 연결 자체가 죽는다. `'-`는 안내가 없던 동안 작은따옴표
     * 회피를 스스로 찾아 쓴 사람의 파일이다 — 거부가 아니라 수용이다(개발 의도 4번).
     */
    @Test
    fun `legacy markers still read as lower wins`() {
        val expected = listOf(Link("age", higherWins = false))
        assertEquals("옛 `-` 표식이 죽었다 — 이미 내보낸 파일 전부가 여기 매달려 있다", expected, DuelFieldLinks.parseText("-age"))
        assertEquals("작은따옴표 회피로 적은 파일이 안 읽힌다", expected, DuelFieldLinks.parseText("'-age"))
        // 저장된 JSON도 옛 표기 그대로다 — 같은 경로로 풀린다.
        assertEquals(expected, DuelFieldLinks.decode("""["-age"]"""))
    }

    /**
     * **`'-`를 `-`보다 먼저 잰다.** 짧은 것부터 보면 `'-age`의 앞머리가 `'`만 남긴 채 안 잡혀
     * 키가 `'-age`가 되고, 그 축은 없는 필드를 가리킨다. 순서가 곧 계약이라 시험으로 적는다.
     */
    @Test
    fun `longest legacy marker wins the match`() {
        assertEquals(listOf(Link("age", higherWins = false)), DuelFieldLinks.parseText("'-age"))
    }

    /**
     * **앞머리는 한 번만 뗀다.** 되풀이해 떼면 `-age`라는 *이름의* 필드를 가리킬 길이
     * 없어지고, 사용자가 적은 키를 앱이 마음대로 깎는 셈이 된다(개발 의도 2번).
     */
    @Test
    fun `prefix is stripped once only`() {
        assertEquals(listOf(Link("-age", higherWins = false)), DuelFieldLinks.parseText("▼-age"))
        assertEquals(listOf(Link("▼age", higherWins = false)), DuelFieldLinks.parseText("-▼age"))
    }

    /** 표식만 있고 키가 없는 칸은 옛 표식에서도 버려진다(종전과 같다). */
    @Test
    fun `marker without a key yields nothing`() {
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("▼"))
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("'-"))
    }

    /**
     * **시스템 열과 겹쳐 적을 수 있다** — 시트 안내가 `▼sys:name`을 예로 든다.
     * 접두 둘이 겹치는 자리라, 앞머리를 뗀 뒤에도 `sys:`가 온전해야 한다.
     */
    @Test
    fun `marker composes with the system column prefix`() {
        assertEquals(
            listOf(Link("sys:name", higherWins = false)),
            DuelFieldLinks.parseText("▼sys:name")
        )
        assertEquals(
            listOf(Link("sys:name", higherWins = false)),
            DuelFieldLinks.parseText("-sys:name")
        )
    }

    @Test
    fun `broken storage yields nothing instead of crashing`() {
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode(null))
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode(""))
        assertEquals(emptyList<Link>(), DuelFieldLinks.decode("{이건 JSON이 아니다"))
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("  ,  , "))
        assertEquals(emptyList<Link>(), DuelFieldLinks.parseText("-"))
    }

    /** 같은 필드가 두 번 오면 앞의 것이 남는다 — 순위가 둘일 수는 없다. */
    @Test
    fun `duplicate keys keep the first rank`() {
        val decoded = DuelFieldLinks.parseText("mana, attr, -mana")
        assertEquals(listOf(Link("mana"), Link("attr")), decoded)
    }

    // ── 값 읽기 ──

    @Test
    fun `numbers are read only from the front`() {
        assertEquals(80.0, DuelFieldLinks.numberOf("80")!!, 0.0001)
        assertEquals(3.5, DuelFieldLinks.numberOf("3.5cm")!!, 0.0001)
        assertEquals(1200.0, DuelFieldLinks.numberOf("1,200")!!, 0.0001)
        assertEquals(-5.0, DuelFieldLinks.numberOf("-5")!!, 0.0001)
        // 등급 이름에서 수를 주워 오면 서열이 뒤집힌다 — S-1이 −1이 되어 꼴찌가 된다.
        assertNull(DuelFieldLinks.numberOf("S-1"))
        assertNull(DuelFieldLinks.numberOf("불속성"))
        assertNull(DuelFieldLinks.numberOf(""))
        assertNull(DuelFieldLinks.numberOf(null))
    }

    @Test
    fun `direction flips which side wins`() {
        assertEquals(Side.A, DuelFieldLinks.compareOne(Link("mana"), "90", "40"))
        assertEquals(Side.B, DuelFieldLinks.compareOne(Link("age", higherWins = false), "90", "40"))
        assertEquals(Side.TIE, DuelFieldLinks.compareOne(Link("mana"), "40", "40"))
    }

    /** 차례가 없는 값(속성·소속)은 견주지 않는다 — 억지로 정렬하면 앱이 서열을 지어낸다. */
    @Test
    fun `unorderable values are not compared`() {
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("attr"), "불", "물"))
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("mana"), "90", ""))
        assertEquals(Side.UNKNOWN, DuelFieldLinks.compareOne(Link("mana"), null, "40"))
    }

    // ── 사전식 예측 ──

    /** 1순위에서 갈리면 거기서 끝난다 — 뒤 순위가 반대를 말해도 뒤집지 못한다. */
    @Test
    fun `first rank decides`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "90", "speed" to "10"), mapOf("mana" to "40", "speed" to "99"))
        assertEquals(Side.A, p.side)
        assertEquals("mana", p.decidedBy)
        assertEquals(1, p.rank)
    }

    /** 1순위가 비기면 2순위로 넘어간다 — 사용자가 말한 "1순위·2순위"의 뜻 그대로. */
    @Test
    fun `tie at the first rank falls through to the next`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "50", "speed" to "10"), mapOf("mana" to "50", "speed" to "99"))
        assertEquals(Side.B, p.side)
        assertEquals("speed", p.decidedBy)
        assertEquals(2, p.rank)
    }

    /** 견줄 수 없는 1순위는 **빼는 것이 아니라 넘어간다** — 뒤 순위가 여전히 말할 기회를 갖는다. */
    @Test
    fun `unorderable first rank still lets later ranks speak`() {
        val links = listOf(Link("attr"), Link("mana"))
        val p = DuelFieldLinks.predict(links, mapOf("attr" to "불", "mana" to "90"), mapOf("attr" to "물", "mana" to "40"))
        assertEquals(Side.A, p.side)
        assertEquals("mana", p.decidedBy)
        assertEquals(2, p.rank)
    }

    @Test
    fun `nothing comparable yields unknown`() {
        val links = listOf(Link("attr"))
        val p = DuelFieldLinks.predict(links, mapOf("attr" to "불"), mapOf("attr" to "물"))
        assertEquals(Side.UNKNOWN, p.side)
        assertEquals(0, p.comparable)
        assertNull(p.decidedBy)

        val none = DuelFieldLinks.predict(emptyList(), emptyMap(), emptyMap())
        assertEquals(Side.UNKNOWN, none.side)
    }

    /** 전부 비기면 '갈리지 않았다'이지 '모른다'가 아니다 — 값은 있었다. */
    @Test
    fun `all ties report a tie not unknown`() {
        val links = listOf(Link("mana"), Link("speed"))
        val p = DuelFieldLinks.predict(links, mapOf("mana" to "50", "speed" to "7"), mapOf("mana" to "50", "speed" to "7"))
        assertEquals(Side.TIE, p.side)
        assertEquals(2, p.comparable)
    }

    // ── 대조 ──

    @Test
    fun `agreement counts only real reversals`() {
        val decided = DuelFieldLinks.Prediction(Side.A, "mana", 1, 1)
        assertEquals(Agreement.AGREE, DuelFieldLinks.agreementOf(decided, Side.A))
        assertEquals(Agreement.DISAGREE, DuelFieldLinks.agreementOf(decided, Side.B))
        // 무승부와 판정 불가는 어긋남이 아니다 — 세면 값을 안 적은 캐릭터가 소음이 된다.
        assertEquals(Agreement.UNDECIDED, DuelFieldLinks.agreementOf(decided, null))
        assertEquals(Agreement.UNKNOWN, DuelFieldLinks.agreementOf(DuelFieldLinks.Prediction(Side.UNKNOWN), Side.A))
        assertEquals(Agreement.UNDECIDED, DuelFieldLinks.agreementOf(DuelFieldLinks.Prediction(Side.TIE, null, 0, 2), Side.A))
    }

    // ── 산출 필드: 순위와의 대조 ──

    /** 순위가 위인 쪽을 필드가 아래로 보면 어긋남이다 — *"필드를 고칠 때가 됐다"*의 신호. */
    @Test
    fun `outcome field reports pairs the ranking disagrees with`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C"),          // 대결 순위: A > B > C
            values = mapOf("A" to "10", "B" to "90", "C" to "50")
        )
        // 필드는 B > C > A라고 말한다 → 어긋난 짝은 (A,B)·(A,C) 둘.
        assertEquals(2, report.total)
        assertEquals(3, report.comparable)
        assertEquals(listOf("A" to "B", "A" to "C"), report.mismatches.map { it.higherCode to it.lowerCode })
    }

    @Test
    fun `outcome field agrees when the field follows the ranking`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C"),
            values = mapOf("A" to "90", "B" to "50", "C" to "10")
        )
        assertEquals(0, report.total)
        assertTrue(report.mismatches.isEmpty())
    }

    /**
     * **견줄 수 있는 값이 없는 것과 어긋남이 없는 것은 다르다** — 둘 다 `total = 0`이지만
     * 사용자가 할 일이 다르므로 `comparable`이 그것을 가른다.
     */
    @Test
    fun `outcome field separates 'nothing to compare' from 'nothing wrong'`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("attr"),
            rankedCodes = listOf("A", "B"),
            values = mapOf("A" to "불", "B" to "물")
        )
        assertEquals(0, report.total)
        assertEquals(0, report.comparable)
    }

    /** 상한에 걸려도 **전량은 센다** — 조용히 자르지 않는다. */
    @Test
    fun `outcome field counts everything even when the list is capped`() {
        // 순위는 A>B>C>D인데 필드값은 정확히 반대라 모든 짝(6)이 어긋난다.
        val report = DuelFieldLinks.outcomeReport(
            Link("power"),
            rankedCodes = listOf("A", "B", "C", "D"),
            values = mapOf("A" to "1", "B" to "2", "C" to "3", "D" to "4"),
            limit = 2
        )
        assertEquals(6, report.total)
        assertEquals(2, report.mismatches.size)
    }

    /** 방향을 뒤집으면 어긋남도 뒤집힌다 — 낮을수록 유리한 산출 필드. */
    @Test
    fun `outcome direction flips what counts as a mismatch`() {
        val report = DuelFieldLinks.outcomeReport(
            Link("rank", higherWins = false),
            rankedCodes = listOf("A", "B"),
            values = mapOf("A" to "1", "B" to "9")   // 낮을수록 유리 → 필드도 A가 위다
        )
        assertEquals(0, report.total)
    }

    // ── 축 단위 ──

    /** 같은 필드가 영향과 산출에 함께 걸리면 알린다 — 재료이면서 결과일 수는 없다. */
    @Test
    fun `axis reports fields linked as both material and outcome`() {
        val axis = DuelFieldLinks.Axis(
            influences = listOf(Link("mana"), Link("power")),
            outcomes = listOf(Link("power"))
        )
        assertEquals(listOf("power"), axis.conflicts)
        assertTrue(axis.hasAny)
        assertTrue(DuelFieldLinks.Axis().conflicts.isEmpty())
    }

    // ── 프로필 필드 (B-122) ──

    /**
     * 프로필로 걸린 산출 필드를 **세어서 알린다.**
     *
     * 고르는 창이 막지만 엑셀은 그 창을 지나지 않는다 — 사람이 `프로필필드` 칸에 산출 필드
     * 키를 적을 수 있고, 그때 값을 지우지도 조용히 띄우지도 않는 것이 이 목록의 몫이다.
     */
    @Test
    fun `axis reports profile links that are actually outcome fields`() {
        val axis = DuelFieldLinks.Axis(
            outcomes = listOf(Link("power")),
            profiles = listOf(Link("faction"), Link("power"))
        )
        assertEquals(listOf("power"), axis.profileBlocked)
        // 영향↔산출의 겹침과는 **다른 것을 센다** — 여기 영향 필드는 하나도 없다.
        assertTrue(axis.conflicts.isEmpty())
    }

    /** 프로필만 걸린 축도 '연결이 있다' — 카드가 그것으로 달라지기 때문이다. */
    @Test
    fun `axis with only profile links counts as linked`() {
        assertTrue(DuelFieldLinks.Axis(profiles = listOf(Link("faction"))).hasAny)
        assertTrue(DuelFieldLinks.Axis().profileBlocked.isEmpty())
    }

    /**
     * 프로필도 같은 저장 형식을 왕복한다 — **차례가 표시 차례**라 지켜야 한다.
     *
     * 앞머리 `-`는 프로필에서 뜻이 없지만, 사람이 엑셀에 적어 넣을 수 있으므로 **해석은
     * 되어야 한다**(깨뜨리지 않는다). 뜻이 없다는 것과 못 읽는다는 것은 다르다.
     */
    @Test
    fun `profile links survive the storage round trip in order`() {
        val links = listOf(Link("faction"), Link("origin"), Link("hobby"))
        assertEquals(links, DuelFieldLinks.decode(DuelFieldLinks.encode(links)))
        assertEquals(links, DuelFieldLinks.parseText(DuelFieldLinks.toText(links)))
        assertEquals(
            listOf(Link("faction"), Link("age", higherWins = false)),
            DuelFieldLinks.parseText("faction, -age")
        )
    }
}
