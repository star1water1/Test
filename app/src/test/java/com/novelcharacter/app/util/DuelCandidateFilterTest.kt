package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대결 후보 필터 (B-168).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이다** — 나머지는 그 넷이 서 있는지를 받쳐 준다.
 *
 * 1. **해석 실패는 후보 0이다(닫히는 실패).** 지워진 필드·모르는 `sys:` 키를 든 필터를
 *    건너뛰면 필터가 **소리 없이 넓어져** 사용자가 좁혀 둔 범위 밖의 짝이 만들어진다 —
 *    목록 정렬의 "기본으로 되돌아가며 말한다"와 달리 이쪽은 **데이터가 생기는 자리**라
 *    실패가 열리는 쪽이면 안 된다. *건너뛰는 것이 관대해 보여* 다음 사람이 바꾸기 쉬운
 *    자리라 시험으로 박는다.
 * 2. **커스텀 필터 없음(null)과 전원 탈락(빈 집합)은 다른 사실이다.** 합치면 커스텀 필터가
 *    전원을 걸러 냈을 때 조용히 전원 통과로 뒤집힌다 — `?: emptySet()` 한 줄로 무너지는
 *    자리다.
 * 3. **메모는 exact에서도 쉼표로 나뉘지 않는다.** 이명·태그와 반대 방향으로 세워야 구별이
 *    잠긴다(B-167 ⓓ와 같은 부류) — 다중값만 재는 시험은 셋을 다 쉼표로 나눠도 통과한다.
 * 4. **전적 있는 비후보는 참가자에 남는다.** 빼면 그 판이 고아가 되어 적합에서 빠지고
 *    **남의 점수까지 움직인다**(BT는 결과 집합의 함수다 — 설계 9-3).
 */
class DuelCandidateFilterTest {

    private fun character(
        id: Long,
        name: String = "이름$id",
        anotherName: String = "",
        memo: String = "",
        novelId: Long? = null
    ) = Character(
        id = id,
        name = name,
        anotherName = anotherName,
        memo = memo,
        novelId = novelId,
        code = "CHR-$id"
    )

    private fun fd(id: Long, key: String, name: String = "필드$id") = FieldDefinition(
        id = id, universeId = 1L, key = key, name = name, type = "TEXT", config = "{}"
    )

    private fun filter(
        fieldKey: String? = null,
        fieldId: Long = -1L,
        values: List<String>,
        matchMode: String = "exact"
    ) = FieldFilter(
        fieldId = fieldId, fieldName = "라벨", values = values, matchMode = matchMode, fieldKey = fieldKey
    )

    // ──────────────────────────────────────────────────────────────────────
    // 해석 사다리 — 키 → 시스템 열 → (구식) id
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `키가 커스텀 필드에 있으면 필드로 해석한다`() {
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "gender", values = listOf("여성"))),
            listOf(fd(10L, "gender"))
        )
        assertEquals(10L, resolved.single().field?.id)
        assertNull(resolved.single().column)
    }

    /**
     * **진짜 필드가 이긴다** — B-167이 값 읽기에 세운 규칙 그대로다. 사용자가 `sys:tags`라는
     * 키의 필드를 만들었으면 필터도 그 필드를 대조해야, 카드가 그 필드 값을 보여 주면서
     * 필터는 태그 표를 보는 **두 벌**이 생기지 않는다.
     */
    @Test
    fun `sys 키를 든 진짜 필드가 시스템 열을 이긴다`() {
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "sys:tags", values = listOf("악당"))),
            listOf(fd(10L, "sys:tags"))
        )
        assertEquals(10L, resolved.single().field?.id)
        assertNull(resolved.single().column)
    }

    @Test
    fun `아는 sys 키는 시스템 열로 해석한다`() {
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "sys:tags", values = listOf("악당"))),
            emptyList()
        )
        assertEquals(DuelSystemFields.Column.TAGS, resolved.single().column)
    }

    /** 엑셀 오타(`sys:anothername`)는 어느 열도 아니다 — 살아나지 않는 키라 해석 실패다. */
    @Test
    fun `모르는 sys 키는 해석 실패다`() {
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "sys:anothername", values = listOf("x"))),
            emptyList()
        )
        assertTrue(resolved.single().unresolved)
    }

    /** 손으로 만든 파일이 키 없이 id만 적을 수 있다 — 실재하는 필드를 가리키면 살린다. */
    @Test
    fun `키가 없으면 id로 폴백한다`() {
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldId = 10L, values = listOf("여성"))),
            listOf(fd(10L, "gender"))
        )
        assertEquals(10L, resolved.single().field?.id)
    }

    @Test
    fun `지워진 필드를 가리키는 필터는 해석 실패다`() {
        val byKey = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "deleted", values = listOf("x"))), emptyList()
        )
        val byId = DuelCandidateFilter.resolve(
            listOf(filter(fieldId = 99L, values = listOf("x"))), emptyList()
        )
        assertTrue(byKey.single().unresolved)
        assertTrue(byId.single().unresolved)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 1 — 해석 실패는 후보 0이다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `해석 실패 필터가 하나라도 있으면 후보는 없다`() {
        val chars = listOf(character(1L), character(2L))
        val resolved = DuelCandidateFilter.resolve(
            listOf(
                filter(fieldKey = "sys:tags", values = listOf("악당")),
                filter(fieldKey = "sys:오타", values = listOf("x"))
            ),
            emptyList()
        )
        val codes = DuelCandidateFilter.candidateCodes(chars, resolved, null, emptyMap())
        assertTrue("건너뛰면 필터가 소리 없이 넓어진다", codes.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 2 — null(제약 없음)과 빈 집합(전원 탈락)은 다르다
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `커스텀 필터가 없으면 제약이 없고 전원 탈락이면 아무도 없다`() {
        val chars = listOf(character(1L), character(2L))
        val none = DuelCandidateFilter.candidateCodes(chars, emptyList(), null, emptyMap())
        val all = DuelCandidateFilter.candidateCodes(chars, emptyList(), emptySet(), emptyMap())
        assertEquals(setOf("CHR-1", "CHR-2"), none)
        assertTrue("빈 교집합이 전원 통과로 뒤집히면 안 된다", all.isEmpty())
    }

    @Test
    fun `커스텀 교집합이 시스템 조건과 AND로 묶인다`() {
        val chars = listOf(
            character(1L, anotherName = "그림자"),
            character(2L, anotherName = "그림자"),
            character(3L, anotherName = "빛")
        )
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "sys:another_name", values = listOf("그림자"))),
            emptyList()
        )
        // 커스텀 쪽이 1·3만 통과시켰다 → 시스템 쪽(그림자)과의 AND는 1뿐이다.
        val codes = DuelCandidateFilter.candidateCodes(chars, resolved, setOf(1L, 3L), emptyMap())
        assertEquals(setOf("CHR-1"), codes)
    }

    // ──────────────────────────────────────────────────────────────────────
    // 대조 규칙 — 값 간 OR · 토큰/통짜 · contains
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `한 조건의 값 여럿은 OR다`() {
        val chars = listOf(
            character(1L, anotherName = "그림자"),
            character(2L, anotherName = "빛"),
            character(3L, anotherName = "돌")
        )
        val resolved = DuelCandidateFilter.resolve(
            listOf(filter(fieldKey = "sys:another_name", values = listOf("그림자", "빛"))),
            emptyList()
        )
        assertEquals(
            setOf("CHR-1", "CHR-2"),
            DuelCandidateFilter.candidateCodes(chars, resolved, null, emptyMap())
        )
    }

    /** 이명은 쉼표 다중값이다 — `Character.aliases`와 같은 규칙이라야 화면과 필터가 같은 수를 센다. */
    @Test
    fun `이명 exact는 토큰 단위로 맞는다`() {
        val c = character(1L, anotherName = "그림자, 검은 별")
        val f = filter(fieldKey = "sys:another_name", values = listOf("검은 별"))
        assertTrue(
            DuelCandidateFilter.matchesSystem(
                DuelSystemFields.Column.ANOTHER_NAME, f, c, DuelSystemFields.Extras()
            )
        )
    }

    // ── 방어선 3 — 메모는 나뉘지 않는다 ──

    @Test
    fun `메모 exact는 쉼표로 나뉘지 않는다`() {
        val c = character(1L, memo = "왕국의 기사, 몰락한 가문")
        val f = filter(fieldKey = "sys:memo", values = listOf("왕국의 기사"))
        assertFalse(
            "메모를 나누면 문장 하나가 값 여럿이 된다 — 이명·태그와 반대 방향으로 세워야 구별이 잠긴다",
            DuelCandidateFilter.matchesSystem(
                DuelSystemFields.Column.MEMO, f, c, DuelSystemFields.Extras()
            )
        )
        // 통짜 전체와는 맞는다 — 나뉘지 않을 뿐 대조가 죽은 것이 아니다.
        val whole = filter(fieldKey = "sys:memo", values = listOf("왕국의 기사, 몰락한 가문"))
        assertTrue(
            DuelCandidateFilter.matchesSystem(
                DuelSystemFields.Column.MEMO, whole, c, DuelSystemFields.Extras()
            )
        )
    }

    /** 메모의 맞는 길은 contains다 — 창이 직접 입력과 함께 여는 그 길. */
    @Test
    fun `contains는 원문 부분 일치이고 대소문자를 무시한다`() {
        val c = character(1L, memo = "Royal Knight of the North")
        val f = filter(fieldKey = "sys:memo", values = listOf("royal knight"), matchMode = "contains")
        assertTrue(
            DuelCandidateFilter.matchesSystem(
                DuelSystemFields.Column.MEMO, f, c, DuelSystemFields.Extras()
            )
        )
    }

    @Test
    fun `태그는 extras에서 오고 exact 토큰으로 맞는다`() {
        val c = character(1L)
        val f = filter(fieldKey = "sys:tags", values = listOf("악당"))
        val extras = DuelSystemFields.Extras(tags = listOf("악당", "귀족"))
        assertTrue(
            DuelCandidateFilter.matchesSystem(DuelSystemFields.Column.TAGS, f, c, extras)
        )
        assertFalse(
            DuelCandidateFilter.matchesSystem(
                DuelSystemFields.Column.TAGS, f, c, DuelSystemFields.Extras()
            )
        )
    }

    // ──────────────────────────────────────────────────────────────────────
    // 방어선 4 — 참가자 = 후보 ∪ 전적 보유
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `전적 있는 비후보는 참가자에 남고 전적 없는 비후보는 빠진다`() {
        val all = listOf(character(1L), character(2L), character(3L))
        val participants = DuelCandidateFilter.participants(
            all,
            candidateCodes = setOf("CHR-1"),
            codesWithMatches = setOf("CHR-2")
        )
        assertEquals(
            "빼면 그 판이 고아가 되어 남의 점수까지 움직인다",
            listOf("CHR-1", "CHR-2"),
            participants.map { it.code }
        )
    }

    @Test
    fun `필터가 없으면 전원이 참가자다`() {
        val all = listOf(character(1L), character(2L))
        assertEquals(all, DuelCandidateFilter.participants(all, null, emptySet()))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 저장 형식 — 정화와 시트 칸
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `encode와 parse가 fieldKey까지 왕복한다`() {
        val filters = listOf(
            filter(fieldKey = "gender", values = listOf("여성"), matchMode = "exact"),
            filter(fieldKey = "sys:tags", values = listOf("악당", "귀족"), matchMode = "contains")
        )
        val back = DuelCandidateFilter.parse(DuelCandidateFilter.encode(filters))
        assertEquals(filters.map { it.fieldKey }, back.map { it.fieldKey })
        assertEquals(filters.map { it.values }, back.map { it.values })
        assertEquals(filters.map { it.matchMode }, back.map { it.matchMode })
    }

    /**
     * Gson은 Kotlin 기본값을 실행하지 않는다 — 값 목록이 빠진 손편집 JSON은 **non-null
     * 선언에도 null을 주입한다**(R-2의 지뢰). parse가 걸러야 대조 어디에서도 안 터진다.
     */
    @Test
    fun `parse는 값 없는 조건과 null 값을 걷어낸다`() {
        val json = """[
            {"fieldKey":"gender","fieldName":"성별"},
            {"fieldKey":"sys:tags","fieldName":"태그","values":[null," 악당 ",""]},
            null
        ]"""
        val parsed = DuelCandidateFilter.parse(json)
        assertEquals(1, parsed.size)
        assertEquals(listOf("악당"), parsed.single().values)
        assertEquals("exact", parsed.single().matchMode)
    }

    @Test
    fun `빈 칸과 빈 JSON은 필터 지우기다`() {
        assertEquals(DuelCandidateFilter.SheetCell.Value(null), DuelCandidateFilter.fromSheetCell(""))
        assertEquals(DuelCandidateFilter.SheetCell.Value(null), DuelCandidateFilter.fromSheetCell("  "))
        assertEquals(DuelCandidateFilter.SheetCell.Value(null), DuelCandidateFilter.fromSheetCell("{}"))
        assertEquals(DuelCandidateFilter.SheetCell.Value(null), DuelCandidateFilter.fromSheetCell("[]"))
    }

    @Test
    fun `읽히는 칸은 정화해 다시 담은 JSON이 된다`() {
        val cell = DuelCandidateFilter.fromSheetCell(
            """[{"fieldKey":"gender","fieldName":"성별","values":["여성"],"matchMode":"exact"}]"""
        )
        val value = cell as DuelCandidateFilter.SheetCell.Value
        val parsed = DuelCandidateFilter.parse(value.json)
        assertEquals("gender", parsed.single().fieldKey)
        assertEquals(listOf("여성"), parsed.single().values)
    }

    /**
     * **읽을 수 없는 칸은 '지워라'가 아니다.** 괄호 하나 틀린 손편집이 멀쩡한 필터를 조용히
     * 지우면 안 된다 — 가져오기가 기존 값을 지키고 경고한다(개발 의도 2번·4번).
     * *지워라*(빈 칸)와 붙여 두면 관대한 파서가 그 구별을 삼킨다 — 그래서 따로 세운다.
     */
    @Test
    fun `깨진 JSON과 값 없는 조건뿐인 JSON은 해석 불가다`() {
        assertEquals(
            DuelCandidateFilter.SheetCell.Malformed,
            DuelCandidateFilter.fromSheetCell("[{oops")
        )
        assertEquals(
            "값이 하나도 없는 조건뿐이면 빈 필터와 같아져 '지워라'로 접힌다 — 그것은 손편집 실수지 지우기가 아니다",
            DuelCandidateFilter.SheetCell.Malformed,
            DuelCandidateFilter.fromSheetCell("""[{"fieldKey":"gender","fieldName":"성별"}]""")
        )
    }
}
