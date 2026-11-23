package com.novelcharacter.app.pcbridge

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterRelationship
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 관계 편집의 기술자와 값 수집.
 *
 * **여기서 잠그는 것은 "웹이 판정하지 않게 한 것"들이다** — 화살표 글자 · 고칠 수 있는가와
 * 그 사유 · 상대 후보의 세계관 범위 · 저장할 양 끝의 배치.
 */
class BridgeRelationshipsTest {

    private fun rel(
        id: Long = 1,
        end1: Long = 10,
        end2: Long = 20,
        type: String = "연인",
        description: String = "",
        intensity: Int = 5,
        bidirectional: Boolean = true,
        factionId: Long? = null,
        code: String? = "REL-0001",
        displayOrder: Int = 0,
        createdAt: Long = 1_000L
    ) = CharacterRelationship(
        id = id,
        characterId1 = end1,
        characterId2 = end2,
        relationshipType = type,
        description = description,
        intensity = intensity,
        isBidirectional = bidirectional,
        displayOrder = displayOrder,
        code = code,
        createdAt = createdAt,
        factionId = factionId
    )

    private fun character(id: Long, name: String, novelId: Long?) =
        Character(id = id, name = name, novelId = novelId)

    // ───────── 화살표 ─────────

    /** 화살표는 **보고 있는 캐릭터 기준**이다 — 받는 쪽에서 보면 반대로 가리켜야 한다. */
    @Test
    fun arrow_isRelativeToTheViewedCharacter() {
        assertEquals(BridgeRelationships.ARROW_BOTH, BridgeRelationships.arrowFor(true, true))
        assertEquals(BridgeRelationships.ARROW_BOTH, BridgeRelationships.arrowFor(true, false))
        assertEquals(BridgeRelationships.ARROW_OUT, BridgeRelationships.arrowFor(false, true))
        assertEquals(BridgeRelationships.ARROW_IN, BridgeRelationships.arrowFor(false, false))
    }

    @Test
    fun describe_arrowFollowsTheStoredEnds() {
        val rows = listOf(
            rel(id = 1, end1 = 10, end2 = 20, bidirectional = false),
            rel(id = 2, end1 = 20, end2 = 10, bidirectional = false)
        )
        val views = BridgeRelationships.describe(rows, 10, mapOf(20L to "상대"), emptyMap())
        assertEquals(BridgeRelationships.ARROW_OUT, views[0].arrow)
        assertTrue(views[0].outgoing)
        assertEquals(BridgeRelationships.ARROW_IN, views[1].arrow)
        assertFalse(views[1].outgoing)
    }

    // ───────── 세력 자동 관계 ─────────

    /**
     * **이 슬라이스의 급소다.** 세력 자동 관계는 소속에서 파생된 행이라 손으로 고치면 다음
     * 재동기에서 어긋난다 — 읽기 전용으로 내보내고 **어느 세력인지**까지 싣는다
     * (어디를 고치면 되는지 말할 수 없으면 사용자가 이 화면을 계속 두드린다).
     */
    @Test
    fun describe_factionRowsAreReadOnlyWithTheirFactionNamed() {
        val views = BridgeRelationships.describe(
            rows = listOf(rel(factionId = 7, type = "동료")),
            selfId = 10,
            names = mapOf(20L to "상대"),
            factionNames = mapOf(7L to "검은 장막")
        )
        val v = views.single()
        assertTrue(v.readOnly)
        assertEquals(BridgeRelationships.NOTICE_FACTION, v.notice)
        assertEquals("검은 장막", v.factionName)
    }

    /** 세력 이름을 모르면 **없다고 말한다** — 빈 글자를 이름처럼 내보내지 않는다. */
    @Test
    fun describe_factionRowWithoutAKnownName() {
        val v = BridgeRelationships.describe(
            listOf(rel(factionId = 7)), 10, mapOf(20L to "상대"), emptyMap()
        ).single()
        assertTrue(v.readOnly)
        assertEquals(BridgeRelationships.NOTICE_FACTION, v.notice)
        assertNull(v.factionName)
    }

    @Test
    fun describe_manualRowsAreEditable() {
        val v = BridgeRelationships.describe(
            listOf(rel()), 10, mapOf(20L to "상대"), emptyMap()
        ).single()
        assertFalse(v.readOnly)
        assertNull(v.notice)
        assertNull(v.factionName)
    }

    /**
     * 상대를 찾지 못한 행도 **조용히 빼지 않는다**(R-14).
     *
     * 앱의 관계 목록은 `mapNotNull`로 그런 행을 빼는데, 그러면 사용자는 그 관계가 있다는
     * 사실조차 알 수 없다. 사유를 들고 읽기 전용으로 내보낸다.
     */
    @Test
    fun describe_keepsRowsWhoseOtherEndIsMissing() {
        val v = BridgeRelationships.describe(
            listOf(rel()), 10, emptyMap(), emptyMap()
        ).single()
        assertTrue(v.readOnly)
        assertEquals(BridgeRelationships.NOTICE_MISSING_OTHER, v.notice)
        assertEquals("", v.otherCharacterName)
        assertEquals(20, v.otherCharacterId)
    }

    /** DAO가 준 순서를 그대로 둔다 — 여기서 다시 정렬하면 앱 목록과 순서가 갈린다. */
    @Test
    fun describe_keepsTheGivenOrder() {
        val rows = listOf(rel(id = 3), rel(id = 1), rel(id = 2))
        val views = BridgeRelationships.describe(rows, 10, mapOf(20L to "상대"), emptyMap())
        assertEquals(listOf(3L, 1L, 2L), views.map { it.id })
    }

    @Test
    fun describe_carriesTheStableCode() {
        val v = BridgeRelationships.describe(
            listOf(rel(code = "REL-ABCD")), 10, mapOf(20L to "상대"), emptyMap()
        ).single()
        assertEquals("REL-ABCD", v.code)
    }

    // ───────── 상대 후보 ─────────

    /**
     * **같은 세계관 안에서만 고를 수 있다.** AI 쪽 `validEndpoints`가 마지막에 요구하는
     * 조건이고, 세계관이 다른 둘을 이으면 그 관계는 어느 세계관의 유형에도 속하지 않는다.
     */
    @Test
    fun candidates_areScopedToTheSameUniverse() {
        val all = listOf(
            character(10, "나", 1),
            character(11, "같은 세계관", 2),
            character(12, "다른 세계관", 3),
            character(13, "작품 없음", null)
        )
        val universeOf = mapOf<Long, Long?>(1L to 100L, 2L to 100L, 3L to 200L)
        val titles = mapOf(1L to "작품 가", 2L to "작품 나", 3L to "작품 다")

        val candidates = BridgeRelationships.candidatesFor(all, 10, 100L, universeOf, titles)
        assertEquals(listOf(11L), candidates.map { it.id })
        assertEquals("작품 나", candidates.single().novelTitle)
    }

    /** 세계관이 없는 캐릭터끼리는 이을 수 있다 — 둘 다 null이면 같은 세계관이다. */
    @Test
    fun candidates_universelessCharactersPairUp() {
        val all = listOf(
            character(10, "나", null),
            character(11, "짝", null),
            character(12, "세계관 있음", 1)
        )
        val candidates = BridgeRelationships.candidatesFor(
            all, 10, null, mapOf<Long, Long?>(1L to 100L), emptyMap()
        )
        assertEquals(listOf(11L), candidates.map { it.id })
        assertEquals("", candidates.single().novelTitle)
    }

    @Test
    fun candidates_excludeTheCharacterItself() {
        val all = listOf(character(10, "나", 1), character(11, "짝", 1))
        val candidates = BridgeRelationships.candidatesFor(
            all, 10, 100L, mapOf<Long, Long?>(1L to 100L), mapOf(1L to "작품")
        )
        assertEquals(listOf(11L), candidates.map { it.id })
    }

    // ───────── 본문 읽기 ─────────

    @Test
    fun parseDraft_readsAFullBody() {
        val r = BridgeRelationships.parseDraft(
            """{"otherCharacterId":21,"type":"동료","description":"같은 부대",
               "intensity":8,"bidirectional":false,"outgoing":false}"""
        )
        val draft = (r as BridgeRelationships.DraftResult.Parsed).draft
        assertEquals(21, draft.otherCharacterId)
        assertEquals("동료", draft.type)
        assertEquals("같은 부대", draft.description)
        assertEquals(8, draft.intensity)
        assertFalse(draft.bidirectional)
        assertFalse(draft.outgoing)
    }

    /** 적혀 있지 않으면 엔티티의 기본값과 같게 둔다. */
    @Test
    fun parseDraft_defaultsToBidirectionalAndOutgoing() {
        val draft = (
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":"동료","intensity":5}"""
            ) as BridgeRelationships.DraftResult.Parsed
            ).draft
        assertTrue(draft.bidirectional)
        assertTrue(draft.outgoing)
        assertEquals("", draft.description)
    }

    @Test
    fun parseDraft_badJson() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("json"),
            BridgeRelationships.parseDraft("그냥 글자")
        )
    }

    @Test
    fun parseDraft_missingOtherCharacter() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("otherCharacterId"),
            BridgeRelationships.parseDraft("""{"type":"동료","intensity":5}""")
        )
        // 0·음수는 저장되지 않는 id라 요청으로 올 수 없다.
        assertEquals(
            BridgeRelationships.DraftResult.Bad("otherCharacterId"),
            BridgeRelationships.parseDraft("""{"otherCharacterId":0,"type":"동료","intensity":5}""")
        )
    }

    @Test
    fun parseDraft_blankType() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("type"),
            BridgeRelationships.parseDraft("""{"otherCharacterId":21,"type":"   ","intensity":5}""")
        )
    }

    /**
     * 강도가 없으면 **기본값으로 메우지 않는다.**
     *
     * 5로 접으면 사용자가 칸을 비운 채 저장했을 때 *"적지 않은 5"*가 값처럼 저장된다.
     */
    @Test
    fun parseDraft_missingIntensityIsRefused() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("intensity"),
            BridgeRelationships.parseDraft("""{"otherCharacterId":21,"type":"동료"}""")
        )
    }

    /**
     * **빈 강도 칸이 숫자로 메워지지 않는다.**
     *
     * 웹은 칸이 비면 `intensity: null`을 보낸다(`JSON.stringify`가 그렇게 적는다). 그것이
     * 조용히 기본값으로 접히면 *"적지 않은 5"*가 값처럼 저장되므로, 적혀 있으나 숫자가
     * 아닌 경우까지 사유로 돌려보내는지 잠근다.
     */
    @Test
    fun parseDraft_jsonNullIntensityIsRefused() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("intensity"),
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":"동료","intensity":null}"""
            )
        )
        assertEquals(
            BridgeRelationships.DraftResult.Bad("intensity"),
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":"동료","intensity":"높음"}"""
            )
        )
    }

    /** 범위는 여기서 보지 않는다 — `RelationshipRules`가 가린다(두 자리에서 보면 갈린다). */
    @Test
    fun parseDraft_doesNotJudgeTheIntensityRange() {
        val draft = (
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":"동료","intensity":99}"""
            ) as BridgeRelationships.DraftResult.Parsed
            ).draft
        assertEquals(99, draft.intensity)
    }

    /**
     * **JSON null에 `"null"`이 들어오지 않는다**(R-70).
     *
     * 기기(libcore)의 `optString`은 JSON null에 문자열 `"null"`을 돌려주므로, 그 함수를 쓰면
     * 유형이 그 글자로 저장될 수 있다.
     */
    @Test
    fun parseDraft_jsonNullIsNotTheWordNull() {
        assertEquals(
            BridgeRelationships.DraftResult.Bad("type"),
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":null,"intensity":5}"""
            )
        )
        val draft = (
            BridgeRelationships.parseDraft(
                """{"otherCharacterId":21,"type":"동료","description":null,"intensity":5}"""
            ) as BridgeRelationships.DraftResult.Parsed
            ).draft
        assertEquals("", draft.description)
    }

    // ───────── 양 끝의 배치 ─────────

    private fun draft(
        other: Long = 20,
        type: String = "연인",
        intensity: Int = 5,
        bidirectional: Boolean = true,
        outgoing: Boolean = true
    ) = BridgeRelationships.Draft(other, type, "", intensity, bidirectional, outgoing)

    @Test
    fun endpoints_newRowPutsTheViewerFirst() {
        assertEquals(10L to 20L, BridgeRelationships.endpointsFor(10, draft(), null))
    }

    @Test
    fun endpoints_newOneWayIncomingReversesThem() {
        assertEquals(
            20L to 10L,
            BridgeRelationships.endpointsFor(10, draft(bidirectional = false, outgoing = false), null)
        )
    }

    /**
     * **강도만 고친 저장이 양 끝을 뒤집지 않는다.**
     *
     * 양방향은 `A ↔ B`와 `B ↔ A`가 같은 뜻이라 늘 (보는 쪽, 상대)로 다시 적어도 뜻은
     * 맞지만, 그러면 행의 모양이 저장만으로 바뀐다 — 엑셀 왕복과 월드패키지에서 같은 관계가
     * 다르게 나가고, 유일 인덱스가 보는 자리도 함께 바뀐다.
     */
    @Test
    fun endpoints_keepStoredOrderWhenNothingAboutThemChanged() {
        val existing = rel(end1 = 20, end2 = 10, bidirectional = true)
        assertEquals(
            20L to 10L,
            BridgeRelationships.endpointsFor(10, draft(intensity = 9), existing)
        )
    }

    @Test
    fun endpoints_rewriteWhenTheDirectionChanges() {
        val existing = rel(end1 = 20, end2 = 10, bidirectional = true)
        // 양방향 → 단방향(내가 출발점)으로 바꾸면 저장된 배치를 그대로 둘 수 없다.
        assertEquals(
            10L to 20L,
            BridgeRelationships.endpointsFor(
                10, draft(bidirectional = false, outgoing = true), existing
            )
        )
    }

    @Test
    fun endpoints_rewriteWhenTheOtherCharacterChanges() {
        val existing = rel(end1 = 10, end2 = 20)
        assertEquals(
            10L to 30L,
            BridgeRelationships.endpointsFor(10, draft(other = 30), existing)
        )
    }

    @Test
    fun endpoints_oneWayKeepsItsStoredOrderWhenUnchanged() {
        val existing = rel(end1 = 20, end2 = 10, bidirectional = false)
        assertEquals(
            20L to 10L,
            BridgeRelationships.endpointsFor(
                10, draft(bidirectional = false, outgoing = false, intensity = 3), existing
            )
        )
    }

    // ───────── 저장할 행 ─────────

    /**
     * 고치는 쪽은 `id` · `code` · `createdAt` · `displayOrder`를 **보존한다.**
     *
     * `code`는 왕복 안정 식별자라 새로 내면 외부 편집기가 같은 관계를 다른 관계로 읽고,
     * `displayOrder`를 0으로 돌리면 사용자가 끌어 둔 순서가 저장만으로 흐트러진다.
     */
    @Test
    fun toRow_editPreservesIdentityAndOrder() {
        val existing = rel(id = 42, code = "REL-KEEP", displayOrder = 7, createdAt = 555L)
        val row = BridgeRelationships.toRow(10, draft(type = "동료", intensity = 9), existing)
        assertEquals(42, row.id)
        assertEquals("REL-KEEP", row.code)
        assertEquals(7, row.displayOrder)
        assertEquals(555L, row.createdAt)
        assertEquals("동료", row.relationshipType)
        assertEquals(9, row.intensity)
    }

    /** PC에서 만드는 것은 **언제나 수동 관계**다. */
    @Test
    fun toRow_newRowIsNeverAutomatic() {
        val row = BridgeRelationships.toRow(10, draft(), null)
        assertNull(row.factionId)
        assertEquals(0, row.id)
        assertEquals(10, row.characterId1)
        assertEquals(20, row.characterId2)
    }

    @Test
    fun toRow_writesTheSubmittedContent() {
        val row = BridgeRelationships.toRow(
            10,
            BridgeRelationships.Draft(20, "적", "원수", 10, false, true),
            null
        )
        assertEquals("적", row.relationshipType)
        assertEquals("원수", row.description)
        assertEquals(10, row.intensity)
        assertFalse(row.isBidirectional)
    }
}
