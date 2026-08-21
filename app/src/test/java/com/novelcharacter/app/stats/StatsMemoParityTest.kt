package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.CharacterRelationship
import com.novelcharacter.app.data.model.CharacterStateChange
import com.novelcharacter.app.data.model.CharacterTag
import com.novelcharacter.app.data.model.EventFieldValue
import com.novelcharacter.app.data.model.Faction
import com.novelcharacter.app.data.model.FactionMembership
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldType
import com.novelcharacter.app.data.model.FieldValueEntry
import com.novelcharacter.app.data.model.NameBankEntry
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.NovelFieldValue
import com.novelcharacter.app.data.model.TimelineCharacterCrossRef
import com.novelcharacter.app.data.model.TimelineEvent
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.util.StatsSnapshot
import com.novelcharacter.app.util.FormulaDisplay
import com.novelcharacter.app.util.FormulaEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.novelcharacter.app.util.stringOr

/**
 * 스냅샷 단위 메모이즈(perSnapshot)의 동작 무변경 대조 (S6 2차).
 *
 * StatsDataProvider는 통계 화면 한 번 적재에서 [augmentedCharacterValues]를 다섯 번,
 * [filledCharacterDefIds]를 다섯 번, [computeCharacterComplexities]를 두 번, 사건/작품
 * 계산값을 여러 번 — 전부 **같은 불변 스냅샷으로** — 다시 지었다. 그 겹을 스냅샷 단위
 * 캐시로 걷었고, 이 시험이 잠그는 것은 셋이다:
 *
 * 1. **옛 구현과 같은 답** — 캐시 도입 전 조립 로직을 이 하네스가 그대로 들고 있다
 *    ([legacyAugmented]). `ExportWorkbookParityTest`·`GraphForceLayoutTest`가 걷어낸 구현을
 *    들고 대조하는 그 규약이다 — 지우면 "고쳐도 답이 그대로다"를 다음 사람이 확인할 길이 없다.
 * 2. **공유 사본 계약** — 캐시가 돌려주는 값은 여러 계산이 나눠 읽는다. 어느 호출부든
 *    변조하면 같은 스냅샷의 다른 계산이 오염된 것을 보므로, 모든 공개 계산을 돌린 뒤
 *    캐시 내용이 새로 지은 것과 같은지를 대조한다(변조 즉시 이 시험이 깨진다).
 * 3. **동시성** — 통계 적재는 계산 10개를 async로 동시에 돌린다(StatsViewModel).
 *    같은 스냅샷의 동시 호출이 전부 콜드 기준과 같은 답을 내는지 실제 스레드로 확인한다.
 */
class StatsMemoParityTest {

    private val uniA = 1L
    private val uniB = 2L

    private fun charField(
        id: Long, key: String, name: String, universeId: Long = uniA,
        type: String = "TEXT", config: String = "{}",
        entityType: String = FieldDefinition.ENTITY_CHARACTER
    ) = FieldDefinition(
        id = id, universeId = universeId, key = key, name = name, type = type, config = config,
        entityType = entityType
    )

    /**
     * 모든 축이 실린 스냅샷 — 저장 값 + 수식(캐릭터·사건·작품) + 복수 텍스트 + 라이브러리 +
     * 관계·사건·상태변화·태그·세력·이름은행. 캐시가 값을 섞으면 어느 축에서든 이 시험이 갈린다.
     * [salt]로 값이 다른 스냅샷을 만들 수 있다(캐시 누출 대조용).
     */
    private fun richSnapshot(salt: String = ""): StatsSnapshot {
        val defs = listOf(
            charField(10, "gender", "성별", type = "SELECT"),
            charField(11, "traits", "특성", type = "MULTI_TEXT"),
            charField(12, "power", "힘", type = "NUMBER"),
            charField(13, "power2", "힘x2", type = "CALCULATED", config = """{"formula":"field('power') * 2"}"""),
            charField(14, "home", "거주지", universeId = uniB),
            charField(20, "e_level", "위험도", type = "NUMBER", entityType = FieldDefinition.ENTITY_EVENT),
            charField(21, "e_level2", "위험도x2", type = "CALCULATED",
                config = """{"formula":"field('e_level') * 2"}""", entityType = FieldDefinition.ENTITY_EVENT),
            charField(30, "n_scale", "규모", type = "NUMBER", entityType = FieldDefinition.ENTITY_NOVEL),
            charField(31, "n_scale2", "규모x2", type = "CALCULATED",
                config = """{"formula":"field('n_scale') * 2"}""", entityType = FieldDefinition.ENTITY_NOVEL)
        )
        val chars = (1L..6L).map {
            Character(id = it, name = "c$it", novelId = if (it <= 4L) 1L else 2L, updatedAt = 1_700_000_000_000L)
        }
        val values = listOf(
            CharacterFieldValue(id = 1, characterId = 1, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 2, characterId = 2, fieldDefinitionId = 10, value = "여$salt"),
            CharacterFieldValue(id = 3, characterId = 3, fieldDefinitionId = 10, value = "남$salt"),
            CharacterFieldValue(id = 4, characterId = 1, fieldDefinitionId = 11, value = "용감, 신중"),
            CharacterFieldValue(id = 5, characterId = 2, fieldDefinitionId = 11, value = "용감"),
            CharacterFieldValue(id = 6, characterId = 1, fieldDefinitionId = 12, value = "10"),
            CharacterFieldValue(id = 7, characterId = 2, fieldDefinitionId = 12, value = "20"),
            CharacterFieldValue(id = 8, characterId = 3, fieldDefinitionId = 12, value = "31"),
            CharacterFieldValue(id = 9, characterId = 5, fieldDefinitionId = 14, value = "수도$salt"),
            CharacterFieldValue(id = 10, characterId = 6, fieldDefinitionId = 14, value = "변경"),
            CharacterFieldValue(id = 11, characterId = 4, fieldDefinitionId = 10, value = "  ")  // 공백 — 조립에서 걸러진다
        )
        val entries = listOf(
            FieldValueEntry(id = 1, fieldDefinitionId = 10, value = "남$salt", displayLabel = "남성$salt",
                aliasesJson = "[]", category = "기본", isHidden = false,
                source = FieldValueEntry.SOURCE_AUTO, usageCount = 2)
        )
        val events = listOf(
            TimelineEvent(id = 1, year = 1000, month = null, day = null, calendarType = "천개력",
                description = "사건1", eventType = "", universeId = uniA, displayOrder = 1,
                isTemporary = false, code = null),
            TimelineEvent(id = 2, year = 1010, month = null, day = null, calendarType = "천개력",
                description = "사건2", eventType = "", universeId = uniA, displayOrder = 2,
                isTemporary = false, code = null)
        )
        return StatsSnapshot(
            characters = chars,
            novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA), Novel(id = 2, title = "B작품", universeId = uniB)),
            universes = listOf(Universe(id = uniA, name = "A"), Universe(id = uniB, name = "B")),
            events = events,
            relationships = listOf(
                CharacterRelationship(id = 1, characterId1 = 1, characterId2 = 2, relationshipType = "친구",
                    description = "", intensity = 5, isBidirectional = true, displayOrder = 1, code = null)
            ),
            relationshipChanges = emptyList(),
            tags = listOf(CharacterTag(id = 1, characterId = 1, tag = "주역")),
            nameBank = listOf(NameBankEntry(id = 1, name = "은행이름", gender = "여", origin = "",
                notes = "", isUsed = true, usedByCharacterId = 1)),
            stateChanges = listOf(
                CharacterStateChange(id = 1, characterId = 1, year = 1005, month = null, day = null,
                    fieldKey = "gender", newValue = "남", description = "", code = null)
            ),
            fieldDefinitions = defs.filter { it.entityType == FieldDefinition.ENTITY_CHARACTER },
            fieldValues = values,
            crossRefs = listOf(TimelineCharacterCrossRef(1, 1), TimelineCharacterCrossRef(2, 2)),
            factions = listOf(Faction(id = 1, universeId = uniA, name = "세력1", description = "",
                color = "#2196F3", autoRelationType = "", autoRelationIntensity = 5, displayOrder = 1)),
            factionMemberships = listOf(FactionMembership(id = 1, factionId = 1, characterId = 1,
                joinYear = null, leaveYear = null, leaveType = null)),
            eventFieldDefinitions = defs.filter { it.entityType == FieldDefinition.ENTITY_EVENT },
            eventFieldValues = listOf(
                EventFieldValue(id = 1, eventId = 1, fieldDefinitionId = 20, value = "7"),
                EventFieldValue(id = 2, eventId = 2, fieldDefinitionId = 20, value = "3")
            ),
            novelFieldDefinitions = defs.filter { it.entityType == FieldDefinition.ENTITY_NOVEL },
            novelFieldValues = listOf(NovelFieldValue(id = 1, novelId = 1, fieldDefinitionId = 30, value = "5")),
            valueEntries = entries
        )
    }

    // ── 리플렉션 헬퍼 — private 메모이즈 자리를 직접 본다(선례: RestoreLossCountsTest) ──

    private fun invokeHelper(p: StatsDataProvider, name: String, s: StatsSnapshot): Any? {
        val m = StatsDataProvider::class.java.getDeclaredMethod(name, StatsSnapshot::class.java)
        m.isAccessible = true
        return m.invoke(p, s)
    }

    @Suppress("UNCHECKED_CAST")
    private fun augmentedOf(p: StatsDataProvider, s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> =
        invokeHelper(p, "augmentedCharacterValues", s) as Map<Long, List<CharacterFieldValue>>

    /**
     * 캐시 도입 **전**의 [augmentedCharacterValues] 조립 로직 — 저장 값 그룹화 + CALCULATED
     * 전 캐릭터 평가·서식. 걷어낸 구현을 하네스가 그대로 드는 것이 이 시험의 요점이다.
     */
    private fun legacyAugmented(s: StatsSnapshot): Map<Long, List<CharacterFieldValue>> {
        val byFieldDef = s.fieldValues.filter { it.value.isNotBlank() }
            .groupByTo(HashMap()) { it.fieldDefinitionId }
        val novelMap = s.novels.associateBy { it.id }
        val fieldDefByUniverse = s.fieldDefinitions.groupBy { it.universeId }
        val allFieldDefById = s.fieldDefinitions.associateBy { it.id }
        val charFieldValues = s.fieldValues.groupBy { it.characterId }
        for (char in s.characters) {
            val novel = char.novelId?.let { novelMap[it] } ?: continue
            val universeFields = fieldDefByUniverse[novel.universeId] ?: continue
            val calcFds = universeFields.filter { it.fieldType == FieldType.CALCULATED }
            if (calcFds.isEmpty()) continue
            val fieldKeyValues = mutableMapOf<String, String>()
            for (fv in charFieldValues[char.id] ?: emptyList()) {
                val fDef = allFieldDefById[fv.fieldDefinitionId] ?: continue
                fieldKeyValues[fDef.key] = fv.value
            }
            val evaluator = FormulaEvaluator(fieldKeyValues, universeFields)
            for (fd in calcFds) {
                val formula = try {
                    org.json.JSONObject(fd.config).stringOr("formula", "")
                } catch (_: Exception) { "" }
                if (formula.isBlank()) continue
                try {
                    val v = evaluator.evaluate(formula)
                    if (!v.isNaN() && !v.isInfinite()) {
                        val text = FormulaDisplay.format(v)
                        if (text.isNotBlank()) {
                            byFieldDef.getOrPut(fd.id) { mutableListOf() }.add(
                                CharacterFieldValue(characterId = char.id, fieldDefinitionId = fd.id, value = text)
                            )
                        }
                    }
                } catch (_: Exception) { /* 옛 구현 그대로 — 평가 실패 필드 제외 */ }
            }
        }
        return byFieldDef
    }

    // ===== 1. 옛 구현 대조 =====

    @Test
    fun `합성 결과가 캐시 도입 전 로직과 같다`() {
        val s = richSnapshot()
        assertEquals(legacyAugmented(s), augmentedOf(StatsDataProvider(), s))
    }

    @Test
    fun `같은 스냅샷 두 번째 호출은 같은 객체다 - 메모이즈 증명`() {
        val p = StatsDataProvider()
        val s = richSnapshot()
        assertSame(augmentedOf(p, s), augmentedOf(p, s))
        assertSame(invokeHelper(p, "filledCharacterDefIds", s), invokeHelper(p, "filledCharacterDefIds", s))
        assertSame(invokeHelper(p, "computeCharacterComplexities", s), invokeHelper(p, "computeCharacterComplexities", s))
        assertSame(invokeHelper(p, "computeAllEventCalculatedValues", s), invokeHelper(p, "computeAllEventCalculatedValues", s))
        assertSame(invokeHelper(p, "computeAllNovelCalculatedValues", s), invokeHelper(p, "computeAllNovelCalculatedValues", s))
    }

    @Test
    fun `다른 스냅샷은 서로의 캐시를 보지 않는다`() {
        val p = StatsDataProvider()
        val a = richSnapshot()
        val b = richSnapshot(salt = "B")
        val fromA = augmentedOf(p, a)
        val fromB = augmentedOf(p, b)
        assertEquals(legacyAugmented(a), fromA)
        assertEquals(legacyAugmented(b), fromB)
        // 값이 실제로 갈리는 필드(성별)에서 두 결과가 다른 것도 확인 — 같으면 위 두 줄이 헛돈다
        assertTrue(fromA[10L]!!.none { it.value.endsWith("B") })
        assertTrue(fromB[10L]!!.all { it.value == "남B" || it.value == "여B" })
    }

    // ===== 2. 공개 계산 결과가 캐시 유무와 무관하게 같다 =====

    /** 10계산 전부를 결과 목록으로 — 콜드(새 프로바이더)와 웜(한 프로바이더 반복)을 견준다. */
    private fun allResults(p: StatsDataProvider, s: StatsSnapshot): List<Any?> = listOf(
        p.computeSummary(s), p.computeFieldInsights(s), p.computeCharacterStats(s),
        p.computeEventStats(s), p.computeRelationshipStats(s), p.computeNameBankStats(s),
        p.computeDataHealth(s), p.computeFieldAnalysis(s), p.detectPatterns(s), p.computeFactionStats(s)
    )

    @Test
    fun `공개 계산 10종의 결과가 콜드와 웜에서 같다`() {
        val s = richSnapshot()
        val cold = allResults(StatsDataProvider(), s)   // 캐시가 전부 비어 있는 첫 실행
        val shared = StatsDataProvider()
        allResults(shared, s)                            // 캐시를 채우는 1회전
        val warm = allResults(shared, s)                 // 전부 캐시 위에서 도는 2회전
        assertEquals(cold, warm)
    }

    // ===== 3. 공유 사본 계약 — 호출부 변조 감지 =====

    @Test
    fun `모든 계산을 돌린 뒤에도 캐시 내용이 새로 지은 것과 같다`() {
        val p = StatsDataProvider()
        val s = richSnapshot()
        allResults(p, s)
        allResults(p, s)
        // 캐시된 사본이 변조됐다면 새로 지은 기준과 갈린다 — 미래의 어떤 호출부가
        // 공유 목록에 add/removeIf를 하는 순간 이 대조가 그 자리를 잡는다.
        assertEquals(legacyAugmented(s), augmentedOf(p, s))
    }

    // ===== 4. 동시성 — StatsViewModel의 async 10계산과 같은 모양 =====

    @Test
    fun `같은 스냅샷의 동시 호출이 전부 콜드 기준과 같다`() {
        val s = richSnapshot()
        val reference = allResults(StatsDataProvider(), s)
        val p = StatsDataProvider()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val results = java.util.concurrent.ConcurrentHashMap<Int, List<Any?>>()
        val threads = (0 until 8).map { i ->
            Thread {
                try { results[i] = allResults(p, s) } catch (t: Throwable) { errors.add(t) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30_000) }
        assertNull(errors.peek())
        assertEquals(8, results.size)
        results.values.forEach { assertEquals(reference, it) }
    }

    // ===== 5. 사건·작품 계산값 축 =====

    @Test
    fun `사건과 작품 수식이 인사이트에 실리고 반복 호출에도 같다`() {
        val p = StatsDataProvider()
        val s = richSnapshot()
        val first = p.computeFieldInsights(s)
        val second = p.computeFieldInsights(s)
        assertEquals(first, second)
        // 수식 축 셋이 전부 실제로 계산됐는지 — 캐시가 emptyMap을 잘못 재사용하면 여기서 빈다
        assertTrue(first.any { it.fieldDefinition.name == "힘x2" })
        assertTrue(first.any { it.fieldDefinition.name == "위험도x2" })
        assertTrue(first.any { it.fieldDefinition.name == "규모x2" })
    }

    // ===== 6. 캐시 상한 — 무한히 자라지 않는다 =====

    @Test
    fun `서로 다른 스냅샷을 계속 넣어도 캐시가 상한 안에 머문다`() {
        val p = StatsDataProvider()
        repeat(7) { augmentedOf(p, richSnapshot(salt = "s$it")) }
        val f = StatsDataProvider::class.java.getDeclaredField("augmentedCache")
        f.isAccessible = true
        val size = (f.get(p) as java.util.concurrent.ConcurrentHashMap<*, *>).size
        // MAX_CACHED_SNAPSHOTS(4)를 넘으면 비우고 새로 담으므로 5를 넘을 수 없다
        assertTrue("캐시가 $size 칸 — 상한 규칙이 죽었다", size <= 5)
    }
}
