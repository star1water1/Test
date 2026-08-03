package com.novelcharacter.app.share

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    const val CURRENT_SCHEMA_VERSION = 5

    const val MANIFEST = "manifest.json"
    const val UNIVERSE = "universe.json"
    const val FIELD_DEFINITIONS = "field_definitions.json"
    const val NOVELS = "novels.json"
    const val CHARACTERS = "characters.json"
    const val FIELD_VALUES = "field_values.json"
    const val STATE_CHANGES = "state_changes.json"
    const val TAGS = "tags.json"
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
    val droppedRows: Map<String, Int>
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
                droppedRows = dropped
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
