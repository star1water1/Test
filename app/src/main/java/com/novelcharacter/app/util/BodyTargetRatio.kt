package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodyAnalysisConfig.TargetRatioSource
import kotlin.math.abs

/**
 * 목표 비율의 **이상값이 어디서 오는가** 한 자리 (B-94 · 판정 P8 · 확정 8번).
 *
 * ## 왜 따로 있는가 — 부르는 자리가 둘이기 때문이다
 *
 * [BodyAnalysisHelper.analyze]가 한 번 부르고, **읽기 카드의 즉석 전환이 또 부른다**
 * (확정 8번 ㄱ2 — 기준을 바꾸면 그 자리에서 수치가 따라 바뀐다). 전환마다 분석을 통째로
 * 다시 돌리는 것은 규칙을 다시 매기고 태그를 다시 세는 일이라 값이 안 나오고, 그렇다고
 * 카드가 이상값 계산을 자기 안에 두면 **두 벌이 되어 갈린다** — 이 저장소가 반복해서
 * 겪은 그 모양이다(B-92의 컵 상수, 15판의 축 라벨).
 *
 * 그래서 계산은 여기 하나이고 **화면 계층이 아니므로 순수 시험이 그대로 닿는다.**
 *
 * ## 겹 규칙 — 기준은 하나, 고정은 그 위
 *
 * [TargetRatioSource]가 **바닥**을 정하고, [BodyAnalysisConfig.goldenRatioIdeals]의
 * 키별 고정은 어느 바닥 위에도 얹힌다. 고정은 기준이 아니라 *"이 비율만은 이 값"*이라서다.
 * [TargetRatioSource.AUTO]만 예외로 종전의 겹(장르 기준 → 이상 몸)을 그대로 쓴다 —
 * 그것이 이 항목 이전의 동작이고, 기본값이 그것이어야 옛 필드가 한 글자도 안 바뀐다.
 */
object BodyTargetRatio {

    /** 이상값 4종의 키 — 화면·설정·시험이 같은 순서로 읽는다. */
    val KEYS = listOf("whr", "bustHipRatio", "waistHeight", "bustHeight")

    /**
     * 실제로 무엇을 기준으로 쟀는가.
     *
     * [requested]와 [source]를 **함께** 드는 것이 요점이다 — 고른 것과 쓴 것이 갈릴 수 있고
     * (재료가 없는 자리), 그 갈림을 카드가 말해야 한다. 하나만 들면 화면이 거짓말을 하거나
     * (고른 것만 보이면) 사용자가 왜 자기 선택이 사라졌는지 모른다(쓴 것만 보이면).
     */
    data class Basis(
        val requested: TargetRatioSource,
        val source: TargetRatioSource,
        val ideals: Map<String, Double>,
        /** 키별로 직접 고정된 자리 — 바닥이 무엇이든 이 키들은 그 값이다. */
        val pinnedKeys: Set<String>
    ) {
        /** 고른 기준을 못 써서 다른 것으로 쟀는가 — 카드가 이때 사유를 함께 낸다. */
        val fellBack: Boolean get() = requested != TargetRatioSource.AUTO && requested != source
    }

    /**
     * 기준을 정하고 이상값을 낸다.
     *
     * @param heightCm 이 캐릭터의 키. 이상은 *"같은 키의 기준 몸"*이라 여기에 적응한다.
     * @param peerAverage 같은 작품 캐릭터의 평균 몸 — [BodyEditorModel.peerAverageBody]가 낸다.
     *   `null`이면 [TargetRatioSource.NOVEL_AVERAGE]를 고를 수 없다.
     * @param override 카드에서 즉석 전환한 기준. `null`이면 필드 설정의 기본 기준을 쓴다.
     */
    fun basis(
        config: BodyAnalysisConfig,
        heightCm: Double,
        peerAverage: BodyAnalysisConfig.IdealBody? = null,
        override: TargetRatioSource? = null
    ): Basis {
        val requested = override ?: config.targetRatioSource
        val rib = config.ribOffset
        val genre = { BodyGenerator.genreTargetIdeals(heightCm, rib, config.generation) }
        val fromIdealBody = {
            config.idealBody?.let { BodyGenerator.idealsFromBody(it, heightCm, rib) }
        }
        val fromPeers = { peerAverage?.let { BodyGenerator.idealsFromBody(it, heightCm, rib) } }

        // 고른 기준을 못 쓰면 **AUTO로 떨어진다** — 장르 기준으로 바로 떨어뜨리지 않는 것은,
        // 이상 몸을 적어 둔 사용자가 작품 평균을 골랐다 재료가 없어 돌아왔을 때 종전보다
        // 못한 자리에 서지 않게 하기 위해서다(떨어질 곳은 언제나 '평소의 그 자리'다).
        val (source, base) = when (requested) {
            TargetRatioSource.NOVEL_AVERAGE ->
                fromPeers()?.let { TargetRatioSource.NOVEL_AVERAGE to it } ?: autoBase(genre, fromIdealBody)
            TargetRatioSource.IDEAL_BODY ->
                fromIdealBody()?.let { TargetRatioSource.IDEAL_BODY to it } ?: (TargetRatioSource.GENRE to genre())
            TargetRatioSource.GENRE -> TargetRatioSource.GENRE to genre()
            TargetRatioSource.AUTO -> autoBase(genre, fromIdealBody)
        }
        // 고정은 마지막에 — 바닥이 무엇이든 이긴다.
        val pinned = config.goldenRatioIdeals.filterKeys { it in KEYS }
        return Basis(requested, source, base + pinned, pinned.keys)
    }

    /** 종전 겹 — 장르 기준 위에 이상 몸이 얹힌다. 이상 몸이 셋을 못 채우면 장르 기준뿐이다. */
    private fun autoBase(
        genre: () -> Map<String, Double>,
        fromIdealBody: () -> Map<String, Double>?
    ): Pair<TargetRatioSource, Map<String, Double>> {
        val body = fromIdealBody()
        return if (body == null) TargetRatioSource.GENRE to genre()
        else TargetRatioSource.IDEAL_BODY to (genre() + body)
    }

    /**
     * 실측과 이상의 거리 4행. [BodyAnalysisHelper.analyze]와 카드의 즉석 전환이 같은 것을 쓴다.
     *
     * 이상값에 없는 키는 **행을 만들지 않는다** — 0으로 채우면 편차가 −100%로 나와
     * *"완전히 어긋난 몸"*처럼 보인다(없는 것과 어긋난 것은 다른 사실이다).
     */
    fun items(
        bust: Double,
        waist: Double,
        hip: Double,
        heightCm: Double,
        basis: Basis
    ): List<GoldenRatioItem> {
        if (heightCm <= 0 || hip <= 0) return emptyList()
        val actual = mapOf(
            "whr" to waist / hip,
            "bustHipRatio" to bust / hip,
            "waistHeight" to waist / heightCm,
            "bustHeight" to bust / heightCm
        )
        return KEYS.mapNotNull { key ->
            val ideal = basis.ideals[key] ?: return@mapNotNull null
            val value = actual.getValue(key)
            val deviation = if (ideal != 0.0) ((value - ideal) / ideal) * 100.0 else 0.0
            GoldenRatioItem(labelOf(key), value, ideal, deviation)
        }
    }

    /** 점수 — 평균 편차를 100에서 뺀다(공식 무변경 — P8은 이상값의 근거만 바꿨다). */
    fun score(items: List<GoldenRatioItem>): Double? {
        if (items.isEmpty()) return null
        val avgDeviation = items.map { abs(it.deviationPercent) }.average()
        return (100.0 - avgDeviation * 5).coerceIn(0.0, 100.0)
    }

    /**
     * 행 이름. **순수 계층이라 문자열을 여기 든다** — 이상값 키와 이름이 한 자리에 있어야
     * 키를 늘릴 때 이름 없는 행이 생기지 않는다(표시 계층은 이 이름을 그대로 쓴다).
     */
    private fun labelOf(key: String): String = when (key) {
        "whr" -> "허리/엉덩이"
        "bustHipRatio" -> "가슴/엉덩이"
        "waistHeight" -> "허리/키"
        else -> "가슴/키"
    }
}
