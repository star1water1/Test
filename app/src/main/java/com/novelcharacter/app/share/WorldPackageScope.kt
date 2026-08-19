package com.novelcharacter.app.share

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef

/**
 * 월드패키지에 **무엇이 실리는가**(범위)와 **어떤 순서로 실리는가**(정렬 계약)의 단일 소스.
 *
 * ## 왜 이 파일이 생겼나 — 내보내기가 세계관 하나를 위해 DB 전체를 훑고 있었다
 *
 * [WorldPackageExporter.export]는 세계관 **하나**를 내보내면서 열다섯 테이블을 `getAll…List()`로
 * **전량 적재한 뒤 메모리에서 걸렀다.** 같은 함수 안에서 `fieldValueEntries` 한 자리만
 * [com.novelcharacter.app.util.SqlInChunks]를 지나고 있었으니, **파일이 스스로 세운 규약(R-54)을
 * 나머지 열넷이 못 따라간 것**이다(통계가 3-13에서 겪은 것과 같은 모양 — 규약이 없던 것이 아니라
 * 일부가 그 밖에 있었다).
 *
 * 그 적재가 무는 것은 둘이다.
 *
 * 1. **질의가 세계관 수에 비례한다.** 목표 규모(scalability 2장)의 세계관은 270개다. 한 세계관을
 *    내보내려고 나머지 269개의 행을 전부 읽어 버린다 — 필드값만 36,510행이고, 그 셋 중 하나만
 *    쓴다. **받쳐주지 못하는 확장은 확장이 아니라 결함이다**(개발 의도 1번).
 * 2. **셋은 조인이 곱이었다.** `relChanges`·`crossRefs`·`eventNovelCrossRefs`가
 *    `.filter { x -> 목록.any { it.id == x.…Id } }`로 **한쪽마다 다른 쪽 전체를 훑었다.**
 *    관계·사건은 이 저장소가 *"구조적으로 가장 위험"*하다고 적어 둔 바로 그 축이고
 *    (scalability 5-5), **상한이 없다** — 관계가 캐릭터 수에 대해 최악 O(n²)로 늘 수 있으므로
 *    곱의 두 변이 함께 자란다. 2장이 이 축을 두고 *"미측정이 가장 아픈 자리가 바로 여기다"*라
 *    적은 그 자리다.
 *
 * 처방은 **새 구조가 아니라 이미 있는 것을 쓰게 한 것**이다 — 걸러 내던 외래키는 **전부 인덱스가
 * 있고**(각 엔티티의 `indices`), 필요한 스코프 질의도 대부분 DAO에 이미 있었다. 곱이던 셋은
 * `WHERE …Id IN (:ids)` 한 번으로 사라진다(색인 조회이므로 곱이 아니다).
 *
 * ## 이 파일이 드는 몫 — 범위 규칙과 정렬 계약
 *
 * 적재를 질의로 옮기면 **순서가 바뀐다.** 종전 순서는 전량 질의의 `ORDER BY`가 정했는데,
 * 스코프 질의는 색인 순으로 돌아오고 [com.novelcharacter.app.util.SqlInChunks]가 나눈 조각을
 * 이어 붙이면 조각 경계에서 또 어긋난다. **순서는 곧 패키지의 내용이므로** 그것을 우연에
 * 맡기지 않고 여기 계약으로 박는다.
 *
 * 계약은 **전순서(total order)**다 — 종전보다 강하다. 전량 질의 중 `ORDER BY`가 아예 없던 것이
 * 다섯(필드값 셋·크로스레프 둘)이고, 있던 것도 동순위의 순서는 정해져 있지 않았다. 즉 **같은
 * 세계관을 두 번 내보내면 다른 파일이 나올 수 있었다.** 여기서는 마지막 키로 반드시 기본키를
 * 두어 그 여지를 없앤다 — 두 번 내보낸 것을 견주는 일(백업 대조·공유 재현)이 그때 비로소 선다.
 *
 * **순수 계층인 이유:** 이 판정들은 DB에 붙어 있으면 순수 JVM 시험이 볼 수 없다. 실제로
 * [WorldPackageExporter]는 시험이 **하나도 없었다**(같은 폴더의 `WorldPackageDuels`·
 * `WorldPackageFactionRelationships`가 이미 같은 이유로 순수 계층으로 나와 있고, 이 파일은
 * 그 손버릇을 범위·정렬에도 적용한 것이다). 방어선은 `WorldPackageScopeTest` —
 * **종전 파이프라인(전량 적재 + 메모리 필터)을 하네스가 그대로 들고 대조한다.**
 */
object WorldPackageScope {

    // ───────────────────────── 범위 규칙 ─────────────────────────

    /**
     * 패키지에 실리는 사건 — **합집합**이다.
     *
     * ⓐ 내보내는 작품에 걸린 사건(작품 크로스레프 경유)과 ⓑ 이 세계관에 직접 속한 사건.
     * 둘 중 하나만 취하면 각각 유실이 난다: ⓐ만 취하면 **작품에 안 걸린 세계관 사건**이 통째로
     * 빠지고, ⓑ만 취하면 **세계관이 다르거나 없는 사건이 이 세계관 작품에 걸려 있을 때** 그것이
     * 빠진다. 종전 구현은 전량 목록 하나에 `id in 작품걸린 || universeId == 이세계관`을 걸어
     * 같은 합집합을 냈다 — 여기서는 질의 둘의 결과를 잇고 **id로 겹을 없앤다**(두 조건을 다
     * 만족하는 사건이 양쪽에서 나온다).
     *
     * 순서는 [EVENTS] 계약이 세운다.
     */
    fun eventsInScope(
        linkedToNovels: List<TimelineEvent>,
        ofUniverse: List<TimelineEvent>
    ): List<TimelineEvent> = unionById(linkedToNovels, ofUniverse, { it.id }, EVENTS)

    /**
     * 패키지에 실리는 관계 — **양 끝 중 하나라도** 내보내는 캐릭터면 싣는다.
     *
     * **끝마다 따로 물어 여기서 잇는 이유는 바인드 상한이다.**
     * `characterId1 IN (:ids) OR characterId2 IN (:ids)`는 Room이 목록을 **두 번** 펼쳐 변수를
     * `2 × 목록길이`만큼 쓴다 — 조각 상한(900)을 그대로 넘기면 1,800이 되어 API 31 미만의 기본
     * 상한(999)을 넘긴다. 열마다 한 목록씩 물으면 그 산술이 아예 없어지고, 대신 **두 끝이 모두
     * 범위 안인 관계가 양쪽에서 나오므로** 겹을 여기서 없앤다.
     *
     * 순서는 [RELATIONSHIPS] 계약이 세운다.
     */
    fun relationshipsInScope(
        matchingEnd1: List<CharacterRelationship>,
        matchingEnd2: List<CharacterRelationship>
    ): List<CharacterRelationship> =
        unionById(matchingEnd1, matchingEnd2, { it.id }, RELATIONSHIPS)

    /**
     * 두 질의 결과를 잇고 **키로 겹을 없앤 뒤** 계약 순서로 세운다.
     *
     * 정렬을 겹 제거 **뒤에** 두는 것이 요점이다 — 먼저 정렬하면 같은 행을 두 번 비교하는
     * 값만 치르고 결과는 같다. 겹 제거는 먼저 나온 쪽을 남기지만, 두 질의가 같은 테이블을
     * 보므로 같은 id의 두 행은 같은 행이다.
     */
    private fun <T, K> unionById(
        first: List<T>,
        second: List<T>,
        key: (T) -> K,
        order: Comparator<T>
    ): List<T> {
        if (second.isEmpty()) return first.sortedWith(order)
        if (first.isEmpty()) return second.sortedWith(order)
        val out = ArrayList<T>(first.size + second.size)
        out.addAll(first)
        out.addAll(second)
        return out.distinctBy(key).sortedWith(order)
    }

    // ───────────────────────── 정렬 계약 ─────────────────────────
    //
    // 각 비교자의 앞부분은 **종전 전량 질의의 `ORDER BY`를 그대로 옮긴 것**이고, 마지막 키(기본키)만
    // 이 판이 더한 것이다. `ORDER BY`가 없던 자리는 기본키만 든다.
    //
    // ⚠️ **NULL은 SQLite와 맞춘다** — `ORDER BY month ASC`에서 SQLite는 NULL을 **먼저** 놓는다.
    //    코틀린 기본 비교자는 null을 다루지 못하므로 [nullsFirst]를 명시한다. 이 한 줄이 없으면
    //    "달을 안 적은 사건"의 자리가 종전과 달라진다.

    /** `ORDER BY isPinned DESC, displayOrder ASC, name ASC` + id */
    val CHARACTERS: Comparator<Character> =
        compareByDescending<Character> { it.isPinned }
            .thenBy { it.displayOrder }
            .thenBy { it.name }
            .thenBy { it.id }

    /** `ORDER BY`가 없던 자리 — 기본키만 */
    val CHARACTER_FIELD_VALUES: Comparator<CharacterFieldValue> = compareBy { it.id }

    /** `ORDER BY characterId ASC, year ASC, month ASC, day ASC` + id */
    val STATE_CHANGES: Comparator<CharacterStateChange> =
        compareBy<CharacterStateChange> { it.characterId }
            .thenBy { it.year }
            .thenBy(nullsFirst()) { it.month }
            .thenBy(nullsFirst()) { it.day }
            .thenBy { it.id }

    /** `ORDER BY tag ASC` + id */
    val TAGS: Comparator<CharacterTag> =
        compareBy<CharacterTag> { it.tag }.thenBy { it.id }

    /** `ORDER BY createdAt DESC` + id */
    val RELATIONSHIPS: Comparator<CharacterRelationship> =
        compareByDescending<CharacterRelationship> { it.createdAt }
            .thenBy { it.id }

    /** `ORDER BY year ASC, month ASC, day ASC` + id (관계 변화 스코프 질의의 순서) */
    val RELATIONSHIP_CHANGES: Comparator<CharacterRelationshipChange> =
        compareBy<CharacterRelationshipChange> { it.year }
            .thenBy(nullsFirst()) { it.month }
            .thenBy(nullsFirst()) { it.day }
            .thenBy { it.id }

    /** `ORDER BY year ASC, month ASC, day ASC, displayOrder ASC` + id */
    val EVENTS: Comparator<TimelineEvent> =
        compareBy<TimelineEvent> { it.year }
            .thenBy(nullsFirst()) { it.month }
            .thenBy(nullsFirst()) { it.day }
            .thenBy { it.displayOrder }
            .thenBy { it.id }

    /** `ORDER BY`가 없던 자리 — 복합 기본키 (eventId, characterId) */
    val CROSS_REFS: Comparator<TimelineCharacterCrossRef> =
        compareBy<TimelineCharacterCrossRef> { it.eventId }.thenBy { it.characterId }

    /** `ORDER BY`가 없던 자리 — 복합 기본키 (eventId, novelId) */
    val EVENT_NOVEL_CROSS_REFS: Comparator<TimelineEventNovelCrossRef> =
        compareBy<TimelineEventNovelCrossRef> { it.eventId }.thenBy { it.novelId }

    /** `ORDER BY`가 없던 자리 — 기본키만 */
    val EVENT_FIELD_VALUES: Comparator<EventFieldValue> = compareBy { it.id }

    /** `ORDER BY`가 없던 자리 — 기본키만 */
    val NOVEL_FIELD_VALUES: Comparator<NovelFieldValue> = compareBy { it.id }

    /** `ORDER BY createdAt DESC` + id */
    val NAME_BANK: Comparator<NameBankEntry> =
        compareByDescending<NameBankEntry> { it.createdAt }.thenBy { it.id }

    /** `ORDER BY displayOrder ASC, createdAt DESC` + id */
    val FACTIONS: Comparator<Faction> =
        compareBy<Faction> { it.displayOrder }
            .thenByDescending { it.createdAt }
            .thenBy { it.id }

    /** `ORDER BY`가 없던 자리 — 기본키만 */
    val FACTION_MEMBERSHIPS: Comparator<FactionMembership> = compareBy { it.id }
}
