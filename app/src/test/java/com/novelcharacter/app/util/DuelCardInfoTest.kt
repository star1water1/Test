package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 카드가 **무엇을 띄우는가**의 계약 (B-122).
 *
 * ## 이 시험이 지키는 것은 건수가 아니라 넷이다
 *
 * 1. **산출 필드는 프로필 경로로도 카드에 못 올라온다.** 고르는 창이 막지만 엑셀은 그 창을
 *    지나지 않는다 — 그 우회가 열리면 *"누가 더 센가"*를 물으면서 '강함' 값을 함께 보이게 되고,
 *    대결이 재는 것이 창작자의 감각이 아니라 이미 적힌 숫자가 된다.
 * 2. **영향 필드는 빈 값에도 줄이 남고, 프로필은 빠진다.** 두 규칙이 반대인 것이 일부러라
 *    시험이 없으면 다음 사람이 하나로 통일해 버린다 — 그러면 두 카드의 영향 줄이 어긋나
 *    엉뚱한 줄끼리 마주 본다.
 * 3. **접힌 수는 전량 기준이다.** 값이 빈 프로필도 *"외 N개"*에 세어지고 펼치면 비어 있음이
 *    그대로 보인다. 세지 않으면 *"걸어 뒀는데 어디에도 없다"*가 된다(개발 의도 2번).
 * 4. **[DuelCardInfo.keysToLoad]가 [DuelCardInfo.build]와 같은 것을 본다.** 갈리면 값이
 *    있는데도 빈 줄이 뜨고, 그것은 *"값을 안 적었다"*와 구별되지 않는다.
 */
class DuelCardInfoTest {

    private fun link(key: String) = DuelFieldLinks.Link(key)

    private val labels = mapOf(
        "power" to "마력량",
        "element" to "속성",
        "faction" to "소속",
        "origin" to "출신",
        "hobby" to "취미",
        "height" to "키",
        "strength" to "강함",
        "gender" to "성별",
        "age" to "나이"
    )

    // ──────────────────────────────────────────────────────────────────────
    // 1. 산출 필드는 프로필로도 못 올라온다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `산출 필드가 프로필에 걸려 있어도 카드에 뜨지 않는다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("strength" to "S", "faction" to "달빛단"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                outcomes = listOf(link("strength")),
                profiles = listOf(link("strength"), link("faction"))
            )
        )
        assertEquals(listOf("faction"), card.profiles.map { it.key })
        // 펼침 시트에도 오르지 않는다 — 접힌 자리로 새는 것도 같은 우회다.
        assertEquals(listOf("faction"), card.allProfiles.map { it.key })
    }

    /**
     * **산출 자리의 시스템 열은 카드를 막지 못한다** (B-167 — 콜드 검토가 잡았다).
     *
     * 위 규칙의 근거는 *"그 값이 곧 이 대결이 물으려는 답"*이라는 것인데, 시스템 열은
     * 산출로 **아무 답도 만들지 않는다**(`DuelFieldLinks.Axis.outcomeBlocked`가 가려낸다).
     * 근거가 성립하지 않는데 감추면 **사용자가 고른 프로필이 이유 없이 카드에서 사라진다** —
     * 막는 것과 버리는 것을 가르는 자리다.
     *
     * **위 시험만으로는 이 자리가 잠기지 않는다.** 감추기가 `outcomes`를 통째로 보든
     * `effectiveOutcomes`를 보든 위쪽은 똑같이 통과한다(거기 걸린 것이 진짜 필드라서다).
     * 둘을 함께 세워야 *구별*이 잠긴다.
     */
    @Test
    fun `산출에 적힌 시스템 열은 프로필을 막지 않는다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("sys:another_name" to "검은 늑대", "faction" to "달빛단"),
            labels = labels + ("sys:another_name" to "이명"),
            links = DuelFieldLinks.Axis(
                // 엑셀로만 들어올 수 있는 모양이다 — 고르는 창은 산출에 시스템 열을 내지 않는다.
                outcomes = listOf(link("sys:another_name")),
                profiles = listOf(link("sys:another_name"), link("faction"))
            )
        )
        assertEquals(listOf("sys:another_name", "faction"), card.profiles.map { it.key })
        assertEquals("검은 늑대", card.profiles.first().value)
        assertEquals("이명", card.profiles.first().label)
    }

    @Test
    fun `막힌 산출 필드는 접힌 수에도 세지 않는다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("strength" to "S"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                outcomes = listOf(link("strength")),
                profiles = listOf(link("strength"))
            )
        )
        // "외 1개"라고 말하면 펼쳤을 때 아무것도 없다 — 세는 것도 거짓말이다.
        assertEquals(0, card.profileOverflow)
        assertFalse(card.canExpand)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. 빈 값의 처분이 두 구역에서 반대다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `영향 필드는 값이 없어도 줄이 남는다`() {
        val card = DuelCardInfo.build(
            name = "벨",
            values = mapOf("power" to "280"),
            labels = labels,
            links = DuelFieldLinks.Axis(influences = listOf(link("power"), link("element")))
        )
        assertEquals(listOf("power", "element"), card.influences.map { it.key })
        assertEquals("", card.influences[1].value)
        // 순위 번호는 1부터, 걸린 차례 그대로다.
        assertEquals(listOf(1, 2), card.influences.map { it.rank })
    }

    @Test
    fun `프로필은 값이 없으면 카드에서 빠진다`() {
        val card = DuelCardInfo.build(
            name = "벨",
            values = mapOf("faction" to "홍련회"),
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = listOf(link("faction"), link("origin")))
        )
        assertEquals(listOf("faction"), card.profiles.map { it.key })
        // 다만 **없어진 것이 아니다** — 전량에는 남고 접힌 수가 그것을 센다.
        assertEquals(listOf("faction", "origin"), card.allProfiles.map { it.key })
        assertEquals(1, card.profileOverflow)
        assertTrue(card.canExpand)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. 상한과 접힌 수
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `상한을 넘긴 만큼이 접힌 수다`() {
        val keys = listOf("faction", "origin", "hobby", "height", "element", "power")
        val card = DuelCardInfo.build(
            name = "아리네",
            values = keys.associateWith { "값" },
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = keys.map { link(it) })
        )
        assertEquals(DuelCardInfo.PROFILE_LIMIT, card.profiles.size)
        assertEquals(keys.size - DuelCardInfo.PROFILE_LIMIT, card.profileOverflow)
        // **차례가 표시 차례다** — 앞자리가 사용자가 먼저 보고 싶은 것이다.
        assertEquals(keys.take(4), card.profiles.map { it.key })
    }

    @Test
    fun `접힌 것이 없으면 펼침을 띄우지 않는다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("faction" to "달빛단"),
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = listOf(link("faction")))
        )
        assertEquals(0, card.profileOverflow)
        assertFalse(card.canExpand)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. 겹치는 필드는 한 구역에만
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `영향 필드와 겹치는 프로필은 프로필 구역에서 빠진다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "350", "faction" to "달빛단"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                influences = listOf(link("power")),
                profiles = listOf(link("power"), link("faction"))
            )
        )
        // 값이 사라진 것이 아니라 **자리가 하나**다 — 영향 구역에 순위 번호까지 달고 떠 있다.
        assertEquals(listOf("faction"), card.profiles.map { it.key })
        assertEquals(listOf("power"), card.influences.map { it.key })
    }

    @Test
    fun `트리오 역할 필드는 프로필 구역에서 빠진다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("gender" to "여", "age" to "17", "faction" to "달빛단"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                profiles = listOf(link("gender"), link("age"), link("faction"))
            ),
            genderKey = "gender",
            ageKey = "age"
        )
        assertEquals(listOf("faction"), card.profiles.map { it.key })
        assertEquals("여", card.gender)
        assertEquals("17", card.age)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. 트리오 — 없으면 그 조각만 빠진다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `역할 필드가 없으면 그 조각이 빠지고 나머지는 뜬다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("age" to "17"),
            labels = labels,
            links = DuelFieldLinks.Axis(),
            genderKey = null,
            ageKey = "age"
        )
        assertNull(card.gender)
        assertEquals("17", card.age)
        assertTrue(card.hasTrio)
    }

    @Test
    fun `역할 필드는 있는데 값이 비면 그 조각도 빠진다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("gender" to "   "),
            labels = labels,
            links = DuelFieldLinks.Axis(),
            genderKey = "gender",
            ageKey = "age"
        )
        assertNull(card.gender)
        assertNull(card.age)
        assertFalse(card.hasTrio)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 6. 보기 설정 — 끄면 구역이 통째로 빈다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `프로필 표시를 끄면 펼침까지 함께 사라진다`() {
        val keys = listOf("faction", "origin", "hobby", "height", "element")
        val card = DuelCardInfo.build(
            name = "아리네",
            values = keys.associateWith { "값" },
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = keys.map { link(it) }),
            showProfiles = false
        )
        assertTrue(card.profiles.isEmpty())
        assertTrue(card.allProfiles.isEmpty())
        // 접힌 것이 없는데 펼침만 남으면 눌러도 빈 시트가 뜬다.
        assertEquals(0, card.profileOverflow)
        assertFalse(card.canExpand)
    }

    @Test
    fun `영향 표시를 꺼도 프로필은 그대로 뜬다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "350", "faction" to "달빛단"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                influences = listOf(link("power")),
                profiles = listOf(link("faction"))
            ),
            showInfluences = false
        )
        assertTrue(card.influences.isEmpty())
        assertEquals(listOf("faction"), card.profiles.map { it.key })
    }

    @Test
    fun `영향 표시를 꺼도 영향 필드와 겹치는 프로필은 여전히 빠진다`() {
        // 껐다고 프로필 구역으로 옮겨 오면, 토글 하나로 **자리가 움직인다** — 사용자는
        // 표시를 껐는데 같은 값이 다른 구역에 나타나는 것을 본다.
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "350"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                influences = listOf(link("power")),
                profiles = listOf(link("power"))
            ),
            showInfluences = false
        )
        assertTrue(card.profiles.isEmpty())
        assertTrue(card.influences.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 7. 낯선 키 · 읽을 키
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `이름을 모르는 키는 키를 그대로 보인다`() {
        // 지워진 필드나 엑셀로 들어온 낯선 키를 조용히 감추면, 사용자는 연결이 걸려 있다는
        // 사실 자체를 알 길이 없다.
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("unknown_key" to "값"),
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = listOf(link("unknown_key")))
        )
        assertEquals("unknown_key", card.profiles.single().label)
    }

    @Test
    fun `읽을 키는 카드가 보는 것과 같다 — 산출 필드만 빠진다`() {
        val links = DuelFieldLinks.Axis(
            influences = listOf(link("power")),
            outcomes = listOf(link("strength")),
            profiles = listOf(link("faction"))
        )
        val keys = DuelCardInfo.keysToLoad(links, genderKey = "gender", ageKey = "age")
        assertEquals(setOf("power", "faction", "gender", "age"), keys)
        // 산출 필드는 읽지 않는다 — 읽어 두면 언젠가 무심코 띄운다.
        assertFalse("strength" in keys)
    }

    @Test
    fun `역할 필드가 없으면 읽을 키에도 오르지 않는다`() {
        val keys = DuelCardInfo.keysToLoad(
            DuelFieldLinks.Axis(influences = listOf(link("power")))
        )
        assertEquals(setOf("power"), keys)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. 접힌 카드 (k지선다 — B-115)
    //
    // **접는 것은 거두는 것이 아니라 옮기는 것이다.** 카드가 화면의 4분의 1이라 줄을 넣을
    // 높이가 없을 뿐이고, 펼침 시트가 전량을 진다. 이 구별이 무너지면 셋 이상으로 두는
    // 순간 그 축의 판단 재료가 화면 어디에서도 닿을 수 없게 된다.
    // ──────────────────────────────────────────────────────────────────────

    private fun compactCard(): DuelCardInfo.Card = DuelCardInfo.build(
        name = "아리네",
        values = mapOf("power" to "12", "faction" to "은빛탑", "gender" to "여", "age" to "17"),
        labels = labels,
        links = DuelFieldLinks.Axis(
            influences = listOf(link("power")),
            profiles = listOf(link("faction"))
        ),
        genderKey = "gender",
        ageKey = "age",
        compact = true
    )

    @Test
    fun `접힌 카드는 이름과 트리오만 남는다`() {
        val card = compactCard()

        assertEquals("아리네", card.name)
        assertEquals("여", card.gender)
        assertEquals("17", card.age)
        assertTrue(card.hasTrio)
        assertTrue("프로필 줄이 카드에 뜨지 않는다", card.profiles.isEmpty())
        assertTrue("영향 줄도 카드에 뜨지 않는다", card.influences.isEmpty())
        assertTrue(card.compact)
    }

    @Test
    fun `접힌 것은 사라지지 않고 펼침 시트로 간다`() {
        val card = compactCard()

        assertEquals(listOf("faction"), card.allProfiles.map { it.key })
        assertEquals(listOf("power"), card.allInfluences.map { it.key })
        assertTrue("펼칠 것이 있으므로 ⌄가 뜬다", card.canExpand)
    }

    /**
     * 프로필이 하나도 없어도 **영향 줄이 통째로 접혀 있으면** 펼칠 것이 있다.
     * 종전처럼 프로필만 세면 그 카드의 판단 재료가 어디에서도 닿을 수 없다.
     */
    @Test
    fun `프로필이 없어도 접힌 영향 줄이 있으면 펼침이 뜬다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "12"),
            labels = labels,
            links = DuelFieldLinks.Axis(influences = listOf(link("power"))),
            compact = true
        )

        assertTrue(card.allProfiles.isEmpty())
        assertTrue(card.canExpand)
    }

    /** 위에 아무 줄도 없이 *"외 3개"*만 남으면 무엇의 나머지인지 알 수 없다. */
    @Test
    fun `접힌 카드는 외 N개 줄을 띄우지 않는다`() {
        val card = compactCard()

        assertTrue(card.profileOverflow > 0)
        assertFalse(card.showProfileMore)
    }

    /**
     * **끈 것을 접었다고 되살리지 않는다.** 자리가 없어 접은 것과 사용자가 안 보겠다고 한
     * 것은 다르고, 뒤엣것은 펼침에서도 안 보여야 한다.
     */
    @Test
    fun `보기 설정으로 끈 구역은 펼침 시트에도 없다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "12", "faction" to "은빛탑"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                influences = listOf(link("power")),
                profiles = listOf(link("faction"))
            ),
            showProfiles = false,
            showInfluences = false,
            compact = true
        )

        assertTrue(card.allProfiles.isEmpty())
        assertTrue(card.allInfluences.isEmpty())
        assertFalse("펼쳐도 아무것도 없으면 ⌄를 띄우지 않는다", card.canExpand)
    }

    /** 접지 않은 카드의 동작이 이 슬라이스로 달라지지 않았다는 잠금. */
    @Test
    fun `접지 않은 카드는 종전 그대로다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = mapOf("power" to "12", "faction" to "은빛탑"),
            labels = labels,
            links = DuelFieldLinks.Axis(
                influences = listOf(link("power")),
                profiles = listOf(link("faction"))
            )
        )

        assertEquals(listOf("faction"), card.profiles.map { it.key })
        assertEquals(listOf("power"), card.influences.map { it.key })
        assertEquals(card.influences, card.allInfluences)
        assertEquals(0, card.profileOverflow)
        assertFalse(card.compact)
        assertFalse(card.canExpand)
    }

    /** 값이 빈 프로필만 있는 카드는 접지 않았으면 *"외 N개"*를 띄운다(종전 동작). */
    @Test
    fun `접지 않은 카드는 빈 프로필만 있어도 외 N개를 띄운다`() {
        val card = DuelCardInfo.build(
            name = "아리네",
            values = emptyMap(),
            labels = labels,
            links = DuelFieldLinks.Axis(profiles = listOf(link("faction"), link("origin")))
        )

        assertTrue(card.profiles.isEmpty())
        assertEquals(2, card.profileOverflow)
        assertTrue(card.showProfileMore)
    }
}
