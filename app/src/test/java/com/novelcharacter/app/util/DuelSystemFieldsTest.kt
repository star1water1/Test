package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 시스템 열을 대결 축에 거는 것 (B-167).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이다** — 나머지는 그 넷이 서 있는지를 받쳐 준다.
 *
 * 1. **`sys:`와 `__`가 겹치지 않는다.** 두 어휘가 뜻이 정반대인데(하나는 *"어느 커스텀 필드가
 *    나이인가"*, 하나는 *"필드가 아니라 표의 열이다"*) 접두가 같아지면 **키만 보고는 가릴 수
 *    없다.** 사람이 나중에 *"둘 다 의사키니 합치자"*로 되돌리기 딱 좋은 자리라 시험으로 박는다.
 * 2. **진짜 필드가 이긴다.** 키가 자유 입력이라 `sys:memo`짜리 필드를 만들 수 있고, 그때
 *    두 줄이 한 키를 다투면 [DuelFieldLinks]의 정규화가 **차례에 따라 하나를 조용히 버린다.**
 *    무엇이 남는지가 목록 순서에 달리는 것은 규칙이 아니다.
 * 3. **다중값 규칙이 열마다 다르다.** 커스텀 필드는 `FieldDefinition`의 타입·config가 나누는
 *    법을 들고 있는데 시스템 열에는 그 정의가 없다 — 이명·태그는 쉼표로 나뉘고 메모는 아니다.
 *    **메모를 쉼표로 나누면** 문장 하나가 범주 여럿으로 흩어져 집계가 통째로 헛돈다.
 * 4. **빈 값은 담지 않는다.** 커스텀 필드 쪽(`DuelViewModel.fieldValuesOf`)이 빈 값을 거르므로,
 *    여기가 빈 문자열을 담으면 카드가 *"값이 없다"*와 *"키가 없다"*를 서로 다르게 그리게 된다.
 */
class DuelSystemFieldsTest {

    private fun character(
        name: String = "루드",
        firstName: String = "",
        lastName: String = "",
        anotherName: String = "",
        memo: String = "",
        novelId: Long? = null
    ) = Character(
        id = 1L,
        name = name,
        firstName = firstName,
        lastName = lastName,
        anotherName = anotherName,
        memo = memo,
        novelId = novelId,
        code = "CHR-1"
    )

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 1 — 어휘가 갈린다
    // ──────────────────────────────────────────────────────────────────────

    /**
     * `SemanticRole.linkedKey`가 쓰는 `__` 어휘와 **한 글자도 겹치지 않는다.**
     *
     * 두 어휘를 합치고 싶어지는 자리라서 세운다 — 합치면 `__age`를 만난 코드가 *"나이 역할을
     * 맡은 커스텀 필드"*와 *"캐릭터 표의 나이 열"* 중 어느 쪽인지 말할 근거를 잃는다.
     */
    @Test
    fun `시스템 열 키는 __ 어휘와 겹치지 않는다`() {
        for (column in DuelSystemFields.Column.entries) {
            assertFalse(
                "${column.key}가 __ 어휘를 침범했다",
                column.key.startsWith("__")
            )
            assertTrue(column.key.startsWith(DuelSystemFields.PREFIX))
        }
        assertNull(DuelSystemFields.columnOf("__age"))
        assertFalse(DuelSystemFields.isSystemKey("__age"))
    }

    /**
     * 앞머리에 쉼표가 없다 — 엑셀 칸에서 연결을 나누는 글자라 담기면 토큰이 찢어진다.
     * 왕복이 실제로 성립하는지는 아래 [엑셀 왕복이 시스템 열을 그대로 되읽는다]가 잰다.
     */
    @Test
    fun `앞머리에 구분자가 들어 있지 않다`() {
        assertFalse(DuelSystemFields.PREFIX.contains(","))
        assertFalse(DuelSystemFields.PREFIX.contains("\n"))
        // *작을수록 유리* 표식으로 시작하면 그것과 구별되지 않는다. **옛 표식도 함께 잰다** —
        // `-`는 이제 읽기 전용이지만 여전히 앞머리로 해석되므로 겹치면 같은 사고가 난다(B-173).
        assertFalse(DuelSystemFields.PREFIX.startsWith("-"))
        assertFalse(DuelSystemFields.PREFIX.startsWith("▼"))
        assertFalse(DuelSystemFields.PREFIX.startsWith("'"))
    }

    @Test
    fun `모르는 sys 키는 열로 풀리지 않지만 시스템 자리인 것은 맞다`() {
        // 엑셀에서 사람이 오타를 낸 자리. **둘을 가르는 것이 요점이다** —
        // 커스텀 필드 오타는 나중에 그 필드를 만들면 살아나지만 이쪽은 영영 살아나지 않는다.
        assertNull(DuelSystemFields.columnOf("sys:오타"))
        assertTrue(DuelSystemFields.isSystemKey("sys:오타"))
        assertFalse(DuelSystemFields.isSystemKey("mana_affinity"))
        assertFalse(DuelSystemFields.isSystemKey(null))
    }

    // ── B-172: 모르는 `sys:` 키를 세 자리 전부에서 모은다 ──

    /**
     * **영향·프로필 자리가 이 행이 열린 이유다.** 산출 자리는 `outcomeBlocked`가 접두로
     * 잡아 이미 말하고 있었고, 남은 구멍이 이 둘이었다 — 값이 영영 비는데 아무 말도 없었다.
     */
    @Test
    fun `모르는 sys 키를 영향과 프로필 자리에서 모은다`() {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("sys:anothername, mana_affinity"),
            profiles = DuelFieldLinks.parseText("sys:오타, sys:tags")
        )
        assertEquals(listOf("sys:anothername", "sys:오타"), axis.unknownSystemKeys)
    }

    /**
     * **아는 열과 커스텀 필드는 걸리지 않는다.** 여기가 무너지면 멀쩡한 연결에 경고가 붙어
     * 진짜 오타가 그 소음에 묻힌다 — 거짓 경고는 침묵만큼 나쁘다.
     */
    @Test
    fun `아는 열과 커스텀 필드는 미해석으로 세지 않는다`() {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("sys:another_name, ▼sys:name"),
            outcomes = DuelFieldLinks.parseText("power"),
            profiles = DuelFieldLinks.parseText("sys:tags, 없는_커스텀_필드")
        )
        assertTrue("아는 열·커스텀 키에 경고가 붙었다", axis.unknownSystemKeys.isEmpty())
    }

    /**
     * **자리 판정과 존재 판정은 다른 물음이다.** 산출 자리의 오타는 둘 다에 걸리는데,
     * 겹치는 것이 아니라 사실이 둘이다 — 자리를 옮겨도 없는 열인 것은 그대로다.
     * 하나로 합치면 *"산출로는 못 건다"*만 듣고 철자를 고치지 않는다.
     */
    @Test
    fun `산출 자리의 오타는 자리 위반이면서 동시에 미해석이다`() {
        val axis = DuelFieldLinks.Axis(outcomes = DuelFieldLinks.parseText("sys:오타"))
        assertEquals(listOf("sys:오타"), axis.outcomeBlocked)
        assertEquals(listOf("sys:오타"), axis.unknownSystemKeys)
    }

    /** 앞머리가 붙어 들어와도 판정은 키로 한다 — 표식은 방향이지 이름이 아니다(B-173). */
    @Test
    fun `작을수록 유리 표식이 붙어도 미해석 판정은 같다`() {
        assertEquals(
            listOf("sys:오타"),
            DuelFieldLinks.Axis(influences = DuelFieldLinks.parseText("▼sys:오타")).unknownSystemKeys
        )
        assertEquals(
            listOf("sys:오타"),
            DuelFieldLinks.Axis(influences = DuelFieldLinks.parseText("-sys:오타")).unknownSystemKeys
        )
    }

    /** 같은 오타가 두 자리에 있어도 한 번만 말한다 — 같은 사실을 두 번 세지 않는다. */
    @Test
    fun `같은 미해석 키는 한 번만 센다`() {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("sys:오타"),
            profiles = DuelFieldLinks.parseText("sys:오타")
        )
        assertEquals(listOf("sys:오타"), axis.unknownSystemKeys)
    }

    @Test
    fun `키는 접두와 이름을 이은 것이다`() {
        assertEquals("sys:another_name", DuelSystemFields.Column.ANOTHER_NAME.key)
        assertEquals(
            DuelSystemFields.Column.ANOTHER_NAME,
            DuelSystemFields.columnOf("sys:another_name")
        )
    }

    /** 이름이 둘 겹치면 [DuelSystemFields.columnOf]가 어느 쪽을 낼지 정할 수 없다. */
    @Test
    fun `열 이름은 서로 다르다`() {
        val suffixes = DuelSystemFields.Column.entries.map { it.suffix }
        assertEquals(suffixes.size, suffixes.distinct().size)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 2 — 진짜 필드가 이긴다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `같은 키의 진짜 필드가 있으면 그 열은 가려진다`() {
        val shadowed = DuelSystemFields.shadowed(listOf("mana_affinity", "sys:memo"))
        assertEquals(setOf(DuelSystemFields.Column.MEMO), shadowed)

        val available = DuelSystemFields.available(listOf("sys:memo"))
        assertFalse(DuelSystemFields.Column.MEMO in available)
        // 가려지는 것은 **그 열 하나**다 — 형제까지 함께 사라지면 필드 하나가 기능을 통째로 끈다.
        assertTrue(DuelSystemFields.Column.ANOTHER_NAME in available)
        assertEquals(DuelSystemFields.Column.entries.size - 1, available.size)
    }

    @Test
    fun `가리는 필드가 없으면 전부 고를 수 있다`() {
        assertEquals(
            DuelSystemFields.Column.entries.toList(),
            DuelSystemFields.available(listOf("mana_affinity", "residence"))
        )
        assertEquals(DuelSystemFields.Column.entries.toList(), DuelSystemFields.available(emptyList()))
        assertTrue(DuelSystemFields.shadowed(emptyList()).isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 3 — 다중값 규칙이 열마다 다르다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `이명과 태그는 쉼표로 나뉘고 메모는 통째로 한 토큰이다`() {
        assertEquals(
            listOf("검은 늑대", "북부의 방패"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.ANOTHER_NAME, "검은 늑대, 북부의 방패")
        )
        assertEquals(
            listOf("주역", "기사"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.TAGS, "주역, 기사")
        )
        // **메모를 나누면** 문장 하나가 범주 여럿이 되어 집계가 헛돈다.
        assertEquals(
            listOf("북부 출신, 검을 쓴다"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.MEMO, "북부 출신, 검을 쓴다")
        )
        assertEquals(
            listOf("루드"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.NAME, "  루드  ")
        )
    }

    /** 이명을 나누는 규칙은 [Character.aliases]와 **같아야** 한다 — 갈리면 화면마다 수가 다르다. */
    @Test
    fun `이명 토큰은 Character aliases와 같다`() {
        val raw = "검은 늑대, , 북부의 방패 ,"
        val subject = character(anotherName = raw)
        assertEquals(
            subject.aliases,
            DuelSystemFields.tokensOf(DuelSystemFields.Column.ANOTHER_NAME, raw)
        )
    }

    @Test
    fun `빈 값의 토큰은 없다`() {
        for (column in DuelSystemFields.Column.entries) {
            assertTrue(DuelSystemFields.tokensOf(column, "").isEmpty())
            assertTrue(DuelSystemFields.tokensOf(column, "   ").isEmpty())
            assertTrue(DuelSystemFields.tokensOf(column, null).isEmpty())
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 4 — 값 읽기
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `값은 캐릭터 행과 곁재료에서 온다`() {
        val subject = character(
            name = "루드",
            firstName = "루드",
            lastName = "베인",
            anotherName = "검은 늑대",
            memo = "북부 출신"
        )
        val extras = DuelSystemFields.Extras(novelTitle = "북부 연대기", tags = listOf("주역", "기사"))

        assertEquals("루드", DuelSystemFields.valueOf(DuelSystemFields.Column.NAME, subject, extras))
        assertEquals("루드", DuelSystemFields.valueOf(DuelSystemFields.Column.FIRST_NAME, subject, extras))
        assertEquals("베인", DuelSystemFields.valueOf(DuelSystemFields.Column.LAST_NAME, subject, extras))
        assertEquals(
            "검은 늑대",
            DuelSystemFields.valueOf(DuelSystemFields.Column.ANOTHER_NAME, subject, extras)
        )
        assertEquals("북부 출신", DuelSystemFields.valueOf(DuelSystemFields.Column.MEMO, subject, extras))
        assertEquals("북부 연대기", DuelSystemFields.valueOf(DuelSystemFields.Column.NOVEL, subject, extras))
        // 태그 이음은 `FieldValueTokenizer.join`을 따른다 — 다중값 렌더러·폼과 같은 관례라야
        // 카드에 뜬 글을 사용자가 다른 화면에서 본 것과 같게 읽는다.
        assertEquals("주역, 기사", DuelSystemFields.valueOf(DuelSystemFields.Column.TAGS, subject, extras))
    }

    /** 곁재료를 안 준 것과 값이 빈 것은 같은 결과다 — 부르는 쪽이 걸린 열만 읽어 오기 때문이다. */
    @Test
    fun `곁재료가 없으면 작품과 태그는 빈 값이다`() {
        val subject = character(novelId = 7L)
        assertEquals("", DuelSystemFields.valueOf(DuelSystemFields.Column.NOVEL, subject))
        assertEquals("", DuelSystemFields.valueOf(DuelSystemFields.Column.TAGS, subject))
    }

    @Test
    fun `빈 값은 담지 않고 커스텀 키는 답하지 않는다`() {
        val subject = character(name = "루드", anotherName = "")
        val values = DuelSystemFields.valuesOf(
            subject,
            listOf("sys:name", "sys:another_name", "mana_affinity", "sys:오타")
        )
        // 이명이 비었으므로 자리 자체가 없다 — 커스텀 필드 쪽 규약과 같다.
        assertEquals(mapOf("sys:name" to "루드"), values)
    }

    @Test
    fun `요청한 키가 없으면 아무것도 읽지 않는다`() {
        assertTrue(DuelSystemFields.valuesOf(character(), emptyList()).isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 산출로는 걸리지 않는다 (설계 물음 ⓑ) · 엑셀 왕복 (ⓒ)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 산출 자리에 들어온 시스템 열은 **버려지지 않고 사유와 함께 남는다.**
     *
     * 지우는 편이 깔끔해 보이는 자리라서 세운다 — 지우면 엑셀을 한 번 왕복시킨 것만으로
     * 사용자가 적은 줄이 사라지고, 사라진 뒤에는 무엇이 있었는지 알 길이 없다.
     */
    @Test
    fun `산출로 걸린 시스템 열은 남되 막힌 것으로 보고된다`() {
        val axis = DuelFieldLinks.Axis(
            outcomes = DuelFieldLinks.parseText("strength, sys:another_name, sys:오타")
        )
        assertEquals(listOf("sys:another_name", "sys:오타"), axis.outcomeBlocked)
        // 연결 자체는 그대로다.
        assertEquals(
            listOf("strength", "sys:another_name", "sys:오타"),
            axis.outcomes.map { it.key }
        )
    }

    /**
     * **걸려 있는 것과 일하는 것을 가르는 자리 — 네 소비처가 이것 하나를 본다.**
     *
     * B-167의 첫 판이 순위표에서만 손으로 걸러내고 나머지 셋(카드 감추기·충돌 경고·프로필
     * 막기)을 그대로 둬, **일하지도 않는 연결이 카드에서 이명을 감추고 없는 충돌을
     * 경고했다.** 콜드 검토가 잡았고, 갈라진 자리를 다시 붙이는 것이 이 시험의 몫이다.
     */
    @Test
    fun `산출로 일하는 것에서 시스템 열은 빠진다`() {
        val axis = DuelFieldLinks.Axis(
            outcomes = DuelFieldLinks.parseText("strength, sys:another_name")
        )
        assertEquals(listOf("strength"), axis.effectiveOutcomes.map { it.key })
        // 저장된 것은 그대로다 — 가려내는 것과 버리는 것은 다른 일이다.
        assertEquals(2, axis.outcomes.size)
    }

    /**
     * **일하지 않는 산출은 프로필을 막지도, 충돌을 만들지도 못한다.**
     *
     * *"물음과 답을 한 화면에 두지 않는다"*가 근거인데 **답을 만들지 않으므로** 그 근거가
     * 성립하지 않는다. 막으면 사용자가 고른 프로필이 근거 없이 카드에서 빠진다.
     */
    @Test
    fun `산출 자리의 시스템 열은 프로필을 막지 않고 충돌도 아니다`() {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("sys:another_name"),
            outcomes = DuelFieldLinks.parseText("sys:another_name"),
            profiles = DuelFieldLinks.parseText("sys:another_name")
        )
        assertTrue("없는 충돌을 경고했다", axis.conflicts.isEmpty())
        assertTrue("근거 없이 프로필을 막았다", axis.profileBlocked.isEmpty())
        // 다만 **산출로 못 쓴다는 사실은 그대로 말한다** — 조용해지면 안 된다.
        assertEquals(listOf("sys:another_name"), axis.outcomeBlocked)
    }

    /** 진짜 산출은 종전 그대로 막고 경고한다 — 위 둘이 그 기능을 끄지 않았는가. */
    @Test
    fun `진짜 산출 필드는 여전히 프로필을 막고 충돌을 낸다`() {
        val axis = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("strength"),
            outcomes = DuelFieldLinks.parseText("strength"),
            profiles = DuelFieldLinks.parseText("strength")
        )
        assertEquals(listOf("strength"), axis.conflicts)
        assertEquals(listOf("strength"), axis.profileBlocked)
        assertTrue(axis.outcomeBlocked.isEmpty())
    }

    @Test
    fun `커스텀 필드만 걸린 축에는 막힌 산출이 없다`() {
        val axis = DuelFieldLinks.Axis(outcomes = DuelFieldLinks.parseText("strength, agility"))
        assertTrue(axis.outcomeBlocked.isEmpty())
        // 영향·프로필에 걸린 시스템 열은 **막힌 것이 아니다** — 그쪽은 읽기라 성립한다.
        val ok = DuelFieldLinks.Axis(
            influences = DuelFieldLinks.parseText("sys:another_name"),
            profiles = DuelFieldLinks.parseText("sys:tags")
        )
        assertTrue(ok.outcomeBlocked.isEmpty())
    }

    /**
     * 엑셀 칸 ↔ 연결의 왕복 (설계 물음 ⓒ).
     *
     * 접두가 쉼표를 담지 않는다는 것만으로는 부족하다 — `▼`(작을수록 유리)와 겹쳐 적히는
     * 자리가 있어 **둘이 함께 성립하는지**를 실제로 왕복시켜 잰다.
     */
    @Test
    fun `엑셀 왕복이 시스템 열을 그대로 되읽는다`() {
        val links = listOf(
            DuelFieldLinks.Link("sys:another_name"),
            DuelFieldLinks.Link("sys:name", higherWins = false),
            DuelFieldLinks.Link("mana_affinity")
        )
        val text = DuelFieldLinks.toText(links)
        assertEquals("sys:another_name, ▼sys:name, mana_affinity", text)
        assertEquals(links, DuelFieldLinks.parseText(text))
        // DB 저장 형식도 같은 왕복을 지킨다.
        assertEquals(links, DuelFieldLinks.decode(DuelFieldLinks.encode(links)))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 세력 열과 다중값 규약 (B-171)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * **잇는 쪽과 되쪼개는 쪽이 한 벌이다.**
     *
     * [DuelSystemFields.valueOf]는 [FieldValueTokenizer.join]으로 잇는데(쉼표를 품은 값은
     * 감싼다 — R-47 · B-178) 종전 [DuelSystemFields.tokensOf]는 맨 `split(",")`이었다.
     * 그래서 `"기사, 단"`이라는 세력 하나가 두 조각으로 갈렸고, **범주 집계가 없는 값 둘을
     * 세고 후보 필터의 `exact`가 실재하는 값을 영영 못 찾았다**(칩으로 고른 값인데도).
     * 세력 이름은 조직명이라 쉼표를 품을 여지가 크다.
     */
    @Test
    fun `쉼표를 품은 세력 이름이 한 토큰으로 살아 돌아온다`() {
        val raw = DuelSystemFields.valueOf(
            DuelSystemFields.Column.FACTION,
            character(),
            DuelSystemFields.Extras(factions = listOf("기사, 단", "상단"))
        )
        assertEquals(
            listOf("기사, 단", "상단"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.FACTION, raw)
        )
    }

    @Test
    fun `태그도 같은 규약을 지난다`() {
        // 세력을 고치면서 함께 닫힌 자리다 — 같은 함수를 쓰므로 한쪽만 맞을 수가 없다.
        val raw = DuelSystemFields.valueOf(
            DuelSystemFields.Column.TAGS,
            character(),
            DuelSystemFields.Extras(tags = listOf("주인공, 남자", "기사"))
        )
        assertEquals(
            listOf("주인공, 남자", "기사"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.TAGS, raw)
        )
    }

    /**
     * **반대편** — 감싸기를 몰랐던 옛 값은 종전 그대로 갈린다.
     *
     * 위 시험만 있으면 *"따옴표가 있으면 언제나 CSV"*인 구현이 통과하는데, 그러면
     * `"별칭", 본명`처럼 **따옴표를 글자로 쓰던 이명**이 뜻을 잃는다. `splitMulti`가
     * 좁힌 판정(`quotedOnlyIfNeeded`)을 쓰는 이유가 그것이고, 여기서 그 성질을 잠근다.
     */
    @Test
    fun `따옴표를 글자로 쓰던 옛 값은 옛 규칙 그대로 나뉜다`() {
        assertEquals(
            listOf("\"별칭\"", "본명"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.ANOTHER_NAME, "\"별칭\", 본명")
        )
        // 따옴표가 없던 값도 한 글자도 달라지지 않는다.
        assertEquals(
            listOf("붉은 검", "방랑자"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.ANOTHER_NAME, "붉은 검, 방랑자")
        )
    }

    @Test
    fun `메모는 여전히 통째로 한 토큰이다`() {
        // 다중값이 아닌 열까지 규약을 바꾸면 문장 하나가 범주 여럿으로 흩어진다.
        assertEquals(
            listOf("기사단 출신, 북부에서 태어났다"),
            DuelSystemFields.tokensOf(DuelSystemFields.Column.MEMO, "기사단 출신, 북부에서 태어났다")
        )
    }

    @Test
    fun `세력이 없으면 값 자체가 담기지 않는다`() {
        // 빈 값을 담으면 카드가 *"값이 없다"*와 *"키가 없다"*를 다르게 그린다(방어선 4).
        val values = DuelSystemFields.valuesOf(
            character(), listOf("sys:faction"), DuelSystemFields.Extras(factions = emptyList())
        )
        assertTrue(values.isEmpty())
    }

    @Test
    fun `세력 키는 산출 자리에서 막힌다`() {
        // 대결 결과를 소속에 써 넣을 수는 없다 — 시스템 열의 규칙이 새 열에도 그대로 선다.
        val axis = DuelFieldLinks.Axis(outcomes = DuelFieldLinks.parseText("sys:faction"))
        assertEquals(listOf("sys:faction"), axis.outcomeBlocked)
        assertTrue(axis.effectiveOutcomes.isEmpty())
        // 영향·프로필로는 성립한다.
        val ok = DuelFieldLinks.Axis(profiles = DuelFieldLinks.parseText("sys:faction"))
        assertTrue(ok.outcomeBlocked.isEmpty())
        assertTrue(ok.unknownSystemKeys.isEmpty())
    }
}
