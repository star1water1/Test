package com.novelcharacter.app.share

import com.google.gson.Gson
import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.DuelAxis
import com.novelcharacter.app.data.model.DuelCounterVerdict
import com.novelcharacter.app.data.model.DuelMatch
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.GradeSystem
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 월드패키지 파서 (S-5).
 *
 * 내보내기와 같은 Gson 직렬화로 엔트리를 만들어 파싱한다 — 엔트리 이름·형식이 한쪽만
 * 바뀌면 여기서 깨진다(왕복 계약). R-2(구버전/손편집 JSON의 null 주입)의 방어도 고정한다.
 */
class WorldPackageParserTest {

    private val gson = Gson()

    private fun manifestJson(version: Int = WorldPackageEntries.CURRENT_SCHEMA_VERSION): String =
        gson.toJson(WorldPackageManifest(schemaVersion = version, universeName = "아르카나", includesImages = true))

    private fun universeJson(): String =
        gson.toJson(Universe(id = 7, name = "아르카나", code = "UNI7"))

    private fun baseEntries(version: Int = WorldPackageEntries.CURRENT_SCHEMA_VERSION): MutableMap<String, String> =
        mutableMapOf(
            WorldPackageEntries.MANIFEST to manifestJson(version),
            WorldPackageEntries.UNIVERSE to universeJson()
        )

    private fun success(entries: Map<String, String>): WorldPackageContents {
        val result = WorldPackageParser.parse(entries)
        assertTrue("expected Success but was $result", result is WorldPackageParseResult.Success)
        return (result as WorldPackageParseResult.Success).contents
    }

    @Test
    fun `전체 엔트리 왕복 - 내보내기 형식 그대로 읽힌다`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.NOVELS] = gson.toJson(listOf(Novel(id = 1, title = "1부", universeId = 7)))
        entries[WorldPackageEntries.CHARACTERS] = gson.toJson(listOf(Character(id = 11, name = "리안", novelId = 1)))
        entries[WorldPackageEntries.FIELD_DEFINITIONS] = gson.toJson(
            listOf(
                FieldDefinition(id = 21, universeId = 7, key = "hair", name = "머리색", type = "TEXT"),
                FieldDefinition(
                    id = 22, universeId = 7, key = "chapter", name = "회차", type = "TEXT",
                    entityType = FieldDefinition.ENTITY_EVENT
                ),
                FieldDefinition(
                    id = 23, universeId = 7, key = "form", name = "형식", type = "TEXT",
                    entityType = FieldDefinition.ENTITY_NOVEL
                )
            )
        )
        entries[WorldPackageEntries.FIELD_VALUES] = gson.toJson(
            listOf(CharacterFieldValue(id = 31, characterId = 11, fieldDefinitionId = 21, value = "은발"))
        )
        entries[WorldPackageEntries.TIMELINE_EVENTS] = gson.toJson(
            listOf(TimelineEvent(id = 41, year = 1200, description = "건국", universeId = 7))
        )
        entries[WorldPackageEntries.EVENT_FIELD_VALUES] = gson.toJson(
            listOf(EventFieldValue(id = 51, eventId = 41, fieldDefinitionId = 22, value = "3화"))
        )
        entries[WorldPackageEntries.NOVEL_FIELD_VALUES] = gson.toJson(
            listOf(NovelFieldValue(id = 52, novelId = 1, fieldDefinitionId = 23, value = "장편"))
        )
        entries[WorldPackageEntries.FIELD_VALUE_ENTRIES] = gson.toJson(
            listOf(FieldValueEntry(id = 61, fieldDefinitionId = 21, value = "은발", displayLabel = "은색 머리"))
        )
        entries[WorldPackageEntries.FACTIONS] = gson.toJson(
            listOf(Faction(id = 71, universeId = 7, name = "왕국", autoRelationType = "소속", code = "FC71"))
        )
        entries[WorldPackageEntries.FACTION_RELATIONSHIPS] = gson.toJson(
            listOf(
                PortableFactionRelationship(
                    factionCode1 = "FC71", factionName1 = "왕국",
                    factionCode2 = "FC72", factionName2 = "제국",
                    relationType = "적대", description = "", intensity = 5,
                    isBidirectional = true, displayOrder = 0
                )
            )
        )
        entries[WorldPackageEntries.NAME_BANK] = gson.toJson(
            listOf(NameBankEntry(id = 81, name = "리안", isUsed = true, usedByCharacterId = 11))
        )
        entries[WorldPackageEntries.GRADE_SYSTEMS] = gson.toJson(
            listOf(GradeSystem(id = 91, universeId = 7, name = "능력 등급",
                gradesJson = """{"C":0.5,"S":3}""", code = "GS91"))
        )

        entries[WorldPackageEntries.DUEL_AXES] = gson.toJson(
            listOf(DuelAxis(id = 101, universeId = 7, name = "강함", code = "AX101"))
        )
        entries[WorldPackageEntries.DUEL_MATCHES] = gson.toJson(
            listOf(
                DuelMatch(id = 111, axisId = 101, aCode = "CH11", bCode = "CH12", winnerCode = "CH11", code = "MT111")
            )
        )
        entries[WorldPackageEntries.DUEL_VERDICTS] = gson.toJson(
            listOf(
                DuelCounterVerdict(
                    id = 121, axisId = 101, kind = DuelCounterVerdict.KIND_COUNTER,
                    shape = DuelCounterVerdict.SHAPE_DIRECT,
                    memberCodes = """["CH11","CH12"]""", memberKey = "CH11|CH12", code = "VD121"
                )
            )
        )

        val contents = success(entries)
        assertEquals(WorldPackageEntries.CURRENT_SCHEMA_VERSION, contents.manifest.schemaVersion)
        assertEquals("AX101", contents.duelAxes.single().code)
        assertEquals("CH11", contents.duelMatches.single().winnerCode)
        assertEquals("VD121", contents.duelVerdicts.single().code)
        assertEquals("아르카나", contents.universe.name)
        assertEquals(7L, contents.universe.id)
        assertEquals(1, contents.novels.size)
        assertEquals(1, contents.characters.size)
        assertEquals(3, contents.fieldDefinitions.size)
        assertEquals("은발", contents.fieldValues.single().value)
        assertEquals("3화", contents.eventFieldValues.single().value)
        assertEquals("장편", contents.novelFieldValues.single().value)
        assertEquals("은색 머리", contents.fieldValueEntries.single().displayLabel)
        assertEquals("FC71", contents.factionRelationships.single().factionCode1)
        assertEquals(11L, contents.nameBank.single().usedByCharacterId)
        assertEquals("GS91", contents.gradeSystems.single().code)
        assertTrue(contents.droppedRows.isEmpty())
    }

    @Test
    fun `등급 체계 엔트리가 없는 v4 이하 패키지는 빈 목록이다 - 파손과 다르다`() {
        val contents = success(baseEntries(version = 4))
        assertTrue(contents.gradeSystems.isEmpty())
    }

    @Test
    fun `등급 체계 엔트리가 깨지면 그 이름으로 Malformed다`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.GRADE_SYSTEMS] = "[ not json"
        assertEquals(
            WorldPackageParseResult.Malformed(WorldPackageEntries.GRADE_SYSTEMS),
            WorldPackageParser.parse(entries)
        )
    }

    @Test
    fun `등급 체계의 필수 문자열이 null인 행은 걸러 세고 나머지는 살린다`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.GRADE_SYSTEMS] =
            """[{"id":1,"universeId":7,"name":"능력 등급","gradesJson":"{}","displayOrder":0,"createdAt":0,"code":"GS1"},
                {"id":2,"universeId":7,"displayOrder":0,"createdAt":0}]"""
        val contents = success(entries)
        assertEquals(1, contents.gradeSystems.size)
        assertEquals(1, contents.droppedRows[WorldPackageEntries.GRADE_SYSTEMS])
    }

    // ── v6 대결 (B-118) ──

    @Test
    fun `대결 엔트리가 없는 v5 이하 패키지는 빈 목록이다 - 파손과 다르다`() {
        // **이 갈래가 ⓐ 고지의 근거다** — 옛 패키지를 읽는 것이 실패가 아니라 "대결이 없는
        // 형식"이고, 임포터가 그 사실을 고지한다(확정 15번: ⓐ는 ⓑ의 폴백 경로에 편입된다).
        val contents = success(baseEntries(version = 5))
        assertTrue(contents.duelAxes.isEmpty())
        assertTrue(contents.duelMatches.isEmpty())
        assertTrue(contents.duelVerdicts.isEmpty())
    }

    @Test
    fun `대결 엔트리가 깨지면 그 이름으로 Malformed다`() {
        for (entry in listOf(
            WorldPackageEntries.DUEL_AXES,
            WorldPackageEntries.DUEL_MATCHES,
            WorldPackageEntries.DUEL_VERDICTS
        )) {
            val entries = baseEntries()
            entries[entry] = "[ not json"
            assertEquals(WorldPackageParseResult.Malformed(entry), WorldPackageParser.parse(entries))
        }
    }

    @Test
    fun `축의 후보 필터가 없어도 행이 살아남는다`() {
        // `candidateFiltersJson`은 nullable이 곧 *필터 없음*이다 — 필수로 검사하면
        // **필터를 안 쓰는 축이 전부 사라진다**(R-2 · 캐릭터의 대표 이미지 칸과 같은 부류).
        val entries = baseEntries()
        entries[WorldPackageEntries.DUEL_AXES] =
            """[{"id":1,"universeId":7,"name":"강함","targetType":"character","displayOrder":0,
                 "createdAt":0,"influenceFieldKeys":"[]","outcomeFieldKeys":"[]",
                 "profileFieldKeys":"[]","isBasisAxis":false,"code":"AX1"}]"""
        val contents = success(entries)
        assertEquals("AX1", contents.duelAxes.single().code)
        assertNull(contents.duelAxes.single().candidateFiltersJson)
        assertTrue(contents.droppedRows.isEmpty())
    }

    @Test
    fun `비긴 판은 승자 없이 그대로 읽힌다`() {
        // `winnerCode` null은 **무승부**다 — 필수로 검사하면 비긴 판이 통째로 사라진다.
        val entries = baseEntries()
        entries[WorldPackageEntries.DUEL_MATCHES] =
            """[{"id":1,"axisId":1,"aCode":"CH1","bCode":"CH2","decidedAt":0,"code":"MT1"}]"""
        val contents = success(entries)
        assertNull(contents.duelMatches.single().winnerCode)
        assertTrue(contents.droppedRows.isEmpty())
    }

    @Test
    fun `대결 행의 필수 문자열이 null이면 걸러 세고 나머지는 살린다`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.DUEL_MATCHES] =
            """[{"id":1,"axisId":1,"aCode":"CH1","bCode":"CH2","decidedAt":0,"code":"MT1"},
                {"id":2,"axisId":1,"bCode":"CH3","decidedAt":0,"code":"MT2"}]"""
        entries[WorldPackageEntries.DUEL_VERDICTS] =
            """[{"id":1,"axisId":1,"kind":"counter","shape":"direct",
                 "memberCodes":"[\"CH1\",\"CH2\"]","memberKey":"CH1|CH2","decidedAt":0,"code":"VD1"},
                {"id":2,"axisId":1,"kind":"counter","decidedAt":0,"code":"VD2"}]"""
        val contents = success(entries)
        assertEquals(1, contents.duelMatches.size)
        assertEquals(1, contents.droppedRows[WorldPackageEntries.DUEL_MATCHES])
        assertEquals(1, contents.duelVerdicts.size)
        assertEquals(1, contents.droppedRows[WorldPackageEntries.DUEL_VERDICTS])
    }

    @Test
    fun `manifest가 없으면 패키지가 아니다`() {
        val result = WorldPackageParser.parse(mapOf(WorldPackageEntries.UNIVERSE to universeJson()))
        assertTrue(result is WorldPackageParseResult.NotAPackage)
    }

    @Test
    fun `manifest JSON이 깨지면 Malformed`() {
        val result = WorldPackageParser.parse(
            mapOf(WorldPackageEntries.MANIFEST to "{ not json", WorldPackageEntries.UNIVERSE to universeJson())
        )
        assertEquals(
            WorldPackageParseResult.Malformed(WorldPackageEntries.MANIFEST),
            result
        )
    }

    @Test
    fun `미래 버전은 거부한다 - 절반만 읽는 것보다 낫다`() {
        val result = WorldPackageParser.parse(baseEntries(version = 99))
        assertEquals(
            WorldPackageParseResult.UnsupportedVersion(99, WorldPackageEntries.CURRENT_SCHEMA_VERSION),
            result
        )
    }

    @Test
    fun `schemaVersion 누락 - Gson 기본 0 - 은 Malformed`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.MANIFEST] = """{"universeName":"아르카나","includesImages":false}"""
        val result = WorldPackageParser.parse(entries)
        assertEquals(WorldPackageParseResult.Malformed(WorldPackageEntries.MANIFEST), result)
    }

    @Test
    fun `universe 엔트리가 없으면 MissingEntry`() {
        val result = WorldPackageParser.parse(mapOf(WorldPackageEntries.MANIFEST to manifestJson()))
        assertEquals(
            WorldPackageParseResult.MissingEntry(WorldPackageEntries.UNIVERSE),
            result
        )
    }

    @Test
    fun `universe의 필수 필드가 null이면 Malformed - R-2 null 주입`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.UNIVERSE] = """{"id":7,"code":"UNI7"}"""
        val result = WorldPackageParser.parse(entries)
        assertEquals(WorldPackageParseResult.Malformed(WorldPackageEntries.UNIVERSE), result)
    }

    @Test
    fun `구버전 v1 - 없는 엔트리는 빈 목록이다 - 파손과 다르다`() {
        val contents = success(baseEntries(version = 1))
        assertTrue(contents.novels.isEmpty())
        assertTrue(contents.factionRelationships.isEmpty())
        assertTrue(contents.eventFieldValues.isEmpty())
        assertTrue(contents.fieldValueEntries.isEmpty())
        // v4에서 생긴 엔트리도 구버전 파일에서는 '없음'이지 파손이 아니다(확-3)
        assertTrue(contents.novelFieldValues.isEmpty())
        assertTrue(contents.droppedRows.isEmpty())
    }

    @Test
    fun `작품 필드값 엔트리가 깨지면 그 이름으로 Malformed다`() {
        // 새 엔트리도 기존 것과 같은 계약을 따라야 한다 — '없음'과 '파손'은 다르다.
        val entries = baseEntries()
        entries[WorldPackageEntries.NOVEL_FIELD_VALUES] = "[{ broken"
        val result = WorldPackageParser.parse(entries)
        assertTrue(result is WorldPackageParseResult.Malformed)
        assertEquals(
            WorldPackageEntries.NOVEL_FIELD_VALUES,
            (result as WorldPackageParseResult.Malformed).entryName
        )
    }

    @Test
    fun `작품 필드값의 필수 문자열이 null이면 그 행만 걸러 센다`() {
        // R-2: Gson은 JSON에 키가 없으면 non-null 선언에도 null을 주입한다.
        val entries = baseEntries()
        entries[WorldPackageEntries.NOVEL_FIELD_VALUES] =
            """[{"id":1,"novelId":1,"fieldDefinitionId":2},{"id":2,"novelId":1,"fieldDefinitionId":3,"value":"장편"}]"""
        val contents = success(entries)
        assertEquals(1, contents.novelFieldValues.size)
        assertEquals(1, contents.droppedRows[WorldPackageEntries.NOVEL_FIELD_VALUES])
    }

    @Test
    fun `목록 엔트리 JSON이 깨지면 그 엔트리 이름으로 Malformed`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.CHARACTERS] = "[{ broken"
        val result = WorldPackageParser.parse(entries)
        assertEquals(WorldPackageParseResult.Malformed(WorldPackageEntries.CHARACTERS), result)
    }

    @Test
    fun `필수 문자열이 null인 행은 걸러서 세고 나머지는 살린다`() {
        val entries = baseEntries()
        // 두 번째 행은 name이 없다(손편집) — 통째 실패가 아니라 행 단위 제외 + 계수
        entries[WorldPackageEntries.CHARACTERS] =
            """[${gson.toJson(Character(id = 11, name = "리안"))}, {"id":12}, null]"""
        val contents = success(entries)
        assertEquals(1, contents.characters.size)
        assertEquals("리안", contents.characters.single().name)
        assertEquals(mapOf(WorldPackageEntries.CHARACTERS to 2), contents.droppedRows)
    }

    @Test
    fun `code가 null인 행은 버리지 않는다 - 재발급은 임포터의 몫`() {
        val entries = baseEntries()
        entries[WorldPackageEntries.CHARACTERS] = """[{"id":11,"name":"리안","firstName":"",
            "lastName":"","anotherName":"","imagePaths":"[]","memo":""}]"""
        val contents = success(entries)
        assertEquals(1, contents.characters.size)
        @Suppress("SENSELESS_COMPARISON")
        assertNull(contents.characters.single().code as String?)
    }
}
