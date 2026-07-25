package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 시트가 열로 담지 못하는 필드값(미분류 캐릭터·타 세계관 잔여분) 선별 규칙.
 * 이 선별이 깨지면 백업에서 값이 무음 유실되고, 덮어쓰기 복원 시 CASCADE로 영구 소멸한다.
 */
class CharacterFieldValueOverflowTest {

    private fun field(id: Long, universeId: Long, key: String, type: String = "TEXT", order: Int = 0) =
        FieldDefinition(id = id, universeId = universeId, key = key, name = key, type = type, displayOrder = order)

    private fun value(charId: Long, fieldId: Long, v: String) =
        CharacterFieldValue(characterId = charId, fieldDefinitionId = fieldId, value = v)

    private val fields = listOf(
        field(1, 10, "gender"),
        field(2, 10, "residence"),
        field(3, 20, "rank"),                      // 다른 세계관
        field(4, 10, "age", type = "CALCULATED")   // 파생값
    ).associateBy { it.id }

    @Test
    fun unclassifiedCharacter_allValuesOverflow() {
        // 미분류 캐릭터는 세계관이 없어 필드 열이 하나도 없다 → 값 전량이 오버플로
        val values = listOf(value(1, 1, "여성"), value(1, 2, "서울"))
        val picked = CharacterFieldValueOverflow.select(values, emptySet(), fields)
        assertEquals(2, picked.size)
        assertEquals(listOf("gender", "residence"), picked.map { it.second.key })
    }

    @Test
    fun fullyCoveredCharacter_producesNothing() {
        // 정상 세계관 캐릭터: 모든 값이 캐릭터 시트 열로 이미 담긴다 → 오버플로 0건 (시트 자체가 안 생김)
        val values = listOf(value(1, 1, "여성"), value(1, 2, "서울"))
        assertTrue(CharacterFieldValueOverflow.select(values, setOf(1L, 2L), fields).isEmpty())
    }

    @Test
    fun crossUniverseLeftover_onlyThatValueOverflows() {
        // 세계관 10 소속 캐릭터가 세계관 20의 필드값을 들고 있는 잔여 상태
        val values = listOf(value(1, 1, "여성"), value(1, 3, "기사단장"))
        val picked = CharacterFieldValueOverflow.select(values, setOf(1L, 2L), fields)
        assertEquals(1, picked.size)
        assertEquals("rank", picked.single().second.key)
    }

    @Test
    fun calculatedField_excluded() {
        // 계산 필드는 수식으로 산출되는 파생값 — 가져오기가 저장하지 않으므로 내보내기도 쓰지 않는다
        val picked = CharacterFieldValueOverflow.select(listOf(value(1, 4, "27")), emptySet(), fields)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun orphanValue_withoutFieldDefinition_excluded() {
        // 정의가 사라진 고아값은 복원할 정체성(세계관+필드키)이 없다 — 쓰면 가져오기가 해석 못 해 경고만 낸다
        val picked = CharacterFieldValueOverflow.select(listOf(value(1, 99, "값")), emptySet(), fields)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun blankValue_excluded() {
        // 빈 값을 내보내면 F1-A상 재가져오기가 '비움 의도'로 읽어 삭제 행이 늘어난다(왕복 멱등성 위반)
        val picked = CharacterFieldValueOverflow.select(listOf(value(1, 1, "  ")), emptySet(), fields)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun sheetIdentity_notMistakenForCharacterSheet() {
        // findSheetForUniverse는 첫 열이 "이름"인 시트를 캐릭터 시트로 본다.
        // 이 성질이 깨지면 '캐릭터 필드값' 시트가 캐릭터 시트로 오인된다.
        val spec = characterFieldValueSpec()
        assertEquals("캐릭터코드", spec.firstColumnHeader)
        assertTrue(spec.firstColumnHeader != "이름")
        assertTrue(spec.sheetName in RESERVED_SHEET_NAMES)
        // 내보내기 셀 인덱스(0..7)와 헤더 순서가 단일 소스로 일치해야 한다
        assertEquals(
            listOf("캐릭터코드", "캐릭터이름", "세계관", "세계관코드", "필드키", "필드명", "대상", "값"),
            spec.columns.map { it.header }
        )
    }
}
