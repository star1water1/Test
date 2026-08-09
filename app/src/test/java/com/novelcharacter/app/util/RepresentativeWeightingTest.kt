package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RepresentativeWeighting] — 대표 추첨을 **균등에서 점수 가중으로** (B-104 소비처 ⓑ).
 *
 * **이 판의 방어선은 셋이고 셋 다 시험이 유일한 검출이다** — 무게가 틀려도 앱은 죽지 않고
 * 그림만 조용히 기운다(컴파일도 정적 검사도 통과한다):
 *
 * 1. *"대결을 안 한 캐릭터는 지금과 완전히 같다"* — 사용자에게 한 약속이다. 이것이 문서의
 *    말이 아니라 코드의 성질이려면 **무게가 전부 같을 때 null이 나와야** 한다(그래야 호출부가
 *    종전 `randomIndex`를 그대로 탄다. 1.0으로 채운 표를 내면 뽑는 방법이 나머지 연산에서
 *    누적합으로 갈려 **같은 시드가 다른 그림을 고른다**).
 * 2. *"확률이지 고정이 아니다"* — 사용자가 물리친 것이 고정이다([SPREAD_CAP]에서 끊지 않으면
 *    점수가 크게 벌어진 캐릭터에서 1위의 무게가 수백 배가 되어 사실상 고정이 된다).
 * 3. *"한 판도 안 친 그림은 앵커에 선다"* — 0이 아니다. 0으로 두면 새로 넣은 그림이
 *    **영영 안 뜬다.**
 */
class RepresentativeWeightingTest {

    private val dir = "/data/user/0/com.novelcharacter.app/files"
    private val a = "$dir/a.jpg"
    private val b = "$dir/b.jpg"
    private val c = "$dir/c.jpg"

    private fun canon(path: String) = ImagePathMatch.canonical(path)

    // ── 1. 기울일 것이 없으면 null (= 종전 그대로) ──

    @Test
    fun `세기가 꺼져 있으면 점수가 있어도 무게가 없다`() {
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf(canon(a) to 1700, canon(b) to 1300),
            RepresentativeWeighting.Strength.OFF
        )
        assertNull("꺼둠은 점수를 읽지도 않는다", w)
    }

    @Test
    fun `점수가 하나도 없으면 무게가 없다 — 대결을 안 한 캐릭터가 이 자리다`() {
        assertNull(
            RepresentativeWeighting.weights(listOf(a, b, c), emptyMap(), RepresentativeWeighting.Strength.WEAK)
        )
    }

    @Test
    fun `전원이 같은 점수면 무게가 없다 — 1점 표를 내면 뽑는 방법이 갈린다`() {
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf(canon(a) to 1500, canon(b) to 1500),
            RepresentativeWeighting.Strength.STRONG
        )
        assertNull("같은 점수는 기울일 것이 없다", w)
    }

    @Test
    fun `그림이 한 장이면 무게가 없다 — 뽑을 것이 없다`() {
        assertNull(
            RepresentativeWeighting.weights(
                listOf(a), mapOf(canon(a) to 1900), RepresentativeWeighting.Strength.STRONG
            )
        )
    }

    // ── 2. 기울기의 모양 ──

    @Test
    fun `1위는 1이고 나머지는 그보다 작다`() {
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf(canon(a) to 1700, canon(b) to 1500),
            RepresentativeWeighting.Strength.STRONG
        )
        assertNotNull(w)
        assertEquals(1.0, w!!.byPath[canon(a)]!!, 1e-9)
        assertTrue("아래 순위는 1보다 작다", w.byPath[canon(b)]!! < 1.0)
    }

    @Test
    fun `세게는 한 눈금 차이를 열 배로 — 약하게는 그 제곱근이다`() {
        val scores = mapOf(canon(a) to 1900, canon(b) to 1500)  // 정확히 한 눈금(400점)
        val strong = RepresentativeWeighting.weights(
            listOf(a, b), scores, RepresentativeWeighting.Strength.STRONG
        )!!
        val weak = RepresentativeWeighting.weights(
            listOf(a, b), scores, RepresentativeWeighting.Strength.WEAK
        )!!
        assertEquals(0.1, strong.byPath[canon(b)]!!, 1e-9)
        assertEquals(0.316227766, weak.byPath[canon(b)]!!, 1e-6)
    }

    @Test
    fun `아무리 벌어져도 열 배에서 끊긴다 — 확률이지 고정이 아니다`() {
        // 2500점은 앵커보다 두 눈금 반 위다. 끊지 않으면 무게 비율이 300배를 넘는다.
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf(canon(a) to 2500, canon(b) to 1500),
            RepresentativeWeighting.Strength.STRONG
        )!!
        assertEquals("세게의 최악 비율은 10:1이다", 0.1, w.byPath[canon(b)]!!, 1e-9)
    }

    // ── 3. 안 겨룬 그림 ──

    @Test
    fun `점수가 없는 그림은 앵커에 선다 — 0이 아니다`() {
        // c는 방금 넣은 그림이라 점수가 없다. 앵커(1500)로 쳐서 b(1300)보다 무겁고 a(1700)보다 가볍다.
        val w = RepresentativeWeighting.weights(
            listOf(a, b, c),
            mapOf(canon(a) to 1700, canon(b) to 1300),
            RepresentativeWeighting.Strength.STRONG
        )!!
        val wc = w.byPath[canon(c)]!!
        assertTrue("새 그림이 영영 안 뜨면 안 된다", wc > 0.0)
        assertTrue(wc > w.byPath[canon(b)]!!)
        assertTrue(wc < w.byPath[canon(a)]!!)
    }

    @Test
    fun `점수표의 표기가 목록과 달라도 대조된다 — 개명 추종이 원본 표기를 지킨다`() {
        // 표에는 정규화 전 표기(중복 슬래시)로 들어오고 목록은 깔끔한 표기다.
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf("$dir//a.jpg" to 1900, "$dir//b.jpg" to 1500),
            RepresentativeWeighting.Strength.STRONG
        )
        assertNotNull("대조에 실패하면 둘 다 앵커가 되어 null이 나온다", w)
        assertEquals(0.1, w!!.byPath[canon(b)]!!, 1e-9)
    }

    @Test
    fun `무게표는 넘긴 표기로도 맞는다 — 읽는 쪽의 파일 시스템 호출을 없앤다`() {
        // `canonical`은 File.canonicalPath, 즉 파일 시스템 호출이다. 읽는 자리가 어댑터의
        // bind라 목록을 튕길 때마다 그림 수만큼 붙는데, 두 표기를 함께 담아 두면 건너뛴다.
        val raw = "$dir/./a.jpg"
        val w = RepresentativeWeighting.weights(
            listOf(raw, b),
            mapOf(canon(raw) to 1900, canon(b) to 1500),
            RepresentativeWeighting.Strength.STRONG
        )!!
        assertNotNull("넘긴 표기 그대로도 맞아야 한다", w.byPath[raw])
        assertNotNull("정규 표기로도 여전히 맞는다", w.byPath[canon(raw)])
        assertEquals(w.byPath[canon(raw)]!!, w.byPath[raw]!!, 1e-12)
    }

    @Test
    fun `표에 없는 경로는 앵커의 무게다 — 1이 아니다`() {
        // **1.0은 곧 1위의 무게다**(모든 무게가 (0,1]이고 1위가 정확히 1.0). 표에 없는 경로를
        // 1로 치면 *방금 넣은 그림이 가장 자주 뜨는* 꼴이 되어, 위 시험이 못 박은 앵커 규칙과
        // 정면으로 어긋난다. 무게표가 그 값을 **스스로 들고 다녀야** 두 층이 같은 말을 한다.
        val w = RepresentativeWeighting.weights(
            listOf(a, b),
            mapOf(canon(a) to 1900, canon(b) to 1500),
            RepresentativeWeighting.Strength.STRONG
        )!!
        assertEquals("앵커(1500)에 선 b와 같은 무게여야 한다", w.byPath[canon(b)]!!, w.unknown, 1e-12)
        assertTrue("1위의 무게보다 가벼워야 한다", w.unknown < w.byPath[canon(a)]!!)
        // 표에 없는 경로를 물어보면 그 값이 나온다 — 읽는 쪽이 따로 셈하지 않는다.
        assertEquals(w.unknown, w.of("$dir/never-seen.jpg"), 1e-12)
        assertEquals("null도 앵커로 접힌다(Gson이 목록에 null을 섞을 수 있다)", w.unknown, w.of(null), 1e-12)
    }

    // ── 4. 저장된 값 읽기 ──

    @Test
    fun `모르는 세기 이름은 기본값 약하게로 접힌다`() {
        assertEquals(RepresentativeWeighting.Strength.WEAK, RepresentativeWeighting.Strength.of(null))
        assertEquals(RepresentativeWeighting.Strength.WEAK, RepresentativeWeighting.Strength.of("MEDIUM"))
        assertEquals(RepresentativeWeighting.Strength.OFF, RepresentativeWeighting.Strength.of("OFF"))
    }
}
