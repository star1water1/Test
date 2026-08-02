package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목표 비율의 장르 기준 (P8 재의미화 — 2026.08.02 사용자 요청 "장르문법상 최적 비율").
 *
 * 이 계약의 핵심은 **숫자를 발명하지 않았다**는 것이다 — 이상값은 아워글래스 프리셋
 * (X라인 중심)의 축 목표에서 파생되며, 축 상수(목-⑤ 잠정 승인)가 교정되면 함께 움직인다.
 * 여기 테스트가 값을 하드코딩하지 않고 파생 관계만 묻는 이유다.
 */
class BodyTargetRatioTest {

    @Test
    fun `장르 기준이 파생되는 프리셋이 존재한다`() {
        // 라벨로 찾으므로 프리셋 이름이 바뀌면 여기가 시끄럽게 깨진다 — 조용한 소실 방지.
        assertTrue(
            BodyGenerator.DEFAULT_BODY_PRESETS.any { it.label == BodyGenerator.GENRE_IDEAL_PRESET_LABEL }
        )
    }

    @Test
    fun `장르 기준 몸 자체가 X라인 슬림으로 읽힌다`() {
        // 이상값에서 몸을 복원해 요약에 다시 물으면 장르의 종합 인상이 나와야 한다 —
        // 이상이 자기 체계 안에서 X라인이 아니라면 "장르 기준"이라는 이름이 거짓이다.
        for (h in listOf(145.0, 165.0, 185.0)) {
            val ideals = BodyGenerator.genreTargetIdeals(h)
            val waist = ideals.getValue("waistHeight") * h
            val hip = waist / ideals.getValue("whr")
            val bust = ideals.getValue("bustHipRatio") * hip
            val m = BodySilhouetteSpec.Measures(
                h, BodySilhouetteSpec.BASE.shoulder, bust, waist, hip
            )
            val axis = BodySilhouetteSpec.axisSummary(m)
            assertEquals("키 $h", "X라인", axis.line)
            assertEquals("키 $h", "슬림", axis.torso)
        }
    }

    @Test
    fun `장르 기준 중심의 몸은 만점에 가깝다`() {
        // 기본 설정(빈 이상값 = 자동)에서 이상 그 자체인 몸이 만점이 아니라면
        // 채점이 다른 기준을 보고 있다는 뜻이다.
        for (h in listOf(150.0, 165.0, 180.0)) {
            val ideals = BodyGenerator.genreTargetIdeals(h)
            val waist = ideals.getValue("waistHeight") * h
            val bust = ideals.getValue("bustHeight") * h
            val hip = waist / ideals.getValue("whr")
            val r = BodyAnalysisHelper().analyze(bust, waist, hip, h, null, BodyAnalysisConfig.DEFAULT)
            assertNotNull(r.goldenRatioScore)
            assertTrue("키 $h 점수 ${r.goldenRatioScore}", r.goldenRatioScore!! >= 99.0)
        }
    }

    @Test
    fun `장르 기준은 키에 적응한다`() {
        val short = BodyGenerator.genreTargetIdeals(150.0)
        val tall = BodyGenerator.genreTargetIdeals(180.0)
        // 허리/키는 비율이라 키와 무관하고, cm 오프셋 축(힙·컵)은 작은 키에서 더 깊게 잡힌다.
        assertEquals(short.getValue("waistHeight"), tall.getValue("waistHeight"), 1e-9)
        assertTrue(short.getValue("whr") < tall.getValue("whr"))
    }

    @Test
    fun `직접 정한 키가 장르 기준을 이긴다 - 비운 키만 자동이다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(goldenRatioIdeals = mapOf("whr" to 0.70))
        val details = BodyAnalysisHelper()
            .analyze(86.0, 62.0, 90.0, 165.0, null, config)
            .goldenRatioDetails!!
        assertEquals(0.70, details.first { it.label == "허리/엉덩이" }.ideal, 1e-9)
        val genre = BodyGenerator.genreTargetIdeals(165.0, config.ribOffset)
        assertEquals(
            genre.getValue("bustHipRatio"),
            details.first { it.label == "가슴/엉덩이" }.ideal, 1e-9
        )
    }

    @Test
    fun `흉곽 보정이 이상 가슴도 함께 움직인다`() {
        // 보정은 "이 세계의 밑가슴 규약"이다 — 이상 몸의 가슴도 같은 규약으로 선다.
        // 허리·힙은 보정과 무관해야 한다(가슴만 밑가슴 위에 쌓인다).
        val a = BodyGenerator.genreTargetIdeals(165.0, 0.0)
        val b = BodyGenerator.genreTargetIdeals(165.0, 6.0)
        assertTrue(a.getValue("bustHipRatio") < b.getValue("bustHipRatio"))
        assertEquals(a.getValue("whr"), b.getValue("whr"), 1e-9)
        assertEquals(a.getValue("waistHeight"), b.getValue("waistHeight"), 1e-9)
    }

    @Test
    fun `이상값 저장은 직접 정한 것만 왕복한다`() {
        // 빈 맵(= 장르 기준 자동)은 저장되지 않는다 — 기본값을 구우면 축 교정이 소급되지 않는다.
        val autoJson = BodyAnalysisConfig.applyToConfig("{}", BodyAnalysisConfig.DEFAULT)
        assertFalse("자동 상태가 저장됐다: $autoJson", autoJson.contains("goldenRatioIdeals"))
        assertTrue(BodyAnalysisConfig.fromConfig(autoJson).goldenRatioIdeals.isEmpty())

        val custom = BodyAnalysisConfig.DEFAULT.copy(goldenRatioIdeals = mapOf("whr" to 0.66))
        val json = BodyAnalysisConfig.applyToConfig("{}", custom)
        assertEquals(mapOf("whr" to 0.66), BodyAnalysisConfig.fromConfig(json).goldenRatioIdeals)
    }

    @Test
    fun `키가 없으면 목표 비율 자체가 없다 - 종전 계약 유지`() {
        val r = BodyAnalysisHelper().analyze(86.0, 62.0, 90.0, null, null, BodyAnalysisConfig.DEFAULT)
        assertNull(r.goldenRatioScore)
    }
}
