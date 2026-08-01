package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodyAnalysisConfig
import com.novelcharacter.app.data.model.BodySlot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 실루엣 지오메트리 단일 소스 (설계 `docs/body_visual_redesign_2026-07.md` 4장).
 *
 * **정점 배열과 상수의 정본이 이 객체다 — 뷰는 그리기만 한다**(설계 4-4). 값은
 * 재저작 2차 스냅샷(`docs/mockups/body_silhouette_reauthor_draft_2026-08.html`)과
 * 이식 목업(`docs/mockups/body_silhouette_port_2026-08.html`)에서 그대로 옮겼다.
 *
 * ## 좌표계
 * 전부 **cm**다. x는 몸 중심축에서의 좌우 거리(오른쪽 +), y는 **바닥에서 잰 높이**.
 * 뷰는 `px = cx + xCm * k`, `py = baseY - yCm * k` 하나로 옮기며
 * `k = 그릴 높이 / [CANVAS_HEIGHT_CM]`이다. 화면 단위가 이 객체에 새지 않게 한 것이라
 * 정점 배열을 그대로 스냅샷 비교할 수 있다.
 *
 * ## 저작 규칙 (재저작 2차 — 고치기 전에 설계 4-1을 읽을 것)
 * - **흉통과 가슴은 완전히 독립이다.** 가슴 값은 흉곽 폭에 한 치도 관여하지 않는다.
 *   가슴의 정면 표현은 옆 부풂(최대 [MAX_SPILL_CM])과 W-아크·음영이 나눠 든다.
 * - **부풂은 가산 혹이다.** `max()` 문턱 합성을 쓰지 않는다 — 문턱 교차마다 각이 생겨
 *   "뾰족한 옆가슴"이 된다. 양끝이 0으로 접선 합류하는 프로필만 쓴다.
 * - **클램프는 표시만이다.** 값은 절대 불변이고, [Scales.clamped*] 플래그가 핸들
 *   경고색·한계 캡션으로 나간다(그림의 한계이지 값의 한계가 아니다).
 */
object BodySilhouetteSpec {

    /** 캔버스가 담는 키(cm). 이 키의 캐릭터가 그릴 높이를 꽉 채운다. */
    const val CANVAS_HEIGHT_CM = 205.0

    /** 변형의 원점이 되는 기준 몸. */
    val BASE = Measures(height = 165.0, shoulder = 38.0, bust = 86.0, waist = 62.0, hip = 90.0)

    /**
     * 부위별 변형 지수. **감쇠가 아니라 증폭이다**(α>1) — 이 장르의 문법은 몸매 차이가
     * 실측보다 크게 읽히는 것이다(설계 4-1: 세 번 같은 방향으로 교정된 결론).
     */
    const val ALPHA_SHOULDER = 1.0
    const val ALPHA_WAIST = 1.18
    const val ALPHA_HIP = 1.12

    /** 변형 앵커 높이(키 비율). */
    const val ANCHOR_SHOULDER = .785
    const val ANCHOR_BUST = .700
    const val ANCHOR_WAIST = .630
    const val ANCHOR_HIP = .515

    /** 앵커에서의 기준 반폭(cm) — 핸들이 앉는 자리이자 역산의 기준이다. */
    const val HALF_SHOULDER = 16.6
    const val HALF_WAIST = 9.7
    const val HALF_HIP = 17.70

    /** 표시 클램프 범위(설계 4-2 · 재저작 2차 결정). 값이 아니라 그림만 이 안에 든다. */
    const val CLAMP_SHOULDER_MIN = .78
    const val CLAMP_SHOULDER_MAX = 1.28
    const val CLAMP_WAIST_MIN = .66
    const val CLAMP_WAIST_MAX = 1.50
    const val CLAMP_HIP_MIN = .75
    const val CLAMP_HIP_MAX = 1.38

    /**
     * 그림이 쓰는 밑가슴 근사 보정(cm).
     *
     * 실측 밑가슴이 없을 때 `밑가슴 = 허리 + 6`으로 본다. **[BodyAnalysisConfig.ribOffset]을
     * 쓰지 않는 것은 일부러다** — 그쪽은 사용자가 컵 표를 조율하는 값이고 기본이 0이라,
     * 그대로 쓰면 컵차가 통째로 부풀어 모든 캐릭터가 거유가 된다. 그림의 컵 근사를 6으로
     * 두는 것은 장르 비율 판정이다(설계 9장 P4 — 실사 쪽 디플레 제안이 기각된 자리).
     */
    const val FIGURE_RIB_OFFSET_CM = 6.0

    /** 옆 부풂 상한(cm). 가슴은 앞으로 돌출한다 — 정면 옆 폭이 아니다(스필·덩어리 분리). */
    const val MAX_SPILL_CM = 3.6

    /** 컵차 상한(cm)과 폭 성장이 둔화되는 지점 — 극단 컵에서 판형이 되는 것을 막는다. */
    private const val CUP_DIFF_CAP = 40.0
    private const val CUP_DIFF_SOFT_KNEE = 20.0
    private const val CUP_DIFF_SOFT_SLOPE = .35

    private const val ELBOW_F = .645

    /** cm 좌표의 한 점. x는 중심축 기준 좌우, y는 바닥에서 잰 높이. */
    data class PointCm(val x: Double, val y: Double)

    /** 실루엣이 받는 수치. 부분 슬롯은 [estimated]가 어느 부위가 추정치인지 든다. */
    data class Measures(
        val height: Double,
        val shoulder: Double,
        val bust: Double,
        val waist: Double,
        val hip: Double,
        /** 실측 밑가슴(cm). null이면 `허리 + [FIGURE_RIB_OFFSET_CM]` 근사를 쓴다. */
        val underbust: Double? = null,
        /** 값이 없어 이웃에서 보간한 부위 — 해당 핸들은 비활성·점선으로 표시된다. */
        val estimated: Set<BodySlot> = emptySet(),
        /** 키가 실제 입력값인가. false면 화면이 "키 미입력 — 비례만 표시"를 단다. */
        val heightKnown: Boolean = true
    )

    /** 변형 스케일과 표시 클램프 결과. */
    data class Scales(
        val shoulder: Double,
        val waist: Double,
        val hip: Double,
        /** 흉곽 스케일 — 허리를 계수 .6으로 따라간다(가슴 값은 관여하지 않는다). */
        val rib: Double,
        val clampedShoulder: Boolean,
        val clampedWaist: Boolean,
        val clampedHip: Boolean
    ) {
        val anyClamped: Boolean get() = clampedShoulder || clampedWaist || clampedHip

        fun clampedFor(slot: BodySlot): Boolean = when (slot) {
            BodySlot.SHOULDER -> clampedShoulder
            BodySlot.WAIST -> clampedWaist
            BodySlot.HIP -> clampedHip
            else -> false
        }
    }

    /** 가슴 볼륨 기하 — 컵차에서 파생되는 값 전부. */
    data class BustGeom(
        /** 컵차(cm) = 가슴 − 밑가슴, 0..[CUP_DIFF_CAP]. */
        val cupDiff: Double,
        /** 실루엣 옆 부풂(cm) — 상한 [MAX_SPILL_CM]. */
        val spill: Double,
        /** W-아크의 이론 폭(cm). 흉곽 폭 상한에 걸리면 포화된다. */
        val massBase: Double,
        /** 가슴 대역 상단(키 비율). */
        val topF: Double,
        /** 가슴 대역 하단 = 언더 라인(키 비율). */
        val botF: Double
    )

    /** 핸들이 앉는 자리. */
    data class HandleAnchor(
        val slot: BodySlot,
        /** 중심축에서의 반폭(cm). */
        val halfCm: Double,
        /** 높이(키 비율). */
        val heightFraction: Double,
        /** 그림의 한계에 닿았는가 — 경고색으로 나간다. */
        val warn: Boolean,
        /** 값이 없어 추정으로 그린 부위인가 — 핸들 비활성·점선. */
        val estimated: Boolean
    )

    /** 정면 한 벌. 뷰는 이 정점 배열을 [cubicSegments]로 잇기만 한다. */
    data class FrontFigure(
        /** 몸통 바깥 윤곽 — 닫힌 고리. */
        val body: List<PointCm>,
        /** 머리 — 닫힌 고리. */
        val head: List<PointCm>,
        /** 팔 전체(위팔+전완) — 좌우 한 쌍, 닫힌 고리. */
        val arms: List<List<PointCm>>,
        /** 전완만 — 몸 앞에 다시 그려 z-순서를 만든다(좌우 한 쌍). */
        val forearms: List<List<PointCm>>,
        val scales: Scales,
        val bust: BustGeom,
        val handles: List<HandleAnchor>
    )

    /** 측면 한 벌 — 표시 전용(핸들 없음, P10). */
    data class SideFigure(
        val body: List<PointCm>,
        val head: List<PointCm>,
        val scales: Scales,
        val bust: BustGeom,
        /** 가슴 전방 돌출 깊이(cm) — 셀 표기(밑가슴 턱 라인)가 쓴다. */
        val bustDepthCm: Double
    )

    // ══════════════════════════════════════════════════════════════════════
    // 정점 정본 — 재저작 2차 스냅샷 그대로. 고치려면 설계 4-1을 먼저 읽을 것.
    // 각 행은 (키 비율, 반폭 cm).
    // ══════════════════════════════════════════════════════════════════════

    /** 몸통 바깥선(오른쪽 절반): 어깨 → 흉곽 → 허리 → 힙 크레스트(플래토) → 다리 → 발. */
    private val R_OUTER: List<DoubleArray> = listOf(
        d(.856, 2.85), d(.848, 2.75), d(.840, 2.72), d(.832, 2.80), d(.825, 3.10),
        d(.818, 4.50), d(.812, 7.80), d(.806, 11.60), d(.800, 14.20),
        d(.794, 15.20), d(.789, 15.35), d(.783, 15.20), d(.777, 14.60),
        d(.770, 14.00),
        d(.763, 13.55), d(.756, 13.30), d(.749, 13.15), d(.742, 13.00),
        d(.735, 12.85), d(.728, 12.70), d(.721, 12.55), d(.714, 12.40),
        d(.707, 12.25), d(.700, 12.10),
        d(.693, 11.75), d(.687, 11.40), d(.681, 11.10), d(.675, 10.82),
        d(.668, 10.55), d(.661, 10.30), d(.653, 10.08), d(.645, 9.90), d(.637, 9.77),
        d(.630, 9.70),
        d(.622, 9.80), d(.612, 10.10), d(.602, 10.55), d(.591, 11.20),
        d(.580, 12.10), d(.569, 13.10), d(.558, 14.15), d(.547, 15.15),
        d(.537, 16.05), d(.528, 16.80), d(.520, 17.35), d(.513, 17.68),
        d(.506, 17.74), d(.499, 17.70), d(.491, 17.55), d(.482, 17.25),
        d(.472, 16.80), d(.461, 16.25), d(.449, 15.60), d(.436, 14.85),
        d(.422, 14.00), d(.407, 13.10), d(.391, 12.20), d(.374, 11.30),
        d(.357, 10.50), d(.340, 9.85), d(.323, 9.35), d(.307, 8.95),
        d(.295, 8.62), d(.291, 8.50), d(.280, 8.42),
        d(.270, 8.55), d(.259, 8.78), d(.247, 8.80), d(.234, 8.50),
        d(.220, 8.00), d(.205, 7.35), d(.189, 6.70), d(.172, 6.05),
        d(.154, 5.50), d(.135, 5.00), d(.116, 4.60), d(.097, 4.30), d(.079, 4.05),
        d(.063, 3.90), d(.050, 3.95), d(.039, 3.92),
        d(.024, 4.10), d(.014, 4.85), d(.006, 5.20), d(.001, 5.00)
    )

    /** 가랑이에서 발까지 올라오는 안쪽선(오른쪽 절반). */
    private val R_INNER_UP: List<DoubleArray> = listOf(
        d(.001, 2.00), d(.009, 1.58), d(.020, 1.45), d(.034, 1.40), d(.048, 1.55),
        d(.065, 1.80), d(.085, 2.05), d(.108, 2.30), d(.133, 2.52), d(.158, 2.70),
        d(.183, 2.84), d(.207, 2.94), d(.228, 3.00),
        d(.247, 2.97), d(.264, 2.88), d(.279, 2.80), d(.291, 2.76),
        d(.304, 2.76), d(.320, 2.80), d(.338, 2.85), d(.357, 2.88), d(.377, 2.86),
        d(.397, 2.78), d(.416, 2.62), d(.434, 2.42), d(.450, 2.16),
        d(.464, 1.86), d(.477, 1.50), d(.488, 1.10), d(.497, 0.70), d(.503, 0.42),
        d(.5065, 0.28)
    )

    /** 머리 — 머리 중심([HEAD_CENTER_F])에서의 (x cm, y cm) 오프셋. */
    private val HEAD: List<DoubleArray> = listOf(
        d(0.0, 12.4), d(5.0, 11.5), d(8.0, 7.8), d(8.8, 3.0), d(8.2, -1.8), d(6.4, -5.6),
        d(4.1, -9.0), d(1.8, -11.5), d(0.0, -12.2),
        d(-1.8, -11.5), d(-4.1, -9.0), d(-6.4, -5.6), d(-8.2, -1.8), d(-8.8, 3.0),
        d(-8.0, 7.8), d(-5.0, 11.5)
    )

    private const val HEAD_CENTER_F = .925

    /** 팔 — (키 비율, 중심선 cm, 반굵기 cm). 팔은 뼈다: 굵기는 값에 불변이고 이동만 한다. */
    private val ARM: List<DoubleArray> = listOf(
        t(.788, 13.10, 3.25), t(.768, 13.30, 3.02), t(.740, 13.80, 2.72),
        t(.705, 14.10, 2.52), t(.672, 14.18, 2.40), t(.645, 14.15, 2.28),
        t(.607, 14.05, 2.36),
        t(.567, 13.75, 2.12), t(.528, 13.25, 1.80), t(.500, 12.95, 1.52),
        t(.480, 13.02, 1.55),
        t(.458, 13.10, 1.26), t(.438, 13.14, 0.85), t(.426, 13.08, 0.52)
    )

    /** 옆 부풂 혹의 프로필 — (대역 내 위치 0..1, 세기 0..1). 양끝이 0이라 각이 생기지 않는다. */
    private val SIDE_BUMP: List<DoubleArray> = listOf(
        d(0.0, 0.0), d(.08, .22), d(.18, .55), d(.30, .85), d(.40, 1.0),
        d(.55, .88), d(.70, .62), d(.85, .30), d(1.0, 0.0)
    )

    /** 측면 등선 — (키 비율, x cm). 음수가 등 쪽이다. */
    private val S_BACK: List<DoubleArray> = listOf(
        d(.845, -3.2), d(.832, -3.9), d(.818, -4.8), d(.803, -5.8), d(.788, -6.8),
        d(.772, -7.6), d(.756, -8.2), d(.738, -8.5), d(.720, -8.3), d(.702, -7.8),
        d(.684, -7.0), d(.666, -6.2), d(.649, -5.6), d(.633, -5.2), d(.617, -5.2),
        d(.601, -5.6), d(.586, -6.2), d(.572, -7.1), d(.558, -8.3), d(.545, -9.4),
        d(.533, -10.1), d(.522, -10.4), d(.511, -10.2), d(.501, -9.5), d(.492, -8.4),
        d(.484, -7.4), d(.472, -6.9), d(.455, -6.7), d(.435, -6.4), d(.413, -6.1),
        d(.390, -5.7), d(.366, -5.2), d(.342, -4.8), d(.318, -4.5), d(.298, -4.4),
        d(.278, -4.8), d(.262, -5.7), d(.248, -6.1), d(.232, -5.9), d(.212, -5.3),
        d(.190, -4.5), d(.166, -3.7), d(.142, -3.1), d(.118, -2.7), d(.094, -2.5),
        d(.070, -2.5), d(.050, -2.8), d(.034, -3.6), d(.018, -4.5), d(.006, -4.6),
        d(.001, -4.2)
    )

    /** 측면 앞선 — (키 비율, x cm). */
    private val S_FRONT: List<DoubleArray> = listOf(
        d(.845, 3.4), d(.833, 3.7), d(.821, 4.1), d(.809, 4.7), d(.797, 5.3),
        d(.785, 5.8), d(.773, 6.3), d(.762, 6.5), d(.757, 6.5),
        d(.748, 6.4), d(.738, 6.3), d(.728, 6.1), d(.718, 5.9), d(.710, 5.7),
        d(.702, 5.5), d(.694, 5.3), d(.684, 5.5), d(.672, 5.4), d(.658, 5.1),
        d(.645, 4.8), d(.632, 4.6), d(.618, 4.6), d(.604, 4.8), d(.590, 5.1),
        d(.576, 5.4), d(.562, 5.7), d(.548, 5.9), d(.534, 6.0), d(.524, 5.9),
        d(.514, 5.6), d(.507, 5.2), d(.501, 4.9),
        d(.494, 6.2), d(.484, 6.8), d(.472, 6.9), d(.458, 6.8), d(.440, 6.5),
        d(.420, 6.1), d(.398, 5.7), d(.375, 5.2), d(.352, 4.8), d(.330, 4.4),
        d(.308, 4.2), d(.288, 4.0), d(.266, 3.8), d(.244, 3.6), d(.222, 3.4),
        d(.200, 3.2), d(.178, 3.1), d(.156, 3.0), d(.134, 2.9), d(.112, 2.8),
        d(.090, 2.8), d(.070, 2.9), d(.052, 3.3), d(.038, 5.0), d(.026, 9.6),
        d(.014, 12.5), d(.005, 13.0), d(.001, 12.1)
    )

    /** 측면 머리 — 머리 중심 오프셋 (x cm, y cm). */
    private val S_HEAD: List<DoubleArray> = listOf(
        d(1.0, 12.3), d(5.4, 10.8), d(7.8, 7.2), d(8.6, 3.0), d(8.2, -1.5),
        d(7.4, -5.2), d(6.6, -8.6), d(4.6, -11.2), d(1.6, -12.2), d(-1.8, -12.0),
        d(-4.6, -10.6), d(-7.2, -8.0), d(-8.8, -4.2), d(-9.3, 0.0), d(-8.9, 4.4),
        d(-7.2, 8.6), d(-4.0, 11.4)
    )

    /** 측면 가슴 융기 프로필 — 매달린 물방울: 봉우리는 낮게, 윗면은 긴 사면. */
    private val S_BUMP: List<DoubleArray> = listOf(
        d(0.0, 0.0), d(.05, .30), d(.10, .62), d(.16, .86), d(.22, .96), d(.30, 1.0),
        d(.38, 1.0), d(.46, .97), d(.55, .88), d(.65, .72), d(.75, .53), d(.85, .33),
        d(.93, .16), d(1.0, 0.0)
    )

    /** 측면 머리 중심의 앞쪽 치우침(cm)과 몸 중심축 오프셋(cm). */
    private const val SIDE_HEAD_DX = 1.3
    private const val SIDE_AXIS_DX = -1.0

    private fun d(a: Double, b: Double) = doubleArrayOf(a, b)
    private fun t(a: Double, b: Double, c: Double) = doubleArrayOf(a, b, c)

    // ══════════════════════════════════════════════════════════════════════
    // 변형
    // ══════════════════════════════════════════════════════════════════════

    /** 실루엣이 쓰는 밑가슴(cm) — 실측이 있으면 그것, 없으면 허리 + [FIGURE_RIB_OFFSET_CM]. */
    fun figureUnderbust(m: Measures): Double = m.underbust ?: (m.waist + FIGURE_RIB_OFFSET_CM)

    /** 컵차(cm) = 가슴 − 밑가슴. 음수는 0으로, 상한은 [CUP_DIFF_CAP]. */
    fun cupDiff(m: Measures): Double = max(0.0, min(m.bust - figureUnderbust(m), CUP_DIFF_CAP))

    /**
     * 부위별 변형 스케일. 표시 클램프가 걸리면 해당 축의 플래그가 선다 — **값은 그대로다.**
     */
    fun scales(m: Measures): Scales {
        val sh0 = (m.shoulder / BASE.shoulder).pow(ALPHA_SHOULDER)
        val w0 = (m.waist / BASE.waist).pow(ALPHA_WAIST)
        val h0 = (m.hip / BASE.hip).pow(ALPHA_HIP)
        val sh = sh0.coerceIn(CLAMP_SHOULDER_MIN, CLAMP_SHOULDER_MAX)
        val w = w0.coerceIn(CLAMP_WAIST_MIN, CLAMP_WAIST_MAX)
        val h = h0.coerceIn(CLAMP_HIP_MIN, CLAMP_HIP_MAX)
        return Scales(
            shoulder = sh,
            waist = w,
            hip = h,
            rib = 1 + (w - 1) * .6,
            clampedShoulder = sh != sh0,
            clampedWaist = w != w0,
            clampedHip = h != h0
        )
    }

    /** 높이 [frac]에서의 몸통 스케일 — 앵커 사이를 코사인으로 잇는다. */
    fun scaleAt(frac: Double, sc: Scales): Double {
        val knots = listOf(
            .845 to 1.0,
            ANCHOR_SHOULDER to sc.shoulder,
            ANCHOR_BUST to sc.rib,
            ANCHOR_WAIST to sc.waist,
            ANCHOR_HIP to sc.hip,
            .290 to 1 + (sc.hip - 1) * .52,
            .006 to 1 + (sc.hip - 1) * .12
        )
        if (frac >= knots[0].first) return 1.0
        for (i in 0 until knots.size - 1) {
            val (a, sa) = knots[i]
            val (b, sb) = knots[i + 1]
            if (frac <= a && frac >= b) {
                val tt = (a - frac) / (a - b)
                var w = (1 - cos(PI * tt)) / 2
                // 허리→힙 구간만 늦게 벌어지게 — 힙 플레어가 빨라 보이는 것을 잡는다.
                if (a == ANCHOR_WAIST && b == ANCHOR_HIP) w = w.pow(1.3)
                return sa + (sb - sa) * w
            }
        }
        return knots.last().second
    }

    /** 컵차에서 파생되는 가슴 기하 전부. */
    fun bustGeom(m: Measures): BustGeom {
        val cd = cupDiff(m)
        // 컵차 20 이후 폭 성장 둔화 — 극단 컵에서 판형이 되는 것을 막는다.
        val eff = if (cd <= CUP_DIFF_SOFT_KNEE) cd
        else CUP_DIFF_SOFT_KNEE + (cd - CUP_DIFF_SOFT_KNEE) * CUP_DIFF_SOFT_SLOPE
        return BustGeom(
            cupDiff = cd,
            spill = min(.25 + max(0.0, cd - 5) * .105, MAX_SPILL_CM),
            massBase = 2.4 + eff * .64,
            topF = .757,
            // 밑가슴 라인은 흉곽 중부에 머문다 — 성장의 표현은 처짐이 아니라 돌출·폭이다.
            botF = .757 - (.047 + min(cd * .0008, .031))
        )
    }

    /** W-아크가 실제로 그려지는 폭의 흉곽 상한(cm). */
    fun arcCapCm(sc: Scales, bg: BustGeom): Double = 12.3 * sc.rib + bg.spill - .4

    /** W-아크 바깥 가장자리의 반폭(cm) — 상한에 걸리면 포화된다. */
    fun arcOuterCm(sc: Scales, bg: BustGeom): Double = min(bg.massBase, arcCapCm(sc, bg))

    /**
     * 가슴 핸들이 앉는 점 (P7).
     *
     * 실루엣 스필이 아니라 **W-아크 바깥 가장자리**다 — 스필 기준으로 잡으면 드래그
     * 이동거리가 4.7px라 조작이 안 된다(재저작 2차 결정의 해석 정정).
     */
    fun arcEdge(sc: Scales, bg: BustGeom): Triple<Double, Double, Boolean> {
        val g = min(1.0, bg.cupDiff / 22)
        val band = bg.topF - bg.botF
        val xCm = arcOuterCm(sc, bg) * (.98 - .10 * (1 - g))
        val yF = bg.botF + band * (.16 + .16 * g)
        val saturated = bg.massBase > arcCapCm(sc, bg) + .01
        return Triple(xCm, yF, saturated)
    }

    /** 높이 [frac]에서의 옆 부풂(cm) — 가슴 대역 밖은 0이다. */
    fun sideBump(frac: Double, bg: BustGeom): Double {
        if (frac >= bg.topF || frac <= bg.botF) return 0.0
        val tt = (frac - bg.botF) / (bg.topF - bg.botF)
        return bg.spill * profileAt(SIDE_BUMP, tt)
    }

    /** 프로필 표에서 코사인 보간으로 세기를 읽는다. */
    private fun profileAt(profile: List<DoubleArray>, t: Double): Double {
        for (i in 0 until profile.size - 1) {
            val (ta, wa) = profile[i][0] to profile[i][1]
            val (tb, wb) = profile[i + 1][0] to profile[i + 1][1]
            if (t in ta..tb) {
                val u = (t - ta) / (tb - ta)
                return wa + (wb - wa) * (1 - cos(PI * u)) / 2
            }
        }
        return 0.0
    }

    // ══════════════════════════════════════════════════════════════════════
    // 정면
    // ══════════════════════════════════════════════════════════════════════

    /** 정면 한 벌을 만든다. */
    fun front(m: Measures): FrontFigure {
        val sc = scales(m)
        val bg = bustGeom(m)
        val yOf = { f: Double -> m.height * f }
        val halfAt = { f: Double, halfCm: Double -> halfCm * scaleAt(f, sc) + sideBump(f, bg) }

        val loop = ArrayList<PointCm>(R_OUTER.size * 2 + R_INNER_UP.size * 2)
        for (p in R_OUTER) loop.add(PointCm(halfAt(p[0], p[1]), yOf(p[0])))
        for (p in R_INNER_UP) loop.add(PointCm(halfAt(p[0], p[1]), yOf(p[0])))
        for (i in R_INNER_UP.size - 2 downTo 0) {
            val p = R_INNER_UP[i]
            loop.add(PointCm(-halfAt(p[0], p[1]), yOf(p[0])))
        }
        for (i in R_OUTER.indices.reversed()) {
            val p = R_OUTER[i]
            loop.add(PointCm(-halfAt(p[0], p[1]), yOf(p[0])))
        }

        val headCy = yOf(HEAD_CENTER_F)
        val head = HEAD.map { PointCm(it[0], headCy + it[1]) }

        // 강체 플랭크 푸시 — 몸 굴곡을 점별로 따라 굽히면 뱀이 된다. 최대 부족량 하나로 민다.
        var push = 0.0
        for (a in ARM) {
            val f = a[0]
            if (f < .58 || f > .72) continue
            val need = outerHalfAt(f) * scaleAt(f, sc) + .4 + a[2] - a[1] * sc.shoulder
            if (need > push) push = need
        }
        val pushBlend = { f: Double ->
            when {
                f >= .78 -> 0.0
                f >= .70 -> (1 - cos(PI * (.78 - f) / .08)) / 2
                else -> 1.0
            }
        }
        val armPts = ARM.map { t(it[0], it[1] * sc.shoulder + push * pushBlend(it[0]), it[2]) }
        val arms = listOf(1.0, -1.0).map { side -> buildArm(armPts, side, yOf, capTop = true) }
        val forePts = armPts.filter { it[0] <= ELBOW_F }
        val forearms = listOf(1.0, -1.0).map { side -> buildArm(forePts, side, yOf, capTop = false) }

        return FrontFigure(
            body = loop,
            head = head,
            arms = arms,
            forearms = forearms,
            scales = sc,
            bust = bg,
            handles = handles(m, sc, bg)
        )
    }

    /** 팔 하나를 닫힌 고리로 만든다. 팔꿈치 절단면은 긋지 않는다(열린 윤곽 + 팁). */
    private fun buildArm(
        pts: List<DoubleArray>,
        side: Double,
        yOf: (Double) -> Double,
        capTop: Boolean
    ): List<PointCm> {
        if (pts.isEmpty()) return emptyList()
        val out = ArrayList<PointCm>(pts.size * 2 + 2)
        if (capTop) out.add(PointCm(side * pts[0][1], yOf(pts[0][0] + .0045)))
        for (p in pts) out.add(PointCm(side * (p[1] + p[2]), yOf(p[0])))
        val last = pts.last()
        out.add(PointCm(side * (last[1] + .04), yOf(last[0] - .004)))
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            out.add(PointCm(side * (p[1] - p[2]), yOf(p[0])))
        }
        return out
    }

    /** 변형 전 몸통 바깥 반폭(cm) — 팔 푸시 계산이 쓴다. */
    private fun outerHalfAt(frac: Double): Double {
        for (i in 0 until R_OUTER.size - 1) {
            val (a, xa) = R_OUTER[i][0] to R_OUTER[i][1]
            val (b, xb) = R_OUTER[i + 1][0] to R_OUTER[i + 1][1]
            if (frac <= a && frac >= b) return xa + (xb - xa) * (a - frac) / (a - b)
        }
        return R_OUTER.last()[1]
    }

    /** 네 핸들의 자리. 가슴만 W-아크 가장자리이고 나머지는 앵커 반폭이다. */
    fun handles(m: Measures, sc: Scales = scales(m), bg: BustGeom = bustGeom(m)): List<HandleAnchor> {
        val (bustX, bustY, saturated) = arcEdge(sc, bg)
        return listOf(
            HandleAnchor(
                BodySlot.SHOULDER, HALF_SHOULDER * sc.shoulder, ANCHOR_SHOULDER,
                sc.clampedShoulder, BodySlot.SHOULDER in m.estimated
            ),
            HandleAnchor(
                BodySlot.BUST, bustX, bustY,
                saturated, BodySlot.BUST in m.estimated
            ),
            HandleAnchor(
                BodySlot.WAIST, HALF_WAIST * sc.waist, ANCHOR_WAIST,
                sc.clampedWaist, BodySlot.WAIST in m.estimated
            ),
            HandleAnchor(
                BodySlot.HIP, HALF_HIP * sc.hip, ANCHOR_HIP,
                sc.clampedHip, BodySlot.HIP in m.estimated
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 측면 (P10 — 표시 전용)
    // ══════════════════════════════════════════════════════════════════════

    /** 측면 한 벌. 같은 수치에서 파생되며 핸들·드래그는 없다. */
    fun side(m: Measures): SideFigure {
        val sc = scales(m)
        val bg = bustGeom(m)
        val yOf = { f: Double -> m.height * f }
        val depth = min(0.8 + bg.cupDiff * .46, 14.0)
        val sideTop = bg.topF + .018

        val bump = { f: Double ->
            if (f >= sideTop || f <= bg.botF) 0.0
            else depth * profileAt(S_BUMP, (f - bg.botF) / (sideTop - bg.botF))
        }
        // 경계는 코사인 램프다 — 계단 문턱을 쓰면 클램프 극단에서 단차가 생긴다
        // ("max() 문턱 합성 금지"의 측면 이행, 판정 ③).
        val band = { f: Double, lo: Double, hi: Double ->
            val r = .02
            when {
                f <= lo - r || f >= hi + r -> 0.0
                f >= lo + r && f <= hi - r -> 1.0
                else -> {
                    val dd = if (f < lo + r) (f - (lo - r)) / (2 * r) else ((hi + r) - f) / (2 * r)
                    (1 - cos(PI * dd.coerceIn(0.0, 1.0))) / 2
                }
            }
        }
        val fMul = { f: Double -> 1 + (sc.waist - 1) * 1.05 * band(f, .495, .665) }
        val bMul = { f: Double ->
            1 + (sc.hip - 1) * 1.0 * band(f, .470, .585) +
                (sc.waist - 1) * .55 * band(f, .585, .680) +
                (sc.shoulder - 1) * .4 * band(f, .680, .800)
        }

        val loop = ArrayList<PointCm>(S_FRONT.size + S_BACK.size)
        for (p in S_FRONT) loop.add(PointCm(SIDE_AXIS_DX + p[1] * fMul(p[0]) + bump(p[0]), yOf(p[0])))
        for (i in S_BACK.indices.reversed()) {
            val p = S_BACK[i]
            loop.add(PointCm(SIDE_AXIS_DX + p[1] * bMul(p[0]), yOf(p[0])))
        }
        val headCy = yOf(HEAD_CENTER_F)
        val head = S_HEAD.map { PointCm(SIDE_AXIS_DX + it[0] + SIDE_HEAD_DX, headCy + it[1]) }

        return SideFigure(body = loop, head = head, scales = sc, bust = bg, bustDepthCm = depth)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 역산 — 핸들 드래그가 값으로 돌아오는 길 (양방향이 같은 상수를 쓴다)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 어깨·허리·엉덩이 핸들의 반폭(cm)에서 둘레 값을 되돌린다.
     *
     * 변형식 `s = (값/기준값)^α`의 역산 하나다 — `값 = 기준값 × (목표폭/기준폭)^(1/α)`.
     */
    fun valueFromHandle(slot: BodySlot, halfCm: Double): Double? {
        val (baseValue, baseHalf, alpha) = when (slot) {
            BodySlot.SHOULDER -> Triple(BASE.shoulder, HALF_SHOULDER, ALPHA_SHOULDER)
            BodySlot.WAIST -> Triple(BASE.waist, HALF_WAIST, ALPHA_WAIST)
            BodySlot.HIP -> Triple(BASE.hip, HALF_HIP, ALPHA_HIP)
            else -> return null
        }
        val needed = max(halfCm / baseHalf, .2)
        return baseValue * needed.pow(1 / alpha)
    }

    /** 가슴 역산 결과 — 포화되면 드래그가 거기서 멈추고 더 큰 컵은 슬라이더 경로다. */
    data class BustInverse(val bust: Double, val saturated: Boolean)

    /**
     * 가슴 핸들의 반폭(cm)에서 가슴둘레를 되돌린다 (P7의 닫힌 식).
     *
     * 아크 폭 → massBase → 컵차 → 가슴. 가장자리 계수 `g`가 컵차에 걸려 있어 반복하되,
     * 2회면 수렴한다(목업 실측).
     */
    fun bustFromHandle(m: Measures, halfCm: Double): BustInverse {
        var cd = cupDiff(m)
        var saturated = false
        val under = figureUnderbust(m)
        repeat(3) {
            val g = min(1.0, cd / 22)
            val edgeFactor = .98 - .10 * (1 - g)
            val probe = m.copy(bust = under + cd)
            val cap = arcCapCm(scales(probe), bustGeom(probe))
            var mass = halfCm / edgeFactor
            if (mass >= cap) {
                mass = cap
                saturated = true
            } else {
                saturated = false
            }
            val eff = max(0.0, (mass - 2.4) / .64)
            cd = if (eff <= CUP_DIFF_SOFT_KNEE) eff
            else CUP_DIFF_SOFT_KNEE + (eff - CUP_DIFF_SOFT_KNEE) / CUP_DIFF_SOFT_SLOPE
        }
        return BustInverse(bust = under + cd, saturated = saturated)
    }

    // ══════════════════════════════════════════════════════════════════════
    // 부분 슬롯 — 빠진 부위는 이웃에서 보간하고 그 사실을 남긴다
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 해석된 수치에서 실루엣 입력을 만든다.
     *
     * 빠진 부위는 있는 이웃의 기준 대비 비율로 채우고 [Measures.estimated]에 담는다 —
     * 화면은 그 핸들을 비활성·점선으로 그려 추정임을 보인다(무음 금지).
     * 하나도 없으면 null을 돌려주며, 그때는 실루엣 대신 바 폴백이다.
     */
    fun measuresFrom(m: BodyMeasurements): Measures? {
        val bust = m.bust
        val waist = m.waist
        val hip = m.hip
        val shoulder = m.shoulder
        if (bust == null && waist == null && hip == null && shoulder == null) return null

        val estimated = mutableSetOf<BodySlot>()
        // 있는 부위들의 기준 대비 평균 비율 — 아무것도 없을 때의 마지막 기댈 곳.
        val known = buildList {
            bust?.let { add(it / BASE.bust) }
            waist?.let { add(it / BASE.waist) }
            hip?.let { add(it / BASE.hip) }
            shoulder?.let { add(it / BASE.shoulder) }
        }
        val overall = if (known.isEmpty()) 1.0 else known.average()

        val rWaist = waist?.div(BASE.waist)
        val rHip = hip?.div(BASE.hip)
        val rBust = bust?.div(BASE.bust)

        val resolvedWaist = waist ?: run {
            estimated.add(BodySlot.WAIST)
            // 허리는 가슴·엉덩이 사이에 있다 — 있는 쪽들의 평균으로 잡는다.
            BASE.waist * (listOfNotNull(rBust, rHip).takeIf { it.isNotEmpty() }?.average() ?: overall)
        }
        val resolvedBust = bust ?: run {
            estimated.add(BodySlot.BUST)
            BASE.bust * (rWaist ?: rHip ?: overall)
        }
        val resolvedHip = hip ?: run {
            estimated.add(BodySlot.HIP)
            BASE.hip * (rWaist ?: rBust ?: overall)
        }
        val resolvedShoulder = shoulder ?: run {
            estimated.add(BodySlot.SHOULDER)
            BASE.shoulder * (rBust ?: overall)
        }
        val heightKnown = m.heightCm != null && m.heightCm > 0

        return Measures(
            height = if (heightKnown) m.heightCm!! else BASE.height,
            shoulder = resolvedShoulder,
            bust = resolvedBust,
            waist = resolvedWaist,
            hip = resolvedHip,
            underbust = m.underbust,
            estimated = estimated,
            heightKnown = heightKnown
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // 곡선 — Catmull-Rom을 3차 베지어로. 뷰는 이 결과를 Path에 넣기만 한다.
    // ══════════════════════════════════════════════════════════════════════

    /** 3차 베지어 한 구간 (시작점은 앞 구간의 끝점). */
    data class CubicSegment(
        val c1x: Double, val c1y: Double,
        val c2x: Double, val c2y: Double,
        val x: Double, val y: Double
    )

    /**
     * 정점 배열을 잇는 베지어 구간들을 만든다.
     *
     * 접힘·크레스트 근처에서 마루가 깎이지 않으려면 표본 간격이 촘촘해야 한다 —
     * 정점 정본이 가슴 대역에서 촘촘한 이유가 그것이다(설계 4-1 구현 노트).
     */
    fun cubicSegments(points: List<PointCm>, closed: Boolean): List<CubicSegment> {
        val n = points.size
        if (n < 2) return emptyList()
        val at = { i: Int ->
            if (closed) points[((i % n) + n) % n] else points[i.coerceIn(0, n - 1)]
        }
        val last = if (closed) n else n - 1
        val out = ArrayList<CubicSegment>(last)
        for (i in 0 until last) {
            val p0 = at(i - 1)
            val p1 = at(i)
            val p2 = at(i + 1)
            val p3 = at(i + 2)
            out.add(
                CubicSegment(
                    c1x = p1.x + (p2.x - p0.x) / 6, c1y = p1.y + (p2.y - p0.y) / 6,
                    c2x = p2.x - (p3.x - p1.x) / 6, c2y = p2.y - (p3.y - p1.y) / 6,
                    x = p2.x, y = p2.y
                )
            )
        }
        return out
    }

    // ══════════════════════════════════════════════════════════════════════
    // 단위 휴리스틱 (P9-①)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 인치로 적은 것 같은가 — 전 파트가 20~50이고 비율이 정합이면 의심한다.
     *
     * 판단이지 강제가 아니다: 화면은 고지하고 교정 경로를 줄 뿐 값을 바꾸지 않는다.
     */
    fun looksLikeInches(m: BodyMeasurements): Boolean {
        val core = listOfNotNull(m.bust, m.waist, m.hip)
        if (core.size < 3) return false
        if (!core.all { it in 20.0..50.0 }) return false
        val h = m.heightCm ?: return false
        return m.bust!! >= m.waist!! && m.hip!! >= m.waist!! && h >= 130
    }

    /** 인치로 본 값을 cm로 옮긴다. */
    fun inchesToCm(value: Double): Double = value * 2.54

    // ══════════════════════════════════════════════════════════════════════
    // 요약 축 (P5·P6) — 3축 + 종합 인상 한 줄
    // ══════════════════════════════════════════════════════════════════════

    /** 3축 요약. 어휘는 전부 긍정 프레이밍이다(고르는 옵션이 다 매력적이어야 한다). */
    data class AxisSummary(val torso: String, val cup: String, val hip: String, val line: String)

    private val CUP_LABELS = listOf("AA", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J+")

    /** 컵차(cm)를 라벨로. 표는 [BodyAnalysisConfig.cupMapping]이 정본이고 여기는 그림용 근사다. */
    fun cupLabel(cupDiff: Double): String {
        if (cupDiff <= 0) return "—"
        val idx = kotlin.math.ceil((cupDiff - 7.5) / 2.5).toInt().coerceIn(0, CUP_LABELS.size - 1)
        return CUP_LABELS[idx]
    }

    /** 3축 요약과 종합 인상을 낸다. 문턱값은 목-⑤의 잠정치이며 작품 평균 실측으로 교정한다. */
    fun axisSummary(m: Measures): AxisSummary {
        val cd = cupDiff(m)
        val torsoR = m.waist / m.height
        val hipD = m.hip - m.waist
        val whr = if (m.hip > 0) m.waist / m.hip else 1.0
        val torso = when {
            torsoR <= .362 -> "슬림"
            torsoR <= .402 -> "표준"
            else -> "소프트"
        }
        val hip = when {
            hipD <= 24 -> "힙 슬림"
            hipD <= 31 -> "힙 표준"
            else -> "볼륨힙"
        }
        // 종합 인상 한 개 — X/I/A/V/O. 종전 '배형'은 O/A로 흡수했다(긍정 프레이밍).
        val line = when {
            torsoR >= .43 -> "O라인"
            cd >= 15 && hipD >= 28 && whr <= .74 -> "X라인"
            cd >= 15 && hipD < 24 -> "V라인"
            cd < 12 && hipD >= 30 -> "A라인"
            cd < 12 && hipD < 24 -> "I라인"
            else -> "밸런스"
        }
        return AxisSummary(torso = torso, cup = cupLabel(cd), hip = hip, line = line)
    }

    /** 두 스펙이 같은 그림인가 — 회귀 테스트가 쓰는 허용 오차 비교. */
    fun samePoints(a: List<PointCm>, b: List<PointCm>, tolerance: Double = 1e-6): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { abs(a[it].x - b[it].x) <= tolerance && abs(a[it].y - b[it].y) <= tolerance }
    }
}
