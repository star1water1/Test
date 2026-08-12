package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.TargetRatioSource
import com.novelcharacter.app.data.model.BodySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 목표 비율의 **기준**을 사용자가 고른다 (B-94 · 확정 8번 — 필드 설정에 기본 기준,
 * 체형 카드에서 즉석 전환).
 *
 * **이 판의 방어선은 건수가 아니라 그 안의 넷이고, 앞의 둘은 서로 반대 방향의 사고를 막는다:**
 *
 * ① *"기본값에서는 이 항목 이전과 한 글자도 다르지 않다"* — 기본을 [TargetRatioSource.GENRE]로
 *    두면 **이상 몸을 적어 둔 사용자가 설정을 그대로 둔 채 효력만 잃는다.** 화면에는 그 몸이
 *    여전히 적혀 있으므로 무엇이 달라졌는지 알 길이 없다(개발 의도 2번). 이 시험은 옛 겹
 *    (장르 기준 → 이상 몸)을 그대로 재어 그 되돌림을 막는다.
 *
 * ② *"고른 기준을 못 쓰면 떨어지되 그 사실이 결과에 남는다"* — **반대편이다.** ①만 있으면
 *    *"재료가 없으면 그냥 자동으로"*가 통과하는데, 그러면 작품 평균을 고른 사용자가
 *    **장르 기준으로 매겨진 점수를 자기 작품과의 거리로 읽는다.** [Basis.fellBack]이
 *    갈라 두므로 카드가 사유를 말할 수 있다.
 *
 * ③ *"어느 기준을 골라도 비율 직접 고정은 그 위에 얹힌다"* — 기준은 바닥이고 고정은 못이다.
 *    기준을 바꿨다고 못박은 값이 풀리면, 사용자가 *"이 비율만은 이 값"*이라 정한 것이
 *    **다른 화면 조작 때문에** 사라진다.
 *
 * ④ *"그림의 작품 평균과 점수의 작품 평균이 같은 몸이다"* — 평균 셈이 두 벌이 되면 유령
 *    실루엣과 점수가 서로 다른 '작품 평균'을 말하고, **둘 다 그럴듯해 보여 잡히지 않는다**
 *    (B-92의 컵 상수가 정확히 그 모양이었다).
 *
 * **다섯째는 시험이되 성격이 다르다** — *"카드의 즉석 전환과 분석이 같은 답을 낸다"*는
 * 오늘 갈릴 수 없는 자리다([BodyTargetRatio] 하나를 둘 다 부른다). 그래도 적는 것은
 * **그 하나됨 자체를 잠그기 때문**이다: 카드가 자기 안에서 이상값을 다시 매기고 싶은
 * 유혹이 정확히 그 자리에 있고(전환마다 분석을 통째로 돌리기 아까워서), 그렇게 갈린
 * 순간을 이 시험이 잡는다.
 */
class BodyTargetRatioSourceTest {

    private val height = 165.0
    private val idealBody = BodyAnalysisConfig.IdealBody(bust = 92.0, waist = 58.0, hip = 90.0, heightCm = 165.0)
    private val peerAverage = BodyAnalysisConfig.IdealBody(bust = 80.0, waist = 66.0, hip = 84.0, heightCm = 160.0)

    private fun peer(bust: Double?, waist: Double?, hip: Double?, h: Double?) = BodyMeasurements(
        values = buildMap {
            bust?.let { put(BodySlot.BUST, it) }
            waist?.let { put(BodySlot.WAIST, it) }
            hip?.let { put(BodySlot.HIP, it) }
        },
        mode = BodyMeasurements.MappingMode.EXPLICIT,
        partSlots = emptyList(),
        partValues = emptyList(),
        unmappedParts = emptyList(),
        heightCm = h,
        weightKg = null
    )

    // ── ① 기본값은 종전 동작 그대로 ──

    @Test
    fun `기본 기준은 자동이다`() {
        assertEquals(TargetRatioSource.AUTO, BodyAnalysisConfig.DEFAULT.targetRatioSource)
    }

    @Test
    fun `자동은 이상 몸이 없으면 장르 기준이다`() {
        val basis = BodyTargetRatio.basis(BodyAnalysisConfig.DEFAULT, height)
        assertEquals(TargetRatioSource.GENRE, basis.source)
        assertEquals(
            BodyGenerator.genreTargetIdeals(height, BodyAnalysisConfig.DEFAULT_RIB_OFFSET),
            basis.ideals
        )
    }

    @Test
    fun `자동은 이상 몸을 적었으면 그 몸이다`() {
        // 되돌림 감지: 기본을 GENRE로 바꾸면 여기가 깨진다 — 적어 둔 몸이 조용히 무시되는 자리다.
        val config = BodyAnalysisConfig.DEFAULT.copy(idealBody = idealBody)
        val basis = BodyTargetRatio.basis(config, height)
        assertEquals(TargetRatioSource.IDEAL_BODY, basis.source)
        assertEquals(
            BodyGenerator.idealsFromBody(idealBody, height, BodyAnalysisConfig.DEFAULT_RIB_OFFSET),
            basis.ideals
        )
    }

    @Test
    fun `자동은 반만 적은 이상 몸을 쓰지 않는다`() {
        // 셋이 안 갖춰지면 비율을 낼 수 없다. 그렇다고 지우지도 않는다(R-27) — 쓰지 않을 뿐이다.
        val half = BodyAnalysisConfig.IdealBody(bust = 92.0, waist = null, hip = 90.0)
        val basis = BodyTargetRatio.basis(BodyAnalysisConfig.DEFAULT.copy(idealBody = half), height)
        assertEquals(TargetRatioSource.GENRE, basis.source)
    }

    // ── ② 고른 기준을 못 쓰면 떨어지되 말한다 ──

    @Test
    fun `작품 평균은 재료가 있어야 선다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.NOVEL_AVERAGE)
        val basis = BodyTargetRatio.basis(config, height, peerAverage)
        assertEquals(TargetRatioSource.NOVEL_AVERAGE, basis.source)
        assertFalse(basis.fellBack)
        assertEquals(
            BodyGenerator.idealsFromBody(peerAverage, height, BodyAnalysisConfig.DEFAULT_RIB_OFFSET),
            basis.ideals
        )
    }

    @Test
    fun `재료가 없는 작품 평균은 떨어진 사실을 남긴다`() {
        // 이것이 없으면 카드가 *작품 평균으로 쟀다*고 말하면서 장르 기준 점수를 보인다.
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.NOVEL_AVERAGE)
        val basis = BodyTargetRatio.basis(config, height, peerAverage = null)
        assertEquals(TargetRatioSource.GENRE, basis.source)
        assertEquals(TargetRatioSource.NOVEL_AVERAGE, basis.requested)
        assertTrue("고른 것과 쓴 것이 갈렸는데 그 사실이 안 남았다", basis.fellBack)
    }

    @Test
    fun `재료가 없으면 평소의 그 자리로 떨어진다`() {
        // 장르 기준으로 바로 떨어뜨리면 **이상 몸을 적어 둔 사용자가 종전보다 못한 자리**에
        // 선다. 떨어질 곳은 언제나 그 필드의 평소 답이다.
        val config = BodyAnalysisConfig.DEFAULT.copy(
            idealBody = idealBody, targetRatioSource = TargetRatioSource.NOVEL_AVERAGE
        )
        val basis = BodyTargetRatio.basis(config, height, peerAverage = null)
        assertEquals(TargetRatioSource.IDEAL_BODY, basis.source)
        assertTrue(basis.fellBack)
    }

    @Test
    fun `이상 몸을 골랐는데 안 적었으면 장르 기준으로 떨어진다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.IDEAL_BODY)
        val basis = BodyTargetRatio.basis(config, height)
        assertEquals(TargetRatioSource.GENRE, basis.source)
        assertTrue(basis.fellBack)
    }

    @Test
    fun `쓸 수 있는 기준을 골랐으면 떨어졌다고 말하지 않는다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.GENRE)
        assertFalse(BodyTargetRatio.basis(config, height).fellBack)
        // 자동은 애초에 '고른 기준'이 아니므로 어디로 풀려도 떨어진 것이 아니다.
        assertFalse(BodyTargetRatio.basis(BodyAnalysisConfig.DEFAULT, height).fellBack)
    }

    @Test
    fun `장르 기준을 고르면 적어 둔 이상 몸을 쓰지 않는다`() {
        // *"이번엔 장르로 보고 싶다"*가 성립해야 즉석 전환이 뜻을 갖는다.
        val config = BodyAnalysisConfig.DEFAULT.copy(
            idealBody = idealBody, targetRatioSource = TargetRatioSource.GENRE
        )
        val basis = BodyTargetRatio.basis(config, height)
        assertEquals(TargetRatioSource.GENRE, basis.source)
        assertNotEquals(
            BodyGenerator.idealsFromBody(idealBody, height, BodyAnalysisConfig.DEFAULT_RIB_OFFSET),
            basis.ideals
        )
    }

    // ── ③ 고정은 어느 기준 위에도 얹힌다 ──

    @Test
    fun `비율 직접 고정은 기준을 바꿔도 남는다`() {
        val pinned = BodyAnalysisConfig.DEFAULT.copy(goldenRatioIdeals = mapOf("whr" to 0.68))
        for (source in TargetRatioSource.entries) {
            val basis = BodyTargetRatio.basis(pinned, height, peerAverage, override = source)
            assertEquals("기준 $source 에서 못박은 값이 풀렸다", 0.68, basis.ideals.getValue("whr"), 1e-9)
            assertEquals(setOf("whr"), basis.pinnedKeys)
        }
    }

    @Test
    fun `고정하지 않은 칸은 기준을 따라 움직인다`() {
        val pinned = BodyAnalysisConfig.DEFAULT.copy(goldenRatioIdeals = mapOf("whr" to 0.68))
        val genre = BodyTargetRatio.basis(pinned, height, peerAverage, TargetRatioSource.GENRE)
        val novel = BodyTargetRatio.basis(pinned, height, peerAverage, TargetRatioSource.NOVEL_AVERAGE)
        assertNotEquals(
            "기준을 바꿨는데 고정하지 않은 칸까지 그대로다 — 전환이 아무 일도 안 한다",
            genre.ideals.getValue("bustHipRatio"), novel.ideals.getValue("bustHipRatio")
        )
    }

    @Test
    fun `이상값에 없는 키는 행을 만들지 않는다`() {
        // 0으로 채우면 편차가 −100%로 나와 *완전히 어긋난 몸*처럼 보인다.
        val basis = BodyTargetRatio.Basis(
            requested = TargetRatioSource.AUTO,
            source = TargetRatioSource.GENRE,
            ideals = mapOf("whr" to 0.7),
            pinnedKeys = emptySet()
        )
        val items = BodyTargetRatio.items(88.0, 60.0, 90.0, height, basis)
        assertEquals(1, items.size)
        assertEquals("허리/엉덩이", items[0].label)
    }

    // ── ④ 작품 평균은 한 셈이다 ──

    @Test
    fun `작품 평균은 부위별로 값이 있는 캐릭터만 센다`() {
        // 빈 칸을 0으로 세면 평균이 무너지고, 그 무너짐은 화면에서 *작은 몸*으로만 보인다.
        val peers = listOf(
            peer(90.0, 60.0, 92.0, 165.0),
            peer(null, 62.0, 88.0, 160.0)
        )
        val avg = BodyEditorModel.peerAverageBody(peers)
        assertNotNull(avg)
        assertEquals(90.0, avg!!.bust!!, 1e-9)     // 한 명뿐이면 그 한 명이 평균이다
        assertEquals(61.0, avg.waist!!, 1e-9)
        assertEquals(90.0, avg.hip!!, 1e-9)
        assertEquals(162.5, avg.heightCm!!, 1e-9)
    }

    @Test
    fun `셋을 못 채우는 작품 평균은 기준이 되지 않는다`() {
        // 비율을 낼 수 없으므로 칩도 안 나오고 기준으로도 못 쓴다(구색 금지).
        assertNull(BodyEditorModel.peerAverageBody(listOf(peer(90.0, null, null, 165.0))))
        assertNull(BodyEditorModel.peerAverageBody(emptyList()))
    }

    @Test
    fun `그림의 작품 평균과 점수의 작품 평균이 같은 몸이다`() {
        // 두 셈이 갈리면 유령 실루엣과 점수가 서로 다른 '작품 평균'을 말한다.
        val peers = listOf(peer(90.0, 60.0, 92.0, 165.0), peer(84.0, 64.0, 88.0, 159.0))
        val body = BodyEditorModel.peerAverageBody(peers)!!
        val drawn = BodyEditorModel.peerAverage(peers)!!
        assertEquals(body.bust!!, drawn.bust, 1e-9)
        assertEquals(body.waist!!, drawn.waist, 1e-9)
        assertEquals(body.hip!!, drawn.hip, 1e-9)
        assertEquals(body.heightCm!!, drawn.height, 1e-9)
    }

    @Test
    fun `작품 평균 기준은 캐릭터 키에 맞춰 조정된다`() {
        // 160 평균을 180 캐릭터에 그대로 대면 같은 체형인데도 점수가 깎인다.
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.NOVEL_AVERAGE)
        val short = BodyTargetRatio.basis(config, 150.0, peerAverage)
        val tall = BodyTargetRatio.basis(config, 185.0, peerAverage)
        // 비율 축(허리/키)은 키에 무관하게 유지되고, cm 축에서 파생된 가슴/키는 달라진다.
        assertEquals(short.ideals.getValue("waistHeight"), tall.ideals.getValue("waistHeight"), 1e-9)
        assertNotEquals(short.ideals.getValue("bustHeight"), tall.ideals.getValue("bustHeight"))
    }

    // ── ⑤ 카드의 즉석 전환과 분석이 한 답이다 ──

    @Test
    fun `분석이 실제로 쓴 기준을 결과에 남긴다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.NOVEL_AVERAGE)
        val r = BodyAnalysisHelper().analyze(88.0, 60.0, 90.0, height, null, config, peerAverage)
        assertEquals(TargetRatioSource.NOVEL_AVERAGE, r.targetRatioBasis?.source)
        // 카드가 같은 함수를 부르므로 이상값이 글자까지 같다 — 두 벌이 되면 여기가 깨진다.
        assertEquals(
            BodyTargetRatio.basis(config, height, peerAverage).ideals,
            r.targetRatioBasis?.ideals
        )
    }

    @Test
    fun `즉석 전환이 분석의 기본 기준을 이긴다`() {
        val config = BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = TargetRatioSource.GENRE)
        val r = BodyAnalysisHelper().analyze(
            88.0, 60.0, 90.0, height, null, config, peerAverage,
            targetRatioOverride = TargetRatioSource.NOVEL_AVERAGE
        )
        assertEquals(TargetRatioSource.NOVEL_AVERAGE, r.targetRatioBasis?.source)
        assertNotEquals(
            BodyTargetRatio.basis(config, height).ideals,
            r.targetRatioBasis!!.ideals
        )
    }

    @Test
    fun `기준을 바꾸면 점수도 따라 바뀐다`() {
        // 전환이 표시만 바꾸고 수치는 그대로면 그것이 겉핥기다(원칙 02).
        val config = BodyAnalysisConfig.DEFAULT
        val genre = BodyAnalysisHelper()
            .analyze(80.0, 66.0, 84.0, 160.0, null, config, peerAverage, TargetRatioSource.GENRE)
        val novel = BodyAnalysisHelper()
            .analyze(80.0, 66.0, 84.0, 160.0, null, config, peerAverage, TargetRatioSource.NOVEL_AVERAGE)
        assertNotEquals(genre.goldenRatioScore, novel.goldenRatioScore)
        // 평균 그 자체인 몸은 작품 평균 기준에서 만점에 가깝다 — 기준이 제 일을 한다는 증명이다.
        assertTrue("작품 평균과 같은 몸인데 만점이 아니다: ${novel.goldenRatioScore}", novel.goldenRatioScore!! > 99.0)
    }

    @Test
    fun `키가 없으면 목표 비율 자체가 없다`() {
        val r = BodyAnalysisHelper().analyze(88.0, 60.0, 90.0, null, null, BodyAnalysisConfig.DEFAULT)
        assertNull(r.goldenRatioScore)
        assertNull(r.targetRatioBasis)
    }

    // ── 왕복 ──

    @Test
    fun `기준이 설정 JSON을 왕복한다`() {
        for (source in TargetRatioSource.entries) {
            val json = BodyAnalysisConfig.applyToConfig(
                "{}", BodyAnalysisConfig.DEFAULT.copy(targetRatioSource = source)
            )
            assertEquals(source, BodyAnalysisConfig.fromConfig(json).targetRatioSource)
        }
    }

    @Test
    fun `기본 기준은 파일에 굽지 않는다`() {
        // 구우면 나중에 기본값을 손볼 때 **옛 값이 굳은 필드만 옛 기준으로 남는다**.
        val json = BodyAnalysisConfig.applyToConfig("{}", BodyAnalysisConfig.DEFAULT)
        assertFalse("기본값인데 키가 실렸다", json.contains("targetRatioSource"))
    }

    @Test
    fun `모르는 기준 이름은 자동으로 접힌다`() {
        // 밖에서 편집된 파일이 읽기 화면을 죽이지 않게 한다 — 거부가 아니라 교정이다.
        val json = """{"bodyAnalysis":{"targetRatioSource":"평균같은거"}}"""
        assertEquals(TargetRatioSource.AUTO, BodyAnalysisConfig.fromConfig(json).targetRatioSource)
    }
}
