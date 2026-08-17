package com.novelcharacter.app.stats

import com.novelcharacter.app.data.model.Character
import com.novelcharacter.app.data.model.CharacterFieldValue
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.data.model.FieldStatsConfig
import com.novelcharacter.app.data.model.Novel
import com.novelcharacter.app.data.model.Universe
import com.novelcharacter.app.ui.stats.StatsDataProvider
import com.novelcharacter.app.ui.stats.StatsSnapshot
import com.novelcharacter.app.util.FieldValueMatchSpec
import com.novelcharacter.app.util.NumericBinning
import com.novelcharacter.app.util.ValueDistributions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **값 분포를 고른 수치 필드의 자동 구간 접기** 계약 (B-196 · 확정 15장 3번).
 *
 * 결함이었던 것: `getFieldValues`는 `binning.mode == "custom"`일 때만 구간 라벨을 돌려주므로,
 * 자동 구간 필드에 *값 분포*를 걸면 **값 하나마다 조각**이 됐다 — 키 600명이면 60종이 되고
 * 표시 상한 10에 잘려 *"기타 50종"*으로 접힌다. **분포를 보려던 사람이 분포를 못 본다.**
 *
 * 확정이 고른 수위와 이 시험이 각각 잠그는 것:
 * - **상한 안이면 그대로** — '값 분포'라는 선택의 뜻을 보존한다(자녀 수 0~3 같은 필드).
 * - **넘치면 접고 접었다고 말한다**(R-14).
 * - **구간 수 ≤ 표시 상한**(확정 조건 ⓒ) — 두 문턱이 갈리면 *잘리는데 안 접는* 구간이 생긴다.
 * - **스펙을 표시 계층까지 실어 나른다**(확정 조건 ⓑ) — 안 나르면 그 조각이 **다시 0명**이다.
 * - **수로 안 읽히는 값은 접지 않는다** — 접으면 그 값이 어디에도 없다(개발 의도 2번).
 */
class NumericDistributionFoldTest {

    private val provider = StatsDataProvider()
    private val uniA = 1L

    /** 값 분포 하나만 걸린 NUMBER 필드. [limit]이 표시 상한이자 접기 문턱이다. */
    private fun distField(limit: Int = 10, extra: String = "", type: String = "NUMBER") =
        FieldDefinition(
            id = 10, universeId = uniA, key = "height", name = "키", type = type,
            config = """{"stats":{"analyses":[{"type":"distribution","limit":$limit}]$extra}}""",
            entityType = FieldDefinition.ENTITY_CHARACTER
        )

    private fun snapshot(fd: FieldDefinition, rawValues: List<String>): StatsSnapshot =
        StatsSnapshot(
            characters = rawValues.indices.map { Character(id = it + 1L, name = "c${it + 1}", novelId = 1) },
            novels = listOf(Novel(id = 1, title = "A작품", universeId = uniA)),
            universes = listOf(Universe(id = uniA, name = "A")),
            events = emptyList(), relationships = emptyList(), relationshipChanges = emptyList(),
            tags = emptyList(), nameBank = emptyList(), stateChanges = emptyList(),
            fieldDefinitions = listOf(fd),
            fieldValues = rawValues.mapIndexed { i, v ->
                CharacterFieldValue(characterId = i + 1L, fieldDefinitionId = 10, value = v)
            },
            crossRefs = emptyList()
        )

    private fun result(fd: FieldDefinition, rawValues: List<String>) =
        provider.computeFieldInsights(snapshot(fd, rawValues))
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.DISTRIBUTION }

    /** 150·151·…·(150+n-1) — 고유 값 n종. */
    private fun distinctHeights(n: Int) = (0 until n).map { (150 + it).toString() }

    // ===== 상한 안이면 손대지 않는다 =====

    @Test
    fun `값 종류가 상한 안이면 값 그대로 둔다`() {
        // '값 분포'를 고른 뜻을 보존한다 — 자녀 수 0~3처럼 소수 값 필드가 이 부류다.
        val r = result(distField(limit = 10), distinctHeights(4))
        assertFalse("접을 이유가 없다", r.autoBinned)
        assertEquals(setOf("150", "151", "152", "153"), r.distributionData!!.keys)
        assertEquals("라벨이 곧 값이면 스펙을 실을 필요가 없다", null, r.distributionSpecs)
    }

    @Test
    fun `상한과 값 종류가 같으면 접지 않는다`() {
        // 문턱은 '넘칠 때'다 — 같을 때 접으면 상한이 감추는 장치가 된다.
        val r = result(distField(limit = 5), distinctHeights(5))
        assertFalse(r.autoBinned)
        assertEquals(5, r.distributionData!!.size)
    }

    @Test
    fun `상한이 없으면 접지 않는다`() {
        // limit 0 이하는 '상한 없음'이다(ValueDistributions.view의 규약) — 넘칠 일이 없다.
        val r = result(distField(limit = 0), distinctHeights(40))
        assertFalse(r.autoBinned)
        assertEquals(40, r.distributionData!!.size)
    }

    // ===== 넘치면 접는다 =====

    @Test
    fun `값 종류가 상한을 넘치면 자동 구간으로 접는다`() {
        val r = result(distField(limit = 10), distinctHeights(40))
        assertTrue("접어야 한다", r.autoBinned)
        assertEquals(
            "구간 수는 기본 구간 수를 따른다(상한보다 작으므로)",
            NumericBinning.DEFAULT_BIN_COUNT, r.distributionData!!.size
        )
        assertEquals("접기 전 종 수를 함께 실어 보낸다", 40, r.preFoldKinds)
    }

    @Test
    fun `접어도 합은 모집단이다`() {
        val r = result(distField(limit = 10), distinctHeights(40))
        assertEquals(40, r.distributionData!!.values.sum())
    }

    @Test
    fun `구간 수는 표시 상한을 넘지 않는다`() {
        // 확정 조건 ⓒ — 두 문턱이 갈리면 접은 결과가 **또 잘려** '잘리는데 안 접는' 구간이 생긴다.
        val r = result(distField(limit = 3), distinctHeights(40))
        assertTrue(r.autoBinned)
        assertEquals(3, r.distributionData!!.size)
        assertEquals(40, r.distributionData!!.values.sum())
    }

    @Test
    fun `구간 순서는 건수순이 아니라 구간 순이다`() {
        // 인접 구간이 흩어지면 '어디에 몰렸는가'를 읽을 수 없다 — 순서 자체가 정보다.
        // 아래로 갈수록 값이 몰리는 분포를 만들어, 건수순이면 순서가 뒤집히게 한다.
        val values = distinctHeights(20) + List(30) { "150" }
        val r = result(distField(limit = 10), values)
        val labels = r.distributionData!!.keys.toList()
        val expected = NumericBinning.autoBins(
            (0 until 20).map { (150 + it).toFloat() },
            binCount = NumericBinning.DEFAULT_BIN_COUNT
        ).map { it.label }
        assertEquals(expected, labels)
    }

    // ===== 스펙을 실어 나른다 (확정 조건 ⓑ — 안 나르면 다시 0명) =====

    @Test
    fun `접힌 구간 조각의 인원이 조각 수치와 일치한다`() {
        val fd = distField(limit = 10)
        val s = snapshot(fd, distinctHeights(40))
        val r = provider.computeFieldInsights(s)
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.DISTRIBUTION }

        val specs = r.distributionSpecs!!
        assertEquals("구간마다 스펙이 있어야 한다", r.distributionData!!.size, specs.size)

        var total = 0
        for ((label, count) in r.distributionData!!) {
            val listed = provider.getCharactersByFieldValue(s, listOf(10L), specs.getValue(label))!!
            assertEquals("[$label] 조각 수치와 드릴다운 인원이 같아야 한다", count, listed.size)
            total += listed.size
        }
        assertEquals("모두가 정확히 한 구간에 든다", 40, total)
    }

    @Test
    fun `구간 라벨로 조회하면 아무도 못 찾는다 — 스펙이 필요한 이유`() {
        // 이 사실을 남겨 둬야 위 시험이 무엇을 막는지 분명해진다. 라벨('160~168')은 계산
        // 결과라 저장값과 같을 수 없다 — 스펙을 안 실으면 그 조각이 **다시 0명**이다.
        val fd = distField(limit = 10)
        val s = snapshot(fd, distinctHeights(40))
        val label = result(fd, distinctHeights(40)).distributionData!!.keys.first()
        assertEquals(0, provider.getCharactersByFieldValue(s, listOf(10L), label)!!.size)
    }

    @Test
    fun `접힌 스펙은 그 구간에 든 값들의 집합이다`() {
        // 원문이 아니라 **이미 파싱된 통계 키**를 접으므로, 어느 키가 어느 구간에 들었는지를
        // 접는 쪽이 정확히 안다. 그 집합을 그대로 실으면 라벨·카테고리가 살아 있는 필드에서도
        // 조각 수치와 목록 인원이 어긋나지 않는다.
        val r = result(distField(limit = 10), distinctHeights(40))
        val all = r.distributionSpecs!!.values
            .filterIsInstance<FieldValueMatchSpec.Values>()
            .flatMap { it.values }
        assertEquals("40종이 빠짐없이 어느 구간엔가 실려 있어야 한다", 40, all.toSet().size)
        assertEquals("두 구간에 겹쳐 실리면 안 된다", 40, all.size)
    }

    // ===== 접지 않는 자리 =====

    @Test
    fun `사용자 구간 필드는 접지 않는다`() {
        // 이미 구간 라벨이라 접을 것이 없다 — 종류 수도 구간 수만큼이다.
        val extra = ""","binning":{"mode":"custom","ranges":["~160:작은","160~180:보통","180~:큰"]}"""
        val r = result(distField(limit = 2, extra = extra), distinctHeights(40))
        assertFalse(r.autoBinned)
        assertEquals(setOf("작은", "보통", "큰"), r.distributionData!!.keys)
    }

    @Test
    fun `순위는 접지 않는다`() {
        // 순위는 "어떤 값 하나가 가장 많은가"를 묻는 그림이다 — 구간으로 접으면 그 물음이 사라진다.
        val fd = FieldDefinition(
            id = 10, universeId = uniA, key = "height", name = "키", type = "NUMBER",
            config = """{"stats":{"analyses":[{"type":"ranking","limit":10}]}}""",
            entityType = FieldDefinition.ENTITY_CHARACTER
        )
        val r = provider.computeFieldInsights(snapshot(fd, distinctHeights(40)))
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.RANKING }
        assertFalse(r.autoBinned)
        assertEquals(40, r.distributionData!!.size)
    }

    @Test
    fun `수치가 아닌 타입은 접지 않는다`() {
        // 구간은 수치의 것이다. TEXT 필드의 값 40종은 값 40종이 맞다.
        val r = result(distField(limit = 10, type = "TEXT"), distinctHeights(40))
        assertFalse(r.autoBinned)
        assertEquals(40, r.distributionData!!.size)
    }

    @Test
    fun `값이 전부 같으면 접지 않는다`() {
        // 나눌 폭이 없으면 접어도 조각이 줄지 않는다 — 라벨만 바꿔 혼란을 더할 뿐이다.
        // (종류가 상한을 넘으려면 값이 여럿이어야 하므로, 여기서는 비수치를 섞어 종류를 늘린다.)
        val values = List(3) { "170" } + (0 until 12).map { "값$it" }
        val r = result(distField(limit = 10), values)
        assertFalse(r.autoBinned)
    }

    // ===== 수로 읽히지 않는 값은 남긴다 =====

    @Test
    fun `수로 읽히지 않는 값은 접히지 않고 그대로 남는다`() {
        // 접으면 그 값이 **어디에도 없다** — 조용한 유실이라 개발 의도 2번이 걸린다.
        val values = distinctHeights(20) + listOf("미상", "미상", "비공개")
        val r = result(distField(limit = 10), values)

        assertTrue(r.autoBinned)
        val dist = r.distributionData!!
        assertEquals("미상이 살아 있어야 한다", 2, dist["미상"])
        assertEquals("비공개도 살아 있어야 한다", 1, dist["비공개"])
        assertEquals("합은 여전히 모집단이다", 23, dist.values.sum())
    }

    @Test
    fun `남은 비수치 값도 눌러서 목록을 볼 수 있다`() {
        val fd = distField(limit = 10)
        val values = distinctHeights(20) + listOf("미상", "미상")
        val s = snapshot(fd, values)
        val r = provider.computeFieldInsights(s)
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.DISTRIBUTION }

        val listed = provider.getCharactersByFieldValue(
            s, listOf(10L), r.distributionSpecs!!.getValue("미상")
        )!!
        assertEquals(2, listed.size)
        assertTrue(listed.all { it.fieldValue == "미상" })
    }

    @Test
    fun `비수치 값이 구간 라벨과 같아도 그 구간의 인원이 사라지지 않는다`() {
        // **콜드 검토가 잡은 자리.** 구간 라벨은 `150~160` 꼴이고, 사용자가 수치 칸에 대략적인
        // 범위를 그대로 적는 것은 이 앱이 받아들이려는 입력의 전형이다(그 원문은 수로 안 읽혀
        // 비수치 갈래로 온다). 종전 한 줄(`counts[k] = v`)은 **그 구간의 인원을 개수 고지도 없이
        // 지웠다** — 합쳐야 조각 수치와 목록 인원이 맞는다.
        val fd = distField(limit = 10)
        val plain = distinctHeights(20)
        // 20종 150~169를 5등분하면 첫 구간 라벨이 `150~154`다 — 그 글자를 값으로 가진 캐릭터를 둘 넣는다.
        val s0 = snapshot(fd, plain)
        val firstLabel = provider.computeFieldInsights(s0)
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.DISTRIBUTION }
            .distributionData!!.keys.first()

        val s = snapshot(fd, plain + listOf(firstLabel, firstLabel))
        val r = provider.computeFieldInsights(s)
            .first { it.fieldDefinition.name == "키" }
            .analysisResults.first { it.entry.type == FieldStatsConfig.StatsType.DISTRIBUTION }

        assertEquals("합은 여전히 모집단이다", 22, r.distributionData!!.values.sum())
        val listed = provider.getCharactersByFieldValue(
            s, listOf(10L), r.distributionSpecs!!.getValue(firstLabel)
        )!!
        assertEquals(
            "겹친 조각의 인원이 조각 수치와 같아야 한다",
            r.distributionData!!.getValue(firstLabel), listed.size
        )
        assertTrue("그 라벨을 값으로 가진 캐릭터도 들어 있어야 한다", listed.any { it.fieldValue == firstLabel })
    }

    @Test
    fun `지배적인 비수치 값이 구간에 밀려 기타로 접히지 않는다`() {
        // **콜드 검토 둘째 바퀴가 잡은 자리 — 접기가 만든 퇴행이다.**
        // 실측(종전): 상한 3 · 수치 20종 · `미상` 300건이면 구간 셋(7·6·7명)만 보이고
        // **300건이 "기타 1종"으로 접혔다.** 접기 전에는 건수순 상위 셋에 `미상`이 들어 보였다.
        val values = distinctHeights(20) + List(300) { "미상" }
        val r = result(distField(limit = 3), values)
        assertTrue("접기는 여전히 일어난다", r.autoBinned)

        val view = ValueDistributions.view(r.distributionData!!, 3, preserveOrder = r.autoBinned)
        assertTrue(
            "지배적인 비수치 값이 표시 안에 있어야 한다",
            view.shown.any { it.label == "미상" && it.count == 300 }
        )
        assertFalse("그것이 '기타'로 접히면 안 된다", view.hiddenLabels.contains("미상"))
        assertEquals("비수치 자리를 뺐으므로 구간은 둘이다", 2, r.distributionData!!.size - 1)
    }

    @Test
    fun `비수치 키가 상한을 거의 다 쓰면 접지 않는다`() {
        // 구간이 둘도 안 되면 한 칸으로 뭉친 '분포'가 아무것도 말하지 않는다 —
        // 그 상태로 접는 것보다 종전 동작(건수순 상위 N + 기타)이 낫다.
        val values = distinctHeights(20) + (0 until 9).map { "값$it" }
        val r = result(distField(limit = 10), values)
        assertFalse("구간 하나로는 접지 않는다", r.autoBinned)
    }
}
