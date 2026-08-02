package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodySlot
import com.novelcharacter.app.util.BodySilhouetteSpec.Measures

/**
 * 실루엣 편집기의 순수 계층 (설계 `docs/body_visual_redesign_2026-07.md` 5-4-4).
 *
 * 편집기 시트는 **그리기와 배선만** 한다 — 슬라이더 범위·되쓰기 자리·작품 평균은
 * 전부 여기서 정하고 러너에 태운다(`BodySilhouetteSpec`이 그림에 대해 하는 일과 같은
 * 분업이다). 화면에서 계산하면 증명할 수단이 없다.
 */
object BodyEditorModel {

    /**
     * 슬라이더 범위(cm) — 키에 연동한 '그럴듯한' 폭이다.
     *
     * **이것은 값의 한계가 아니다.** 수치 입력칸은 이 범위 밖도 그대로 받는다(설계 5-4-4 —
     * 극단값은 유효 입력이고, 클램프는 그림에만 걸린다). 슬라이더는 흔한 구간을 빨리
     * 훑는 러프 경로이고, 정밀·극단은 수치 칸이 맡는 이중 경로다(원칙 03).
     */
    fun sliderRange(slot: BodySlot, heightCm: Double?): ClosedFloatingPointRange<Double> {
        val h = heightCm?.takeIf { it > 0 } ?: BodySilhouetteSpec.BASE.height
        val base = baseValue(slot) / BodySilhouetteSpec.BASE.height * h
        return (base * SLIDER_MIN_F)..(base * SLIDER_MAX_F)
    }

    /** 키 슬라이더 범위 — 생성기의 산출 한계와 같은 폭이다(두 경로가 다른 세상을 말하지 않게). */
    val HEIGHT_RANGE: ClosedFloatingPointRange<Double> = 140.0..200.0

    /** 체중 슬라이더 범위 — 위와 같은 근거. */
    val WEIGHT_RANGE: ClosedFloatingPointRange<Double> = 30.0..150.0

    private const val SLIDER_MIN_F = .68
    private const val SLIDER_MAX_F = 1.42

    private fun baseValue(slot: BodySlot): Double = when (slot) {
        BodySlot.SHOULDER -> BodySilhouetteSpec.BASE.shoulder
        BodySlot.BUST -> BodySilhouetteSpec.BASE.bust
        BodySlot.WAIST -> BodySilhouetteSpec.BASE.waist
        // 밑가슴은 기준 몸에 실측이 없다 — 그림이 쓰는 근사(허리 + 상수)를 그대로 기준으로 삼는다.
        BodySlot.UNDERBUST -> BodySilhouetteSpec.BASE.waist + BodySilhouetteSpec.FIGURE_RIB_OFFSET_CM
        BodySlot.HIP -> BodySilhouetteSpec.BASE.hip
        BodySlot.NONE -> BodySilhouetteSpec.BASE.waist
    }

    /** 편집기가 다루는 부위 — 이 순서로 행을 만든다(위에서 아래 = 몸의 위에서 아래). */
    val EDITABLE_SLOTS = listOf(BodySlot.SHOULDER, BodySlot.BUST, BodySlot.UNDERBUST, BodySlot.WAIST, BodySlot.HIP)

    // ══════════════════════════════════════════════════════════════════════
    // 되쓰기 — 편집 결과를 파트 원문으로
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 편집한 부위 값을 파트 원문 목록에 되쓴다.
     *
     * **매핑되지 않은 파트는 손대지 않는다.** 사용자가 "허벅지"·"발 크기" 같은 칸을 함께
     * 쓰고 있을 수 있고, 실루엣이 모르는 칸을 비우는 것은 말없는 유실이다(개발 의도 2번).
     */
    fun writeBack(
        partSlots: List<BodySlot>,
        partValues: List<String>,
        values: Map<BodySlot, Double>
    ): List<String> {
        val size = maxOf(partSlots.size, partValues.size)
        return (0 until size).map { i ->
            val slot = partSlots.getOrNull(i) ?: BodySlot.NONE
            val edited = if (slot == BodySlot.NONE) null else values[slot]
            edited?.let { formatValue(it) } ?: (partValues.getOrNull(i) ?: "")
        }
    }

    /**
     * 파트가 정해지지 않은 값(구조화 설정이 없는 BODY_SIZE, 또는 아직 빈 새 캐릭터)의
     * 기본 배치 — 앞 세 칸을 가슴·허리·엉덩이로 본다.
     *
     * [BodyMeasurements]의 위치 폴백과 같은 순서이며, 화면은 이 배치를 **고지한다**
     * (설계 5-4-5 파트 연결로 언제든 바꿀 수 있다는 사실과 함께).
     */
    val POSITIONAL_SLOTS = listOf(BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP)

    /**
     * 되쓸 자리를 정한다 — 해석된 배치에 매핑이 하나도 없으면 위치 폴백으로 채운다.
     *
     * 값이 비어 있는 새 캐릭터는 [BodyMeasurements.resolveSlots]가 아무것도 못 정하는데
     * (숫자가 없으니 위치 폴백 조건도 못 넘는다), 그렇다고 편집기를 못 쓰게 하면
     * **가장 필요한 순간에 도구가 없다.**
     */
    fun writableSlots(partSlots: List<BodySlot>, partCount: Int): List<BodySlot> {
        if (partSlots.any { it != BodySlot.NONE }) {
            return (0 until maxOf(partCount, partSlots.size)).map { partSlots.getOrNull(it) ?: BodySlot.NONE }
        }
        val count = maxOf(partCount, POSITIONAL_SLOTS.size)
        return (0 until count).map { POSITIONAL_SLOTS.getOrNull(it) ?: BodySlot.NONE }
    }

    /** 수치 표기 — 정수는 정수로, 소수는 한 자리로(원문이 "86"이던 칸이 "86.0"이 되지 않게). */
    fun formatValue(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.floor(rounded)) rounded.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", rounded)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 초안 — 값이 없을 때 무엇을 그리는가
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 편집기에 띄울 첫 수치. 값이 하나도 없으면 기준 몸을 키에 맞춰 띄운다.
     *
     * 빈 화면 대신 만질 것을 주는 초안이며, **적용을 누르기 전에는 아무것도 쓰이지 않는다**
     * (변경이 없으면 [적용]이 비활성이라 초안이 조용히 저장되는 경로가 없다).
     */
    fun draftMeasures(m: BodyMeasurements): Measures {
        BodySilhouetteSpec.measuresFrom(m)?.let { return it }
        val heightKnown = m.heightCm != null && m.heightCm > 0
        val h = if (heightKnown) m.heightCm!! else BodySilhouetteSpec.BASE.height
        val f = h / BodySilhouetteSpec.BASE.height
        return Measures(
            height = h,
            shoulder = BodySilhouetteSpec.BASE.shoulder * f,
            bust = BodySilhouetteSpec.BASE.bust * f,
            waist = BodySilhouetteSpec.BASE.waist * f,
            hip = BodySilhouetteSpec.BASE.hip * f,
            underbust = null,
            estimated = emptySet(),
            heightKnown = heightKnown
        )
    }

    /** 편집 중인 수치를 부위별 맵으로 — 되쓰기와 변경 판정이 같은 자료를 본다. */
    fun valuesOf(m: Measures): Map<BodySlot, Double> = buildMap {
        put(BodySlot.SHOULDER, m.shoulder)
        put(BodySlot.BUST, m.bust)
        put(BodySlot.WAIST, m.waist)
        put(BodySlot.HIP, m.hip)
        m.underbust?.let { put(BodySlot.UNDERBUST, it) }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 작품 평균 — 유령 오버레이(설계 4-3)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 같은 작품 캐릭터들의 평균 실루엣. 부위별로 **값이 있는 캐릭터만** 평균 낸다 —
     * 빈 칸을 0으로 세면 평균이 무너진다.
     *
     * 한 명분도 못 모으면 null이며, 그때 화면은 오버레이 토글을 내놓지 않는다
     * (눌러도 아무것도 안 나타나는 토글은 구색이다).
     */
    fun peerAverage(peers: List<BodyMeasurements>): Measures? {
        if (peers.isEmpty()) return null
        fun avg(pick: (BodyMeasurements) -> Double?): Double? =
            peers.mapNotNull(pick).takeIf { it.isNotEmpty() }?.average()

        val bust = avg { it.bust }
        val waist = avg { it.waist }
        val hip = avg { it.hip }
        val shoulder = avg { it.shoulder }
        if (bust == null && waist == null && hip == null && shoulder == null) return null

        val height = avg { it.heightCm }
        return BodySilhouetteSpec.measuresFrom(
            BodyMeasurements(
                values = buildMap {
                    bust?.let { put(BodySlot.BUST, it) }
                    waist?.let { put(BodySlot.WAIST, it) }
                    hip?.let { put(BodySlot.HIP, it) }
                    shoulder?.let { put(BodySlot.SHOULDER, it) }
                    avg { it.underbust }?.let { put(BodySlot.UNDERBUST, it) }
                },
                mode = BodyMeasurements.MappingMode.EXPLICIT,
                partSlots = emptyList(),
                partValues = emptyList(),
                unmappedParts = emptyList(),
                heightCm = height,
                weightKg = null
            )
        )
    }
}
