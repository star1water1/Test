package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.TypeMismatchList
import com.novelcharacter.app.util.StatsSnapshot
import com.novelcharacter.app.util.FieldValueTypeMismatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 데이터 건강도 재편 (로드맵 14판 — B-59 · B-156).
 *
 * 고정하는 계약:
 * - **B-59** 입력 현황(이미지·메모·별명 미작성)은 *"발견 사항"*에 세지 않는다.
 * - **B-156** 타입과 맞지 않게 된 값을 **캐릭터·사건·작품 세 축 모두에서** 세고,
 *   어느 칸인지(대상·필드·값·사유)까지 담는다.
 */
class DataHealthReorgTest {

    private val provider = StatsDataProvider()
    private val uni = 1L

    private fun def(
        id: Long, name: String, type: String,
        entityType: String = FieldDefinition.ENTITY_CHARACTER, config: String = "{}"
    ) = FieldDefinition(
        id = id, universeId = uni, key = "k$id", name = name, type = type,
        config = config, entityType = entityType
    )

    private fun snapshot(
        characters: List<Character> = emptyList(),
        fieldDefinitions: List<FieldDefinition> = emptyList(),
        fieldValues: List<CharacterFieldValue> = emptyList(),
        events: List<TimelineEvent> = emptyList(),
        eventFieldDefinitions: List<FieldDefinition> = emptyList(),
        eventFieldValues: List<EventFieldValue> = emptyList(),
        novels: List<Novel> = listOf(Novel(id = 1, title = "A작품", universeId = uni)),
        novelFieldDefinitions: List<FieldDefinition> = emptyList(),
        novelFieldValues: List<NovelFieldValue> = emptyList()
    ) = StatsSnapshot(
        characters = characters, novels = novels,
        universes = listOf(Universe(id = uni, name = "A")),
        events = events, relationships = emptyList(), relationshipChanges = emptyList(),
        tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
        fieldDefinitions = fieldDefinitions, fieldValues = fieldValues, crossRefs = emptyList(),
        eventFieldDefinitions = eventFieldDefinitions, eventFieldValues = eventFieldValues,
        novelFieldDefinitions = novelFieldDefinitions, novelFieldValues = novelFieldValues
    )

    // ===== B-59: 입력량은 문제가 아니다 =====

    /**
     * 아무것도 안 쓴 캐릭터 셋. 종전 합계라면 이미지 3 + 메모 3이 그대로 *"발견 사항 6건"*이
     * 됐고, 진짜 점검거리가 있어도 그 안에 묻혔다.
     */
    private fun blankCharacters() = snapshot(
        characters = (1L..3L).map { Character(id = it, name = "c$it", novelId = 1) }
    )

    @Test
    fun `이미지와 메모를 안 쓴 것은 발견 사항으로 세지 않는다`() {
        val h = provider.computeDataHealth(blankCharacters())
        // 명단 자체는 그대로 있다 — 지운 것이 아니라 자리를 옮겼다(B-59는 "삭제가 아니라 재배치").
        assertEquals(3, h.inputProgress.noImageChars.size)
        assertEquals(3, h.inputProgress.noMemoChars.size)
        assertEquals(3, h.inputProgress.noAnotherNameChars.size)
        // 안 쓴 칸이 아홉인데도 건수는 관계·사건 항목뿐이다. 종전 합계라면 여기에 6이 더 붙었다.
        assertEquals(h.isolatedChars.size + h.unlinkedChars.size, h.issueCount)
    }

    /** 같은 스냅샷에 **값 하나만** 어긋나게 두고 건수 차이를 잰다 — 다른 항목이 섞이지 않는다. */
    @Test
    fun `타입이 안 맞는 값은 발견 사항으로 센다`() {
        val chars = listOf(Character(id = 1, name = "가", novelId = 1))
        val defs = listOf(def(10, "키", "NUMBER"))
        val ok = snapshot(
            characters = chars, fieldDefinitions = defs,
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "170"))
        )
        val broken = snapshot(
            characters = chars, fieldDefinitions = defs,
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "큼"))
        )
        assertEquals(1, provider.computeDataHealth(broken).issueCount - provider.computeDataHealth(ok).issueCount)
    }

    /**
     * 반대편 잘못 — 상세가 점검거리로 보여 주는 것을 카드가 안 세면 카드의 약속과 드릴다운의
     * 범위가 갈린다(R-15). 종전 합계는 **입력량은 세면서 이 둘은 빼고 있었다.**
     */
    @Test
    fun `작품 미배정은 발견 사항으로 센다`() {
        val assigned = snapshot(characters = listOf(Character(id = 1, name = "가", novelId = 1)))
        val orphan = snapshot(characters = listOf(Character(id = 1, name = "가", novelId = null)))
        assertEquals(
            1,
            provider.computeDataHealth(orphan).issueCount - provider.computeDataHealth(assigned).issueCount
        )
    }

    /** 연도만 적힌 사건은 **틀린 것이 아니다** — 요약이 따로 말하고 발견 사항에는 세지 않는다. */
    @Test
    fun `시간 정밀도가 낮은 사건은 발견 사항으로 세지 않는다`() {
        val s = snapshot(events = listOf(TimelineEvent(id = 5, year = 100, description = "개전")))
        val h = provider.computeDataHealth(s)
        assertEquals(1, h.lowPrecisionEvents)
        assertEquals(0, h.issueCount)
    }

    // ===== B-156: 세 축을 모두 센다 =====

    @Test
    fun `캐릭터 사건 작품 세 축의 불일치를 모두 센다`() {
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            fieldDefinitions = listOf(def(10, "키", "NUMBER")),
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "큼")),
            events = listOf(TimelineEvent(id = 5, year = 100, description = "개전")),
            eventFieldDefinitions = listOf(def(20, "규모", "NUMBER", FieldDefinition.ENTITY_EVENT)),
            eventFieldValues = listOf(EventFieldValue(eventId = 5, fieldDefinitionId = 20, value = "대규모")),
            novelFieldDefinitions = listOf(def(30, "권수", "NUMBER", FieldDefinition.ENTITY_NOVEL)),
            novelFieldValues = listOf(NovelFieldValue(novelId = 1, fieldDefinitionId = 30, value = "여러 권"))
        )
        val found = provider.computeDataHealth(s).typeMismatchedValues
        assertEquals(3, found.size)
        assertEquals(
            setOf(
                FieldDefinition.ENTITY_CHARACTER,
                FieldDefinition.ENTITY_EVENT,
                FieldDefinition.ENTITY_NOVEL
            ),
            found.map { it.ownerType }.toSet()
        )
        // 사건은 제목 칸이 없다 — 설명이 이름 자리다.
        assertEquals("개전", found.first { it.ownerType == FieldDefinition.ENTITY_EVENT }.ownerName)
        assertEquals("A작품", found.first { it.ownerType == FieldDefinition.ENTITY_NOVEL }.ownerName)
    }

    @Test
    fun `어느 캐릭터의 어느 칸에 무엇이 남았는지까지 담는다`() {
        val s = snapshot(
            characters = listOf(Character(id = 7, name = "레이", novelId = 1)),
            fieldDefinitions = listOf(def(10, "키", "NUMBER")),
            fieldValues = listOf(CharacterFieldValue(characterId = 7, fieldDefinitionId = 10, value = "큰 편"))
        )
        val row = provider.computeDataHealth(s).typeMismatchedValues.single()
        assertEquals(7L, row.ownerId)          // 이것이 없으면 바로잡을 경로를 못 만든다
        assertEquals("레이", row.ownerName)
        assertEquals("키", row.fieldName)
        assertEquals("NUMBER", row.fieldType)
        assertEquals("큰 편", row.value)
        assertEquals(FieldValueTypeMismatch.Reason.NOT_A_NUMBER, row.reason)
    }

    /**
     * 정상 데이터에서 0건이어야 한다. 판정을 [com.novelcharacter.app.util.FieldTypeCompatibility]로
     * 되돌리면 여기 등급·체형 값이 걸려 **정상인 목록이 통째로 경고가 된다.**
     */
    @Test
    fun `정상인 등급값과 체형값은 한 건도 잡지 않는다`() {
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            fieldDefinitions = listOf(
                def(10, "전투력", "GRADE", config = """{"grades":{"S":10,"A":8}}"""),
                def(11, "체형", "BODY_SIZE"),
                def(12, "메모", "TEXT"),
                def(13, "키", "NUMBER")
            ),
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "S"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "86-60-88"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 12, value = "아무 말"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 13, value = "170.5")
            )
        )
        assertTrue(provider.computeDataHealth(s).typeMismatchedValues.isEmpty())
    }

    @Test
    fun `대상이 사라진 값도 개수에서 빠지지 않는다 — id로 말한다`() {
        val s = snapshot(
            characters = emptyList(),
            fieldDefinitions = listOf(def(10, "키", "NUMBER")),
            fieldValues = listOf(CharacterFieldValue(characterId = 99, fieldDefinitionId = 10, value = "큼"))
        )
        val row = provider.computeDataHealth(s).typeMismatchedValues.single()
        assertEquals("#99", row.ownerName)
    }

    /**
     * 표시 계층이 축마다 뒷부분을 접으므로(R-14), 순서가 값 표의 행 순서면 **무엇이 접히는지가
     * 사실상 임의로 정해진다** — 같은 데이터를 다시 열었을 때 다른 50개가 보일 수 있다.
     */
    @Test
    fun `축 안에서는 이름 필드 순으로 결정적으로 정렬된다`() {
        val s = snapshot(
            characters = listOf(
                Character(id = 1, name = "하늘", novelId = 1),
                Character(id = 2, name = "가온", novelId = 1)
            ),
            fieldDefinitions = listOf(def(10, "키", "NUMBER"), def(11, "몸무게", "NUMBER")),
            // 일부러 뒤섞어 넣는다 — 값 표의 행 순서가 그대로 나오면 이 시험이 빨간불이다.
            fieldValues = listOf(
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "큼"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 10, value = "큼"),
                CharacterFieldValue(characterId = 1, fieldDefinitionId = 11, value = "무거움"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = 11, value = "무거움")
            )
        )
        val rows = provider.computeDataHealth(s).typeMismatchedValues
        assertEquals(
            listOf("가온·몸무게", "가온·키", "하늘·몸무게", "하늘·키"),
            rows.map { "${it.ownerName}·${it.fieldName}" }
        )
    }

    // ===== 표시 상한 (R-14) =====

    private fun rows(ownerType: String, n: Int) = (1..n).map {
        com.novelcharacter.app.ui.stats.TypeMismatchedValue(
            ownerType = ownerType, ownerId = it.toLong(), ownerName = "o$it",
            fieldDefId = 1L, fieldName = "f", fieldType = "NUMBER", value = "v",
            reason = FieldValueTypeMismatch.Reason.NOT_A_NUMBER
        )
    }

    /**
     * 상한이 통짜였다면 캐릭터 60건이 자리를 다 차지해 **사건 축이 한 줄도 안 보인다** —
     * 이 판이 없애려는 상태를 표시 계층에서 되살리는 꼴이다.
     */
    @Test
    fun `한 축이 넘쳐도 다른 축은 가려지지 않는다`() {
        val view = TypeMismatchList.view(
            rows(FieldDefinition.ENTITY_CHARACTER, 60) + rows(FieldDefinition.ENTITY_EVENT, 3),
            limitPerOwnerType = 50
        )
        assertEquals(50, view.shown.count { it.ownerType == FieldDefinition.ENTITY_CHARACTER })
        assertEquals(3, view.shown.count { it.ownerType == FieldDefinition.ENTITY_EVENT })
    }

    @Test
    fun `잘라낸 것은 축마다 개수로 알린다`() {
        val view = TypeMismatchList.view(rows(FieldDefinition.ENTITY_CHARACTER, 60), limitPerOwnerType = 50)
        assertEquals(mapOf(FieldDefinition.ENTITY_CHARACTER to 10), view.hiddenByOwnerType)
    }

    @Test
    fun `상한에 닿지 않으면 아무것도 접지 않는다`() {
        val view = TypeMismatchList.view(rows(FieldDefinition.ENTITY_NOVEL, 2), limitPerOwnerType = 50)
        assertEquals(2, view.shown.size)
        assertTrue(view.hiddenByOwnerType.isEmpty())
    }

    @Test
    fun `정의가 없는 값은 세지 않는다 — 필드가 지워지면 그 값은 어느 타입도 아니다`() {
        val s = snapshot(
            characters = listOf(Character(id = 1, name = "가", novelId = 1)),
            fieldDefinitions = emptyList(),
            fieldValues = listOf(CharacterFieldValue(characterId = 1, fieldDefinitionId = 10, value = "큼"))
        )
        assertTrue(provider.computeDataHealth(s).typeMismatchedValues.isEmpty())
    }
}
