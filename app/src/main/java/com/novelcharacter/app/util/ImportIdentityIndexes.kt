package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.DefaultFieldTemplate
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FieldDefinition

/**
 * 가져오기와 **복원 미리보기**가 함께 쓰는 정체성 색인 묶음 (B-236).
 *
 * ## 무엇을 한 자리에 모으는가
 *
 * [ImportLookupIndex]는 *색인 그 자체*이고, 이 파일은 그 색인을 **어떤 키로 짓고 어떤 순서로
 * 싣는가**를 든다. 그 둘이 이 저장소에서 조용히 갈리던 자리였다:
 *
 * - **키 모양** — `getChangeByNaturalKey(characterId, year, fieldKey, newValue)` 꼴을 호출부마다
 *   다시 적으면 칸 하나를 빠뜨려도 컴파일이 통과한다.
 * - **싣는 순서** — 대체하는 질의는 `LIMIT 1`(= rowid 순)이거나 `ORDER BY`가 붙어 있고,
 *   [ImportLookupIndex.first]는 **먼저 실린 것**을 준다. 순서가 틀리면 **같은 키를 든 행이 둘
 *   있는 파일에서 합쳐지는 상대가 바뀐다** — 오류도 고지도 없이.
 *
 * **왜 지금 모으는가:** B-210이 가져오기 쪽에 이 색인들을 세웠는데 **미리보기(`analyze*`)는
 * 행마다 DAO를 부르는 채로 남아 있었다**(B-236). 미리보기를 고치면서 순서 규약을 그쪽에 **다시
 * 적으면** 같은 사실이 두 자리에 살고, 이 저장소가 여러 번 확인했듯 **한쪽이 반드시 낡는다.**
 * 규약 R-33(미리보기 ↔ 가져오기 정합)이 요구하는 것은 *같은 답*인데, 순서가 갈리면 같은
 * `merge*`를 써도 **비교 상대가 달라져** 답이 갈린다.
 *
 * ## 왜 순수 파일인가
 *
 * `ExcelImportService`는 순수 JVM 시험이 닿지 못한다(그 파일 [ImportLookupIndex] KDoc의 근거와
 * 같다). **미리보기의 계수는 DB에 묶여 있어 순수로 잴 수 없지만, 위 두 가지 — 키 모양과 싣는
 * 순서 — 는 순수다.** 그래서 여기로 내려 `ImportIdentityIndexesTest`가 잰다.
 *
 * ## 쓰는 쪽 규약
 *
 * - **가져오기**는 insert·update마다 `remember(...)`를 부른다([ImportLookupIndex] 성질 2·3).
 * - **미리보기**는 쓰지 않으므로 짓고 읽기만 한다 — 그래서 갱신 문제가 없다.
 * - 둘 다 **시트 함수의 지역 변수로 둘 것**(서비스에 매달면 가져오기가 끝나도 남아 곧이어 도는
 *   내보내기·자동 백업의 바닥을 올린다 — [ImportLookupIndex]의 '메모리' 절).
 */

/**
 * `getEventByNaturalKey(year, description)`의 키.
 *
 * 아래 키들은 **미리보기가 쓰지 않는 것까지 함께 둔다** — 이 파일이 *"가져오기 정체성 키는
 * 여기 산다"*가 아니라 *"미리보기도 쓰는 것만 여기 산다"*가 되면, 다음에 키를 만드는 사람이
 * 어느 파일인지 고르게 되고 그 선택은 반드시 갈린다.
 */
data class EventNaturalKey(val year: Int, val description: String)

/** `getByAxisAndMember(axisId, memberKey)`의 키. */
data class DuelVerdictMemberKey(val axisId: Long, val memberKey: String)

/** `getChangeByNaturalKey(characterId, year, fieldKey, newValue)`의 키. */
data class StateChangeNaturalKey(
    val characterId: Long, val year: Int, val fieldKey: String, val newValue: String
)

/** `getChangeByNaturalKey(relationshipId, year, month, day)`의 키 — 월·일은 null도 값이다. */
data class RelChangeNaturalKey(
    val relationshipId: Long, val year: Int, val month: Int?, val day: Int?
)

/** `getByNameAndUniverse(name, universeId)`의 키. */
data class FactionNameKey(val name: String, val universeId: Long)

/** `getByUniverseAndName(universeId, targetType, name)`의 키. */
data class DuelAxisNameKey(val universeId: Long, val targetType: String, val name: String)

/**
 * `getNovelByTitleAndUniverse` / `getNovelByTitleNoUniverse`의 키.
 * **세계관 없음(null)은 별개의 키다** — SQL도 `universeId IS NULL`을 따로 물으므로,
 * 미지정 작품과 특정 세계관의 동명 작품이 서로를 찾으면 안 된다.
 */
data class NovelTitleKey(val title: String, val universeId: Long?)

/**
 * `getFieldByKey(universeId, key, entityType)` / `getGlobalFieldByKey(key, entityType)`의 키.
 * 전역 필드(universeId = null)는 [NovelTitleKey]와 같은 이유로 별개의 키다.
 */
data class FieldDefKey(val universeId: Long?, val key: String, val entityType: String)

/**
 * 관계의 **쌍** 키 — 관계는 두 캐릭터에 함께 매달려 방향이 없다.
 * 작은 id를 앞에 두어 `(가, 나)`와 `(나, 가)`가 한 키가 된다.
 */
data class CharacterPairKey(val low: Long, val high: Long) {
    companion object {
        fun of(a: Long, b: Long) = CharacterPairKey(minOf(a, b), maxOf(a, b))
    }
}

/**
 * 상태 변화 — `getChangeByCode`(코드) + `getChangeByNaturalKey`(자연키).
 * 둘 다 `LIMIT 1`이라 **id 오름차순**이 그 답의 순서다
 * (`getAllChangesList()`는 캐릭터·연월일 순으로 나오므로 다시 정렬한다).
 */
class StateChangeIndexes(rows: List<CharacterStateChange>) {
    val byCode = ImportLookupIndex<String, CharacterStateChange>(
        idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
    )
    val byNaturalKey = ImportLookupIndex<StateChangeNaturalKey, CharacterStateChange>(
        idOf = { it.id },
        keyOf = { StateChangeNaturalKey(it.characterId, it.year, it.fieldKey, it.newValue) }
    )

    init {
        val ordered = rows.sortedBy { it.id }
        byCode.load(ordered)
        byNaturalKey.load(ordered)
    }

    /**
     * 이 행을 썼다고 기록한다. **자연키의 칸(연도·필드키·새 값)이 바뀌었을 수 있으므로**
     * 두 축을 함께 갱신한다 — 한쪽만 갱신하면 뒤 행이 *이미 다른 이력이 된 행*을 옛 키로 잡는다.
     */
    fun remember(row: CharacterStateChange) {
        byCode.put(row)
        byNaturalKey.put(row)
    }
}

/**
 * 관계 — `getByCode`(코드) + 쌍(`getRelationshipsForCharacterList` 뒤의 쌍 거르기).
 *
 * **싣는 순서는 `ORDER BY displayOrder ASC, createdAt DESC`다** — 호출부가 그 목록에서
 * `find { 유형이 같다 }`로 하나를 고르므로, 같은 쌍에 같은 유형이 둘인 파일에서
 * **고르는 상대가 바뀐다.** 코드 축도 같은 목록으로 싣는다(가져오기가 그렇게 해 왔고,
 * 미리보기는 가져오기와 같은 답을 내야 한다 — R-33).
 */
class RelationshipIndexes(rows: List<CharacterRelationship>) {
    val byCode = ImportLookupIndex<String, CharacterRelationship>(
        idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
    )
    val byPair = ImportLookupIndex<CharacterPairKey, CharacterRelationship>(
        idOf = { it.id }, keyOf = { CharacterPairKey.of(it.characterId1, it.characterId2) }
    )

    init {
        val ordered = rows.sortedWith(
            compareBy<CharacterRelationship> { it.displayOrder }.thenByDescending { it.createdAt }
        )
        byCode.load(ordered)
        byPair.load(ordered)
    }

    /** 이 쌍의 관계 전부 — `getRelationshipsForCharacterList(...).filter { 쌍이 같다 }`의 자리. */
    fun pair(a: Long, b: Long): List<CharacterRelationship> = byPair.all(CharacterPairKey.of(a, b))

    fun remember(row: CharacterRelationship) {
        byCode.put(row)
        byPair.put(row)
    }
}

/**
 * 관계 변화 — `getChangeByCode` + `getChangeByNaturalKey`.
 * 둘 다 `LIMIT 1`이라 **id 오름차순**이 그 답의 순서다.
 */
class RelationshipChangeIndexes(rows: List<CharacterRelationshipChange>) {
    val byCode = ImportLookupIndex<String, CharacterRelationshipChange>(
        idOf = { it.id }, keyOf = { it.code?.takeIf { c -> c.isNotBlank() } }
    )
    val byNaturalKey = ImportLookupIndex<RelChangeNaturalKey, CharacterRelationshipChange>(
        idOf = { it.id },
        keyOf = { RelChangeNaturalKey(it.relationshipId, it.year, it.month, it.day) }
    )

    init {
        val ordered = rows.sortedBy { it.id }
        byCode.load(ordered)
        byNaturalKey.load(ordered)
    }

    /** 자연키의 칸(부모 관계·연월일)이 바뀌었을 수 있으므로 두 축을 함께 갱신한다. */
    fun remember(row: CharacterRelationshipChange) {
        byCode.put(row)
        byNaturalKey.put(row)
    }
}

/**
 * 세력 — `getByCode` + `getByNameAndUniverse`. **id 오름차순**이 `LIMIT 1`의 순서다.
 * (`getByCode`는 `LIMIT`이 없지만 Room이 첫 행을 주므로 같은 자리다.)
 */
class FactionIdentityIndexes(rows: List<Faction>) {
    val byCode = ImportLookupIndex<String, Faction>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )
    val byNameKey = ImportLookupIndex<FactionNameKey, Faction>(
        idOf = { it.id }, keyOf = { FactionNameKey(it.name, it.universeId) }
    )

    init {
        val ordered = rows.sortedBy { it.id }
        byCode.load(ordered)
        byNameKey.load(ordered)
    }

    fun remember(row: Faction) {
        byCode.put(row)
        byNameKey.put(row)
    }
}

/**
 * 대결 축 — `getByCode` + `getByUniverseAndName`. **id 오름차순**이 `LIMIT 1`의 순서다
 * (`getAllList()`는 세계관·표시순으로 나오므로 다시 정렬한다).
 */
class DuelAxisIndexes(rows: List<DuelAxis>) {
    val byCode = ImportLookupIndex<String, DuelAxis>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )
    val byNameKey = ImportLookupIndex<DuelAxisNameKey, DuelAxis>(
        idOf = { it.id }, keyOf = { DuelAxisNameKey(it.universeId, it.targetType, it.name) }
    )

    init {
        val ordered = rows.sortedBy { it.id }
        byCode.load(ordered)
        byNameKey.load(ordered)
    }

    fun remember(row: DuelAxis) {
        byCode.put(row)
        byNameKey.put(row)
    }
}

/** 대결 기록 — `getByCode` 하나뿐이다. **id 오름차순**이 `LIMIT 1`의 순서다. */
class DuelMatchIndexes(rows: List<DuelMatch>) {
    val byCode = ImportLookupIndex<String, DuelMatch>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )

    init {
        byCode.load(rows.sortedBy { it.id })
    }

    fun remember(row: DuelMatch) = byCode.put(row)
}

/**
 * 필드 정의 — `getFieldByKey` / `getGlobalFieldByKey`.
 * 두 질의 모두 `LIMIT`이 없고 Room이 **첫 행**을 주므로 **id 오름차순**이 그 답의 순서다
 * (`getAllFieldsAllTypes()`는 세계관·표시순으로 나오므로 다시 정렬한다).
 */
class FieldDefinitionIndexes(rows: List<FieldDefinition>) {
    val byKey = ImportLookupIndex<FieldDefKey, FieldDefinition>(
        idOf = { it.id }, keyOf = { FieldDefKey(it.universeId, it.key, it.entityType) }
    )

    init {
        byKey.load(rows.sortedBy { it.id })
    }

    /** 세계관 필드는 그 세계관에서만, 전역 필드는 `universeId = null`에서만 찾는다. */
    fun find(universeId: Long?, key: String, entityType: String): FieldDefinition? =
        byKey.first(FieldDefKey(universeId, key, entityType))

    fun remember(row: FieldDefinition) = byKey.put(row)
}

/** `getBySlot(entityType, key)`의 키 — 표의 유니크 `(entityType, key)`와 같은 짝이다. */
data class DefaultFieldSlot(val entityType: String, val key: String)

/**
 * 전역 기본 필드 템플릿의 정체성 색인 (B-252).
 *
 * **왜 색인인가 — 두 가지가 한꺼번에 걸린다.**
 *
 * 1. **정합(R-33).** 미리보기에 [importDefaultFieldTemplates][com.novelcharacter.app.excel.ExcelImportService]의
 *    *자리 충돌 게이트*가 빠져 있었다 — 가져오기는 자리를 옮기려는 편집이 남의 자리와 부딪치면
 *    키·대상을 **되돌리는데**, 미리보기는 그 행을 '변경'이라 예고하고 실제로는 키가 그대로였다.
 *    게이트를 세우려면 *"그 자리를 누가 쓰는가"*를 물어야 하고, 그 물음의 자리가 여기다.
 * 2. **행마다 묻지 않는다(R-53).** 그 게이트를 DAO로 세우면 미리보기가 행마다 조회를 **하나 더**
 *    하게 된다. 미리보기는 이미 `getByCode`·`getBySlot`를 행마다 물고 있었는데, 그 자리는
 *    `check_preview_row_queries.sh`가 스스로 적어 둔 **사각**이다(클래스 수준 private 함수 속의
 *    DAO 호출은 그 검사가 보지 못한다). 표 한 번 읽기로 내리면 셋이 함께 0이 된다.
 *
 * **싣는 순서는 형제들과 같다** — id 오름차순. 대체하는 질의가 `LIMIT 1`(= rowid 순)이라
 * [ImportLookupIndex.first]가 같은 행을 골라야 한다.
 */
class DefaultFieldTemplateIndexes(rows: List<DefaultFieldTemplate>) {
    /** 빈 코드는 싣지 않는다 — 통을 하나 만들어 **코드 없는 행 전부를 서로의 답으로** 만든다. */
    val byCode = ImportLookupIndex<String, DefaultFieldTemplate>(
        idOf = { it.id }, keyOf = { it.code.takeIf { c -> c.isNotBlank() } }
    )
    val bySlot = ImportLookupIndex<DefaultFieldSlot, DefaultFieldTemplate>(
        idOf = { it.id }, keyOf = { DefaultFieldSlot(it.entityType, it.key) }
    )

    /** `getMaxOrder`가 쓰는 전량 — 색인은 키로만 답해 훑을 수 없다. */
    private val allById = LinkedHashMap<Long, DefaultFieldTemplate>()

    init {
        val ordered = rows.sortedBy { it.id }
        byCode.load(ordered)
        bySlot.load(ordered)
        ordered.forEach { allById[it.id] = it }
    }

    /** 정체는 **코드 우선, 없으면 (대상, 필드키)** — 다른 시트와 같은 규약이다(R-1). */
    fun resolve(code: String, entityType: String, key: String): DefaultFieldTemplate? =
        code.takeIf { it.isNotBlank() }?.let { byCode.first(it) }
            ?: bySlot.first(DefaultFieldSlot(entityType, key))

    /** 이 자리를 지금 누가 쓰는가 — `getBySlot`의 자리. */
    fun slotOwner(entityType: String, key: String): DefaultFieldTemplate? =
        bySlot.first(DefaultFieldSlot(entityType, key))

    /** 이 코드를 지금 누가 쓰는가 — `getByCode`의 자리. */
    fun codeOwner(code: String): DefaultFieldTemplate? =
        code.takeIf { it.isNotBlank() }?.let { byCode.first(it) }

    /** 이 종류의 최대 순서 — `getMaxOrder`의 자리(없으면 null). */
    fun maxOrder(entityType: String): Int? =
        allById.values.filter { it.entityType == entityType }.maxOfOrNull { it.displayOrder }

    fun remember(row: DefaultFieldTemplate) {
        byCode.put(row)
        bySlot.put(row)
        allById[row.id] = row
    }
}

/**
 * 자리를 옮기는 편집이 **남의 자리와 부딪치면 키·대상을 되돌린다** — 유니크 `(대상, 필드키)`
 * (B-252). 미리보기와 가져오기가 **이 함수 하나**를 지난다(R-33).
 *
 * 종전에는 이 규칙이 `importDefaultFieldTemplates` 안에만 있었고, 미리보기는
 * `merge*(existing, r) != existing` 하나로 판정했다 — **되돌려질 행을 '변경'이라 예고**했다.
 * 설계 1-2가 등급 체계의 `rename` 게이트에서 고친 것과 **글자 그대로 같은 부류**다.
 *
 * **되돌리는 것은 키·대상뿐이다** — 이름·타입·설정 같은 나머지 편집은 그대로 반영된다.
 * 그래서 돌려준 값이 `existing`과 같을 수도, 다를 수도 있고, 부르는 쪽은 그것을 견주어
 * '변경/동일'을 가른다. **부딪쳤는가는 돌려준 값이 [merged]와 다른가로 알 수 있다.**
 *
 * @param slotOwner 그 자리를 지금 쓰고 있는 행(자기 자신이면 부딪친 것이 아니다).
 */
fun guardDefaultFieldSlot(
    existing: DefaultFieldTemplate,
    merged: DefaultFieldTemplate,
    slotOwner: (String, String) -> DefaultFieldTemplate?
): DefaultFieldTemplate {
    // 자리를 옮기지 않는 편집은 물을 것이 없다 — 자기 자리는 언제나 자기 것이다.
    if (merged.entityType == existing.entityType && merged.key == existing.key) return merged
    slotOwner(merged.entityType, merged.key)?.takeIf { it.id != existing.id } ?: return merged
    return merged.copy(key = existing.key, entityType = existing.entityType)
}
