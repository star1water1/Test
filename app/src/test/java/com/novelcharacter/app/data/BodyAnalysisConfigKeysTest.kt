package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `bodyAnalysis` 설정 키의 계약 — 없앤 설정을 조용히 삼키지 않는다 (B-95 · P-5).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 둘이다:**
 *
 * ① *"쓰는 키는 하나도 '모르는 키'가 아니다"* — [자기가 쓴 설정은 모르는 키로 고지되지 않는다].
 *    [BodyAnalysisConfig.READ_KEYS]는 손으로 적은 목록이라 **파싱에 키를 더하면서 여기를
 *    빠뜨릴 수 있는데**, 그러면 멀쩡히 동작하는 설정이 *"더 이상 쓰지 않는 것"*으로 고지된다.
 *    사용자에게는 **거짓말이고 화면·동작에는 아무 증상이 없어** 사람 눈으로는 통과한다.
 *    그래서 목록을 다시 손으로 적어 대조하지 않고 **내보내기가 실제로 쓴 키를 되읽혀** 잰다 —
 *    키를 더하면 이 시험이 그 키를 자동으로 함께 재고, 목록을 빠뜨린 순간 실패한다.
 *
 * ② *"없앤 설정은 세어진다"* — [없앤 설정이 담긴 옛 파일은 그 사실이 세어진다].
 *    ①만 있으면 [BodyAnalysisConfig.unusedKeysIn]이 **항상 빈 목록을 돌려줘도 통과한다**
 *    (그 함수를 통째로 죽여도 ①은 초록이다). 고지 경로가 실제로 살아 있는지는 이쪽이 잰다.
 *
 * 곁들여 잠그는 것: 손상 JSON과 `bodyAnalysis`가 아예 없는 config는 **잴 것이 없는 것**이지
 * 버려지는 것이 아니다(손상은 가져오기가 따로 경고한다 — 두 곳에서 같은 것을 말하면
 * 사용자가 두 번 고쳐야 하는 줄 안다).
 */
class BodyAnalysisConfigKeysTest {

    /** 모든 칸이 기본값과 다른 설정 — 내보내기가 조건부로 싣는 키까지 전부 나오게 한다. */
    private val fullyPopulated = BodyAnalysisConfig.DEFAULT.copy(
        defaultBodyType = "표준",
        ribOffset = 7.5,
        bodyTagRules = listOf(
            BodyAnalysisConfig.BodyTagRule(
                label = "허리가 가늘다",
                layer = "silhouette",
                conditions = mapOf("whr" to BodyAnalysisConfig.RangeCondition(max = 0.7)),
                priority = 1
            )
        ),
        goldenRatioIdeals = mapOf("whr" to 0.7),
        partSlots = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP),
        idealBody = BodyAnalysisConfig.IdealBody(bust = 88.0, waist = 60.0, hip = 90.0, heightCm = 165.0)
    )

    // ── ① 쓰는 키는 '모르는 키'가 아니다 ──

    @Test
    fun `자기가 쓴 설정은 모르는 키로 고지되지 않는다`() {
        val json = BodyAnalysisConfig.applyToConfig("{}", fullyPopulated)
        assertEquals(
            "내보내기가 쓴 키가 READ_KEYS에 없다 — 멀쩡한 설정이 '더 이상 쓰지 않는 것'으로 고지된다",
            emptyList<String>(),
            BodyAnalysisConfig.unusedKeysIn(json)
        )
    }

    @Test
    fun `기본값 설정도 마찬가지다`() {
        // 조건부로 실리는 키(ribOffset·partSlots…)가 빠진 최소 형태에서도 성립해야 한다.
        val json = BodyAnalysisConfig.applyToConfig("{}", BodyAnalysisConfig.DEFAULT)
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn(json))
    }

    @Test
    fun `쓴 키가 실제로 여럿이다`() {
        // 위 둘이 '아무것도 쓰지 않아서' 통과하는 것을 막는다 — 빈 객체를 내보내도
        // 모르는 키는 0이기 때문이다.
        val json = BodyAnalysisConfig.applyToConfig("{}", fullyPopulated)
        val written = JSONObject(json).getJSONObject("bodyAnalysis").keys().asSequence().toSet()
        assertEquals(
            "내보내기가 쓰는 키와 파싱이 읽는 키가 갈렸다",
            BodyAnalysisConfig.READ_KEYS, written
        )
    }

    // ── ② 없앤 설정은 세어진다 ──

    @Test
    fun `없앤 설정이 담긴 옛 파일은 그 사실이 세어진다`() {
        // 2026.08.09 이전 앱이 실제로 내보내던 모양(B-95로 없앤 `underbustEstimation`).
        val old = """{"bodyAnalysis":{"defaultBodyType":"보통체형","underbustEstimation":"waist"}}"""
        assertEquals(listOf("underbustEstimation"), BodyAnalysisConfig.unusedKeysIn(old))
        // 나머지 설정은 그대로 읽힌다 — 모르는 키 하나가 config 전체를 버리게 하지 않는다.
        assertEquals("보통체형", BodyAnalysisConfig.fromConfig(old).defaultBodyType)
    }

    @Test
    fun `모르는 키가 여럿이면 전부 이름으로 나온다`() {
        // 순서는 JSON 객체의 키 순서라 정해져 있지 않다 — 집합으로 잰다.
        val json = """{"bodyAnalysis":{"underbustEstimation":"waist","legacyFoo":1,"cupMapping":[]}}"""
        assertEquals(
            setOf("underbustEstimation", "legacyFoo"),
            BodyAnalysisConfig.unusedKeysIn(json).toSet()
        )
    }

    // ── 잴 것이 없는 자리 ──

    @Test
    fun `체형 설정이 없는 config는 잴 것이 없다`() {
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn("""{"separator":"-"}"""))
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn("{}"))
    }

    @Test
    fun `손상된 config는 모르는 키로 고지되지 않는다`() {
        // 가져오기가 이미 '올바른 형식이 아닙니다'로 따로 경고하는 자리다.
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn("broken{"))
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn(""))
    }

    @Test
    fun `체형 설정이 객체가 아니면 잴 것이 없다`() {
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn("""{"bodyAnalysis":"주석"}"""))
    }

    // ── 없앤 설정이 되살아나지 않는다 ──

    @Test
    fun `없앤 설정은 다시 내보내지지 않는다`() {
        // 옛 값을 든 config를 앱에서 한 번 저장하면(= applyToConfig) 그 키가 사라진다.
        // 가져오기 고지가 사용자에게 약속하는 것이 이것이다.
        val old = """{"bodyAnalysis":{"defaultBodyType":"표준","underbustEstimation":"waist"}}"""
        val resaved = BodyAnalysisConfig.applyToConfig(old, BodyAnalysisConfig.fromConfig(old))
        assertTrue("없앤 키가 다시 실렸다", "underbustEstimation" !in resaved)
        assertEquals("표준", BodyAnalysisConfig.fromConfig(resaved).defaultBodyType)
        assertEquals(emptyList<String>(), BodyAnalysisConfig.unusedKeysIn(resaved))
    }
}
