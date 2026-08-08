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
        // `-`로 시작하면 *작을수록 유리* 표식과 구별되지 않는다.
        assertFalse(DuelSystemFields.PREFIX.startsWith("-"))
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
     * 접두가 쉼표를 담지 않는다는 것만으로는 부족하다 — `-`(작을수록 유리)와 겹쳐 적히는
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
        assertEquals("sys:another_name, -sys:name, mana_affinity", text)
        assertEquals(links, DuelFieldLinks.parseText(text))
        // DB 저장 형식도 같은 왕복을 지킨다.
        assertEquals(links, DuelFieldLinks.decode(DuelFieldLinks.encode(links)))
    }
}
