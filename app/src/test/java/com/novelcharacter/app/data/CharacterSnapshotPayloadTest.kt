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
        assertEquals(1, snap.fieldValues.orEmpty().size)
        assertEquals(12L, snap.fieldValues.orEmpty()[0].fieldDefinitionId)
        assertEquals(listOf(41L, 42L), snap.eventIds)
    }

    @Test
    fun `목록 키가 통째로 없으면 일곱 전부 null이다 — 읽는 쪽이 폴백해야 한다`() {
        // B-19. **손편집 payload가 실제 경로다** — 앱이 만드는 payload에는 늘 키가 있지만,
        // 이 저장소의 규약은 *"들어온 것을 거부가 아니라 유연하게 수용한다"*이고 R-2는
        // Gson이 키 부재에 null을 주입한다는 사실 자체를 계약으로 세운 것이다.
        // 선언이 non-null이던 동안 이 payload는 **복원 순간 NPE로 터질 자리였다**(실제 사고는 없었다).
        //
        // ⚠️ **이 시험은 선언이 non-null로 되돌아가도 초록이다** — 런타임에는 여전히 null이
        // 오므로 assertNull이 통과한다. 여기서 잠그는 것은 *위험의 존재*이고, 널 처분을
        // 강제하는 것은 **읽는 쪽을 컴파일하는 컴파일러**다. 둘을 헷갈리지 말 것.
        val onlyCharacter = """
            {
              "character": {"id": 5, "name": "가온", "code": "CHR-1",
                            "imagePaths": "[]", "displayOrder": 0}
            }
        """.trimIndent()
        val snap = gson.fromJson(onlyCharacter, CharacterSnapshot::class.java)

        assertNull(snap.fieldValues)
        assertNull(snap.stateChanges)
        assertNull(snap.tags)
        assertNull(snap.relationships)
        assertNull(snap.relationshipChanges)
        assertNull(snap.factionMemberships)
        assertNull(snap.eventIds)

        // 그리고 읽는 쪽 규약(.orEmpty())이 그 null을 그대로 받는다 — 이 두 줄이 짝이다.
        assertEquals(emptyList<Long>(), snap.eventIds.orEmpty())
        assertEquals(0, snap.fieldValues.orEmpty().size)
    }

    @Test
    fun `목록 하나만 빠져도 그 하나만 null이다 — 나머지는 멀쩡히 살아난다`() {
        // 전부 빠지는 것보다 **하나만 빠지는 쪽이 위험하다.** 나머지가 정상이라 복원이
        // 시작되고, 그 하나를 읽는 자리에서 비로소 터진다 — 그때는 앞 항목이 이미 복원돼 있다.
        val noTags = """
            {
              "character": {"id": 5, "name": "가온", "code": "CHR-1",
                            "imagePaths": "[]", "displayOrder": 0},
              "fieldValues": [{"id": 1, "characterId": 5, "fieldDefinitionId": 12, "value": "높음"}],
              "stateChanges": [], "relationships": [], "relationshipChanges": [],
              "factionMemberships": [], "eventIds": [41]
            }
        """.trimIndent()
        val snap = gson.fromJson(noTags, CharacterSnapshot::class.java)

        assertNull("tags 키만 없다", snap.tags)
        assertEquals(1, snap.fieldValues.orEmpty().size)
        assertEquals(listOf(41L), snap.eventIds)
        assertEquals(emptyList<Any>(), snap.tags.orEmpty())
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
    fun `구버전 payload는 representativeImagePath가 null이다 — 복원이 기본값으로 접어야 한다`() {
        // v47(B-103) 이전에 지운 캐릭터에는 이 키가 없다. 선언은 non-null이므로
        // 읽는 쪽이 접지 않으면 **그때 휴지통에 있던 항목 전량이 복원 불가**가 된다.
        val snap = gson.fromJson(legacyPayload, CharacterSnapshot::class.java)
        @Suppress("SENSELESS_COMPARISON")
        assertNull("v47 이전 payload에는 이 키가 없다", snap.character.representativeImagePath as String?)
        assertEquals("", snap.character.representativeImagePath.orEmpty())
    }

    /** v47 직전 형식 — 그때 있던 칸은 **전부** 들어 있고 representativeImagePath만 없다. */
    private val v46Payload = """
        {
          "character": {"id": 5, "name": "가온", "firstName": "", "lastName": "",
                        "anotherName": "", "novelId": 3, "imagePaths": "[]",
                        "createdAt": 1, "updatedAt": 2, "memo": "", "code": "CHR-1",
                        "displayOrder": 0, "isPinned": false},
          "fieldValues": [], "stateChanges": [], "tags": [],
          "relationships": [], "relationshipChanges": [],
          "factionMemberships": [], "eventIds": []
        }
    """.trimIndent()

    @Test
    fun `v47 이전 payload를 copy할 때는 새 칸을 명시로 넘겨야 한다`() {
        // Kotlin의 `copy`는 **넘기지 않아 기본값으로 채워지는 인자에도** null 검사를 건다.
        // 그래서 새 non-null 칸을 더하면 **그 이전 payload를 그냥 copy하는 모든 자리가**
        // 조용히 NPE 지뢰가 된다. 실제로 이 슬라이스에서 `WorldPackageContents`가 그렇게
        // 깨졌고(파서 테스트가 잡았다), 휴지통 복원도 같은 모양이라 함께 고쳤다.
        // 칸을 더할 때마다 되풀이되는 함정이라 계약으로 고정해 둔다.
        val character = gson.fromJson(v46Payload, CharacterSnapshot::class.java).character
        @Suppress("SENSELESS_COMPARISON")
        assertNull("v47 이전 payload에는 이 키가 없다", character.representativeImagePath as String?)

        var threw = false
        try {
            character.copy(novelId = 7L)
        } catch (_: NullPointerException) {
            threw = true
        }
        assertTrue("새 칸을 명시하지 않은 copy는 NPE로 죽는다 — 이것이 함정의 실물이다", threw)

        // 명시로 넘기면 살아난다. 휴지통 복원·월드패키지 임포터가 하는 일이 이것이다.
        val ok = character.copy(
            novelId = 7L,
            representativeImagePath = character.representativeImagePath.orEmpty()
        )
        assertEquals("", ok.representativeImagePath)
        assertEquals(7L, ok.novelId)
        assertEquals("가온", ok.name)
    }

    @Test
    fun `eventIds 원소 타입은 유지된다 — 코드는 refs로 병기한다`() {
        // 기존 필드의 **원소** 타입을 바꾸면 구버전 payload의 역직렬화가 깨지므로,
        // List<Long>은 그대로 두고 코드를 refs.events에 병기하는 형태를 고정한다.
        // **널 허용은 원소 타입 변경이 아니다**(B-19) — 같은 JSON이 그대로 읽히고,
        // 바뀌는 것은 *키가 없을 때* null이 오는 것을 선언이 인정하는가뿐이다.
        val snap = gson.fromJson(legacyPayload, CharacterSnapshot::class.java)
        assertTrue(snap.eventIds.orEmpty().all { it is Long })
    }
}
