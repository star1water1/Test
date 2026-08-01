package com.novelcharacter.app.util

import com.novelcharacter.app.data.model.BodySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 실루엣 지오메트리의 계약 (설계 4장).
 *
 * 여기서 지키는 것은 좌표 그 자체가 아니라 **저작 규칙**이다 — 재저작 세션이 렌더로
 * 피 흘려 얻은 규칙들(흉통·가슴 독립 · 가산 혹 · 클램프는 표시만 · 팔은 뼈)이
 * 코드에서 깨지면 그림이 다시 뾰족해지고 마름모가 된다.
 */
class BodySilhouetteSpecTest {

    private val spec = BodySilhouetteSpec

    private fun m(
        height: Double = 165.0, shoulder: Double = 38.0,
        bust: Double = 86.0, waist: Double = 62.0, hip: Double = 90.0,
        underbust: Double? = null
    ) = BodySilhouetteSpec.Measures(height, shoulder, bust, waist, hip, underbust)

    // ── 변형 ─────────────────────────────────────────────────────────────

    @Test
    fun `기준 몸은 스케일이 정확히 1이다`() {
        val sc = spec.scales(spec.BASE)
        assertEquals(1.0, sc.shoulder, 1e-9)
        assertEquals(1.0, sc.waist, 1e-9)
        assertEquals(1.0, sc.hip, 1e-9)
        assertEquals(1.0, sc.rib, 1e-9)
        assertFalse(sc.anyClamped)
    }

    @Test
    fun `변형은 감쇠가 아니라 증폭이다`() {
        // 이 장르의 문법은 몸매 차이가 실측보다 크게 읽히는 것이다(설계 4-1 — 세 번 같은
        // 방향으로 교정된 결론). α<1로 되돌아가면 "차이가 안 읽힌다"가 재발한다.
        val wide = spec.scales(m(waist = 74.4))   // 기준 대비 +20%
        val narrow = spec.scales(m(waist = 49.6)) // 기준 대비 -20%
        assertTrue("허리 확대가 실측 비율보다 커야 한다", wide.waist > 1.20)
        assertTrue("허리 축소가 실측 비율보다 커야 한다", narrow.waist < 0.80)

        val bigHip = spec.scales(m(hip = 108.0))  // +20%
        assertTrue("엉덩이도 증폭", bigHip.hip > 1.20)
    }

    @Test
    fun `흉곽은 허리를 따르되 덜 따른다`() {
        val sc = spec.scales(m(waist = 74.4))
        assertTrue("흉곽이 허리를 아예 안 따르면 몸통이 끊긴다", sc.rib > 1.0)
        assertTrue("흉곽이 허리만큼 따르면 통이 된다", sc.rib < sc.waist)
    }

    // ── 클램프는 표시만 ───────────────────────────────────────────────────

    @Test
    fun `극단값은 그림만 한계 안에 들고 값은 건드리지 않는다`() {
        val extreme = m(waist = 20.0, hip = 200.0, shoulder = 90.0)
        val sc = spec.scales(extreme)

        assertEquals(BodySilhouetteSpec.CLAMP_WAIST_MIN, sc.waist, 1e-9)
        assertEquals(BodySilhouetteSpec.CLAMP_HIP_MAX, sc.hip, 1e-9)
        assertEquals(BodySilhouetteSpec.CLAMP_SHOULDER_MAX, sc.shoulder, 1e-9)

        assertTrue(sc.clampedWaist)
        assertTrue(sc.clampedHip)
        assertTrue(sc.clampedShoulder)
        assertTrue(sc.anyClamped)

        // 값은 절대 불변 — 클램프는 표시만이다.
        assertEquals(20.0, extreme.waist, 1e-9)
        assertEquals(200.0, extreme.hip, 1e-9)
    }

    @Test
    fun `클램프 플래그는 걸린 축에만 선다`() {
        val sc = spec.scales(m(waist = 20.0))
        assertTrue(sc.clampedWaist)
        assertFalse("멀쩡한 축까지 경고색이 되면 경고가 뜻을 잃는다", sc.clampedHip)
        assertFalse(sc.clampedShoulder)
    }

    @Test
    fun `극단값에서도 좌표가 유한하다`() {
        // 공격 상태 — 새 기하를 만들면 반드시 부숴 볼 것(인수인계 4장).
        val attacks = listOf(
            m(height = 130.0, shoulder = 20.0, bust = 60.0, waist = 40.0, hip = 60.0),
            m(height = 205.0, shoulder = 60.0, bust = 200.0, waist = 110.0, hip = 160.0),
            m(bust = 200.0, waist = 45.0, hip = 60.0),
            m(bust = 60.0, waist = 110.0, hip = 60.0),
            m(height = 1.0, bust = 1.0, waist = 1.0, hip = 1.0, shoulder = 1.0)
        )
        for (a in attacks) {
            val f = spec.front(a)
            val s = spec.side(a)
            val all = f.body + f.head + f.arms.flatten() + f.forearms.flatten() + s.body + s.head
            for (p in all) {
                assertTrue("$a 에서 NaN/무한 좌표가 나왔다", p.x.isFinite() && p.y.isFinite())
            }
            for (h in f.handles) {
                assertTrue("$a 의 핸들 좌표", h.halfCm.isFinite() && h.heightFraction.isFinite())
            }
        }
    }

    // ── 흉통과 가슴의 완전 독립 ───────────────────────────────────────────

    @Test
    fun `가슴 값은 가슴 대역 밖의 몸통 폭을 한 치도 바꾸지 않는다`() {
        // 설계 4-1의 확정 규칙. 가슴을 몸통 옆선에 넣으면 마름모(◇)가 되고,
        // 약한 결합조차 "가슴을 키우면 흉통이 같이 큰다"로 지각된다.
        val small = m(bust = 70.0)
        val huge = m(bust = 130.0)
        val fs = spec.front(small)
        val fh = spec.front(huge)

        assertEquals(fs.body.size, fh.body.size)
        val bandTop = fs.bust.topF
        val bandBot = spec.bustGeom(huge).botF
        var differedInBand = false
        for (i in fs.body.indices) {
            val a = fs.body[i]
            val b = fh.body[i]
            val frac = a.y / small.height
            if (frac in bandBot..bandTop) {
                if (abs(a.x - b.x) > 1e-9) differedInBand = true
            } else {
                assertEquals(
                    "가슴 대역 밖(키 비율 $frac)의 몸통 폭이 가슴 값에 흔들렸다", a.x, b.x, 1e-9
                )
            }
        }
        assertTrue("가슴 대역 안에서는 달라져야 한다(옆 부풂)", differedInBand)
    }

    @Test
    fun `옆 부풂은 상한을 넘지 않는다`() {
        // 가슴은 앞으로 돌출한다 — 정면 옆 폭이 아니다(스필·덩어리 분리).
        for (bust in listOf(70.0, 90.0, 110.0, 150.0, 200.0)) {
            val bg = spec.bustGeom(m(bust = bust))
            assertTrue(
                "가슴 ${bust}에서 옆 부풂 ${bg.spill}이 상한을 넘었다",
                bg.spill <= BodySilhouetteSpec.MAX_SPILL_CM + 1e-9
            )
        }
    }

    @Test
    fun `부풂은 대역 양끝에서 정확히 0이라 각이 생기지 않는다`() {
        // `max()` 문턱 합성 금지 — 문턱 교차마다 각이 생겨 옆가슴이 뾰족해진다.
        val v = m(bust = 100.0)
        val bg = spec.bustGeom(v)
        assertEquals(0.0, spec.sideBump(bg.topF, bg), 1e-12)
        assertEquals(0.0, spec.sideBump(bg.botF, bg), 1e-12)
        assertEquals(0.0, spec.sideBump(bg.topF + .01, bg), 1e-12)
        assertEquals(0.0, spec.sideBump(bg.botF - .01, bg), 1e-12)

        // 양끝 바로 안쪽도 0에 붙어 있어야 한다(접선 합류).
        val band = bg.topF - bg.botF
        assertTrue(spec.sideBump(bg.botF + band * .002, bg) < .05)
        assertTrue(spec.sideBump(bg.topF - band * .002, bg) < .05)
    }

    @Test
    fun `부풂은 대역 안에서 연속이다`() {
        val v = m(bust = 110.0)
        val bg = spec.bustGeom(v)
        var prev = spec.sideBump(bg.botF, bg)
        var step = bg.botF
        val delta = (bg.topF - bg.botF) / 400
        while (step <= bg.topF) {
            val cur = spec.sideBump(step, bg)
            assertTrue("키 비율 $step 에서 부풂이 튀었다 ($prev → $cur)", abs(cur - prev) < .12)
            prev = cur
            step += delta
        }
    }

    @Test
    fun `극단 컵의 폭 성장은 둔화된다`() {
        // 판형 재발 차단 — 컵차 20 이후 완만해져야 한다.
        val a = spec.bustGeom(m(bust = 62.0 + 6 + 10)).massBase
        val b = spec.bustGeom(m(bust = 62.0 + 6 + 20)).massBase
        val c = spec.bustGeom(m(bust = 62.0 + 6 + 30)).massBase
        assertTrue("둔화 전 구간의 성장", b - a > 5.0)
        assertTrue("둔화 후 구간이 더 완만해야 한다", c - b < (b - a) * .5)
    }

    @Test
    fun `컵차는 음수가 되지 않고 상한이 있다`() {
        assertEquals(0.0, spec.cupDiff(m(bust = 50.0, waist = 62.0)), 1e-9)
        assertTrue(spec.cupDiff(m(bust = 300.0, waist = 62.0)) <= 40.0)
    }

    @Test
    fun `실측 밑가슴이 있으면 컵차가 그것으로 계산된다`() {
        // 갈비뼈가 큰 캐릭터를 정확히 표현할 수 있게 하는 자리.
        val approx = m(bust = 95.0, waist = 62.0)                  // 밑가슴 근사 = 68
        val measured = m(bust = 95.0, waist = 62.0, underbust = 80.0)
        assertEquals(27.0, spec.cupDiff(approx), 1e-9)
        assertEquals(15.0, spec.cupDiff(measured), 1e-9)
        assertTrue(
            "실측 밑가슴이 크면 같은 가슴둘레라도 컵이 작아야 한다",
            spec.bustGeom(measured).massBase < spec.bustGeom(approx).massBase
        )
    }

    // ── 역산 (양방향이 같은 상수를 쓴다) ──────────────────────────────────

    @Test
    fun `어깨 허리 엉덩이 핸들은 왕복 항등이다`() {
        for (v in listOf(m(), m(waist = 55.0, hip = 96.0, shoulder = 41.0), m(waist = 70.0, hip = 82.0))) {
            val handles = spec.front(v).handles.associateBy { it.slot }
            assertEquals(
                "어깨 왕복", v.shoulder,
                spec.valueFromHandle(BodySlot.SHOULDER, handles[BodySlot.SHOULDER]!!.halfCm)!!, 1e-6
            )
            assertEquals(
                "허리 왕복", v.waist,
                spec.valueFromHandle(BodySlot.WAIST, handles[BodySlot.WAIST]!!.halfCm)!!, 1e-6
            )
            assertEquals(
                "엉덩이 왕복", v.hip,
                spec.valueFromHandle(BodySlot.HIP, handles[BodySlot.HIP]!!.halfCm)!!, 1e-6
            )
        }
    }

    @Test
    fun `가슴 핸들도 왕복 항등이다`() {
        // 포화되지 않은 컵 — 아크 폭 → massBase → 컵차 → 가슴의 닫힌 식(P7).
        val v = m(bust = 82.0, waist = 62.0)
        val handle = spec.front(v).handles.first { it.slot == BodySlot.BUST }
        val back = spec.bustFromHandle(v, handle.halfCm)
        assertFalse("이 값은 포화되지 않아야 한다", back.saturated)
        assertEquals(v.bust, back.bust, 1e-6)
    }

    @Test
    fun `가슴 핸들은 흉곽 폭에 포화되면 그것을 알린다`() {
        // 포화되면 드래그는 거기서 멈추고 더 큰 컵은 슬라이더 경로다(P7).
        val v = m(bust = 120.0, waist = 62.0)
        val handle = spec.front(v).handles.first { it.slot == BodySlot.BUST }
        assertTrue("포화가 핸들 경고로 나가야 한다", handle.warn)
        assertTrue(spec.bustFromHandle(v, handle.halfCm).saturated)
    }

    @Test
    fun `핸들을 넓게 끌면 값이 커진다`() {
        val v = m()
        val handles = spec.front(v).handles.associateBy { it.slot }
        for (slot in listOf(BodySlot.SHOULDER, BodySlot.WAIST, BodySlot.HIP)) {
            val here = handles[slot]!!.halfCm
            assertTrue(
                "$slot 을 넓히면 값도 커져야 한다",
                spec.valueFromHandle(slot, here + 2.0)!! > spec.valueFromHandle(slot, here)!!
            )
        }
        // 가슴은 포화 전 구간에서만 드래그가 값을 옮긴다 — 포화 뒤는 슬라이더 경로다(P7).
        val unsaturated = m(bust = 76.0, waist = 62.0)
        val bustHandle = spec.front(unsaturated).handles.first { it.slot == BodySlot.BUST }
        assertFalse(bustHandle.warn)
        assertTrue(
            "가슴도 마찬가지",
            spec.bustFromHandle(unsaturated, bustHandle.halfCm + 1.0).bust >
                spec.bustFromHandle(unsaturated, bustHandle.halfCm).bust
        )
    }

    @Test
    fun `가슴 핸들은 스필보다 훨씬 크게 움직인다`() {
        // P7의 근거 그 자체 — 스필 기준으로 핸들을 앉히면 이동거리가 4.7px라 조작이 안 됐다.
        // 아크 가장자리 기준이라야 잡을 수 있다.
        val small = m(bust = 72.0, waist = 62.0)   // 컵차 4
        val large = m(bust = 82.0, waist = 62.0)   // 컵차 14 (아직 포화 전)
        val here = spec.front(small).handles.first { it.slot == BodySlot.BUST }.halfCm
        val there = spec.front(large).handles.first { it.slot == BodySlot.BUST }.halfCm
        val handleTravel = there - here
        val spillTravel = spec.bustGeom(large).spill - spec.bustGeom(small).spill

        assertTrue("컵차 10cm 차이가 핸들에서 읽혀야 한다", handleTravel > 2.5)
        assertTrue(
            "아크 기준 이동($handleTravel)이 스필 기준($spillTravel)보다 훨씬 커야 한다",
            handleTravel > spillTravel * 3
        )
    }

    @Test
    fun `역산은 몸통 부위에만 답한다`() {
        assertNull(spec.valueFromHandle(BodySlot.BUST, 10.0))
        assertNull(spec.valueFromHandle(BodySlot.NONE, 10.0))
        assertNull(spec.valueFromHandle(BodySlot.UNDERBUST, 10.0))
    }

    // ── 부분 슬롯 ─────────────────────────────────────────────────────────

    @Test
    fun `빠진 부위는 이웃에서 보간하고 추정임을 남긴다`() {
        val partial = BodyMeasurements.resolveParts(
            labels = listOf("가슴", "허리"), texts = listOf("88", "60"), heightText = "165"
        )
        val v = spec.measuresFrom(partial)!!
        assertTrue("엉덩이는 추정이다", BodySlot.HIP in v.estimated)
        assertFalse("있는 값은 추정이 아니다", BodySlot.BUST in v.estimated)
        assertFalse(BodySlot.WAIST in v.estimated)
        assertTrue("보간값이 말이 되는 범위여야 한다", v.hip in 60.0..130.0)

        val handles = spec.front(v).handles.associateBy { it.slot }
        assertTrue("추정 부위의 핸들은 그 사실을 들고 있어야 한다", handles[BodySlot.HIP]!!.estimated)
        assertFalse(handles[BodySlot.BUST]!!.estimated)
    }

    @Test
    fun `아무 부위도 없으면 실루엣을 만들지 않는다`() {
        // 그때는 그림 대신 바 폴백이다 — 없는 값을 그럴듯하게 그려 주지 않는다.
        val none = BodyMeasurements.resolveParts(labels = listOf("a"), texts = listOf("모름"))
        assertNull(spec.measuresFrom(none))
    }

    @Test
    fun `키가 없으면 기준 신장으로 비례만 그린다`() {
        val noHeight = BodyMeasurements.resolveParts(
            labels = listOf("B", "W", "H"), texts = listOf("88", "60", "90")
        )
        val v = spec.measuresFrom(noHeight)!!
        assertFalse("키 미입력이 화면에 고지돼야 한다", v.heightKnown)
        assertEquals(spec.BASE.height, v.height, 1e-9)
    }

    // ── 팔 ───────────────────────────────────────────────────────────────

    @Test
    fun `팔 굵기는 값에 불변이고 위치만 옮긴다`() {
        // 팔이 굵어지면 값을 암시하게 된다 — 팔은 어깨 스케일을 따라 이동만 한다.
        fun widths(v: BodySilhouetteSpec.Measures): List<Double> {
            val arm = spec.front(v).arms[0]
            // 닫힌 고리의 앞 절반이 바깥선, 뒤 절반이 안쪽선 — 같은 높이끼리의 폭을 본다.
            return arm.map { it.x }
        }
        val slim = widths(m(hip = 80.0))
        val wide = widths(m(hip = 110.0))
        assertEquals(slim.size, wide.size)
        assertTrue("엉덩이만 바꿨는데 팔이 달라지면 안 된다", slim.zip(wide).all { abs(it.first - it.second) < 1e-9 })
    }

    @Test
    fun `팔은 넓어진 몸을 관통하지 않는다`() {
        // 강체 플랭크 푸시 — 몸이 넓어지면 팔이 통째로 바깥으로 밀린다.
        val narrow = spec.front(m(waist = 55.0))
        val wide = spec.front(m(waist = 100.0))
        // 전완 구간은 푸시가 온전히 실리는 자리다(어깨 쪽은 블렌드가 0으로 잦아든다 —
        // 팔이 어깨에서 뽑히지 않게 하는 장치라 거기서 재면 아무 차이도 안 보인다).
        val narrowInner = narrow.forearms[0].minOf { it.x }
        val wideInner = wide.forearms[0].minOf { it.x }
        assertTrue("허리가 굵어지면 팔이 바깥으로 밀려야 한다", wideInner > narrowInner)
    }

    @Test
    fun `전완은 팔꿈치 아래만 든다`() {
        val f = spec.front(m())
        assertTrue(f.forearms[0].isNotEmpty())
        assertTrue("전완이 위팔보다 짧아야 z-순서가 뜻을 갖는다", f.forearms[0].size < f.arms[0].size)
    }

    // ── 측면 (표시 전용) ──────────────────────────────────────────────────

    @Test
    fun `측면은 같은 수치에서 나오고 컵이 클수록 깊다`() {
        val small = spec.side(m(bust = 70.0))
        val big = spec.side(m(bust = 110.0))
        assertTrue("가슴이 크면 전방 돌출이 깊어야 한다", big.bustDepthCm > small.bustDepthCm)
        assertTrue("깊이에도 상한이 있다", spec.side(m(bust = 250.0)).bustDepthCm <= 14.0)
    }

    @Test
    fun `측면은 배와 엉덩이가 각자의 값을 따른다`() {
        // 부위 깊이 스케일은 허리·힙·어깨에 각각 걸린다 — 한 축이 온 몸을 밀면 안 된다.
        fun frontMax(v: BodySilhouetteSpec.Measures, lo: Double, hi: Double) =
            spec.side(v).body.filter { it.x > 0 && it.y / v.height in lo..hi }.maxOf { it.x }

        fun backMin(v: BodySilhouetteSpec.Measures, lo: Double, hi: Double) =
            spec.side(v).body.filter { it.x < 0 && it.y / v.height in lo..hi }.minOf { it.x }

        // 컵차를 10으로 고정해 가슴 융기가 배 구간을 건드리지 않게 둔다.
        val slimWaist = m(waist = 55.0, bust = 71.0)
        val softWaist = m(waist = 75.0, bust = 91.0)
        assertTrue(
            "허리가 굵으면 배가 앞으로 나와야 한다",
            frontMax(softWaist, .55, .66) > frontMax(slimWaist, .55, .66)
        )

        assertTrue(
            "엉덩이가 크면 둔부가 뒤로 나와야 한다",
            backMin(m(hip = 105.0), .48, .56) < backMin(m(hip = 80.0), .48, .56)
        )
    }

    // ── 볼륨 표기층 (셀 문법 + 컵 게이팅) ─────────────────────────────────

    @Test
    fun `빈유는 가슴 음영을 걷고 언더 힌트만 남긴다`() {
        // 물방울 ⑤항 — 작을 때는 볼륨이 옅어져 납작해지되 형상 자체가 사라지지는 않는다.
        val flat = spec.volumeShapes(m(bust = 66.0, waist = 62.0))   // 컵차 0
        val full = spec.volumeShapes(m(bust = 92.0, waist = 62.0))   // 컵차 18

        val flatShade = flat.filterIsInstance<BodySilhouetteSpec.VolumeShape.Shade>()
        assertTrue("컵차 0에서 가슴 플랫 음영이 남아 있으면 안 된다", flatShade.isEmpty())
        assertTrue(full.filterIsInstance<BodySilhouetteSpec.VolumeShape.Shade>().isNotEmpty())

        // 형상이 통째로 사라지지는 않는다 — 쇄골·허리·골반·배꼽은 컵과 무관하다.
        assertTrue(
            "컵이 0이어도 쇄골은 남는다",
            flat.filterIsInstance<BodySilhouetteSpec.VolumeShape.Contour>().isNotEmpty()
        )
        assertTrue(
            "컵이 0이어도 허리·골반·배꼽 음영은 남는다",
            flat.filterIsInstance<BodySilhouetteSpec.VolumeShape.Blob>().size >= 5
        )
    }

    @Test
    fun `골 라인과 상부 사면선은 컵이 자라야 든다`() {
        fun hasCleavage(bust: Double): Boolean {
            val shapes = spec.volumeShapes(m(bust = bust, waist = 62.0))
            return shapes.filterIsInstance<BodySilhouetteSpec.VolumeShape.Contour>()
                .any { it.points.size == 2 && it.points.all { p -> abs(p.x) < 1e-9 } }
        }
        assertFalse("작은 컵에 골이 생기면 안 된다", hasCleavage(72.0))
        assertTrue("C쯤부터는 골이 들어야 한다", hasCleavage(92.0))
    }

    @Test
    fun `볼륨 음영은 전부 셀 문법의 유효한 값이다`() {
        for (v in listOf(m(), m(bust = 130.0, waist = 50.0, hip = 110.0), m(bust = 60.0, waist = 100.0))) {
            val shapes = spec.volumeShapes(v)
            assertTrue(shapes.isNotEmpty())
            for (s in shapes) {
                assertTrue("불투명도가 범위를 벗어났다 (${s.alpha})", s.alpha in 0.0..1.0)
                val pts = when (s) {
                    is BodySilhouetteSpec.VolumeShape.Shade -> s.points
                    is BodySilhouetteSpec.VolumeShape.Contour -> s.points
                    is BodySilhouetteSpec.VolumeShape.Blob -> listOf(s.center)
                }
                assertTrue("빈 도형", pts.isNotEmpty())
                for (p in pts) assertTrue("$v 볼륨층 좌표가 유한하지 않다", p.x.isFinite() && p.y.isFinite())
            }
        }
    }

    @Test
    fun `아크는 컵이 클수록 길고 깊고 굵어진다`() {
        fun arc(bust: Double) = spec.volumeShapes(m(bust = bust, waist = 62.0))
            .filterIsInstance<BodySilhouetteSpec.VolumeShape.Contour>()
            .first { it.accent && it.points.size == 5 }
        val small = arc(76.0)
        val large = arc(96.0)
        assertTrue("굵기", large.widthCm > small.widthCm)
        assertTrue("진하기", large.alpha > small.alpha)
    }

    // ── 곡선 ─────────────────────────────────────────────────────────────

    @Test
    fun `닫힌 고리는 정점 수만큼 구간을 만든다`() {
        val pts = listOf(
            BodySilhouetteSpec.PointCm(0.0, 0.0),
            BodySilhouetteSpec.PointCm(1.0, 0.0),
            BodySilhouetteSpec.PointCm(1.0, 1.0),
            BodySilhouetteSpec.PointCm(0.0, 1.0)
        )
        assertEquals(4, spec.cubicSegments(pts, closed = true).size)
        assertEquals(3, spec.cubicSegments(pts, closed = false).size)
        assertTrue(spec.cubicSegments(pts.take(1), closed = false).isEmpty())
        assertTrue(spec.cubicSegments(emptyList(), closed = true).isEmpty())
    }

    @Test
    fun `구간의 끝점은 정점을 지난다`() {
        val f = spec.front(m())
        val segs = spec.cubicSegments(f.body, closed = true)
        for (i in segs.indices) {
            val expected = f.body[(i + 1) % f.body.size]
            assertEquals(expected.x, segs[i].x, 1e-9)
            assertEquals(expected.y, segs[i].y, 1e-9)
        }
    }

    // ── 요약 축 ───────────────────────────────────────────────────────────

    @Test
    fun `3축 요약은 어휘가 전부 긍정이다`() {
        val negatives = listOf("마름", "배형", "뚱뚱", "빈유")
        val samples = listOf(
            m(), m(bust = 110.0, waist = 58.0, hip = 100.0), m(bust = 70.0, waist = 75.0, hip = 80.0),
            m(bust = 75.0, waist = 55.0, hip = 78.0), m(bust = 100.0, waist = 80.0, hip = 88.0)
        )
        for (s in samples) {
            val a = spec.axisSummary(s)
            for (label in listOf(a.torso, a.hip, a.line)) {
                assertFalse("'$label' 은 부정 어휘다 — 고르는 옵션은 전부 매력적이어야 한다",
                    negatives.any { label.contains(it) })
            }
            assertTrue(a.torso.isNotBlank() && a.hip.isNotBlank() && a.line.isNotBlank())
        }
    }

    @Test
    fun `컵 라벨은 컵차와 함께 커진다`() {
        assertEquals("—", spec.cupLabel(0.0))
        assertEquals("AA", spec.cupLabel(5.0))
        val labels = listOf(5.0, 12.0, 20.0, 30.0, 45.0).map { spec.cupLabel(it) }
        assertEquals("서로 다른 컵차가 같은 라벨로 뭉치면 안 된다", labels.size, labels.distinct().size)
    }

    // ── 단위 휴리스틱 ─────────────────────────────────────────────────────

    @Test
    fun `인치로 적은 것 같으면 알아본다`() {
        val inches = BodyMeasurements.resolveParts(
            labels = listOf("B", "W", "H"), texts = listOf("34", "24", "35"), heightText = "165"
        )
        assertTrue(spec.looksLikeInches(inches))

        val cm = BodyMeasurements.resolveParts(
            labels = listOf("B", "W", "H"), texts = listOf("88", "60", "90"), heightText = "165"
        )
        assertFalse(cm.let { spec.looksLikeInches(it) })

        assertEquals(86.36, spec.inchesToCm(34.0), 1e-9)
    }

    @Test
    fun `비율이 안 맞으면 인치로 몰지 않는다`() {
        // 허리가 가슴보다 큰 값은 인치 오기가 아니라 그냥 그런 몸일 수 있다.
        val odd = BodyMeasurements.resolveParts(
            labels = listOf("B", "W", "H"), texts = listOf("24", "40", "30"), heightText = "165"
        )
        assertFalse(spec.looksLikeInches(odd))
    }

    // ── 회귀 고정 ─────────────────────────────────────────────────────────

    @Test
    fun `같은 수치는 같은 그림을 낸다`() {
        val a = spec.front(m(bust = 92.0, waist = 58.0, hip = 94.0))
        val b = spec.front(m(bust = 92.0, waist = 58.0, hip = 94.0))
        assertTrue(spec.samePoints(a.body, b.body))
        assertTrue(spec.samePoints(a.head, b.head))
    }

    @Test
    fun `키는 세로만 늘리고 가로 비례를 바꾸지 않는다`() {
        val short = spec.front(m(height = 150.0))
        val tall = spec.front(m(height = 180.0))
        assertEquals(short.body.size, tall.body.size)
        for (i in short.body.indices) {
            assertEquals("가로 폭은 키에 흔들리지 않는다", short.body[i].x, tall.body[i].x, 1e-9)
        }
        assertTrue("세로는 키를 따라야 한다", tall.body.maxOf { it.y } > short.body.maxOf { it.y })
    }

    @Test
    fun `몸통 윤곽은 닫힌 고리이고 좌우가 대칭이다`() {
        val f = spec.front(m(bust = 95.0, waist = 58.0, hip = 96.0))
        assertTrue(f.body.size > 100)
        val maxRight = f.body.maxOf { it.x }
        val maxLeft = -f.body.minOf { it.x }
        assertEquals("좌우 폭이 어긋나면 몸이 기운다", maxRight, maxLeft, 1e-9)
    }

    @Test
    fun `핸들 넷이 모두 자리를 갖는다`() {
        val f = spec.front(m())
        assertEquals(4, f.handles.size)
        assertEquals(
            setOf(BodySlot.SHOULDER, BodySlot.BUST, BodySlot.WAIST, BodySlot.HIP),
            f.handles.map { it.slot }.toSet()
        )
        for (h in f.handles) {
            assertTrue("${h.slot} 핸들이 몸 밖에 있다", h.halfCm > 0)
            assertTrue("${h.slot} 핸들 높이", h.heightFraction in 0.0..1.0)
        }
        assertNotNull(f.scales)
    }
}
