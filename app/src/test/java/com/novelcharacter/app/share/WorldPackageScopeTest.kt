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
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 월드패키지 내보내기의 **범위와 순서** — 전량 적재를 범위 질의로 바꾼 판의 방어선.
 *
 * **무엇을 지키는가.** [WorldPackageExporter]는 세계관 하나를 내보내면서 열넷을 `getAll…List()`로
 * 통째로 올린 뒤 메모리에서 걸렀다. 그것을 `WHERE … IN (:ids)` 범위 질의로 옮기면 **두 가지가
 * 달라질 수 있고, 둘 다 조용하다**:
 *
 * 1. **고르는 행이 달라진다** → 패키지에서 무언가가 말없이 빠진다(R-1 — 조용한 유실).
 * 2. **순서가 달라진다** → 질의는 색인 순으로 돌아오고, [com.novelcharacter.app.util.SqlInChunks]가
 *    나눈 조각을 이어 붙이면 조각 경계에서 또 어긋난다.
 *
 * **그래서 종전 파이프라인을 하네스가 그대로 들고 대조한다**(`StatsOverviewParityTest`·
 * `GraphForceLayoutTest`가 세운 규약). 하네스는 *전량 목록에 필터*이고, 대조 대상은 *범위 질의
 * 시뮬레이션 + 정렬 계약*이다. 질의 시뮬레이션은 **조각내기와 임의 반환 순서까지 재현한다** —
 * 그 둘이 순서를 깨는 실제 원인이라, 재현하지 않으면 이 시험은 깨질 것을 못 본다.
 *
 * 내보내기 자신은 Room을 물어 순수 JVM이 닿지 않으므로, 판정을 [WorldPackageScope]에 두고
 * 여기서 실행으로 잠근다(같은 폴더의 `WorldPackageDuels`·`WorldPackageFactionRelationships`와
 * 같은 손버릇).
 *
 * ## 못 보는 자리 — 적어 둔다 (R-49의 관행)
 *
 * **`scopedQuery`는 SQL의 *모형*이지 SQL이 아니다.** 이 시험이 증명하는 것과 못 하는 것을 가른다.
 *
 * | 증명한다 | 못 한다 |
 * |---|---|
 * | 술어 번역이 옳다 — `WHERE x IN (:ids)`가 종전 `filter { x in ids }`와 같은 행을 고른다 | **내보내기가 그 DAO 메서드를 실제로 부르는가.** 배선은 Room에 있어 기기·CI 몫이다 |
 * | [WorldPackageScope]의 합집합·겹 제거·정렬이 옳다 (여기는 **실물 코드**를 민다) | Room이 낸 SQL이 정말 그 인덱스를 타는가 |
 * | 조각내기와 임의 반환 순서에 순서가 안 흔들린다 | 실제 SQLite의 collation(태그 `ORDER BY tag ASC`)이 코틀린 문자열 비교와 같은가 — 한글·ASCII는 부호점 순서가 같아 어긋나지 않지만 **잰 것은 아니다** |
 */
class WorldPackageScopeTest {

    // ───────────────────────── 합성 저장소 ─────────────────────────
    //
    // 세계관 셋 · 작품 다섯 — **내보내는 것 하나에 견줘 나머지가 많은 모양**이라야
    // "전량을 올려 걸렀다"와 "범위만 물었다"가 갈린다.

    private val exportedUniverse = 1L
    private val exportedNovels = listOf(10L, 11L)

    private val characters = buildList {
        // 내보내는 작품 소속
        add(ch(1, "가", novelId = 10, pinned = true, order = 5))
        add(ch(2, "나", novelId = 10, pinned = false, order = 1))
        add(ch(3, "다", novelId = 11, pinned = false, order = 1))
        // 범위 밖 — 다른 세계관의 작품 / 작품 미배정
        add(ch(4, "라", novelId = 20, pinned = true, order = 0))
        add(ch(5, "마", novelId = 21, pinned = false, order = 0))
        add(ch(6, "바", novelId = null, pinned = false, order = 0))
    }
    private val exportedCharacterIds = listOf(1L, 2L, 3L)

    private val fieldValues = (1L..6L).flatMap { c ->
        listOf(CharacterFieldValue(id = c * 10, characterId = c, fieldDefinitionId = 1, value = "v$c"),
               CharacterFieldValue(id = c * 10 + 1, characterId = c, fieldDefinitionId = 2, value = "w$c"))
    }

    private val stateChanges = listOf(
        sc(1, characterId = 2, year = 1000, month = null, day = null),
        sc(2, characterId = 2, year = 1000, month = 3, day = null),
        sc(3, characterId = 1, year = 990, month = 12, day = 31),
        sc(4, characterId = 4, year = 990, month = 1, day = 1)   // 범위 밖
    )

    private val tags = listOf(
        CharacterTag(id = 1, characterId = 3, tag = "나중"),
        CharacterTag(id = 2, characterId = 1, tag = "가장"),
        CharacterTag(id = 3, characterId = 1, tag = "나중"),
        CharacterTag(id = 4, characterId = 5, tag = "가장")      // 범위 밖
    )

    private val relationships = listOf(
        rel(1, 1, 2, createdAt = 300),   // 양 끝 모두 범위 안 → 두 질의에서 함께 나온다
        rel(2, 3, 5, createdAt = 500),   // 한쪽만 범위 안
        rel(3, 4, 2, createdAt = 100),   // 반대쪽만 범위 안
        rel(4, 4, 5, createdAt = 900)    // 범위 밖
    )

    private val relChanges = listOf(
        rc(1, relationshipId = 1, year = 1000, month = null),
        rc(2, relationshipId = 1, year = 1000, month = 5),
        rc(3, relationshipId = 3, year = 900, month = 1),
        rc(4, relationshipId = 4, year = 800, month = 1)         // 범위 밖 관계의 변화
    )

    private val events = listOf(
        ev(100, year = 1000, month = null, universeId = exportedUniverse),  // 세계관 소속
        ev(101, year = 1000, month = 2, universeId = null),                 // 작품 경유로만 들어온다
        ev(102, year = 900, month = 1, universeId = exportedUniverse),      // 둘 다 — 겹
        ev(103, year = 1000, month = 1, universeId = 2L),                   // 남의 세계관인데 우리 작품에 걸림
        ev(104, year = 700, month = 1, universeId = 2L)                     // 범위 밖
    )

    private val eventNovelCrossRefs = listOf(
        TimelineEventNovelCrossRef(eventId = 101, novelId = 10),
        TimelineEventNovelCrossRef(eventId = 102, novelId = 11),
        TimelineEventNovelCrossRef(eventId = 103, novelId = 10),
        // 내보내는 사건이 **이번에 안 싣는 작품**에도 걸려 있다 — 이 줄도 패키지의 것이다
        TimelineEventNovelCrossRef(eventId = 100, novelId = 20),
        TimelineEventNovelCrossRef(eventId = 104, novelId = 21)  // 범위 밖
    )

    private val crossRefs = listOf(
        TimelineCharacterCrossRef(eventId = 100, characterId = 1),
        TimelineCharacterCrossRef(eventId = 102, characterId = 3),
        TimelineCharacterCrossRef(eventId = 100, characterId = 2),
        TimelineCharacterCrossRef(eventId = 104, characterId = 4)  // 범위 밖 사건
    )

    private val nameBank = listOf(
        nb(1, "이름ㄱ", usedBy = 1, createdAt = 100),
        nb(2, "이름ㄴ", usedBy = 3, createdAt = 300),
        nb(3, "이름ㄷ", usedBy = null, createdAt = 200),   // 미사용 — 패키지의 것이 아니다
        nb(4, "이름ㄹ", usedBy = 5, createdAt = 400)       // 범위 밖 캐릭터
    )

    private val factions = listOf(
        fac(1, universeId = exportedUniverse, order = 2, createdAt = 100),
        fac(2, universeId = exportedUniverse, order = 1, createdAt = 200),
        fac(3, universeId = 2L, order = 0, createdAt = 300)          // 남의 세계관
    )

    private val factionMemberships = listOf(
        FactionMembership(id = 1, factionId = 1, characterId = 1),
        FactionMembership(id = 2, factionId = 2, characterId = 3),
        FactionMembership(id = 3, factionId = 3, characterId = 4)     // 범위 밖 세력
    )

    private val eventFieldValues = listOf(
        EventFieldValue(id = 1, eventId = 100, fieldDefinitionId = 1, value = "a"),
        EventFieldValue(id = 2, eventId = 103, fieldDefinitionId = 1, value = "b"),
        EventFieldValue(id = 3, eventId = 104, fieldDefinitionId = 1, value = "c")  // 범위 밖 사건
    )

    private val novelFieldValues = listOf(
        NovelFieldValue(id = 1, novelId = 10, fieldDefinitionId = 1, value = "a"),
        NovelFieldValue(id = 2, novelId = 11, fieldDefinitionId = 1, value = "b"),
        NovelFieldValue(id = 3, novelId = 20, fieldDefinitionId = 1, value = "c")   // 범위 밖 작품
    )

    // ─────────────── 종전 파이프라인 (하네스 — 전량 목록에 필터) ───────────────

    private val legacyNovelIds = exportedNovels.toSet()
    private val legacyCharacterIds get() = legacyCharacters.map { it.id }.toSet()

    private val legacyCharacters get() = characters.filter { it.novelId in legacyNovelIds }
    private val legacyFieldValues get() = fieldValues.filter { it.characterId in legacyCharacterIds }
    private val legacyStateChanges get() = stateChanges.filter { it.characterId in legacyCharacterIds }
    private val legacyTags get() = tags.filter { it.characterId in legacyCharacterIds }
    private val legacyRelationships get() = relationships.filter {
        it.characterId1 in legacyCharacterIds || it.characterId2 in legacyCharacterIds
    }
    private val legacyRelChanges get() = relChanges.filter { c -> legacyRelationships.any { it.id == c.relationshipId } }
    private val legacyEventIdsWithNovels get() =
        eventNovelCrossRefs.filter { it.novelId in legacyNovelIds }.map { it.eventId }.toSet()
    private val legacyEvents get() = events.filter {
        it.id in legacyEventIdsWithNovels || it.universeId == exportedUniverse
    }
    private val legacyCrossRefs get() = crossRefs.filter { c -> legacyEvents.any { it.id == c.eventId } }
    private val legacyEventNovelCrossRefs get() =
        eventNovelCrossRefs.filter { c -> legacyEvents.any { it.id == c.eventId } }
    private val legacyFactions get() = factions.filter { it.universeId == exportedUniverse }
    private val legacyFactionIds get() = legacyFactions.map { it.id }.toSet()
    private val legacyFactionMemberships get() = factionMemberships.filter { it.factionId in legacyFactionIds }
    private val legacyEventFieldValues get() = eventFieldValues.filter { it.eventId in legacyEvents.map { e -> e.id }.toSet() }
    private val legacyNovelFieldValues get() = novelFieldValues.filter { it.novelId in legacyNovelIds }
    private val legacyNameBank get() =
        nameBank.filter { it.usedByCharacterId?.let(legacyCharacterIds::contains) == true }

    // ─────────────── 이 판의 파이프라인 (범위 질의 시뮬레이션) ───────────────
    //
    // `WHERE x IN (:ids)`는 **색인 순**으로 돌아오고 [SqlInChunks]가 나눈 조각은 **이어 붙는다**.
    // 그 둘이 순서를 깨는 실제 원인이므로 시뮬레이션이 그대로 재현한다 — 조각 크기를 2로 두어
    // 경계가 반드시 생기게 하고, 조각마다 반환 순서를 섞는다.

    private fun <T, K> scopedQuery(
        table: List<T>,
        ids: List<K>,
        keyOf: (T) -> K?,
        seed: Long
    ): List<T> {
        val rnd = Random(seed)
        return ids.chunked(2).flatMap { chunk ->
            table.filter { keyOf(it) in chunk }.shuffled(rnd)
        }
    }

    private fun scopedCharacters(seed: Long) =
        scopedQuery(characters, exportedNovels, { it.novelId }, seed)
            .sortedWith(WorldPackageScope.CHARACTERS)

    private fun scopedEvents(seed: Long): List<TimelineEvent> {
        val idsWithNovels = scopedQuery(eventNovelCrossRefs, exportedNovels, { it.novelId }, seed)
            .map { it.eventId }.distinct()
        return WorldPackageScope.eventsInScope(
            scopedQuery(events, idsWithNovels, { it.id }, seed + 1),
            events.filter { it.universeId == exportedUniverse }.shuffled(Random(seed + 2))
        )
    }

    private fun scopedRelationships(seed: Long) = WorldPackageScope.relationshipsInScope(
        scopedQuery(relationships, exportedCharacterIds, { it.characterId1 }, seed),
        scopedQuery(relationships, exportedCharacterIds, { it.characterId2 }, seed + 1)
    )

    // ───────────────────────── 내용 대조 ─────────────────────────

    @Test
    fun `모든 절이 종전과 정확히 같은 행을 고른다`() {
        for (seed in 1L..25L) {
            val chars = scopedCharacters(seed)
            val charIds = chars.map { it.id }
            val evts = scopedEvents(seed)
            val evtIds = evts.map { it.id }

            sameRows("캐릭터", legacyCharacters, chars) { it.id }
            sameRows("사건", legacyEvents, evts) { it.id }
            sameRows("관계", legacyRelationships, scopedRelationships(seed)) { it.id }

            sameRows("필드값", legacyFieldValues,
                scopedQuery(fieldValues, charIds, { it.characterId }, seed)) { it.id }
            sameRows("상태변화", legacyStateChanges,
                scopedQuery(stateChanges, charIds, { it.characterId }, seed)) { it.id }
            sameRows("태그", legacyTags,
                scopedQuery(tags, charIds, { it.characterId }, seed)) { it.id }
            sameRows("관계변화", legacyRelChanges,
                scopedQuery(relChanges, scopedRelationships(seed).map { it.id }, { it.relationshipId }, seed)) { it.id }
            sameRows("사건-캐릭터", legacyCrossRefs,
                scopedQuery(crossRefs, evtIds, { it.eventId }, seed)) { "${it.eventId}:${it.characterId}" }
            sameRows("사건-작품", legacyEventNovelCrossRefs,
                scopedQuery(eventNovelCrossRefs, evtIds, { it.eventId }, seed)) { "${it.eventId}:${it.novelId}" }
            sameRows("이름은행", legacyNameBank,
                scopedQuery(nameBank, charIds, { it.usedByCharacterId }, seed)) { it.id }

            // 세력은 세계관 술어라 조각내기가 없다 — 질의가 곧 범위다
            val facs = factions.filter { it.universeId == exportedUniverse }
                .shuffled(kotlin.random.Random(seed))
                .sortedWith(WorldPackageScope.FACTIONS)
            sameRows("세력", legacyFactions, facs) { it.id }
            sameRows("세력 소속", legacyFactionMemberships,
                scopedQuery(factionMemberships, facs.map { it.id }, { it.factionId }, seed)) { it.id }
            sameRows("사건 필드값", legacyEventFieldValues,
                scopedQuery(eventFieldValues, evtIds, { it.eventId }, seed)) { it.id }
            sameRows("작품 필드값", legacyNovelFieldValues,
                scopedQuery(novelFieldValues, exportedNovels, { it.novelId }, seed)) { it.id }
        }
    }

    private fun <T, K : Comparable<K>> sameRows(what: String, legacy: List<T>, scoped: List<T>, key: (T) -> K) {
        assertEquals("$what — 고른 행이 갈렸다", legacy.map(key).sorted(), scoped.map(key).sorted())
    }

    // ───────────────────────── 범위 규칙 ─────────────────────────

    @Test
    fun `사건 범위는 작품 걸림과 세계관 소속의 합집합이다`() {
        val ids = scopedEvents(7).map { it.id }
        assertTrue("세계관에만 속한 사건이 빠졌다", 100L in ids)
        assertTrue("작품에만 걸린 사건이 빠졌다", 101L in ids)
        assertTrue("남의 세계관이지만 우리 작품에 걸린 사건이 빠졌다", 103L in ids)
        assertTrue("범위 밖 사건이 실렸다", 104L !in ids)
    }

    @Test
    fun `양쪽 조건을 다 만족하는 사건도 한 번만 실린다`() {
        assertEquals(1, scopedEvents(7).count { it.id == 102L })
    }

    @Test
    fun `내보내는 사건이 안 싣는 작품에 걸린 줄도 패키지에 남는다`() {
        // 사건 100은 세계관 소속이라 실리고, 그것이 작품 20(안 싣는 작품)에 걸려 있다.
        // 작품 축으로만 물으면 이 줄이 조용히 빠진다 — 사건 축으로 다시 묻는 이유다.
        val rows = scopedQuery(eventNovelCrossRefs, scopedEvents(3).map { it.id }, { it.eventId }, 3)
        assertTrue(rows.any { it.eventId == 100L && it.novelId == 20L })
        assertEquals(legacyEventNovelCrossRefs.size, rows.size)
    }

    @Test
    fun `관계는 두 끝 질의를 합치고 겹을 없앤다`() {
        val rels = scopedRelationships(11)
        assertEquals("양 끝이 모두 범위 안인 관계가 두 번 실렸다", 1, rels.count { it.id == 1L })
        assertEquals(listOf(1L, 2L, 3L), rels.map { it.id }.sorted())
    }

    @Test
    fun `작품 미배정 캐릭터는 실리지 않는다`() {
        assertTrue(scopedCharacters(5).none { it.id == 6L })
    }

    // ───────────────────────── 정렬 계약 ─────────────────────────

    @Test
    fun `순서는 질의 반환 순서와 조각 경계에 흔들리지 않는다`() {
        val first = scopedCharacters(1).map { it.id }
        val firstEvents = scopedEvents(1).map { it.id }
        val firstRels = scopedRelationships(1).map { it.id }
        for (seed in 2L..40L) {
            assertEquals("캐릭터 순서가 씨앗에 흔들렸다", first, scopedCharacters(seed).map { it.id })
            assertEquals("사건 순서가 씨앗에 흔들렸다", firstEvents, scopedEvents(seed).map { it.id })
            assertEquals("관계 순서가 씨앗에 흔들렸다", firstRels, scopedRelationships(seed).map { it.id })
        }
    }

    @Test
    fun `캐릭터 정렬은 종전 ORDER BY 그대로다 — 고정 먼저, 그다음 표시순서, 그다음 이름`() {
        // ORDER BY isPinned DESC, displayOrder ASC, name ASC
        assertEquals(listOf(1L, 2L, 3L), scopedCharacters(9).map { it.id })
    }

    @Test
    fun `달과 일이 비면 SQLite와 같은 자리에 둔다 — NULL이 먼저다`() {
        // ORDER BY year ASC, month ASC, day ASC 에서 SQLite는 NULL을 먼저 놓는다.
        val ordered = scopedEvents(4).map { it.id }
        assertEquals(listOf(102L, 100L, 103L, 101L), ordered)
        //          ↑ 900년 · 1000년(월 없음) · 1000년(월 1) · 1000년(월 2)
        //            — 같은 해 안에서 **월이 빈 것이 먼저**다
        assertTrue("같은 해에서 월이 빈 사건이 월 1보다 뒤로 갔다",
            ordered.indexOf(100L) < ordered.indexOf(103L))
    }

    @Test
    fun `상태변화 정렬은 캐릭터 그다음 날짜이고 빈 달이 먼저다`() {
        val ordered = stateChanges.filter { it.characterId in setOf(1L, 2L) }
            .shuffled(Random(3))
            .sortedWith(WorldPackageScope.STATE_CHANGES)
        assertEquals(listOf(3L, 1L, 2L), ordered.map { it.id })
    }

    @Test
    fun `정렬 계약은 전순서다 — 같은 세계관을 두 번 내보내면 같은 순서가 나온다`() {
        // 종전 전량 질의 다섯 절에는 ORDER BY가 아예 없었고, 있던 것도 동순위 순서가
        // 정해져 있지 않았다. 계약의 마지막 키가 기본키라 그 여지가 없어야 한다.
        val a = fieldValues.shuffled(Random(1)).sortedWith(WorldPackageScope.CHARACTER_FIELD_VALUES)
        val b = fieldValues.shuffled(Random(2)).sortedWith(WorldPackageScope.CHARACTER_FIELD_VALUES)
        assertEquals(a.map { it.id }, b.map { it.id })

        val c = crossRefs.shuffled(Random(1)).sortedWith(WorldPackageScope.CROSS_REFS)
        val d = crossRefs.shuffled(Random(2)).sortedWith(WorldPackageScope.CROSS_REFS)
        assertEquals(c.map { "${it.eventId}:${it.characterId}" }, d.map { "${it.eventId}:${it.characterId}" })
    }

    // ───────────────────────── 조립 도우미 ─────────────────────────

    /**
     * **정렬 계약의 키를 하나씩 전부 민다.**
     *
     * 이 시험은 콜드 검토가 만들게 했다 — 처음에는 절마다 표본을 손으로 골라 순서를 단언했는데,
     * **표본이 뒷키에 닿지 않아 헛돌았다.** 세력 둘의 `displayOrder`가 서로 달라 `createdAt`
     * 타이브레이크까지 가지도 않았고, 그래서 `createdAt`의 **방향을 뒤집어도 시험이 초록이었다**
     * (같은 병이 캐릭터의 `displayOrder`·관계의 `createdAt`에도 있었다).
     *
     * 그래서 표본을 고르는 대신 **키마다 그 키 하나만 다른 두 행**을 만들어 민다.
     * 키를 빠뜨리면 그 줄이 없어서 보이고, 방향을 뒤집으면 그 줄이 빨개진다.
     */
    @Test
    fun `정렬 계약의 키를 하나씩 민다 — 빠진 키도 뒤집힌 키도 여기서 걸린다`() {
        val S = WorldPackageScope

        // 캐릭터: isPinned DESC · displayOrder ASC · name ASC · id ASC
        before("캐릭터/고정", S.CHARACTERS,
            ch(9, "같음", 10, pinned = true, order = 1), ch(9, "같음", 10, pinned = false, order = 1))
        before("캐릭터/표시순서", S.CHARACTERS,
            ch(9, "같음", 10, pinned = false, order = 1), ch(9, "같음", 10, pinned = false, order = 2))
        before("캐릭터/이름", S.CHARACTERS,
            ch(9, "가", 10, pinned = false, order = 1), ch(9, "나", 10, pinned = false, order = 1))
        before("캐릭터/id", S.CHARACTERS,
            ch(8, "같음", 10, pinned = false, order = 1), ch(9, "같음", 10, pinned = false, order = 1))

        // 상태변화: characterId ASC · year ASC · month/day ASC(NULL 먼저) · id ASC
        before("상태변화/캐릭터", S.STATE_CHANGES, sc(9, 1, 1000, 1, 1), sc(9, 2, 1000, 1, 1))
        before("상태변화/해", S.STATE_CHANGES, sc(9, 1, 999, 1, 1), sc(9, 1, 1000, 1, 1))
        before("상태변화/달 NULL", S.STATE_CHANGES, sc(9, 1, 1000, null, 1), sc(9, 1, 1000, 1, 1))
        before("상태변화/일 NULL", S.STATE_CHANGES, sc(9, 1, 1000, 1, null), sc(9, 1, 1000, 1, 1))
        before("상태변화/id", S.STATE_CHANGES, sc(8, 1, 1000, 1, 1), sc(9, 1, 1000, 1, 1))

        // 태그: tag ASC · id ASC
        before("태그/값", S.TAGS, CharacterTag(9, 1, "가"), CharacterTag(9, 1, "나"))
        before("태그/id", S.TAGS, CharacterTag(8, 1, "같음"), CharacterTag(9, 1, "같음"))

        // 관계: createdAt **DESC** · id ASC
        before("관계/만든시각 역순", S.RELATIONSHIPS, rel(9, 1, 2, 200), rel(9, 1, 2, 100))
        before("관계/id", S.RELATIONSHIPS, rel(8, 1, 2, 100), rel(9, 1, 2, 100))

        // 관계 변화: year ASC · month/day ASC(NULL 먼저) · id ASC
        before("관계변화/해", S.RELATIONSHIP_CHANGES, rc(9, 1, 999, 1), rc(9, 1, 1000, 1))
        before("관계변화/달 NULL", S.RELATIONSHIP_CHANGES, rc(9, 1, 1000, null), rc(9, 1, 1000, 1))
        before("관계변화/id", S.RELATIONSHIP_CHANGES, rc(8, 1, 1000, 1), rc(9, 1, 1000, 1))

        // 사건: year ASC · month/day ASC(NULL 먼저) · displayOrder ASC · id ASC
        before("사건/해", S.EVENTS, ev(9, 999, 1, null), ev(9, 1000, 1, null))
        before("사건/달 NULL", S.EVENTS, ev(9, 1000, null, null), ev(9, 1000, 1, null))
        before("사건/표시순서", S.EVENTS, evOrder(9, 1000, 1, 0), evOrder(9, 1000, 1, 1))
        before("사건/id", S.EVENTS, ev(8, 1000, 1, null), ev(9, 1000, 1, null))

        // 크로스레프: 복합 기본키
        before("사건-캐릭터/사건", S.CROSS_REFS,
            TimelineCharacterCrossRef(1, 5), TimelineCharacterCrossRef(2, 5))
        before("사건-캐릭터/캐릭터", S.CROSS_REFS,
            TimelineCharacterCrossRef(1, 4), TimelineCharacterCrossRef(1, 5))
        before("사건-작품/사건", S.EVENT_NOVEL_CROSS_REFS,
            TimelineEventNovelCrossRef(1, 5), TimelineEventNovelCrossRef(2, 5))
        before("사건-작품/작품", S.EVENT_NOVEL_CROSS_REFS,
            TimelineEventNovelCrossRef(1, 4), TimelineEventNovelCrossRef(1, 5))

        // 이름 은행: createdAt **DESC** · id ASC
        before("이름은행/만든시각 역순", S.NAME_BANK, nb(9, "ㄱ", 1, 200), nb(9, "ㄱ", 1, 100))
        before("이름은행/id", S.NAME_BANK, nb(8, "ㄱ", 1, 100), nb(9, "ㄱ", 1, 100))

        // 세력: displayOrder ASC · createdAt **DESC** · id ASC
        before("세력/표시순서", S.FACTIONS, fac(9, 1, 1, 100), fac(9, 1, 2, 100))
        before("세력/만든시각 역순", S.FACTIONS, fac(9, 1, 1, 200), fac(9, 1, 1, 100))
        before("세력/id", S.FACTIONS, fac(8, 1, 1, 100), fac(9, 1, 1, 100))

        // 기본키만 드는 넷
        before("필드값/id", S.CHARACTER_FIELD_VALUES,
            CharacterFieldValue(id = 8, characterId = 1, fieldDefinitionId = 1),
            CharacterFieldValue(id = 9, characterId = 1, fieldDefinitionId = 1))
        before("사건필드값/id", S.EVENT_FIELD_VALUES,
            EventFieldValue(id = 8, eventId = 1, fieldDefinitionId = 1, value = "v"),
            EventFieldValue(id = 9, eventId = 1, fieldDefinitionId = 1, value = "v"))
        before("작품필드값/id", S.NOVEL_FIELD_VALUES,
            NovelFieldValue(id = 8, novelId = 1, fieldDefinitionId = 1),
            NovelFieldValue(id = 9, novelId = 1, fieldDefinitionId = 1))
        before("세력소속/id", S.FACTION_MEMBERSHIPS,
            FactionMembership(id = 8, factionId = 1, characterId = 1),
            FactionMembership(id = 9, factionId = 1, characterId = 1))
        before("값라이브러리/id", S.FIELD_VALUE_ENTRIES,
            FieldValueEntry(id = 8, fieldDefinitionId = 1, value = "v"),
            FieldValueEntry(id = 9, fieldDefinitionId = 1, value = "v"))

        // 필드 정의: entityType ASC · displayOrder ASC · id ASC
        // (질의는 앞의 둘만 든다 — 셋째가 없어 겹칠 때 자리가 정해지지 않았다)
        before("필드정의/종류", S.FIELD_DEFINITIONS, fd(1, "character", 1), fd(1, "event", 1))
        before("필드정의/표시순서", S.FIELD_DEFINITIONS, fd(1, "character", 1), fd(1, "character", 2))
        before("필드정의/id", S.FIELD_DEFINITIONS, fd(8, "character", 1), fd(9, "character", 1))
    }

    private fun fd(id: Long, entityType: String, order: Int) = FieldDefinition(
        id = id, universeId = 1, key = "k$id", name = "n$id", type = "TEXT",
        displayOrder = order, entityType = entityType
    )

    /** [first]가 [second]보다 앞서야 하고, **뒤집으면 답도 뒤집혀야** 한다(비교자가 그 키를 실제로 본다). */
    private fun <T> before(what: String, cmp: Comparator<T>, first: T, second: T) {
        assertTrue("$what — 앞서야 할 것이 앞서지 않는다", cmp.compare(first, second) < 0)
        assertTrue("$what — 비교자가 이 키를 보지 않는다", cmp.compare(second, first) > 0)
    }

    private fun evOrder(id: Long, year: Int, month: Int?, order: Int) =
        TimelineEvent(id = id, year = year, month = month, description = "d",
            universeId = null, displayOrder = order)

    private fun fac(id: Long, universeId: Long, order: Int, createdAt: Long) =
        Faction(id = id, universeId = universeId, name = "f$id", autoRelationType = "t",
            displayOrder = order, createdAt = createdAt)

    private fun ch(id: Long, name: String, novelId: Long?, pinned: Boolean, order: Long) =
        Character(id = id, name = name, novelId = novelId, isPinned = pinned, displayOrder = order)

    private fun sc(id: Long, characterId: Long, year: Int, month: Int?, day: Int?) =
        CharacterStateChange(id = id, characterId = characterId, year = year, month = month,
            day = day, fieldKey = "k", newValue = "v")

    private fun rel(id: Long, c1: Long, c2: Long, createdAt: Long) =
        CharacterRelationship(id = id, characterId1 = c1, characterId2 = c2,
            relationshipType = "t", createdAt = createdAt)

    private fun rc(id: Long, relationshipId: Long, year: Int, month: Int?) =
        CharacterRelationshipChange(id = id, relationshipId = relationshipId, year = year,
            month = month, relationshipType = "t")

    private fun ev(id: Long, year: Int, month: Int?, universeId: Long?) =
        TimelineEvent(id = id, year = year, month = month, description = "d", universeId = universeId)

    private fun nb(id: Long, name: String, usedBy: Long?, createdAt: Long) =
        NameBankEntry(id = id, name = name, usedByCharacterId = usedBy, createdAt = createdAt)
}
