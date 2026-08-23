package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 관계 시트 두 장의 '관계 유형' 드롭다운 목록.
 *
 * **이 목록이 지켜야 하는 것은 하나다: 그 시트에 실리는 값이 목록에 있어야 한다.**
 * 실측(2026.08.24 내보낸 파일)에서 '캐릭터 관계' 135행 중 87행, '세력 관계' 2행 중 2행이
 * 자기 시트의 드롭다운 밖 값이었고, 유효성 검사가 `showError`라 **되돌리기가 막혔다.**
 */
class RelationshipTypeOptionsTest {

    @Test
    fun `세력 자동관계유형이 캐릭터 관계 목록에 든다`() {
        // 세력을 만들 때 사용자가 자유롭게 적는 글자다 — 멤버가 둘 이상이면 그 글자를 든
        // 관계가 자동으로 생기고, 그것이 이 시트의 행이 된다(실측: 가문원 81행 · 월아 6행).
        val options = RelationshipTypeOptions.forCharacterRelations(
            customTypes = emptyList(),
            factionAutoTypes = listOf("가문원", "월아"),
            typesInUse = listOf("가문원", "월아")
        )
        assertTrue(options.containsAll(listOf("가문원", "월아")))
        assertTrue(options.containsAll(Universe.DEFAULT_RELATIONSHIP_TYPES))
    }

    @Test
    fun `쓰이는 값은 어디에도 정의가 없어도 목록에 든다`() {
        // 세력을 지운 뒤 남은 자동관계·옛 파일로 들여온 유형이 그 자리다.
        val options = RelationshipTypeOptions.forCharacterRelations(
            customTypes = emptyList(),
            factionAutoTypes = emptyList(),
            typesInUse = listOf("전 조직")
        )
        assertTrue("전 조직" in options)
    }

    @Test
    fun `세력 관계는 캐릭터 어휘가 아니라 자기 값을 받는다`() {
        val options = RelationshipTypeOptions.forFactionRelations(
            customTypes = emptyList(),
            typesInUse = listOf("동맹", "동")
        )
        assertTrue(options.containsAll(listOf("동맹", "동")))
    }

    @Test
    fun `세력 자동관계유형은 세력 관계 목록에 들지 않는다`() {
        // 캐릭터 사이의 관계라 세력끼리 고를 수 없는 값이다 — 넣으면 목록이 거짓말을 한다.
        val options = RelationshipTypeOptions.forFactionRelations(
            customTypes = emptyList(),
            typesInUse = listOf("동맹")
        )
        assertTrue("가문원" !in options)
    }

    @Test
    fun `차례는 기본 다음 커스텀 다음 세력 다음 나머지는 사전순이다`() {
        val options = RelationshipTypeOptions.forCharacterRelations(
            customTypes = listOf("약혼"),
            factionAutoTypes = listOf("가문원"),
            typesInUse = listOf("힣값", "ㄱ값", "가문원")
        )
        val expected = Universe.DEFAULT_RELATIONSHIP_TYPES + listOf("약혼", "가문원", "ㄱ값", "힣값")
        assertEquals(expected, options)
    }

    @Test
    fun `같은 값이 여러 재료에 있어도 한 번만 실린다`() {
        val options = RelationshipTypeOptions.forCharacterRelations(
            customTypes = listOf("동료"),                 // 기본에도 있다
            factionAutoTypes = listOf("동료"),
            typesInUse = listOf("동료")
        )
        assertEquals(1, options.count { it == "동료" })
    }

    @Test
    fun `빈 글자와 공백은 목록이 되지 못한다`() {
        // 세력의 자동관계유형은 자유 입력이라 빈 글자일 수 있다 — 그것이 목록에 들어가면
        // 엑셀 드롭다운에 고를 수 없는 빈 줄이 선다.
        val options = RelationshipTypeOptions.forCharacterRelations(
            customTypes = listOf("", "   "),
            factionAutoTypes = listOf(""),
            typesInUse = listOf("  ")
        )
        assertEquals(Universe.DEFAULT_RELATIONSHIP_TYPES, options)
    }

    @Test
    fun `앞뒤 공백은 다듬어 같은 값으로 본다`() {
        val options = RelationshipTypeOptions.forFactionRelations(
            customTypes = listOf(" 동맹 "),
            typesInUse = listOf("동맹")
        )
        assertEquals(1, options.count { it == "동맹" })
    }
}
