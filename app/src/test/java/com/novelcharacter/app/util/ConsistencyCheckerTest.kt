package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.SemanticRole
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsistencyChecker]의 첫 시험 (B-251 ⓓ · 2026.08.19).
 *
 * ## 왜 이제야 서는가
 *
 * 이 검사기는 **시험이 0건이었다.** 순수 판정인데도 `ui.stats.StatsSnapshot`을 import 해
 * `tools/run_jvm_tests.sh`가 실을 수 없었고, 같은 이유로 `tools/probe_compile.sh`도
 * 타입 검사하지 못했다(오류 51건 — 전부 그 import 한 줄의 그림자). **즉 컴파일 증명이
 * CI뿐이었고 동작 증명은 아무 데도 없었다.** 스냅샷을 `util/`로 옮기니 둘 다 닿는다.
 *
 * ## 무엇을 고정하는가
 *
 * 이 검사기가 틀리는 방식은 둘이고 **둘 다 조용하다**:
 *  · **놓치면** 모순이 있는데 사용자가 영영 모른다(개발 의도 2번 '변수 제어'가 걸린다).
 *  · **오탐하면** 더 나쁘다 — *맞는 데이터를 고치라고 사용자에게 권한다.*
 * 그래서 양방향으로 잰다: 잡아야 할 것을 잡는가 · **잡지 말아야 할 것을 안 잡는가.**
 */
class ConsistencyCheckerTest {

    private val universeId = 7L
    private val novelId = 3L
    private val ageFieldId = 11L
    private val birthFieldId = 12L

    private fun roleField(id: Long, key: String, role: SemanticRole, universe: Long? = universeId) =
        FieldDefinition(
            id = id,
            universeId = universe,
            key = key,
            name = key,
            type = "NUMBER",
            config = """{"semanticRole":"${role.key}"}"""
        )

    private fun snapshot(
        characters: List<Character> = emptyList(),
        novels: List<Novel> = listOf(Novel(id = novelId, title = "본편", universeId = universeId, standardYear = 1000)),
        fieldDefinitions: List<FieldDefinition> = listOf(
            roleField(ageFieldId, "age", SemanticRole.AGE),
            roleField(birthFieldId, "birth_year", SemanticRole.BIRTH_YEAR)
        ),
        fieldValues: List<CharacterFieldValue> = emptyList(),
        stateChanges: List<CharacterStateChange> = emptyList(),
        events: List<TimelineEvent> = emptyList(),
        crossRefs: List<TimelineCharacterCrossRef> = emptyList()
    ) = StatsSnapshot(
        characters = characters,
        novels = novels,
        universes = listOf(Universe(id = universeId, name = "세계")),
        events = events,
        relationships = emptyList(),
        relationshipChanges = emptyList(),
        tags = emptyList(),
        nameBank = emptyList(),
        stateChanges = stateChanges,
        fieldDefinitions = fieldDefinitions,
        fieldValues = fieldValues,
        crossRefs = crossRefs
    )

    private fun character(id: Long, name: String = "이름$id", novel: Long? = novelId) =
        Character(id = id, name = name, novelId = novel)

    private fun ageAndBirth(charId: Long, age: String, birth: String) = listOf(
        CharacterFieldValue(characterId = charId, fieldDefinitionId = ageFieldId, value = age),
        CharacterFieldValue(characterId = charId, fieldDefinitionId = birthFieldId, value = birth)
    )

    // ── 나이 ↔ 출생연도 ↔ 표준연도 ────────────────────────────────────────────

    @Test
    fun `표준연도와 어긋난 나이를 잡고 바로잡을 값을 함께 준다`() {
        // 표준연도 1000 · 출생 980 → 기대 나이 20인데 25로 적혀 있다.
        val s = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980")
        )

        val found = ConsistencyChecker.check(s).ageMismatches

        assertEquals(1, found.size)
        val m = found.single()
        assertEquals(1L, m.characterId)
        assertEquals(25, m.inputAge)
        assertEquals(980, m.inputBirthYear)
        assertEquals(20, m.expectedAge)
        // 나이를 믿을 때의 출생연도 — 사용자가 고를 수 있게 둘 다 준다(자율성 우선).
        assertEquals(975, m.suggestedBirthYear)
    }

    @Test
    fun `맞는 데이터는 잡지 않는다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = "20", birth = "980")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `연동을 끈 캐릭터는 어긋나도 잡지 않는다`() {
        // 사용자가 의도적으로 끈 것이므로 모순이 아니다.
        val s = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980"),
            stateChanges = listOf(
                CharacterStateChange(
                    characterId = 1, year = 0,
                    fieldKey = StandardYearLink.KEY, newValue = StandardYearLink.NONE
                )
            )
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `연동을 명시적으로 켠 캐릭터는 종전대로 잡는다`() {
        // KEY 행이 있어도 값이 NONE이 아니면 연동이다 — 면제는 'none'에만 붙는다.
        val s = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980"),
            stateChanges = listOf(
                CharacterStateChange(
                    characterId = 1, year = 0,
                    fieldKey = StandardYearLink.KEY, newValue = StandardYearLink.AUTO
                )
            )
        )
        assertEquals(1, ConsistencyChecker.check(s).ageMismatches.size)
    }

    @Test
    fun `표준연도가 없는 작품은 판정하지 않는다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            novels = listOf(Novel(id = novelId, title = "본편", universeId = universeId, standardYear = null)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `역할 필드가 한쪽만 있으면 판정하지 않는다`() {
        // 출생연도 역할이 없으면 기대 나이를 낼 수 없다 — 없는 근거로 신고하지 않는다.
        val s = snapshot(
            characters = listOf(character(1)),
            fieldDefinitions = listOf(roleField(ageFieldId, "age", SemanticRole.AGE)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `값이 비었거나 숫자가 아니면 넘어간다`() {
        val s = snapshot(
            characters = listOf(character(1), character(2)),
            fieldValues = ageAndBirth(1, age = "", birth = "980") + ageAndBirth(2, age = "스물", birth = "980")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `값의 앞뒤 공백은 값의 일부가 아니다`() {
        // 엑셀 왕복에서 흔히 끼는 공백이다.
        //
        // ⚠️ **'맞는 값에 공백을 붙여도 안 걸린다'만 재면 아무것도 증명하지 못한다** —
        // trim을 없애도 그 값은 파싱에 실패해 `continue`로 빠지므로 결과가 똑같이 비어 있다
        // (2026.08.19 자기 재공격에서 실제로 그렇게 통과했다). **공백이 붙은 채로도 값이
        // 읽히는지**를 재려면 *걸려야 하는* 쪽을 봐야 한다.
        val mismatched = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = " 25 ", birth = " 980 ")
        )
        assertEquals(
            listOf(25 to 980),
            ConsistencyChecker.check(mismatched).ageMismatches.map { it.inputAge to it.inputBirthYear }
        )

        // 그 위에서 '맞는 값은 안 걸린다'가 뜻을 갖는다.
        val matched = snapshot(
            characters = listOf(character(1)),
            fieldValues = ageAndBirth(1, age = " 20 ", birth = " 980 ")
        )
        assertTrue(ConsistencyChecker.check(matched).ageMismatches.isEmpty())
    }

    @Test
    fun `다른 작품의 캐릭터를 그 작품 표준연도로 재지 않는다`() {
        // 작품별로 접어 도는 구조가 바뀌어도 이 경계는 유지돼야 한다(B-251 ⓓ 성능 수리의 안전망).
        val otherNovelId = 4L
        val s = snapshot(
            characters = listOf(character(1, novel = novelId), character(2, novel = otherNovelId)),
            novels = listOf(
                Novel(id = novelId, title = "본편", universeId = universeId, standardYear = 1000),
                Novel(id = otherNovelId, title = "외전", universeId = universeId, standardYear = 900)
            ),
            // 1번은 본편(1000)에서 맞고, 2번은 외전(900)에서 맞는다. 서로의 표준연도로 재면 둘 다 걸린다.
            fieldValues = ageAndBirth(1, age = "20", birth = "980") + ageAndBirth(2, age = "20", birth = "880")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `작품 미배정 캐릭터는 판정하지 않는다`() {
        val s = snapshot(
            characters = listOf(character(1, novel = null)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980")
        )
        assertTrue(ConsistencyChecker.check(s).ageMismatches.isEmpty())
    }

    @Test
    fun `세계관이 다르면 그 세계관의 역할 필드로 잰다`() {
        // 역할 필드는 세계관에 붙는다. 세계관별 memo가 서로를 덮어쓰지 않는지 본다.
        val otherUniverseId = 8L
        val otherNovelId = 4L
        val otherAgeFieldId = 21L
        val otherBirthFieldId = 22L
        val s = snapshot(
            characters = listOf(character(1, novel = novelId), character(2, novel = otherNovelId)),
            novels = listOf(
                Novel(id = novelId, title = "본편", universeId = universeId, standardYear = 1000),
                Novel(id = otherNovelId, title = "타세계", universeId = otherUniverseId, standardYear = 1000)
            ),
            fieldDefinitions = listOf(
                roleField(ageFieldId, "age", SemanticRole.AGE),
                roleField(birthFieldId, "birth_year", SemanticRole.BIRTH_YEAR),
                roleField(otherAgeFieldId, "age", SemanticRole.AGE, universe = otherUniverseId),
                roleField(otherBirthFieldId, "birth_year", SemanticRole.BIRTH_YEAR, universe = otherUniverseId)
            ),
            fieldValues = ageAndBirth(1, age = "20", birth = "980") + listOf(
                CharacterFieldValue(characterId = 2, fieldDefinitionId = otherAgeFieldId, value = "33"),
                CharacterFieldValue(characterId = 2, fieldDefinitionId = otherBirthFieldId, value = "980")
            )
        )

        val found = ConsistencyChecker.check(s).ageMismatches

        // 1번은 맞고 2번만 어긋난다 — 세계관을 섞었다면 둘 다 걸리거나 둘 다 빠진다.
        assertEquals(listOf(2L), found.map { it.characterId })
    }

    @Test
    fun `역할 필드가 없는 세계관이 있어도 다른 세계관 판정을 막지 않는다`() {
        // 세계관별 memo가 '풀어 본 적 있는가'를 값의 null로 판단하면 여기서 헛돌기만 하지만,
        // 그 memo가 세계관을 구분하지 못하면 이 배치에서 결과가 갈린다.
        val bareUniverseId = 9L
        val bareNovelId = 5L
        val s = snapshot(
            characters = listOf(character(1, novel = bareNovelId), character(2, novel = novelId)),
            novels = listOf(
                Novel(id = bareNovelId, title = "빈세계작", universeId = bareUniverseId, standardYear = 1000),
                Novel(id = novelId, title = "본편", universeId = universeId, standardYear = 1000)
            ),
            fieldValues = ageAndBirth(2, age = "25", birth = "980")
        )

        assertEquals(listOf(2L), ConsistencyChecker.check(s).ageMismatches.map { it.characterId })
    }

    // ── 사망 < 출생 ──────────────────────────────────────────────────────────

    @Test
    fun `사망연도가 출생연도보다 앞서면 잡는다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(
                CharacterStateChange(characterId = 1, year = 980, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "980"),
                CharacterStateChange(characterId = 1, year = 950, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "950")
            )
        )

        val found = ConsistencyChecker.check(s).deathBeforeBirth

        assertEquals(1, found.size)
        assertEquals(980, found.single().birthYear)
        assertEquals(950, found.single().deathYear)
    }

    @Test
    fun `같은 해에 나고 죽은 것은 모순이 아니다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(
                CharacterStateChange(characterId = 1, year = 980, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "980"),
                CharacterStateChange(characterId = 1, year = 980, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "980")
            )
        )
        assertTrue(ConsistencyChecker.check(s).deathBeforeBirth.isEmpty())
    }

    @Test
    fun `연도 미상 placeholder는 모순으로 신고하지 않는다`() {
        // year 0은 "연도 미상"일 수 있다 — 0 < 980을 모순으로 읽으면 전부 오탐이 된다.
        val s = snapshot(
            characters = listOf(character(1), character(2)),
            stateChanges = listOf(
                CharacterStateChange(characterId = 1, year = 0, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "0"),
                CharacterStateChange(characterId = 1, year = 980, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "980"),
                CharacterStateChange(characterId = 2, year = 980, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "980"),
                CharacterStateChange(characterId = 2, year = 0, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "0")
            )
        )
        assertTrue(ConsistencyChecker.check(s).deathBeforeBirth.isEmpty())
    }

    @Test
    fun `스냅샷에 없는 캐릭터의 상태변화는 건너뛴다`() {
        // 작품 필터로 좁힌 스냅샷에서는 상태변화만 남는 조합이 실제로 생긴다.
        val s = snapshot(
            characters = emptyList(),
            stateChanges = listOf(
                CharacterStateChange(characterId = 99, year = 980, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "980"),
                CharacterStateChange(characterId = 99, year = 950, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "950")
            )
        )
        assertTrue(ConsistencyChecker.check(s).deathBeforeBirth.isEmpty())
    }

    // ── 합계 ────────────────────────────────────────────────────────────────

    @Test
    fun `total은 두 갈래의 합이다`() {
        val s = snapshot(
            characters = listOf(character(1), character(2)),
            fieldValues = ageAndBirth(1, age = "25", birth = "980"),
            stateChanges = listOf(
                CharacterStateChange(characterId = 2, year = 980, fieldKey = CharacterStateChange.KEY_BIRTH, newValue = "980"),
                CharacterStateChange(characterId = 2, year = 950, fieldKey = CharacterStateChange.KEY_DEATH, newValue = "950")
            )
        )

        val r = ConsistencyChecker.check(s)

        assertEquals(1, r.ageMismatches.size)
        assertEquals(1, r.deathBeforeBirth.size)
        assertEquals(2, r.total)
    }

    @Test
    fun `빈 스냅샷은 조용하다`() {
        val r = ConsistencyChecker.check(snapshot(novels = emptyList()))
        assertEquals(0, r.total)
    }

    // ── 연표 연도 ↔ 캐릭터 출생·사망연도 ──────────────────────────────────────
    //
    // 같은 사실을 두 자리에 적어 둔 것이라 갈리면 한쪽이 틀렸는데, 앱이 그것을 말하는 자리가
    // 없었다(실측 2026.08.24 사용자 파일: 연표 한 행이 두 캐릭터의 탄생을 1303년이라 적고
    // 그 둘의 출생연도는 1301년이었다).

    private fun birthEvent(id: Long, year: Int, desc: String = "탄생") =
        TimelineEvent(id = id, year = year, description = desc, eventType = TimelineEvent.TYPE_BIRTH)

    private fun deathEvent(id: Long, year: Int, desc: String = "사망") =
        TimelineEvent(id = id, year = year, description = desc, eventType = TimelineEvent.TYPE_DEATH)

    private fun birthChange(charId: Long, year: Int) =
        CharacterStateChange(
            characterId = charId, year = year,
            fieldKey = CharacterStateChange.KEY_BIRTH, newValue = year.toString()
        )

    private fun deathChange(charId: Long, year: Int) =
        CharacterStateChange(
            characterId = charId, year = year,
            fieldKey = CharacterStateChange.KEY_DEATH, newValue = year.toString()
        )

    @Test
    fun `연표 탄생 연도가 캐릭터 출생연도와 다르면 잡는다`() {
        val s = snapshot(
            characters = listOf(character(1, "루스트라")),
            stateChanges = listOf(birthChange(1, 1301)),
            events = listOf(birthEvent(90, 1303, "루스트라 출생.")),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )

        val found = ConsistencyChecker.check(s).eventYearMismatches

        assertEquals(1, found.size)
        val m = found.single()
        assertEquals(1L, m.characterId)
        assertEquals("루스트라", m.characterName)
        assertEquals(90L, m.eventId)
        assertEquals(1303, m.eventYear)
        assertEquals(1301, m.characterYear)
        assertTrue(m.isBirth)
    }

    @Test
    fun `한 사건에 걸린 참가자마다 따로 잡는다`() {
        // 실측이 그 모양이었다 — 한 행이 두 캐릭터의 탄생을 함께 적고 있었다.
        val s = snapshot(
            characters = listOf(character(1, "루스트라"), character(2, "시카엘")),
            stateChanges = listOf(birthChange(1, 1301), birthChange(2, 1301)),
            events = listOf(birthEvent(90, 1303, "루스트라 출생. 시카엘 출생.")),
            crossRefs = listOf(
                TimelineCharacterCrossRef(eventId = 90, characterId = 1),
                TimelineCharacterCrossRef(eventId = 90, characterId = 2)
            )
        )

        assertEquals(2, ConsistencyChecker.check(s).eventYearMismatches.size)
    }

    @Test
    fun `사망 사건도 같은 규칙이다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(deathChange(1, 1400)),
            events = listOf(deathEvent(91, 1402)),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 91, characterId = 1))
        )

        val m = ConsistencyChecker.check(s).eventYearMismatches.single()
        assertEquals(false, m.isBirth)
        assertEquals(1402, m.eventYear)
        assertEquals(1400, m.characterYear)
    }

    @Test
    fun `연도가 같으면 조용하다`() {
        val s = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(birthChange(1, 1301)),
            events = listOf(birthEvent(90, 1301)),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )
        assertEquals(0, ConsistencyChecker.check(s).total)
    }

    @Test
    fun `연도 미상 0은 어느 쪽에 있어도 어긋남이 아니다`() {
        // 생일만 적어 둔 캐릭터의 `__birth`는 연도가 0이다 — 그것을 어긋남이라 부르면
        // **맞는 데이터를 고치라고 권하게 되고**, 그 거짓 고지가 진짜 고지를 묻는다.
        val charYearZero = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(birthChange(1, 0)),
            events = listOf(birthEvent(90, 1301)),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )
        assertEquals(0, ConsistencyChecker.check(charYearZero).total)

        val eventYearZero = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(birthChange(1, 1301)),
            events = listOf(birthEvent(90, 0)),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )
        assertEquals(0, ConsistencyChecker.check(eventYearZero).total)
    }

    @Test
    fun `일반 사건은 보지 않는다`() {
        // 시맨틱 유형이 아닌 사건의 연도는 캐릭터의 출생·사망과 아무 관계가 없다.
        val s = snapshot(
            characters = listOf(character(1)),
            stateChanges = listOf(birthChange(1, 1301)),
            events = listOf(TimelineEvent(id = 90, year = 1500, description = "즉위")),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )
        assertEquals(0, ConsistencyChecker.check(s).total)
    }

    @Test
    fun `이력이 없는 참가자는 어긋남이 아니다`() {
        // 연표에만 적힌 캐릭터다 — 견줄 짝이 없으면 말할 것도 없다.
        val s = snapshot(
            characters = listOf(character(1)),
            events = listOf(birthEvent(90, 1301)),
            crossRefs = listOf(TimelineCharacterCrossRef(eventId = 90, characterId = 1))
        )
        assertEquals(0, ConsistencyChecker.check(s).total)
    }

    // ── 연동 계약 자체 ───────────────────────────────────────────────────────

    @Test
    fun `연동 판정의 기본값은 켬이다`() {
        // 끈 적이 없으면(= 행이 없으면) 연동이다. 이 기본값이 뒤집히면 검사기가 통째로 조용해진다.
        assertTrue(StandardYearLink.isLinked(null))
        assertTrue(StandardYearLink.isLinked(StandardYearLink.AUTO))
        assertTrue(StandardYearLink.isUnlinked(StandardYearLink.NONE))
        assertTrue(StandardYearLink.isUnlinkedIn(
            listOf(CharacterStateChange(characterId = 1, year = 0, fieldKey = StandardYearLink.KEY, newValue = StandardYearLink.NONE))
        ))
        assertEquals(false, StandardYearLink.isUnlinkedIn(emptyList()))
        assertEquals(StandardYearLink.AUTO, StandardYearLink.valueFor(true))
        assertEquals(StandardYearLink.NONE, StandardYearLink.valueFor(false))
    }
}
