package com.novelcharacter.app.data.repository

import com.novelcharacter.app.data.database.AppDatabase
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueLibraryConfig
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.util.FieldValueTokenizer
import com.novelcharacter.app.util.SemanticFieldSyncHelper

/**
 * 나이-출생연도 불일치 — 저장 전 관문이 내는 사실.
 *
 * **종전에는 `CharacterViewModel`의 중첩 클래스였다.** 2026.11.23에 이리로 옮겼다 — 아래
 * [CharacterFormGates]의 KDoc이 그 근거를 든다.
 */
data class AgeLinkageConflict(
    val inputAge: Int,
    val inputBirthYear: Int,
    val currentStdYear: Int,
    val expectedAge: Int,
    val suggestedBirthYear: Int,
    val suggestedStdYear: Int,
    val affectedCharacterCount: Int,
    val ageFieldId: Long,
    val birthYearFieldId: Long,
    val novelId: Long
)

/**
 * 캐릭터 폼 저장 **전에 지나는 검증 관문** — 화면 없이 도는 계층.
 *
 * ## 왜 ViewModel에서 꺼냈는가
 *
 * 이 판정들은 줄곧 `CharacterViewModel`에 살았지만 **UI에 의존한 적이 없다** — DAO와 저장소,
 * 그리고 순수 헬퍼만 본다. ViewModel에 있었던 것은 역사이고 설계가 아니었다.
 *
 * PC 동반 편집(`docs/pc_companion_design_2026-11.md`)이 두 번째 편집 창구를 열면서 그 사실이
 * 문제가 됐다. 서버는 `AndroidViewModel`을 만들 수 없으므로 선택은 둘뿐이다 — **복붙하거나
 * 꺼내거나.** 복붙은 R-46이 금지한 모양이다(*"복붙은 그 방향을 자리마다 뒤집는다"*): 두 벌이
 * 되는 순간 *"앱에서 저장하면 걸리는데 PC에서 저장하면 통과하는"* 자리가 생기고, 그것은
 * 사용자가 알 수 없는 종류의 갈림이다. 세 관문 전부 **데이터 무결성 문턱**이라 더욱 그렇다.
 *
 * 그래서 꺼냈다. `CharacterViewModel`은 이제 **이 클래스에 위임한다** — 호출부는 하나도
 * 바뀌지 않았고(같은 메서드 이름·같은 서명), 구현이 한 벌이 됐다.
 *
 * ## 관문은 막지 않는다 — 사실을 낸다
 *
 * 이 클래스의 어느 함수도 저장을 거부하지 않는다. *무엇이 어긋났는가*를 돌려줄 뿐이고,
 * **고지와 교정 경로는 창구가 입힌다**(앱은 다이얼로그, PC는 확인 응답). 개발 의도 2번이
 * 요구하는 순서가 검증 → 고지 → 바로잡을 경로이고, 이 계층은 그 첫 칸이다.
 */
class CharacterFormGates(
    private val db: AppDatabase,
    private val characters: CharacterRepository,
    private val universes: UniverseRepository,
    private val novels: NovelRepository,
    private val fieldValueLibrary: FieldValueLibraryRepository
) {

    /**
     * 시맨틱 연동 여부를 묻는 데만 쓴다.
     *
     * **`CharacterFormSaver`가 트랜잭션 안에서 새로 만드는 것과 같은 이유로 여기서는 필드로
     * 둔다** — 이 클래스의 함수는 트랜잭션을 열지 않고 읽기만 하므로, 그쪽이 경계한
     * *뮤텍스를 쥔 채 Room을 기다리는* 역순 잠금이 생기지 않는다.
     */
    private val semanticSync = SemanticFieldSyncHelper(characters, universes, novels)

    /**
     * 세계관을 옮기는 저장이 **무엇을 잃는가**. 창구가 이 수를 보여 주고 확인을 받는다.
     * 저장소 계층에 이미 있던 것을 관문 묶음에 모아 두는 위임이다 — 세 관문을 한 자리에서
     * 찾게 하는 것이 목적이고, 판정은 여전히 `CharacterRepository`가 든다.
     */
    suspend fun countCrossUniverseLoss(characterId: Long, newUniverseId: Long): UniverseMoveCounts =
        characters.countCrossUniverseLoss(characterId, newUniverseId)

    /**
     * restricted 입력 모드를 어긴 토큰들. 비면 어긴 것이 없다.
     *
     * **차단이 아니라 사유다** — 창구는 이 목록으로 *추가하고 저장* / *입력 수정*을 묻는다.
     */
    suspend fun findRestrictedViolations(
        values: List<CharacterFieldValue>
    ): List<Pair<FieldDefinition, List<String>>> {
        val result = mutableListOf<Pair<FieldDefinition, List<String>>>()
        for (v in values) {
            if (v.value.isBlank()) continue
            val fd = db.fieldDefinitionDao().getFieldById(v.fieldDefinitionId) ?: continue
            if (!FieldValueTokenizer.supportsLibrary(fd)) continue
            if (!FieldValueLibraryConfig.fromConfig(fd.config).isRestricted) continue
            val entries = db.fieldValueEntryDao().getByField(fd.id)
            val violations = FieldValueLibraryRepository.validateRestricted(fd, v.value, entries)
            if (violations.isNotEmpty()) result.add(fd to violations)
        }
        return result
    }

    /** restricted 위반 토큰을 라이브러리에 수동 등재 — '추가하고 저장' 교정 경로. */
    suspend fun addRestrictedValuesToLibrary(violations: List<Pair<FieldDefinition, List<String>>>) {
        for ((fd, tokens) in violations) {
            for (token in tokens) {
                fieldValueLibrary.addEntry(fd.id, token)
            }
        }
    }

    /**
     * 저장 전 나이-출생연도 불일치. 어긋나지 않았거나 판정할 근거가 없으면 null.
     *
     * null이 되는 자리가 여럿이고 **그 전부가 "어긋나지 않았다"는 뜻은 아니다** — 작품이
     * 없다 · 기준연도가 없다 · 세계관이 없다 · 연동이 꺼졌다 · 역할 필드가 없다 · 입력이
     * 숫자가 아니다. 종전 구현의 모양을 그대로 옮긴 것이고, 이 판은 **동작을 바꾸지 않는다.**
     */
    suspend fun detectAgeLinkageConflict(
        novelId: Long?,
        characterId: Long,
        fieldValues: List<CharacterFieldValue>
    ): AgeLinkageConflict? {
        if (novelId == null) return null
        val novel = novels.getNovelById(novelId) ?: return null
        val stdYear = novel.standardYear ?: return null
        val universeId = novel.universeId ?: return null

        // 연동 활성 확인 (신규 캐릭터는 기본 활성)
        if (characterId > 0 && !semanticSync.isLinked(characterId)) return null

        val fields = universes.getFieldsByUniverseList(universeId)
        val ageField = fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.AGE } ?: return null
        val birthYearField =
            fields.find { SemanticRole.fromConfig(it.config) == SemanticRole.BIRTH_YEAR } ?: return null

        val inputAge = fieldValues.find { it.fieldDefinitionId == ageField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null
        val inputBirthYear = fieldValues.find { it.fieldDefinitionId == birthYearField.id }
            ?.value?.trim()?.toIntOrNull() ?: return null

        val expectedAge = stdYear - inputBirthYear
        if (inputAge == expectedAge) return null

        // 기준연도 변경 시 영향받는 다른 캐릭터 수
        val linkedCount = characters.getCharactersByNovelList(novelId)
            .filter { it.id != characterId }
            .count { semanticSync.isLinked(it.id) }

        return AgeLinkageConflict(
            inputAge = inputAge,
            inputBirthYear = inputBirthYear,
            currentStdYear = stdYear,
            expectedAge = expectedAge,
            suggestedBirthYear = stdYear - inputAge,
            suggestedStdYear = inputBirthYear + inputAge,
            affectedCharacterCount = linkedCount,
            ageFieldId = ageField.id,
            birthYearFieldId = birthYearField.id,
            novelId = novelId
        )
    }

    /**
     * 저장된 캐릭터의 모순을 **라이브로 다시** 검출한다 — 스냅샷이 낡았을 수 있다.
     * 이미 해결됐으면 null.
     */
    suspend fun detectAgeLinkageConflictForCharacter(characterId: Long): AgeLinkageConflict? {
        val character = characters.getCharacterById(characterId) ?: return null
        val values = characters.getValuesByCharacterList(characterId)
        return detectAgeLinkageConflict(character.novelId, characterId, values)
    }
}
