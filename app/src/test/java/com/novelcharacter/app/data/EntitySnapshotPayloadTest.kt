package com.novelcharacter.app.data

import com.google.gson.Gson
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.EntityRefs
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.EventSnapshot
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.data.model.FactionSnapshot
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.GradeSystemSnapshot
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelSnapshot
import com.novelcharacter.app.data.model.SnapshotRefs
import com.novelcharacter.app.data.model.StateChangeSnapshot
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.TrashSnapshot
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.data.model.UniverseDataSnapshot
import com.novelcharacter.app.data.model.UniverseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐릭터 외 휴지통 payload의 Gson 왕복·하위호환 계약 (B-1, 규약 R-2).
 *
 * Gson은 Kotlin 생성자 기본값을 실행하지 않고 Unsafe로 객체를 할당한다. 따라서 **payload에
 * 없는 필드는 선언이 non-null이어도 런타임에 null이 주입된다.** 이 사실을 잊고 새 필드를
 * non-null로 선언한 뒤 곧바로 순회하면, 사용자가 이미 휴지통에 갖고 있던 항목 전부가
 * 복원 시 NPE로 실패한다 — [CharacterSnapshotPayloadTest]가 캐릭터에 대해 고정한 계약을
 * 새 4개 타입에도 똑같이 건다.
 */
class EntitySnapshotPayloadTest {

    private val gson = Gson()

    // ──────────────────────────────────────────────────────────────────────
    // 최소 payload — 목록 키가 통째로 없을 때 null이 주입되는가
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `세계관 부가 데이터는 이어붙임 행으로 분리돼 왕복한다`() {
        // 값 라이브러리·고아 필드값은 규모에 따라 무한히 자란다 — 세계관 본체 payload에
        // 몰아넣으면 한 행이 CursorWindow 한도(2MB)를 넘어 백업을 읽을 수 없게 된다.
        val original = UniverseDataSnapshot(
            universeCode = "UNI-1",
            orphanCharacterFieldValues = listOf(
                CharacterFieldValue(id = 1, characterId = 88, fieldDefinitionId = 12, value = "높음")
            ),
            orphanEventFieldValues = listOf(
                EventFieldValue(id = 2, eventId = 41, fieldDefinitionId = 13, value = "3화")
            ),
            orphanNovelFieldValues = listOf(
                com.novelcharacter.app.data.model.NovelFieldValue(
                    id = 3, novelId = 7, fieldDefinitionId = 14, value = "장편"
                )
            ),
            refs = EntityRefs(
                universeCode = "UNI-1",
                characters = mapOf("88" to "CHR-9"),
                events = mapOf("41" to "EVT-1"),
                novels = mapOf("7" to "NVL-1"),
                fieldDefs = mapOf(
                    "12" to FieldDefRef("UNI-1", "character", "mana"),
                    "13" to FieldDefRef("UNI-1", "event", "chapter"),
                    "14" to FieldDefRef("UNI-1", "novel", "form")
                )
            )
        )
        val restored = gson.fromJson(gson.toJson(original), UniverseDataSnapshot::class.java)
        assertEquals("UNI-1", restored.universeCode)
        assertEquals(88L, restored.orphanCharacterFieldValues!![0].characterId)
        assertEquals(41L, restored.orphanEventFieldValues!![0].eventId)
        // 작품 축(확-3)도 같은 계약이다 — 값과 그것을 되찾을 코드가 함께 실린다
        assertEquals(7L, restored.orphanNovelFieldValues!![0].novelId)
        assertEquals("NVL-1", restored.refs!!.novels!!["7"])
        // 옛 필드정의 id는 자연키로만 되찾을 수 있다 — 세계관이 새 id로 다시 만들어지기 때문이다.
        assertEquals(FieldDefRef("UNI-1", "character", "mana"), restored.refs!!.fieldDefs!!["12"])

        // 구버전(이 타입이 없던) payload는 전부 null — 읽는 쪽이 폴백해야 한다 (R-2).
        val bare = gson.fromJson("""{"universeCode": "UNI-1"}""", UniverseDataSnapshot::class.java)
        assertNull(bare.fieldValueEntries)
        assertNull(bare.orphanCharacterFieldValues)
        assertNull(bare.orphanNovelFieldValues)
        assertNull(bare.refs)
        assertEquals(emptyList<CharacterFieldValue>(), bare.orphanCharacterFieldValues.orEmpty())
    }

    @Test
    fun `세계관 payload에 목록 키가 없으면 전부 null이고 읽는 쪽이 폴백한다`() {
        val json = """{"universe": {"id": 3, "name": "아스테리아", "code": "UNI-1",
                       "description": "", "createdAt": 0, "displayOrder": 0,
                       "borderColor": "", "borderWidthDp": 1.5, "imagePaths": "[]",
                       "imageMode": "none", "customRelationshipTypes": "",
                       "customRelationshipColors": ""}}"""
        val snap = gson.fromJson(json, UniverseSnapshot::class.java)
        assertEquals("아스테리아", snap.universe.name)
        assertNull(snap.fieldDefinitions)
        assertNull(snap.refs)
        // 선언이 nullable이므로 컴파일 시점에 폴백이 강제된다 — 이것이 계약이다.
        assertEquals(emptyList<FieldDefinition>(), snap.fieldDefinitions.orEmpty())
    }

    @Test
    fun `작품 payload에 목록 키가 없으면 null이다`() {
        val json = """{"novel": {"id": 7, "title": "1부", "code": "NVL-1", "description": "",
                       "createdAt": 0, "displayOrder": 0, "borderColor": "", "borderWidthDp": 1.5,
                       "inheritUniverseBorder": true, "isPinned": false, "imagePaths": "[]",
                       "imageMode": "none"}}"""
        val snap = gson.fromJson(json, NovelSnapshot::class.java)
        assertEquals("1부", snap.novel.title)
        assertNull(snap.linkedEventIds)
        assertNull(snap.refs)
        assertEquals(emptyList<Long>(), snap.linkedEventIds.orEmpty())
    }

    @Test
    fun `세력 payload에 목록 키가 없으면 전부 null이다`() {
        val json = """{"faction": {"id": 4, "universeId": 3, "name": "은검단", "code": "FAC-1",
                       "description": "", "color": "#2196F3", "autoRelationType": "동료",
                       "autoRelationIntensity": 5, "displayOrder": 0, "createdAt": 0}}"""
        val snap = gson.fromJson(json, FactionSnapshot::class.java)
        assertEquals("은검단", snap.faction.name)
        assertNull(snap.memberships)
        assertNull(snap.factionRelationships)
        assertNull(snap.autoRelationships)
        assertNull(snap.autoRelationshipChanges)
        assertNull(snap.detachedRelationshipCodes)
        assertNull(snap.refs)
    }

    @Test
    fun `사건 payload에 목록 키가 없으면 전부 null이다`() {
        val json = """{"event": {"id": 41, "year": 1200, "description": "대전쟁", "code": "EVT-1",
                       "calendarType": "천개력", "eventType": "", "displayOrder": 0,
                       "isTemporary": false, "createdAt": 0}}"""
        val snap = gson.fromJson(json, EventSnapshot::class.java)
        assertEquals("대전쟁", snap.event.description)
        assertNull(snap.fieldValues)
        assertNull(snap.characterIds)
        assertNull(snap.novelIds)
        assertNull(snap.relationshipChangeCodes)
        assertNull(snap.refs)
    }

    @Test
    fun `refs가 있어도 일부 하위 맵이 없으면 null이 주입된다`() {
        val json = """{"event": {"id": 41, "year": 1200, "description": "대전쟁", "code": "EVT-1",
                       "calendarType": "천개력", "eventType": "", "displayOrder": 0,
                       "isTemporary": false, "createdAt": 0},
                       "refs": {"version": 1, "universeCode": "UNI-1"}}"""
        val snap = gson.fromJson(json, EventSnapshot::class.java)
        val refs = snap.refs
        assertNotNull(refs)
        assertEquals("UNI-1", refs!!.universeCode)
        assertNull(refs.characters)
        assertNull(refs.novels)
        assertNull(refs.fieldDefs)
        assertEquals(emptyMap<String, String>(), refs.factions.orEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────
    // 왕복 — 안정 식별자가 그대로 보존되는가
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `세계관 스냅샷은 왕복에서 필드 정의를 보존한다`() {
        // 본체는 크기가 유계인 것만 담는다 — 자라는 부분은 UniverseDataSnapshot이 나눠 담는다.
        val original = UniverseSnapshot(
            universe = Universe(id = 3, name = "아스테리아", code = "UNI-1", imageCharacterId = 9),
            fieldDefinitions = listOf(
                FieldDefinition(id = 12, universeId = 3, key = "mana", name = "마나친화", type = "NUMBER")
            ),
            refs = EntityRefs(
                characters = mapOf("9" to "CHR-1"),
                novels = mapOf("7" to "NVL-1")
            )
        )
        val restored = gson.fromJson(gson.toJson(original), UniverseSnapshot::class.java)
        assertEquals("UNI-1", restored.universe.code)
        assertEquals(12L, restored.fieldDefinitions!![0].id)
        // Long id를 문자열 키로 담는 이유: Gson의 숫자 맵 키 처리에 기대지 않기 위해서다.
        assertEquals("CHR-1", restored.refs!!.characters!!["9"])
        assertEquals(SnapshotRefs.VERSION, restored.refs!!.version)
    }

    @Test
    fun `세력 스냅샷은 자동 관계와 분리된 관계 코드를 따로 보존한다`() {
        // 두 경우는 복원 방법이 다르다 — 하나는 관계를 다시 만들고, 하나는 살아 있는 관계에
        // '이 세력의 자동 관계'라는 지정만 되붙인다. 한 칸에 담으면 구별이 사라진다.
        val original = FactionSnapshot(
            faction = Faction(id = 4, universeId = 3, name = "은검단", code = "FAC-1", autoRelationType = "동료"),
            memberships = listOf(FactionMembership(id = 1, factionId = 4, characterId = 88)),
            factionRelationships = listOf(
                FactionRelationship(id = 2, factionId1 = 4, factionId2 = 5, relationType = "적")
            ),
            autoRelationships = listOf(
                CharacterRelationship(id = 3, characterId1 = 88, characterId2 = 89,
                    relationshipType = "동료", code = "REL-1", factionId = 4)
            ),
            detachedRelationshipCodes = listOf("REL-9"),
            refs = EntityRefs(
                universeCode = "UNI-1",
                characters = mapOf("88" to "CHR-9", "89" to "CHR-8"),
                factions = mapOf("5" to "FAC-2")
            )
        )
        val restored = gson.fromJson(gson.toJson(original), FactionSnapshot::class.java)
        assertEquals(1, restored.memberships!!.size)
        assertEquals(5L, restored.factionRelationships!![0].factionId2)
        assertEquals("REL-1", restored.autoRelationships!![0].code)
        assertEquals(listOf("REL-9"), restored.detachedRelationshipCodes)
        assertEquals("UNI-1", restored.refs!!.universeCode)
        assertEquals("FAC-2", restored.refs!!.factions!!["5"])
    }

    @Test
    fun `사건 스냅샷은 필드값 자연키와 끊긴 이력 코드를 보존한다`() {
        val original = EventSnapshot(
            event = TimelineEvent(id = 41, year = 1200, description = "대전쟁", code = "EVT-1", universeId = 3),
            fieldValues = listOf(EventFieldValue(id = 1, eventId = 41, fieldDefinitionId = 12, value = "3화")),
            characterIds = listOf(88L, 89L),
            novelIds = listOf(7L),
            relationshipChangeCodes = listOf("CHG-1"),
            refs = EntityRefs(
                universeCode = "UNI-1",
                characters = mapOf("88" to "CHR-9", "89" to "CHR-8"),
                novels = mapOf("7" to "NVL-1"),
                fieldDefs = mapOf("12" to FieldDefRef("UNI-1", "event", "chapter"))
            )
        )
        val restored = gson.fromJson(gson.toJson(original), EventSnapshot::class.java)
        assertEquals(listOf(88L, 89L), restored.characterIds)
        assertEquals(listOf(7L), restored.novelIds)
        assertEquals(listOf("CHG-1"), restored.relationshipChangeCodes)
        assertEquals(FieldDefRef("UNI-1", "event", "chapter"), restored.refs!!.fieldDefs!!["12"])
    }

    @Test
    fun `작품 스냅샷은 사건 연결을 id 목록으로 유지하고 코드는 refs에 병기한다`() {
        // 기존 필드의 타입을 바꾸면 구버전 payload의 역직렬화가 깨지므로, List<Long>은 그대로 두고
        // 코드를 refs.events에 병기하는 형태를 고정한다 (R-2).
        val original = NovelSnapshot(
            novel = Novel(id = 7, title = "1부", code = "NVL-1", universeId = 3),
            linkedEventIds = listOf(41L, 42L),
            refs = EntityRefs(universeCode = "UNI-1", events = mapOf("41" to "EVT-1", "42" to "EVT-2"))
        )
        val restored = gson.fromJson(gson.toJson(original), NovelSnapshot::class.java)
        assertTrue(restored.linkedEventIds!!.all { it is Long })
        assertEquals(listOf(41L, 42L), restored.linkedEventIds)
        assertEquals("EVT-2", restored.refs!!.events!!["42"])
    }

    @Test
    fun `사건 스냅샷은 출생·사망 이력을 담고 구버전 payload에서는 null이다`() {
        // 이 이력은 사건에 FK로 매달려 있지 않다 — 시맨틱 역동기화가 지우므로 사건만 되살리면
        // 캐릭터의 출생 기록은 돌아오지 않는다. payload가 담아야 약속이 사실이 된다.
        val original = EventSnapshot(
            event = TimelineEvent(id = 41, year = 1200, description = "가온 탄생",
                code = "EVT-1", eventType = TimelineEvent.TYPE_BIRTH),
            characterIds = listOf(88L),
            linkedStateChanges = listOf(
                CharacterStateChange(id = 5, characterId = 88, year = 1200,
                    fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "1200", code = "CHG-B")
            ),
            refs = EntityRefs(characters = mapOf("88" to "CHR-9"))
        )
        val restored = gson.fromJson(gson.toJson(original), EventSnapshot::class.java)
        assertEquals(1, restored.linkedStateChanges!!.size)
        assertEquals(88L, restored.linkedStateChanges!![0].characterId)
        assertEquals(CharacterStateChange.KEY_BIRTH, restored.linkedStateChanges!![0].fieldKey)
        assertEquals("CHR-9", restored.refs!!.characters!!["88"])

        // 이 필드가 없던 시절의 payload는 null이 주입된다 — 읽는 쪽이 폴백해야 한다 (R-2).
        val older = """{"event": {"id": 41, "year": 1200, "description": "가온 탄생", "code": "EVT-1",
                        "calendarType": "천개력", "eventType": "birth", "displayOrder": 0,
                        "isTemporary": false, "createdAt": 0}, "characterIds": [88]}"""
        val old = gson.fromJson(older, EventSnapshot::class.java)
        assertNull(old.linkedStateChanges)
        assertEquals(emptyList<CharacterStateChange>(), old.linkedStateChanges.orEmpty())
    }

    @Test
    fun `상태변화 스냅샷은 주인 캐릭터 코드를 담고 구버전 키 없음에도 견딘다`() {
        // 이력만 개별로 지우는 경로의 백업이다. 주인은 **코드로** 다시 찾는다 —
        // id 단독으로 붙이면 남의 캐릭터에 남의 출생 기록을 심는다(오배정은 생략보다 나쁘다).
        val original = StateChangeSnapshot(
            change = CharacterStateChange(
                id = 5, characterId = 88, year = 1200,
                fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "1200", code = "CHG-B"
            ),
            characterCode = "CHR-9",
            refs = EntityRefs(characters = mapOf("88" to "CHR-9"))
        )
        val restored = gson.fromJson(gson.toJson(original), StateChangeSnapshot::class.java)
        assertEquals(88L, restored.change.characterId)
        assertEquals(CharacterStateChange.KEY_BIRTH, restored.change.fieldKey)
        assertEquals("CHR-9", restored.characterCode)
        assertEquals("CHR-9", restored.refs!!.characters!!["88"])

        // 코드·refs가 없는 payload도 역직렬화는 되어야 한다 — 그때는 복원이 막히고
        // 스냅샷이 휴지통에 남는다(R-2 + R-4). 여기서 터지면 목록조차 못 연다.
        val bare = """{"change": {"id": 5, "characterId": 88, "year": 1200,
                       "fieldKey": "__birth", "newValue": "1200", "description": "",
                       "createdAt": 0}}"""
        val old = gson.fromJson(bare, StateChangeSnapshot::class.java)
        assertNull(old.characterCode)
        assertNull(old.refs)
        assertNull(old.change.code)
        assertEquals(emptyMap<String, String>(), old.refs?.characters.orEmpty())
    }

    @Test
    fun `세계관 스냅샷은 등급 체계를 담고 구버전 payload에서는 null이다`() {
        val original = UniverseSnapshot(
            universe = Universe(id = 1, name = "U", code = "UNI-1"),
            gradeSystems = listOf(
                GradeSystem(id = 3, universeId = 1, name = "능력 등급",
                    gradesJson = """{"C":0.5,"S":3}""", code = "GS-1")
            )
        )
        val restored = gson.fromJson(gson.toJson(original), UniverseSnapshot::class.java)
        assertEquals("능력 등급", restored.gradeSystems!!.single().name)
        assertEquals("GS-1", restored.gradeSystems!!.single().code)

        // U-1 이전 payload에는 이 키가 없다 — null이 주입되고 읽는 쪽이 orEmpty로 받는다(R-2).
        val old = gson.fromJson("""{"universe": {"id": 1, "name": "U", "description": "",
            "createdAt": 0, "code": "UNI-1", "displayOrder": 0, "borderColor": "",
            "borderWidthDp": 1.5, "imagePaths": "[]", "imageMode": "none",
            "customRelationshipTypes": "", "customRelationshipColors": ""}}""",
            UniverseSnapshot::class.java)
        assertNull(old.gradeSystems)
        assertEquals(emptyList<GradeSystem>(), old.gradeSystems.orEmpty())
    }

    @Test
    fun `등급 체계 스냅샷은 참조 필드 자연키를 담고 구버전 키 없음에도 견딘다`() {
        val original = GradeSystemSnapshot(
            gradeSystem = GradeSystem(id = 3, universeId = 1, name = "능력 등급",
                gradesJson = """{"C":0.5,"S":3}""", code = "GS-1"),
            referencingFields = listOf(
                FieldDefRef(universeCode = "UNI-1", entityType = "character", key = "mana")
            ),
            universeCode = "UNI-1",
            refs = EntityRefs(universeCode = "UNI-1")
        )
        val restored = gson.fromJson(gson.toJson(original), GradeSystemSnapshot::class.java)
        assertEquals("GS-1", restored.gradeSystem.code)
        assertEquals("mana", restored.referencingFields!!.single().key)
        assertEquals("UNI-1", restored.universeCode)

        // 필수 키만 있는 payload — 목록·참조가 null이어도 역직렬화는 되어야 한다(R-2).
        val bare = """{"gradeSystem": {"id": 3, "universeId": 1, "name": "능력 등급",
                       "gradesJson": "{}", "displayOrder": 0, "createdAt": 0, "code": "GS-1"}}"""
        val old = gson.fromJson(bare, GradeSystemSnapshot::class.java)
        assertNull(old.referencingFields)
        assertNull(old.universeCode)
        assertEquals(emptyList<FieldDefRef>(), old.referencingFields.orEmpty())
    }

    @Test
    fun `등급 체계는 세계관 다음 - 하위 엔티티 이전에 복원된다`() {
        // 체계는 세계관에만 매달린다. 세계관보다 먼저 오면 붙을 자리가 없고,
        // 필드 정의 config가 code로 참조하므로 정의 계층 안에서는 이른 쪽이 안전하다.
        assertTrue(
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_GRADE_SYSTEM) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_UNIVERSE)
        )
        assertTrue(
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_GRADE_SYSTEM) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_UNIVERSE_DATA)
        )
        assertTrue(
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_NOVEL) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_GRADE_SYSTEM)
        )
    }

    @Test
    fun `상태변화는 캐릭터보다 나중에 복원된다`() {
        // 이력은 캐릭터에 FK로 매달린다 — 순서가 뒤집히면 붙을 자리가 없어 통째로 막힌다.
        assertTrue(
            TrashSnapshot.restorePriority(TrashSnapshot.TYPE_STATE_CHANGE) >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_CHARACTER)
        )
        // 알 수 없는 타입(미래의 신규 타입)은 여전히 맨 뒤다 — 아는 타입보다 먼저 오면 안 된다.
        assertTrue(
            TrashSnapshot.restorePriority("unknown_future_type") >
                TrashSnapshot.restorePriority(TrashSnapshot.TYPE_STATE_CHANGE)
        )
    }

    @Test
    fun `사건 코드가 null인 구버전 payload도 역직렬화된다`() {
        // TimelineEvent.code는 nullable이다(v35 이전 스냅샷 수용). 복원이 이 null을 그대로
        // 만나므로 payload 계약에도 남겨 둔다.
        val json = """{"event": {"id": 41, "year": 1200, "description": "대전쟁",
                       "calendarType": "천개력", "eventType": "", "displayOrder": 0,
                       "isTemporary": false, "createdAt": 0}}"""
        val snap = gson.fromJson(json, EventSnapshot::class.java)
        assertNull(snap.event.code)
    }
}
