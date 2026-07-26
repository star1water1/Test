package com.novelcharacter.app.data

import com.google.gson.Gson
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.EntityRefs
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.EventSnapshot
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FactionRelationship
import com.novelcharacter.app.data.model.FactionSnapshot
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelSnapshot
import com.novelcharacter.app.data.model.SnapshotRefs
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
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
    fun `세계관 payload에 목록 키가 없으면 전부 null이고 읽는 쪽이 폴백한다`() {
        val json = """{"universe": {"id": 3, "name": "아스테리아", "code": "UNI-1",
                       "description": "", "createdAt": 0, "displayOrder": 0,
                       "borderColor": "", "borderWidthDp": 1.5, "imagePaths": "[]",
                       "imageMode": "none", "customRelationshipTypes": "",
                       "customRelationshipColors": ""}}"""
        val snap = gson.fromJson(json, UniverseSnapshot::class.java)
        assertEquals("아스테리아", snap.universe.name)
        assertNull(snap.fieldDefinitions)
        assertNull(snap.fieldValueEntries)
        assertNull(snap.orphanCharacterFieldValues)
        assertNull(snap.orphanEventFieldValues)
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
    fun `세계관 스냅샷은 왕복에서 필드 정의와 고아 필드값을 보존한다`() {
        val original = UniverseSnapshot(
            universe = Universe(id = 3, name = "아스테리아", code = "UNI-1", imageCharacterId = 9),
            fieldDefinitions = listOf(
                FieldDefinition(id = 12, universeId = 3, key = "mana", name = "마나친화", type = "NUMBER")
            ),
            orphanCharacterFieldValues = listOf(
                CharacterFieldValue(id = 1, characterId = 88, fieldDefinitionId = 12, value = "높음")
            ),
            orphanEventFieldValues = listOf(
                EventFieldValue(id = 2, eventId = 41, fieldDefinitionId = 12, value = "3화")
            ),
            refs = EntityRefs(
                characters = mapOf("88" to "CHR-9", "9" to "CHR-1"),
                events = mapOf("41" to "EVT-1"),
                novels = mapOf("7" to "NVL-1")
            )
        )
        val restored = gson.fromJson(gson.toJson(original), UniverseSnapshot::class.java)
        assertEquals("UNI-1", restored.universe.code)
        assertEquals(12L, restored.fieldDefinitions!![0].id)
        assertEquals(88L, restored.orphanCharacterFieldValues!![0].characterId)
        assertEquals(41L, restored.orphanEventFieldValues!![0].eventId)
        // Long id를 문자열 키로 담는 이유: Gson의 숫자 맵 키 처리에 기대지 않기 위해서다.
        assertEquals("CHR-9", restored.refs!!.characters!!["88"])
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
