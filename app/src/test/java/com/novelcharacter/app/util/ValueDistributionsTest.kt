package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 분포 상한과 비율 분모의 계약 (S-17 · 규약 R-14).
 *
 * 고정하는 사실:
 * - 비율의 분모는 **언제나 전체 합**이다. 상위 N개의 합이 아니다.
 * - 잘라낸 것은 **종수와 건수**로 존재를 알린다. 감추지 않는다.
 * - 잘린 값의 목록을 들고 있어야 '기타'를 눌러 목록을 볼 수 있다.
 */
class ValueDistributionsTest {

    private val dist = linkedMapOf(
        "서울" to 10, "부산" to 5, "대구" to 3, "인천" to 1, "광주" to 1
    )

    @Test
    fun `상한이 없으면 전량을 표시하고 잘린 것은 없다`() {
        val view = ValueDistributions.view(dist, 10)
        assertEquals(5, view.shown.size)
        assertFalse(view.hasHidden)
        assertEquals(20, view.totalCount)
    }

    @Test
    fun `상한을 넘으면 나머지를 접고 종수와 건수를 알린다`() {
        val view = ValueDistributions.view(dist, 2)
        assertEquals(listOf("서울", "부산"), view.shown.map { it.label })
        assertEquals(3, view.hiddenKinds)
        assertEquals(5, view.hiddenCount)
        assertEquals("동수는 값 이름 오름차순 — 순서가 흔들리면 상위 N이 실행마다 달라진다",
            listOf("대구", "광주", "인천"), view.hiddenLabels)
    }

    @Test
    fun `비율의 분모는 잘리기 전 전체 합이다`() {
        val view = ValueDistributions.view(dist, 2)
        // 종전 결함: 분모가 상위 2종의 합(15)이 되어 서울이 66.7%로 부풀려졌다.
        assertEquals(50f, view.ratioOf(10), 0.01f)
        assertEquals(25f, view.ratioOf(5), 0.01f)
        assertEquals(25f, view.hiddenRatio(), 0.01f)
    }

    @Test
    fun `표시분과 기타의 비율 합은 100퍼센트다`() {
        val view = ValueDistributions.view(dist, 3)
        val shownRatio = view.shown.sumOf { view.ratioOf(it.count).toDouble() }
        assertEquals(100.0, shownRatio + view.hiddenRatio(), 0.01)
    }

    @Test
    fun `상한이 0 이하면 상한 없음으로 본다`() {
        // 상한을 끄는 설정이 조용히 "아무것도 안 보임"이 되면 안 된다.
        val view = ValueDistributions.view(dist, 0)
        assertEquals(5, view.shown.size)
        assertFalse(view.hasHidden)
    }

    @Test
    fun `빈 분포는 분모가 0이어도 터지지 않는다`() {
        val view = ValueDistributions.view(emptyMap(), 10)
        assertTrue(view.shown.isEmpty())
        assertEquals(0, view.totalCount)
        assertEquals(0f, view.ratioOf(0), 0.001f)
    }

    @Test
    fun `순서가 정보인 분포는 재정렬하지 않는다`() {
        // 수치 구간 분포를 건수순으로 재정렬하면 인접 구간이 흩어져 '어디에 몰렸는가'를 읽을 수 없다.
        val bins = linkedMapOf("150~160" to 1, "160~170" to 1, "170~180" to 1, "180~190" to 0, "190~200" to 2)
        val view = ValueDistributions.view(bins, 10, preserveOrder = true)
        assertEquals(bins.keys.toList(), view.shown.map { it.label })
        // 상한도 순서를 지킨 채 적용된다(앞에서부터 자른다)
        val capped = ValueDistributions.view(bins, 3, preserveOrder = true)
        assertEquals(listOf("150~160", "160~170", "170~180"), capped.shown.map { it.label })
        assertEquals(2, capped.hiddenKinds)
        assertEquals(2, capped.hiddenCount)
    }

    @Test
    fun `집계는 건수 내림차순이고 동수는 값 이름 오름차순이다`() {
        // 순서가 흔들리면 '상위 N개'가 실행마다 달라진다.
        val of = ValueDistributions.of(listOf("나", "가", "다", "가", "나", "다"))
        assertEquals(listOf("가", "나", "다"), of.keys.toList())
        val tie = ValueDistributions.of(listOf("b", "a"))
        assertEquals(listOf("a", "b"), tie.keys.toList())
    }
}
