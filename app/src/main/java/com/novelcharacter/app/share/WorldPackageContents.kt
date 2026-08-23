package com.novelcharacter.app.share

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterRelationshipChange
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterQuote
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TimelineEventNovelCrossRef
import com.novelcharacter.app.data.model.Universe

/**
 * schemaVersion 이력:
 * - 1: 최초 형식
 * - 2: faction_relationships.json 추가 (B-6 — 세력 간 관계, code 참조).
 *   임포터는 v1 패키지에서 "세력 간 관계 없음"을 구버전 형식으로 안내한다.
 * - 3 (S-5): 내보내기 완결 —
 *   · field_definitions.json이 **사건 필드 정의를 포함**한다(전 entityType — 종전에는
 *     캐릭터 필드만 실려 사건 필드·값이 통째로 유실됐다)
 *   · event_field_values.json (사건 필드값) 추가
 *   · field_value_entries.json (값 라이브러리 — 별칭·라벨·카테고리 큐레이션 포함) 추가
 *   · 세계관·작품의 직접 등록 이미지를 `images/universe_*`·`images/novel_{id}_*` 엔트리로 포함
 *   · name_bank.json을 **내보내는 캐릭터가 사용 중인 이름으로 한정** — 종전에는 전체 이름
 *     은행이 통째로 실려, 패키지를 공유하면 무관한 전역 데이터까지 수신자에게 넘어갔다
 *   임포터([WorldPackageImporter])는 v1~v3 전부 읽으며, 구버전에 없는 엔트리는
 *   "없음"으로 처리하고 무엇이 빠진 형식인지 결과에 고지한다.
 * - 4 (확-3): novel_field_values.json 추가 (작품 커스텀 필드값).
 * - 5 (U-1): grade_systems.json 추가 (세계관 등급 체계 — 라벨 집합 + 기본 숫자).
 *   필드 config의 참조는 체계 code라 별도 재배선 표가 필요 없다. 다만 code가 이 기기에서
 *   충돌해 재발급되면 임포터가 config의 참조를 새 code로 다시 잇는다(R-1).
 * - 6 (B-118): duel_axes.json · duel_matches.json · duel_counter_verdicts.json 추가
 *   (세계관의 **대결** — 축·판·상성). 종전에는 세계관을 통째로 주고받아도 대결만 통째로
 *   사라진 채 도착했다. 판·상성은 참가자를 코드로 가리키므로 재발급 표를 따라가야 하며,
 *   그 매핑은 [WorldPackageDuels]가 단일 소스다. **v1~v5 패키지는 그대로 읽히고**
 *   임포터가 "대결이 없는 형식"임을 고지한다(그 고지가 B-118 ⓐ의 재사용이다).
 *
 * **v6 유지 (B-234): `image_failures.json`은 판올림이 아니다.** 잘린 이미지 엔트리의 이름을
 * 덧붙이는 엔트리라, 없으면 "잘린 장을 모른다"(= 오늘까지의 동작)이고 있으면 그 장을 결번으로
 * 다룬다 — **어느 쪽도 다른 엔트리의 뜻을 바꾸지 않는다.** 판올림했다면 이 앱보다 낮은 버전이
 * 패키지를 통째로 거부했을 것이다. 판올림의 기준은 *새 엔트리가 생겼는가*가 아니라
 * **옛 규칙으로 읽으면 틀리는가**다(R-64).
 */
data class WorldPackageManifest(
    val schemaVersion: Int = WorldPackageEntries.CURRENT_SCHEMA_VERSION,
    val appVersion: String = "1.0",
    val createdAt: Long = System.currentTimeMillis(),
    val universeName: String,
    val includesImages: Boolean
)

/**
 * 월드패키지(.ncworld) 엔트리 이름의 단일 소스 — 내보내기([WorldPackageExporter])와
 * 가져오기([WorldPackageParser]/[WorldPackageImporter])가 같은 상수를 봐야
 * 한쪽만 바뀌어 왕복이 조용히 깨지는 일이 없다.
 */
object WorldPackageEntries {
    const val CURRENT_SCHEMA_VERSION = 6

    const val MANIFEST = "manifest.json"
    const val UNIVERSE = "universe.json"
    const val FIELD_DEFINITIONS = "field_definitions.json"
    const val NOVELS = "novels.json"
    const val CHARACTERS = "characters.json"
    const val FIELD_VALUES = "field_values.json"
    const val STATE_CHANGES = "state_changes.json"
    const val TAGS = "tags.json"
    /** 명대사 (사용자 요청 2026.08.20). */
    const val QUOTES = "quotes.json"
    const val RELATIONSHIPS = "relationships.json"
    const val RELATIONSHIP_CHANGES = "relationship_changes.json"
    const val TIMELINE_EVENTS = "timeline_events.json"
    const val TIMELINE_CROSS_REFS = "timeline_cross_refs.json"
    const val TIMELINE_EVENT_NOVEL_CROSS_REFS = "timeline_event_novel_cross_refs.json"
    const val NAME_BANK = "name_bank.json"
    const val FACTIONS = "factions.json"
    const val FACTION_MEMBERSHIPS = "faction_memberships.json"
    const val FACTION_RELATIONSHIPS = "faction_relationships.json"
    const val EVENT_FIELD_VALUES = "event_field_values.json"
    const val FIELD_VALUE_ENTRIES = "field_value_entries.json"
    const val NOVEL_FIELD_VALUES = "novel_field_values.json"
    const val GRADE_SYSTEMS = "grade_systems.json"
    const val DUEL_AXES = "duel_axes.json"
    const val DUEL_MATCHES = "duel_matches.json"
    const val DUEL_VERDICTS = "duel_counter_verdicts.json"

    /**
     * 잘린 이미지 엔트리의 이름 목록(B-234) — 내보내는 중 `copyTo`가 중간에 실패해
     * **내용만 잘린 채 정상 종료된** 엔트리다. 이미지 구간 뒤에 실린다(그전에는 알 수 없다).
     *
     * **schemaVersion을 올리지 않는다.** 덧붙임이라 옛 앱은 이 엔트리를 그냥 안 읽고,
     * 판올림하면 그 앱들이 [WorldPackageParseResult.UnsupportedVersion]으로 **패키지 전체를
     * 거부한다** — 잃는 것이 "잘린 장 하나의 고지"인데 대가가 "전부 못 읽음"이다.
     * R-58이 *"옛 앱이 새 파일을 읽지 못하는 것은 판올림의 불가피한 대가"*라 적은 그 대가는
     * 뜻이 **바뀌는** 형식의 것이고, 이 엔트리는 뜻을 바꾸지 않는다(R-64).
     */
    const val IMAGE_FAILURES = "image_failures.json"

    /** 이미지 엔트리 접두사 — `images/{캐릭터id}_{i}.jpg` · `images/universe_{i}.jpg` · `images/novel_{작품id}_{i}.jpg` */
    const val IMAGES_PREFIX = "images/"
    const val IMAGE_UNIVERSE_PREFIX = "images/universe_"
    const val IMAGE_NOVEL_PREFIX = "images/novel_"
}

/**
 * 파싱이 끝난 월드패키지 내용물. 모든 id는 **원 기기의 DB id**이므로 그대로 삽입해서는
 * 안 되고, [WorldPackageImporter]가 삽입 순서대로 old→new id 매핑을 만들어 재배선한다.
 *
 * @property droppedRows 엔트리별 형식 이탈(필수 문자열이 null인) 행 수 — 손편집·손상 파일에서만
 *   발생한다. 0이 아니면 호출부가 반드시 고지할 것(무통보 유실 금지).
 */
data class WorldPackageContents(
    val manifest: WorldPackageManifest,
    val universe: Universe,
    val fieldDefinitions: List<FieldDefinition>,
    val novels: List<Novel>,
    val characters: List<Character>,
    val fieldValues: List<CharacterFieldValue>,
    val stateChanges: List<CharacterStateChange>,
    val tags: List<CharacterTag>,
    val quotes: List<CharacterQuote>,
    val relationships: List<CharacterRelationship>,
    val relationshipChanges: List<CharacterRelationshipChange>,
    val events: List<TimelineEvent>,
    val crossRefs: List<TimelineCharacterCrossRef>,
    val eventNovelCrossRefs: List<TimelineEventNovelCrossRef>,
    val nameBank: List<NameBankEntry>,
    val factions: List<Faction>,
    val factionMemberships: List<FactionMembership>,
    val factionRelationships: List<PortableFactionRelationship>,
    val eventFieldValues: List<EventFieldValue>,
    val fieldValueEntries: List<FieldValueEntry>,
    val novelFieldValues: List<NovelFieldValue>,
    val gradeSystems: List<GradeSystem>,
    val duelAxes: List<DuelAxis>,
    val duelMatches: List<DuelMatch>,
    val duelVerdicts: List<DuelCounterVerdict>,
    val droppedRows: Map<String, Int>,
    /**
     * 내용이 잘린 이미지 엔트리의 이름(B-234) — 내보내는 기기가 실패를 아는 자리에서 적었다.
     * 임포터는 이 이름들을 **결번처럼** 다룬다([WorldPackageImages.isRestorable]).
     * 없는 엔트리(v6 이전 · 이 판 이전의 v6)는 빈 집합이고, 그때 동작은 종전과 같다.
     */
    val truncatedImages: Set<String> = emptySet()
)

/** 파싱 실패의 원인별 분류 — UI는 이것으로 일반 오류 대신 원인별 메시지를 만든다(S-5). */
sealed class WorldPackageParseResult {
    data class Success(val contents: WorldPackageContents) : WorldPackageParseResult()

    /** manifest.json이 없다 — 월드패키지가 아니다(ZIP 판별을 통과했다면 도달하지 않아야 정상). */
    object NotAPackage : WorldPackageParseResult()

    /** 이 앱이 아는 것보다 새 형식 — 데이터를 절반만 읽는 것보다 거부가 낫다. */
    data class UnsupportedVersion(val found: Int, val supportedMax: Int) : WorldPackageParseResult()

    /** 필수 엔트리(universe.json)가 없다. */
    data class MissingEntry(val entryName: String) : WorldPackageParseResult()

    /** 엔트리 JSON이 깨졌거나 필수 필드가 없다. */
    data class Malformed(val entryName: String) : WorldPackageParseResult()
}

/**
 * 월드패키지 JSON 엔트리들의 파서 — 순수 로직(ZIP·DB 비의존), 순수 JVM 하네스가 실행 검증한다.
 *
 * R-2의 교훈이 패키지 JSON에도 그대로 적용된다: Gson은 Kotlin 기본값을 실행하지 않으므로
 * **JSON에 키가 없으면 non-null 선언 필드에도 null이 주입된다.** 실제 앱이 만든 패키지는
 * 모든 non-null 필드가 값과 함께 직렬화되지만, 손편집·구버전 파일은 그렇지 않다.
 * 필수 문자열이 null인 행은 조용히 태우지 않고 **행 단위로 걸러 개수를 센다**(droppedRows) —
 * 세계관 본체가 그런 상태면 행을 버릴 수 없으므로 [WorldPackageParseResult.Malformed]다.
 */
object WorldPackageParser {

    fun parse(entries: Map<String, String>): WorldPackageParseResult {
        val gson = Gson()

        val manifestJson = entries[WorldPackageEntries.MANIFEST]
            ?: return WorldPackageParseResult.NotAPackage
        val manifest = try {
            gson.fromJson(manifestJson, WorldPackageManifest::class.java)
        } catch (_: Exception) { null }
            ?: return WorldPackageParseResult.Malformed(WorldPackageEntries.MANIFEST)
        if (manifest.schemaVersion < 1) {
            return WorldPackageParseResult.Malformed(WorldPackageEntries.MANIFEST)
        }
        if (manifest.schemaVersion > WorldPackageEntries.CURRENT_SCHEMA_VERSION) {
            return WorldPackageParseResult.UnsupportedVersion(
                manifest.schemaVersion, WorldPackageEntries.CURRENT_SCHEMA_VERSION
            )
        }

        val universeJson = entries[WorldPackageEntries.UNIVERSE]
            ?: return WorldPackageParseResult.MissingEntry(WorldPackageEntries.UNIVERSE)
        val universe = try {
            gson.fromJson(universeJson, Universe::class.java)
        } catch (_: Exception) { null }
            ?: return WorldPackageParseResult.Malformed(WorldPackageEntries.UNIVERSE)
        if (!universeValid(universe)) {
            return WorldPackageParseResult.Malformed(WorldPackageEntries.UNIVERSE)
        }

        val dropped = LinkedHashMap<String, Int>()

        fun <T> scrub(name: String, list: List<T?>, valid: (T) -> Boolean): List<T> {
            val kept = list.filterNotNull().filter(valid)
            val droppedCount = list.size - kept.size
            if (droppedCount > 0) dropped[name] = droppedCount
            return kept
        }

        // 엔트리 부재(구버전 형식)는 빈 목록, JSON 파손은 Malformed — 이 둘은 다르다.
        fun <T> read(name: String, typeToken: TypeToken<List<T?>>): List<T?>? {
            val json = entries[name] ?: return emptyList()
            return try {
                gson.fromJson<List<T?>>(json, typeToken.type) ?: emptyList()
            } catch (_: Exception) { null }
        }

        fun malformed(name: String) = WorldPackageParseResult.Malformed(name)

        val e = WorldPackageEntries
        val fieldDefs = read(e.FIELD_DEFINITIONS, object : TypeToken<List<FieldDefinition?>>() {})
            ?: return malformed(e.FIELD_DEFINITIONS)
        val novels = read(e.NOVELS, object : TypeToken<List<Novel?>>() {})
            ?: return malformed(e.NOVELS)
        val characters = read(e.CHARACTERS, object : TypeToken<List<Character?>>() {})
            ?: return malformed(e.CHARACTERS)
        val fieldValues = read(e.FIELD_VALUES, object : TypeToken<List<CharacterFieldValue?>>() {})
            ?: return malformed(e.FIELD_VALUES)
        val stateChanges = read(e.STATE_CHANGES, object : TypeToken<List<CharacterStateChange?>>() {})
            ?: return malformed(e.STATE_CHANGES)
        val tags = read(e.TAGS, object : TypeToken<List<CharacterTag?>>() {})
            ?: return malformed(e.TAGS)
        // **옛 꾸러미(엔트리 없음)는 [read]가 이미 빈 목록으로 받는다** — 그러므로 여기 오는
        // `null`은 오직 *JSON이 깨졌을 때*뿐이고, 그것은 형제 스무 곳과 같이 사유와 함께
        // 거부해야 한다. 종전 `?: emptyList()`는 그 둘을 한 값으로 접어, 손상·손편집으로
        // `quotes.json`이 깨진 꾸러미가 **«명대사가 없는 꾸러미»로 통과하고 명대사 전량이
        // 아무 고지 없이 빠졌다.** `droppedRows` 고지에도 안 잡힌다 — `scrub`을 지나기 전에
        // 이미 빈 목록이라 버린 행이 0이기 때문이다. 바로 위 [read]의 주석이 세운 규칙
        // (*"엔트리 부재는 빈 목록, JSON 파손은 Malformed"*)이 이 자리에만 안 걸려 있었다.
        val quotes = read(e.QUOTES, object : TypeToken<List<CharacterQuote?>>() {})
            ?: return malformed(e.QUOTES)
        val relationships = read(e.RELATIONSHIPS, object : TypeToken<List<CharacterRelationship?>>() {})
            ?: return malformed(e.RELATIONSHIPS)
        val relationshipChanges =
            read(e.RELATIONSHIP_CHANGES, object : TypeToken<List<CharacterRelationshipChange?>>() {})
                ?: return malformed(e.RELATIONSHIP_CHANGES)
        val events = read(e.TIMELINE_EVENTS, object : TypeToken<List<TimelineEvent?>>() {})
            ?: return malformed(e.TIMELINE_EVENTS)
        val crossRefs = read(e.TIMELINE_CROSS_REFS, object : TypeToken<List<TimelineCharacterCrossRef?>>() {})
            ?: return malformed(e.TIMELINE_CROSS_REFS)
        val eventNovelCrossRefs = read(
            e.TIMELINE_EVENT_NOVEL_CROSS_REFS, object : TypeToken<List<TimelineEventNovelCrossRef?>>() {}
        ) ?: return malformed(e.TIMELINE_EVENT_NOVEL_CROSS_REFS)
        val nameBank = read(e.NAME_BANK, object : TypeToken<List<NameBankEntry?>>() {})
            ?: return malformed(e.NAME_BANK)
        val factions = read(e.FACTIONS, object : TypeToken<List<Faction?>>() {})
            ?: return malformed(e.FACTIONS)
        val factionMemberships = read(e.FACTION_MEMBERSHIPS, object : TypeToken<List<FactionMembership?>>() {})
            ?: return malformed(e.FACTION_MEMBERSHIPS)
        val factionRelationships = read(
            e.FACTION_RELATIONSHIPS, object : TypeToken<List<PortableFactionRelationship?>>() {}
        ) ?: return malformed(e.FACTION_RELATIONSHIPS)
        val eventFieldValues = read(e.EVENT_FIELD_VALUES, object : TypeToken<List<EventFieldValue?>>() {})
            ?: return malformed(e.EVENT_FIELD_VALUES)
        val fieldValueEntries = read(e.FIELD_VALUE_ENTRIES, object : TypeToken<List<FieldValueEntry?>>() {})
            ?: return malformed(e.FIELD_VALUE_ENTRIES)
        val novelFieldValues = read(e.NOVEL_FIELD_VALUES, object : TypeToken<List<NovelFieldValue?>>() {})
            ?: return malformed(e.NOVEL_FIELD_VALUES)
        val gradeSystems = read(e.GRADE_SYSTEMS, object : TypeToken<List<GradeSystem?>>() {})
            ?: return malformed(e.GRADE_SYSTEMS)
        val duelAxes = read(e.DUEL_AXES, object : TypeToken<List<DuelAxis?>>() {})
            ?: return malformed(e.DUEL_AXES)
        val duelMatches = read(e.DUEL_MATCHES, object : TypeToken<List<DuelMatch?>>() {})
            ?: return malformed(e.DUEL_MATCHES)
        val duelVerdicts = read(e.DUEL_VERDICTS, object : TypeToken<List<DuelCounterVerdict?>>() {})
            ?: return malformed(e.DUEL_VERDICTS)
        // B-234 — **깨졌으면 Malformed다.** 이 엔트리를 못 읽으면 *어느 장이 잘렸는지*를 모르는
        // 것이고, 그 상태로 진행하면 잘린 그림이 다시 조용히 복원된다(이 행이 고친 그 모양).
        // 다른 엔트리와 같은 규칙이기도 하다 — 부재는 빈 목록, 파손은 사유와 함께 거부.
        val truncatedImages = read(e.IMAGE_FAILURES, object : TypeToken<List<String?>>() {})
            ?: return malformed(e.IMAGE_FAILURES)

        return WorldPackageParseResult.Success(
            WorldPackageContents(
                manifest = manifest,
                universe = universe,
                fieldDefinitions = scrub(e.FIELD_DEFINITIONS, fieldDefs) {
                    allPresent(it.key, it.name, it.type, it.config, it.groupName, it.entityType)
                },
                novels = scrub(e.NOVELS, novels) {
                    allPresent(it.title, it.description, it.borderColor, it.imagePaths, it.imageMode)
                },
                characters = scrub(e.CHARACTERS, characters) {
                    // representativeImagePath(v47 — B-103)는 **여기서 다루지 않는다.** 이유가 둘이다:
                    //  ① `allPresent`는 행을 **버리는** 검사인데, 이 칸의 null은 파손이 아니라
                    //     "v47 이전에 내보낸 패키지"라는 뜻이다 — 넣으면 옛 패키지의 캐릭터가 전부 사라진다.
                    //  ② 여기서 `copy()`로 기본값을 접을 수도 없다. Kotlin의 `copy`는 **넘기지 않아
                    //     기본값으로 채워지는 인자에도** null 검사를 걸어서, `code`가 null인 행
                    //     (이 파서가 일부러 살려 두고 임포터가 재발급하는 정상 사례)에서 죽는다.
                    //     실제로 `WorldPackageParserTest`가 그 계약을 지키고 있고, 이 자리에서
                    //     copy를 부르자 그 테스트가 곧바로 깨졌다.
                    // → 접는 것은 **쓰는 쪽**의 몫이다(`WorldPackageImporter`가 삽입 직전에 한다).
                    allPresent(it.name, it.firstName, it.lastName, it.anotherName, it.imagePaths, it.memo)
                },
                fieldValues = scrub(e.FIELD_VALUES, fieldValues) { allPresent(it.value) },
                stateChanges = scrub(e.STATE_CHANGES, stateChanges) {
                    allPresent(it.fieldKey, it.newValue, it.description)
                },
                tags = scrub(e.TAGS, tags) { allPresent(it.tag) },
                quotes = scrub(e.QUOTES, quotes) { allPresent(it.text, it.occasionKey, it.note) },
                relationships = scrub(e.RELATIONSHIPS, relationships) {
                    allPresent(it.relationshipType, it.description)
                },
                relationshipChanges = scrub(e.RELATIONSHIP_CHANGES, relationshipChanges) {
                    allPresent(it.relationshipType, it.description)
                },
                events = scrub(e.TIMELINE_EVENTS, events) {
                    allPresent(it.calendarType, it.description, it.eventType)
                },
                crossRefs = crossRefs.filterNotNull(),
                eventNovelCrossRefs = eventNovelCrossRefs.filterNotNull(),
                nameBank = scrub(e.NAME_BANK, nameBank) {
                    allPresent(it.name, it.gender, it.origin, it.notes)
                },
                factions = scrub(e.FACTIONS, factions) {
                    allPresent(it.name, it.description, it.color, it.autoRelationType)
                },
                factionMemberships = factionMemberships.filterNotNull(),
                factionRelationships = scrub(e.FACTION_RELATIONSHIPS, factionRelationships) {
                    allPresent(
                        it.factionCode1, it.factionName1, it.factionCode2, it.factionName2,
                        it.relationType, it.description
                    )
                },
                eventFieldValues = scrub(e.EVENT_FIELD_VALUES, eventFieldValues) { allPresent(it.value) },
                fieldValueEntries = scrub(e.FIELD_VALUE_ENTRIES, fieldValueEntries) {
                    allPresent(it.value, it.displayLabel, it.aliasesJson, it.category, it.description, it.source)
                },
                novelFieldValues = scrub(e.NOVEL_FIELD_VALUES, novelFieldValues) { allPresent(it.value) },
                gradeSystems = scrub(e.GRADE_SYSTEMS, gradeSystems) {
                    allPresent(it.name, it.gradesJson, it.code)
                },
                // v6(B-118) — **`candidateFiltersJson`은 검사하지 않는다.** 그 칸은 선언이
                // nullable이고 null이 곧 *필터 없음*이라, 넣으면 필터 없는 축이 전부 버려진다.
                // 나중에 칸이 더 늘어도 같은 규칙이다: **v6 이후에 생긴 칸은 여기 넣지 않는다**
                // (넣으면 이 목록이 만들어지기 전 패키지의 축이 통째로 사라진다 — 위 캐릭터
                // 주석의 `representativeImagePath` 교훈이 그 자리다).
                duelAxes = scrub(e.DUEL_AXES, duelAxes) {
                    allPresent(
                        it.name, it.targetType, it.influenceFieldKeys, it.outcomeFieldKeys,
                        it.profileFieldKeys, it.code
                    )
                },
                // 승자(`winnerCode`)와 묶음(`groupId`)은 nullable이 뜻을 갖는다 —
                // 승자 null은 **무승부**이고, 검사에 넣으면 비긴 판이 통째로 사라진다.
                duelMatches = scrub(e.DUEL_MATCHES, duelMatches) { allPresent(it.aCode, it.bCode, it.code) },
                duelVerdicts = scrub(e.DUEL_VERDICTS, duelVerdicts) {
                    allPresent(it.kind, it.shape, it.memberCodes, it.memberKey, it.code)
                },
                droppedRows = dropped,
                // 이름 목록이라 `scrub`의 대상이 아니다 — 버릴 '행'이 없고, 공란은
                // 어떤 엔트리도 가리키지 않으므로 단일 소스가 걸러 낸다.
                truncatedImages = WorldPackageImages.truncatedSet(truncatedImages)
            )
        )
    }

    private fun universeValid(universe: Universe): Boolean = allPresent(
        universe.name, universe.description, universe.borderColor, universe.imagePaths,
        universe.imageMode, universe.customRelationshipTypes, universe.customRelationshipColors
    )

    /**
     * non-null 선언 필드의 실제 null 검사(R-2 — Gson Unsafe 주입).
     * 선언 타입이 non-null이라 컴파일러는 무의미한 비교로 보지만, 런타임에는 실재한다.
     */
    private fun allPresent(vararg values: Any?): Boolean = values.all { it != null }
}
