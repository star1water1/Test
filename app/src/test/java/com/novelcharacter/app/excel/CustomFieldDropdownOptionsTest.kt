package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [customFieldDropdownOptions] — 필드 열의 드롭다운이 **자기 시트에 실리는 값**을 담는가.
 *
 * 2026.08.24에 관계 유형 두 열이 자기 시트의 값을 거부하던 것을 고쳤는데
 * ([RelationshipTypeOptions]), SELECT·GRADE 열은 같은 성질인데 그대로 남아 있었다.
 * 유효성 검사가 `showError = true`로 실리므로, 목록 밖 값을 든 칸은 **한 번 고치면
 * 되돌릴 수 없다.**
 */
class CustomFieldDropdownOptionsTest {

    private fun select(vararg options: String) = FieldDefinition(
        universeId = 1, key = "gender", name = "성별", type = FieldType.SELECT.name,
        config = """{"options":[${options.joinToString(",") { "\"$it\"" }}]}"""
    )

    private fun grade(config: String) = FieldDefinition(
        universeId = 1, key = "rank", name = "등급", type = FieldType.GRADE.name, config = config
    )

    // ── 정의된 목록은 차례까지 그대로다 ──

    @Test
    fun `쓰이는 값이 없으면 정의된 목록 그대로다`() {
        assertEquals(listOf("남", "여", "?"), customFieldDropdownOptions(select("남", "여", "?")))
    }

    @Test
    fun `정의 안에 있는 값만 쓰이면 목록이 늘지 않는다`() {
        assertEquals(
            listOf("남", "여", "?"),
            customFieldDropdownOptions(select("남", "여", "?"), listOf("남", "남", "여"))
        )
    }

    // ── 목록 밖 값이 실리면 그 값을 담는다 ──

    @Test
    fun `선택지에서 빠진 값이 시트에 남아 있으면 목록에 담는다`() {
        // 사용자가 선택지를 지워도 캐릭터에 저장된 값은 지워지지 않는다(안내 시트의 그 문장).
        assertEquals(
            listOf("남", "여", "?", "무성"),
            customFieldDropdownOptions(select("남", "여", "?"), listOf("무성", "남"))
        )
    }

    @Test
    fun `목록 밖 값이 여럿이면 사전순으로 붙는다`() {
        // 차례가 실행마다 흔들리지 않아야 무편집 왕복이 파일을 바꾸지 않는다.
        assertEquals(
            listOf("남", "여", "?", "무성", "불명", "양성"),
            customFieldDropdownOptions(select("남", "여", "?"), listOf("양성", "불명", "무성"))
        )
    }

    @Test
    fun `등급 열도 같은 규칙이다`() {
        val field = grade("""{"grades":{"C":1,"B":2,"A":3}}""")
        assertEquals(listOf("C", "B", "A"), customFieldDropdownOptions(field))
        assertEquals(
            listOf("C", "B", "A", "SS"),
            customFieldDropdownOptions(field, listOf("SS", "A"))
        )
    }

    @Test
    fun `빈 값과 공백은 목록이 되지 못한다`() {
        assertEquals(
            listOf("남", "여"),
            customFieldDropdownOptions(select("남", "여"), listOf("", "   ", "남"))
        )
    }

    @Test
    fun `앞뒤 공백만 다른 값은 같은 값이다`() {
        assertEquals(
            listOf("남", "여"),
            customFieldDropdownOptions(select("남", "여"), listOf(" 남 "))
        )
    }

    // ── 없던 목록을 만들지는 않는다 ──

    @Test
    fun `값 집합이 열린 타입에는 여전히 목록이 없다`() {
        for (type in listOf(
            FieldType.TEXT, FieldType.MULTI_TEXT, FieldType.NUMBER,
            FieldType.CALCULATED, FieldType.BODY_SIZE
        )) {
            val field = FieldDefinition(
                universeId = 1, key = "k", name = "n", type = type.name, config = "{}"
            )
            assertNull(
                "열린 값 집합에 목록을 세우면 들이기보다 좁아진다: $type",
                customFieldDropdownOptions(field, listOf("아무값"))
            )
        }
    }

    @Test
    fun `실효 등급표가 없는 등급 필드에는 여전히 목록이 없다`() {
        // 쓰이는 값만으로 세우면 들이기가 받아들이는 것보다 **좁은** 목록이 된다.
        assertNull(customFieldDropdownOptions(grade("{}"), listOf("A", "B")))
    }
}
