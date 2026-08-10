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

    private fun field(id: Long, universeId: Long?, key: String, type: String = "TEXT", order: Int = 0) =
        FieldDefinition(id = id, universeId = universeId, key = key, name = key, type = type, displayOrder = order)

    private fun value(charId: Long, fieldId: Long, v: String) =
        CharacterFieldValue(characterId = charId, fieldDefinitionId = fieldId, value = v)

    private val fields = listOf(
        field(1, 10, "gender"),
        field(2, 10, "residence"),
        field(3, 20, "rank"),                      // 다른 세계관
        field(4, 10, "age", type = "CALCULATED"),  // 파생값
        field(5, null, "theme"),                   // 전역 구역(무소속) — B-119 확장
        field(6, null, "motif")
    ).associateBy { it.id }

    @Test
    fun unclassifiedCharacter_valuesOutsideItsColumns_overflow() {
        // 미분류 캐릭터도 **전역 구역의 필드는 열로 담는다**(B-149). 그 열 밖의 값 —
        // 세계관에 속했다가 빠져나온 잔여값 — 만 오버플로로 나간다.
        val values = listOf(value(1, 5, "설원"), value(1, 1, "여성"), value(1, 2, "서울"))
        val picked = CharacterFieldValueOverflow.select(values, setOf(5L, 6L), fields)
        assertEquals(listOf("gender", "residence"), picked.map { it.second.key })
    }

    /**
     * **B-149의 본체** — 무소속의 전역 필드 값이 두 시트에 겹쳐 나가지 않는다.
     *
     * 내보내기는 이 값을 '미분류 캐릭터' 시트의 열로 싣는다. 그런데 `coveredFieldIds`를 함께
     * 옮기지 않으면 **같은 값이 오버플로 시트에도 실려**, 가져오기에서 어느 쪽이 권위인지가
     * 값마다 갈린다. 열을 채우는 쪽과 여기가 한 벌이어야 한다는 것이 이 시험이 잠그는 것이다.
     */
    @Test
    fun unclassifiedCharacter_globalFieldValues_areNotDuplicatedIntoOverflow() {
        val values = listOf(value(1, 5, "설원"), value(1, 6, "귀향"))
        assertTrue(CharacterFieldValueOverflow.select(values, setOf(5L, 6L), fields).isEmpty())
    }

    /**
     * 반대쪽 — 전역 필드를 열로 담지 **않았다면** 오버플로가 그것을 받아야 한다.
     * 이 시험이 없으면 위 시험은 *"덮으면 안 나간다"*만 말하고, 그림자가 심기지 않아 열이 없는
     * 상태(템플릿만 있고 심기 전)에서 **값이 어느 시트에도 안 나가는 것**을 못 본다.
     */
    @Test
    fun unclassifiedCharacter_globalFieldValues_overflowWhenNotColumns() {
        val values = listOf(value(1, 5, "설원"), value(1, 6, "귀향"))
        val picked = CharacterFieldValueOverflow.select(values, emptySet(), fields)
        assertEquals(listOf("theme", "motif"), picked.map { it.second.key })
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
