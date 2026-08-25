package com.novelcharacter.app.excel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 엑셀의 '텍스트로 저장된 숫자' 표식을 끌 열의 판정 (2026.08.25).
 *
 * 회귀 대상: 코드는 UUID 앞 16자라 **전부 숫자일 수 있고**(사용자 파일의 캐릭터 코드 하나가
 * `2057265523594451`이었다), 그 칸에서 엑셀의 [숫자로 변환]이 눌리면 16자리가 유효 15자리로
 * 접혀 코드가 다른 글자가 된다. 회색이라 *고치지 마세요*라 안내해 둔 칸을 엑셀이 먼저
 * 고치라고 권하던 자리다.
 *
 * 시험이 잠그는 것 둘: **코드 열은 빠짐없이 들어간다** · **사용자가 적는 열은 안 들어간다**
 * (그쪽 표식은 맞는 경고다 — 나이 칸에 숫자를 텍스트로 넣으면 알려 주어야 한다).
 */
class NumberWarningFreeColumnsTest {

    @Test
    fun `회색 글자 열과 코드 열만 고른다`() {
        val spec = SheetSpec(
            sheetName = "t",
            freezeCols = 1,
            columns = listOf(
                ColumnSpec("이름", required = true),
                ColumnSpec("나이"),
                ColumnSpec("코드", readOnly = true),
                ColumnSpec("생성일", readOnly = true, millis = true)
            )
        )
        assertEquals(listOf(2), spec.numberWarningFreeColumns())
    }

    /**
     * **편집 가능한 참조 코드 열도 대상이다** — 안내가 *"참조 코드 열은 이름이 겹칠 때 직접
     * 채워 대상을 확정할 수 있습니다"*라 말하는 그 열들이고(`이미지캐릭터코드` 등),
     * 사용자가 붙여 넣은 코드도 같은 위험을 진다. 회색만 보던 첫 규칙이 이것을 빠뜨렸다.
     */
    @Test
    fun `회색이 아니어도 코드 열이면 고른다`() {
        val spec = SheetSpec(
            sheetName = "t",
            freezeCols = 0,
            columns = listOf(
                ColumnSpec("이름"),
                ColumnSpec("이미지캐릭터코드"),
                ColumnSpec("설명")
            )
        )
        assertEquals(listOf(1), spec.numberWarningFreeColumns())
    }

    /**
     * millis 열은 **진짜 숫자**로 나가 애초에 이 표식이 붙지 않는다 — 넣어도 해는 없지만
     * 넣지 않는 것이 판정을 정직하게 만든다(끄는 대상은 '텍스트로 실리는 회색 열'이다).
     */
    @Test
    fun `시각 열은 빼고 그 밖의 회색 열은 넣는다`() {
        val spec = SheetSpec(
            sheetName = "t",
            freezeCols = 0,
            columns = listOf(
                ColumnSpec("설명", readOnly = true),
                ColumnSpec("판정일", readOnly = true, millis = true),
                ColumnSpec("코드", readOnly = true)
            )
        )
        assertEquals(listOf(0, 2), spec.numberWarningFreeColumns())
    }

    /**
     * **실제 시트 명세 전수** — 코드를 담는 열이 하나도 빠지지 않는가.
     *
     * 열 이름으로 훑는 것이 요점이다: 새 시트가 코드 열을 회색으로 안 두면 여기서 걸린다.
     */
    @Test
    fun `모든 시트에서 코드 열이 대상에 들어간다`() {
        val specs = listOf(
            universeSpec(), novelSpec(emptyList()), gradeSystemSpec(), factionSpec(),
            factionMembershipSpec(), factionRelationshipSpec(), relationshipSpec(),
            timelineSpec(emptyList()), stateChangeSpec(), quoteSpec(), nameBankSpec(),
            duelAxisSpec(), duelMatchSpec(), duelVerdictSpec(), imageMetaSpec()
        )
        for (spec in specs) {
            val free = spec.numberWarningFreeColumns().toSet()
            spec.columns.forEachIndexed { index, col ->
                if (col.header == "코드" || col.header.endsWith("코드")) {
                    assertTrue(
                        "'${spec.sheetName}'의 '${col.header}' 열이 표식 해제 대상에서 빠졌다",
                        index in free
                    )
                }
            }
        }
    }
}
