package com.novelcharacter.app.data

import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterSnapshot
import com.novelcharacter.app.data.repository.RestoreModes
import com.novelcharacter.app.data.model.FieldDefRef
import com.novelcharacter.app.data.model.SnapshotRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴지통 payload의 Gson 왕복·하위호환 계약 (N1).
 *
 * Gson은 Kotlin 생성자 기본값을 실행하지 않고 Unsafe로 객체를 할당한다. 따라서 **구버전
 * payload에 없는 필드는 선언이 non-null이어도 런타임에 null이 주입된다.** 이 사실을 잊고
 * 새 필드를 non-null로 선언한 뒤 곧바로 순회하면, 사용자가 이미 휴지통에 갖고 있던
 * 모든 항목이 복원 시 NPE로 실패한다 — 이 테스트가 그 회귀를 막는다.
 */
class CharacterSnapshotPayloadTest {

    private val gson = Gson()

    /** N1 이전 형식의 payload (refs 키 자체가 없다) */
    private val legacyPayload = """
        {
          "character": {"id": 5, "name": "가온", "code": "CHR-1", "novelId": 3,
                        "imagePaths": "[]", "displayOrder": 0},
          "fieldValues": [{"id": 1, "characterId": 5, "fieldDefinitionId": 12, "value": "높음"}],
          "stateChanges": [],
          "tags": [],
          "relationships": [],
          "relationshipChanges": [],
          "factionMemberships": [],
          "eventIds": [41, 42]
        }
    """.trimIndent()

    @Test
    fun `구버전 payload는 refs가 null로 역직렬화되고 나머지는 그대로 살아난다`() {
        val snap = gson.fromJson(legacyPayload, CharacterSnapshot::class.java)
        assertNull("refs 키가 없으면 null이어야 폴백 경로가 성립한다", snap.refs)
        assertEquals("가온", snap.character.name)
        assertEquals(1, snap.fieldValues.size)
        assertEquals(12L, snap.fieldValues[0].fieldDefinitionId)
        assertEquals(listOf(41L, 42L), snap.eventIds)
    }

    @Test
    fun `구버전 payload는 nameBankCodes와 revertScope가 null이다 — 읽는 쪽이 폴백해야 한다`() {
        // R-2: Gson은 Kotlin 생성자 기본값을 실행하지 않고 Unsafe로 객체를 할당한다.
        // 키가 없으면 선언이 non-null이어도 런타임에 null이 주입되므로 nullable로 선언하고
        // 읽는 쪽이 .orEmpty()로 받아야 한다 — 아니면 기존 휴지통 항목 전량이 복원 불가가 된다.
        val snap = gson.fromJson(legacyPayload, CharacterSnapshot::class.java)
        assertNull("B-3 이전 payload에는 이 키가 없다", snap.nameBankCodes)
        assertNull("B-21 이전 편집 백업에는 이 키가 없다", snap.revertScope)
        // 구버전 편집 백업은 '모든 편집 경로가 공통으로 파괴하는 것'만 되돌린다.
        assertEquals(
            setOf(RestoreModes.SCOPE_FIELD_VALUES),
            RestoreModes.revertScopeOf(snap.revertScope)
        )
    }

    @Test
    fun `신규 키는 왕복에서 보존된다`() {
        val original = CharacterSnapshot(
            character = gson.fromJson(legacyPayload, CharacterSnapshot::class.java).character,
            nameBankCodes = listOf("NB-1", "NB-2"),
            revertScope = RestoreModes.SCOPE_LIBRARY_ENTRY_DELETE
        )
        val round = gson.fromJson(gson.toJson(original), CharacterSnapshot::class.java)
        assertEquals(listOf("NB-1", "NB-2"), round.nameBankCodes)
        assertEquals(
            setOf(RestoreModes.SCOPE_FIELD_VALUES, RestoreModes.SCOPE_STATE_CHANGES),
            RestoreModes.revertScopeOf(round.revertScope)
        )
    }

    @Test
    fun `refs가 있어도 일부 하위 맵이 없으면 null이 주입된다 — 읽는 쪽이 폴백해야 한다`() {
        val partial = """
            {
              "character": {"id": 5, "name": "가온", "code": "CHR-1", "imagePaths": "[]", "displayOrder": 0},
              "fieldValues": [], "stateChanges": [], "tags": [],
              "relationships": [], "relationshipChanges": [], "factionMemberships": [],
              "eventIds": [],
              "refs": {"version": 1, "novelCode": "NVL-1"}
            }
        """.trimIndent()
        val snap = gson.fromJson(partial, CharacterSnapshot::class.java)
        val refs = snap.refs
        assertNotNull(refs)
        assertEquals("NVL-1", refs!!.novelCode)
        // 선언이 nullable이므로 컴파일 시점에 폴백이 강제된다 — 이것이 계약이다.
        assertNull(refs.fieldDefs)
        assertNull(refs.characters)
        assertEquals(emptyMap<String, String>(), refs.factions.orEmpty())
    }

    @Test
    fun `현행 형식은 Gson 왕복에서 안정 식별자를 그대로 보존한다`() {
        val original = CharacterSnapshot(
            character = Character(id = 5, name = "가온", code = "CHR-1", novelId = 3),
            fieldValues = listOf(CharacterFieldValue(id = 1, characterId = 5, fieldDefinitionId = 12, value = "높음")),
            eventIds = listOf(41L),
            refs = SnapshotRefs(
                novelCode = "NVL-1",
                universeCode = "UNI-1",
                fieldDefs = mapOf("12" to FieldDefRef("UNI-1", "character", "mana")),
                characters = mapOf("9" to "CHR-2"),
                factions = mapOf("4" to "FAC-1"),
                events = mapOf("41" to "EVT-1")
            )
        )
        val restored = gson.fromJson(gson.toJson(original), CharacterSnapshot::class.java)
        val refs = restored.refs!!
        assertEquals(SnapshotRefs.VERSION, refs.version)
        assertEquals("UNI-1", refs.universeCode)
        // Long id를 문자열 키로 담는 이유: Gson의 숫자 맵 키 처리에 기대지 않기 위해서다.
        assertEquals(FieldDefRef("UNI-1", "character", "mana"), refs.fieldDefs!!["12"])
        assertEquals("CHR-2", refs.characters!!["9"])
        assertEquals("FAC-1", refs.factions!!["4"])
        assertEquals("EVT-1", refs.events!!["41"])
        assertEquals(listOf(41L), restored.eventIds)
    }

    @Test
    fun `eventIds 타입은 유지된다 — 코드는 refs로 병기한다`() {
        // 기존 필드의 타입을 바꾸면 구버전 payload의 역직렬화가 깨지므로,
        // List<Long>은 그대로 두고 코드를 refs.events에 병기하는 형태를 고정한다.
        val snap = gson.fromJson(legacyPayload, CharacterSnapshot::class.java)
        assertTrue(snap.eventIds.all { it is Long })
    }
}
