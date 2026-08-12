package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.GenerationPreset
import com.novelcharacter.app.data.model.FieldConfigTransfer
import com.novelcharacter.app.data.model.FieldDefinition
import com.novelcharacter.app.util.BodyGenerator
import com.novelcharacter.app.util.BodySilhouetteSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 🎲 생성 축·프리셋이 **세계관 단위 설정**이 된 계약 (B-93 · 확정 7번 ㄱ1·ㄴ1).
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * ① **고른 축과 돌아오는 요약이 같다 — 축이 누구 것이든.** 종전에는 경계가
 *    `BodySilhouetteSpec`의 상수였고 라벨은 `axisSummary` 안의 문자열이라, 축을 여는 순간
 *    *생성기가 겨누는 자리*와 *요약이 가르는 자리*가 두 벌이 될 자리였다. 이제 둘 다
 *    [BodyAnalysisConfig.GenerationPreset]의 같은 배열을 읽는다 — 그 사실을
 *    **사용자가 정의한 축**으로 굴려서 잰다(기본 축만 재면 두 벌이어도 통과한다).
 *
 * ② **왕복에서 잃지 않는다.** 필드 config JSON은 엑셀 '설정(JSON)' 칸으로 나갔다 들어오므로,
 *    담기는 것과 읽히는 것이 갈리면 사용자가 정한 축이 조용히 기본값으로 되돌아간다.
 *
 * ③ **사람이 손으로 고친 값도 도는 형태로 교정된다.** 그 칸은 외부에서 편집되는 자리다 —
 *    거부가 아니라 수용·교정이 이 앱의 처분이다(개발 의도 4번).
 */
class BodyGenerationConfigTest {

    /**
     * 사용자가 다시 정의한 축 한 벌 — **이름도 경계도 기본과 다르다.**
     * 현대 실사 지향 세계관을 상정했다(B-93 등재가 든 예 — 장르가 다르면 분포가 다르다).
     */
    private val custom = GenerationPreset(
        heightOptions = listOf(
            BodyAnalysisConfig.HeightOption("단신", 150.0, 3.0),
            BodyAnalysisConfig.HeightOption("평균", 160.0, 3.0),
            BodyAnalysisConfig.HeightOption("장신", 170.0, 3.0),
            BodyAnalysisConfig.HeightOption("초장신", 178.0, 3.0)
        ),
        torsoOptions = listOf(
            BodyAnalysisConfig.TorsoOption("가는허리", .330, 17.5, maxRatio = .345),
            BodyAnalysisConfig.TorsoOption("보통허리", .370, 20.0, maxRatio = .395),
            BodyAnalysisConfig.TorsoOption("두툼", .430, 24.0, maxRatio = 1.0)
        ),
        bustOptions = listOf(
            BodyAnalysisConfig.BustOption("작음", 6.0),
            BodyAnalysisConfig.BustOption("보통", 11.0),
            BodyAnalysisConfig.BustOption("큼", 16.0),
            BodyAnalysisConfig.BustOption("아주 큼", 21.0)
        ),
        hipOptions = listOf(
            BodyAnalysisConfig.HipOption("납작", 18.0, maxDiff = 21.0),
            BodyAnalysisConfig.HipOption("보통힙", 25.0, maxDiff = 29.0),
            BodyAnalysisConfig.HipOption("풍성", 33.0, maxDiff = 99.0)
        ),
        bodyPresets = listOf(
            BodyAnalysisConfig.BodyPreset("마름", 0, 0, 0),
            BodyAnalysisConfig.BodyPreset("평범", 1, 1, 1),
            BodyAnalysisConfig.BodyPreset("굴곡", 0, 2, 2),
            BodyAnalysisConfig.BodyPreset("당당", 2, 3, 2)
        )
    )

    private fun configOf(gen: GenerationPreset) = BodyAnalysisConfig.DEFAULT.copy(generation = gen)

    private fun rolls(
        gen: GenerationPreset, torso: Int = 1, bust: Int = 1, hip: Int = 1
    ): List<BodyGenerator.GeneratedBody> = buildList {
        for (h in gen.heightOptions.indices) for (seed in 0 until 30) {
            add(BodyGenerator.generate(gen, h, torso, bust, hip, random = Random(seed * 17 + h)))
        }
    }

    private fun measures(b: BodyGenerator.GeneratedBody) =
        BodySilhouetteSpec.Measures(b.height, BodySilhouetteSpec.BASE.shoulder, b.bust, b.waist, b.hip)

    // ── ① 고른 축 = 돌아오는 요약 (축이 사용자 것이어도) ────────────────────

    @Test
    fun `사용자가 정의한 몸통 축도 요약이 같은 이름으로 답한다`() {
        val config = configOf(custom)
        for ((i, option) in custom.torsoOptions.withIndex()) {
            for (body in rolls(custom, torso = i)) {
                assertEquals(
                    "키 ${body.height} 허리 ${body.waist}",
                    option.label, BodySilhouetteSpec.axisSummary(measures(body), config).torso
                )
            }
        }
    }

    @Test
    fun `사용자가 정의한 힙 축도 요약이 같은 이름으로 답한다`() {
        val config = configOf(custom)
        for ((i, option) in custom.hipOptions.withIndex()) {
            for (body in rolls(custom, hip = i)) {
                assertEquals(
                    "허리 ${body.waist} 엉덩이 ${body.hip}",
                    option.label, BodySilhouetteSpec.axisSummary(measures(body), config).hip
                )
            }
        }
    }

    @Test
    fun `경계를 옮기면 같은 몸이 다른 축으로 읽힌다`() {
        // 같은 몸(허리÷키 = .38)을 두 설정에 물어본다. 기본 경계(.362·.402)에서는 '표준'이고,
        // 사용자가 .345·.395로 좁힌 축에서는 '보통허리'다 — **경계가 실제로 판정을 움직인다.**
        // 이 시험이 없으면 요약이 경계를 아예 안 읽어도 위 둘이 통과할 수 있다
        // (라벨만 config에서 가져오고 문턱은 상수로 남은 구현이 그렇다).
        val m = BodySilhouetteSpec.Measures(160.0, BodySilhouetteSpec.BASE.shoulder, 84.0, 60.8, 88.0)
        assertEquals("표준", BodySilhouetteSpec.axisSummary(m, BodyAnalysisConfig.DEFAULT).torso)
        assertEquals("보통허리", BodySilhouetteSpec.axisSummary(m, configOf(custom)).torso)
    }

    @Test
    fun `마지막 축은 열린 끝이라 그 위의 몸도 이름을 받는다`() {
        val huge = BodySilhouetteSpec.Measures(160.0, BodySilhouetteSpec.BASE.shoulder, 100.0, 90.0, 140.0)
        val axis = BodySilhouetteSpec.axisSummary(huge, configOf(custom))
        assertEquals("두툼", axis.torso)
        assertEquals("풍성", axis.hip)
    }

    // ── 장르 기준(목표 비율)이 사용자 축을 따라간다 ─────────────────────────

    @Test
    fun `장르 기준은 사용자가 다시 정의한 프리셋에서 나온다`() {
        val mine = BodyGenerator.genreTargetIdeals(165.0, 6.0, custom)
        val base = BodyGenerator.genreTargetIdeals(165.0, 6.0)
        assertNotEquals("축을 바꿨는데 목표 비율이 그대로다 — 두 벌로 갈렸다", base, mine)

        // 값이 실제로 그 프리셋에서 파생된 것인지 직접 재조립해 확인한다.
        val preset = custom.bodyPresets[BodyGenerator.GENRE_IDEAL_PRESET_INDEX]
        val (torso, bust, hip) = BodyGenerator.axesOf(preset, custom)
        assertEquals(
            BodyGenerator.idealsFromFigure(torso.waistRatio, bust.cupDiff, hip.hipBonus, 165.0, 6.0),
            mine
        )
    }

    @Test
    fun `프리셋 이름을 전부 바꿔도 장르 기준이 산다`() {
        // 종전 구현은 라벨로 찾았다(`first { it.label == "아워글래스" }`) — 사용자가 이름을
        // 바꿀 수 있게 된 순간 그 코드는 **예외로 죽는다.** 읽기 카드가 캐릭터마다 지나는
        // 경로라 그 예외 하나가 화면을 통째로 죽인다.
        val renamed = BodyAnalysisConfig.DEFAULT_GENERATION.copy(
            bodyPresets = BodyAnalysisConfig.DEFAULT_BODY_PRESETS.mapIndexed { i, p -> p.copy(label = "축$i") }
        )
        assertEquals(
            "이름만 바꿨으므로 값은 기본과 같아야 한다",
            BodyGenerator.genreTargetIdeals(165.0, 6.0),
            BodyGenerator.genreTargetIdeals(165.0, 6.0, renamed)
        )
    }

    // ── 흔들림 폭 0 ────────────────────────────────────────────────────────

    @Test
    fun `흔들림 폭이 0이어도 굴러간다`() {
        // `Random.nextDouble(-0.0, 0.0)`은 예외를 던진다. 폭이 사용자 값이 된 이상
        // 0은 *"정확히 이 키로"*라는 정당한 설정이고, 그것으로 🎲가 죽어서는 안 된다.
        val fixed = custom.copy(heightOptions = custom.heightOptions.map { it.copy(variance = 0.0) })
        for (seed in 0 until 20) {
            val body = BodyGenerator.generate(fixed, 1, 1, 1, 1, random = Random(seed))
            assertEquals(160.0, body.height, 0.001)
        }
    }

    // ── ② 왕복 ────────────────────────────────────────────────────────────

    @Test
    fun `사용자가 정의한 축은 왕복에서 그대로 살아 돌아온다`() {
        val json = BodyAnalysisConfig.applyToConfig("{}", configOf(custom))
        assertEquals(custom, BodyAnalysisConfig.fromConfig(json).generation)
    }

    @Test
    fun `기본 축은 저장하지 않는다`() {
        // 구워 두면 옛 필드의 JSON이 통째로 불어나고, 나중에 기본값을 손보면
        // **그때 저장된 필드만 옛 축으로 굳는다**(ribOffset·partSlots가 같은 이유로 조건부다).
        val json = BodyAnalysisConfig.applyToConfig("{}", BodyAnalysisConfig.DEFAULT)
        assertTrue("generation" !in json)
        assertEquals(BodyAnalysisConfig.DEFAULT_GENERATION, BodyAnalysisConfig.fromConfig(json).generation)
    }

    @Test
    fun `축 설정은 세계관 경계를 넘어도 남고 종류 경계에서는 함께 걷힌다`() {
        // 축은 세계관 안에서만 뜻이 있는 *참조*가 아니라 값이라, 복사되어도 그대로 성립한다.
        // 반대로 종류가 바뀌면 체형 분석 자체가 성립하지 않으므로 `bodyAnalysis`째 걷힌다.
        val json = BodyAnalysisConfig.applyToConfig("{}", configOf(custom))
        assertEquals(custom, BodyAnalysisConfig.fromConfig(FieldConfigTransfer.demoteAcrossUniverse(json)).generation)
        val crossed = FieldConfigTransfer.demoteAcrossEntityType(
            json, FieldDefinition.ENTITY_CHARACTER, FieldDefinition.ENTITY_EVENT
        )
        assertTrue("bodyAnalysis" !in crossed)
    }

    // ── ③ 손으로 고친 값의 교정 ───────────────────────────────────────────

    @Test
    fun `축 개수는 열지 않는다 - 모자라면 채우고 넘치면 자른다`() {
        // 확정 ㄴ1: 수치와 이름만 연다. 개수가 흔들리면 프리셋의 축 인덱스가 가리킬 자리가
        // 사라지고, 화면이 그리는 칸 수도 파일마다 달라진다.
        val json = """
            {"bodyAnalysis":{"generation":{
              "torsos":[{"label":"하나","waistRatio":0.34,"bmi":18.0,"maxRatio":0.37}],
              "busts":[{"label":"a","cupDiff":5},{"label":"b","cupDiff":9},{"label":"c","cupDiff":13},
                       {"label":"d","cupDiff":17},{"label":"e","cupDiff":21},{"label":"f","cupDiff":25}]
            }}}
        """.trimIndent()
        val gen = BodyAnalysisConfig.fromConfig(json).generation
        assertEquals(BodyAnalysisConfig.DEFAULT_TORSO_OPTIONS.size, gen.torsoOptions.size)
        assertEquals(BodyAnalysisConfig.DEFAULT_BUST_OPTIONS.size, gen.bustOptions.size)
        // 적힌 첫 칸은 살고, 모자란 자리는 기본값이 그대로 선다.
        assertEquals("하나", gen.torsoOptions[0].label)
        assertEquals(BodyAnalysisConfig.DEFAULT_TORSO_OPTIONS[1], gen.torsoOptions[1])
        assertEquals("d", gen.bustOptions[3].label)
    }

    @Test
    fun `뒤집힌 경계는 오름차순으로 밀어 올린다`() {
        // 앞 축의 끝이 뒤 축의 끝보다 크면 구간의 폭이 음수가 되어 겨눌 자리가 사라진다.
        // **같은 값으로 눌러 담지 않는 것**이 요점이다 — 폭이 0이면 그 축의 이름이 요약에서
        // 영영 안 돌아오고, 창은 바로 그 상태를 붉은 글씨로 막는다(잣대가 한 벌이어야 한다).
        val json = """
            {"bodyAnalysis":{"generation":{"hips":[
              {"label":"a","hipBonus":22,"maxDiff":40},
              {"label":"b","hipBonus":27,"maxDiff":25},
              {"label":"c","hipBonus":34,"maxDiff":99}]}}}
        """.trimIndent()
        val gen = BodyAnalysisConfig.fromConfig(json).generation
        assertEquals(listOf(40.0, 40.0 + GenerationPreset.Limits.HIP_END_STEP, 99.0), gen.hipOptions.map { it.maxDiff })
        // 구간이 뒤집히지 않았으므로 어떤 축을 골라도 값이 나온다(예외도, NaN도 없다).
        for (i in gen.hipOptions.indices) {
            val body = BodyGenerator.generate(gen, 1, 1, 1, i, random = Random(1))
            assertTrue(body.hip in 60.0..150.0)
        }
    }

    @Test
    fun `수가 아닌 값과 빈 이름은 기본값으로 되돌린다`() {
        val json = """
            {"bodyAnalysis":{"generation":{"heights":[
              {"label":"","center":"글자","variance":-5},
              {"label":"평균","center":160,"variance":3}]}}}
        """.trimIndent()
        val gen = BodyAnalysisConfig.fromConfig(json).generation
        assertEquals(BodyAnalysisConfig.DEFAULT_HEIGHT_OPTIONS[0].label, gen.heightOptions[0].label)
        assertEquals(BodyAnalysisConfig.DEFAULT_HEIGHT_OPTIONS[0].center, gen.heightOptions[0].center, 0.001)
        assertEquals("음수 폭은 흔들림 없음으로 눌린다", 0.0, gen.heightOptions[0].variance, 0.001)
        assertEquals("평균", gen.heightOptions[1].label)
        assertEquals(160.0, gen.heightOptions[1].center, 0.001)
    }

    @Test
    fun `없는 축을 가리키는 프리셋은 그 프리셋의 기본 축으로 돌아간다`() {
        // 0번으로 접으면 프리셋 넷이 조용히 서로 닮아 버린다 — 러프 경로의 폭이 사라진다.
        val json = """
            {"bodyAnalysis":{"generation":{"presets":[
              {"label":"p0","torso":0,"bust":0,"hip":0},
              {"label":"p1","torso":1,"bust":1,"hip":1},
              {"label":"p2","torso":9,"bust":9,"hip":9},
              {"label":"p3","torso":2,"bust":3,"hip":2}]}}}
        """.trimIndent()
        val gen = BodyAnalysisConfig.fromConfig(json).generation
        val fallback = BodyAnalysisConfig.DEFAULT_BODY_PRESETS[2]
        assertEquals("p2", gen.bodyPresets[2].label)
        assertEquals(fallback.torso, gen.bodyPresets[2].torso)
        assertEquals(fallback.bust, gen.bodyPresets[2].bust)
        assertEquals(fallback.hip, gen.bodyPresets[2].hip)
    }

    @Test
    fun `손상된 축 설정은 나머지 체형 설정을 데려가지 않는다`() {
        val json = """{"bodyAnalysis":{"defaultBodyType":"표준","generation":"글자"}}"""
        val config = BodyAnalysisConfig.fromConfig(json)
        assertEquals("표준", config.defaultBodyType)
        assertEquals(BodyAnalysisConfig.DEFAULT_GENERATION, config.generation)
    }

    @Test
    fun `빈 축 한 벌로도 화면이 죽지 않는다`() {
        // 코드가 잘못 세운 한 벌(파싱은 길이를 보장하므로 여기로 오지 않는다). 목표 비율은
        // 읽기 카드가 캐릭터마다 지나는 경로라, 예외 대신 기본 축으로 잇는다.
        val empty = GenerationPreset(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(
            BodyGenerator.genreTargetIdeals(165.0, 6.0),
            BodyGenerator.genreTargetIdeals(165.0, 6.0, empty)
        )
        val body = BodyGenerator.generate(empty, 1, 1, 1, 1, random = Random(5))
        assertTrue(body.waist in 45.0..110.0)
        assertTrue(BodySilhouetteSpec.axisSummary(measures(body), configOf(empty)).torso.isNotBlank())
    }

    // ── 창의 검증과 파일 교정이 같은 잣대인가 ──────────────────────────────

    @Test
    fun `기본 축은 스스로 정한 범위 안에 있다`() {
        // 기본값이 범위를 벗어나 있으면 '기본값으로' 되돌린 직후 저장이 막힌다 —
        // 사용자에게는 앱이 자기 기본값을 거부하는 것으로 보인다.
        val limits = GenerationPreset.Limits
        val gen = BodyAnalysisConfig.DEFAULT_GENERATION
        for (o in gen.heightOptions) {
            assertTrue(o.label, o.center in limits.HEIGHT_CENTER && o.variance in limits.HEIGHT_VARIANCE)
        }
        for (o in gen.torsoOptions) {
            assertTrue(o.label, o.waistRatio in limits.WAIST_RATIO && o.bmiTarget in limits.BMI)
            assertTrue(o.label, o.maxRatio in limits.TORSO_END)
        }
        for (o in gen.bustOptions) assertTrue(o.label, o.cupDiff in limits.CUP_DIFF)
        for (o in gen.hipOptions) {
            assertTrue(o.label, o.hipBonus in limits.HIP_BONUS && o.maxDiff in limits.HIP_END)
        }
        assertTrue(GenerationPreset.Limits.ascendingViolations(gen.torsoOptions.map { it.maxRatio }).isEmpty())
        assertTrue(GenerationPreset.Limits.ascendingViolations(gen.hipOptions.map { it.maxDiff }).isEmpty())
    }

    @Test
    fun `창이 막는 값은 파일로도 들어오지 못한다`() {
        // 잣대가 두 벌이면 **창은 거부하는데 엑셀로는 들어오는** 값이 생기고, 그 값은
        // 화면 어디에도 고칠 자리가 없다. 범위 밖 키 300cm가 그 표본이다.
        val json = """{"bodyAnalysis":{"generation":{"heights":[{"label":"거인","center":300,"variance":99}]}}}"""
        val gen = BodyAnalysisConfig.fromConfig(json).generation
        assertTrue(gen.heightOptions[0].center in GenerationPreset.Limits.HEIGHT_CENTER)
        assertTrue(gen.heightOptions[0].variance in GenerationPreset.Limits.HEIGHT_VARIANCE)
        assertEquals("이름은 사용자의 것이라 그대로 남는다", "거인", gen.heightOptions[0].label)
    }

    @Test
    fun `교정을 지난 한 벌은 순서 규칙을 어기지 않는다`() {
        // 창이 붉은 글씨로 막는 것과 파일 교정이 눌러 담는 것이 **같은 규칙**이라는 계약.
        val broken = custom.copy(
            torsoOptions = custom.torsoOptions.map { it.copy(maxRatio = .3) },
            hipOptions = custom.hipOptions.map { it.copy(maxDiff = 20.0) }
        )
        val fixed = broken.sanitized()
        assertTrue(GenerationPreset.Limits.ascendingViolations(fixed.torsoOptions.map { it.maxRatio }).isEmpty())
        assertTrue(GenerationPreset.Limits.ascendingViolations(fixed.hipOptions.map { it.maxDiff }).isEmpty())
        // 어긋난 자리를 세는 쪽은 실제로 어긋남을 잡는다(빈 목록만 돌려주는 함수가 아니다).
        assertEquals(listOf(1, 2), GenerationPreset.Limits.ascendingViolations(listOf(.3, .3, .3)))
    }

    // ── 저장 형식 ─────────────────────────────────────────────────────────

    @Test
    fun `저장된 축은 이름이 아니라 자리로 프리셋을 잇는다`() {
        // 프리셋이 축을 **인덱스**로 가리키므로, 축 이름을 바꿔도 프리셋이 끊기지 않는다.
        val renamed = custom.copy(torsoOptions = custom.torsoOptions.map { it.copy(label = it.label + "!") })
        val json = BodyAnalysisConfig.applyToConfig("{}", configOf(renamed))
        val back = BodyAnalysisConfig.fromConfig(json).generation
        assertEquals(renamed.bodyPresets, back.bodyPresets)
        assertEquals("두툼!", back.torsoOptions[2].label)
    }

    @Test
    fun `저장 형식은 다섯 축 목록을 그대로 담는다`() {
        val json = BodyAnalysisConfig.applyToConfig("{}", configOf(custom))
        val obj = JSONObject(json).getJSONObject("bodyAnalysis").getJSONObject("generation")
        assertEquals(
            setOf("heights", "torsos", "busts", "hips", "presets"),
            obj.keys().asSequence().toSet()
        )
        assertEquals(custom.torsoOptions.size, obj.getJSONArray("torsos").length())
    }
}
