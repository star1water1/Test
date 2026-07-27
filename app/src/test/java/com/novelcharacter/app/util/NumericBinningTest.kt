package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 수치 구간 생성과 구간 매칭의 계약 (S-16).
 *
 * 가장 중요한 사실 하나: **분포를 만든 규칙과 드릴다운이 세는 규칙이 같아야 한다.**
 * 종전에는 분포가 만든 라벨("160~170")을 드릴다운이 저장값("170-60-80")과 문자열 비교해
 * 어떤 입력에서도 0명이 나왔다.
 */
class NumericBinningTest {

    @Test
    fun `구간은 최소 최대를 등분하고 마지막만 상한을 포함한다`() {
        val bins = NumericBinning.autoBins(listOf(0f, 50f, 100f), binCount = 5)
        assertEquals(5, bins.size)
        assertEquals(0f, bins.first().min, 0.001f)
        assertEquals(100f, bins.last().max, 0.001f)
        assertFalse(bins.first().inclusiveMax)
        assertTrue("마지막 구간이 상한을 포함하지 않으면 최댓값이 어디에도 안 들어간다",
            bins.last().inclusiveMax)
        assertTrue(bins.last().contains(100f))
    }

    @Test
    fun `모든 값은 정확히 한 구간에 들어간다`() {
        val values = listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f)
        val bins = NumericBinning.autoBins(values)
        for (v in values) {
            assertEquals("값 $v 는 한 구간에만 속해야 한다", 1, bins.count { it.contains(v) })
        }
        assertEquals(values.size, bins.sumOf { b -> values.count { b.contains(it) } })
    }

    @Test
    fun `값이 부족하거나 폭이 0이면 구간을 만들지 않는다`() {
        assertTrue(NumericBinning.autoBins(emptyList()).isEmpty())
        assertTrue(NumericBinning.autoBins(listOf(5f)).isEmpty())
        assertTrue("전부 같은 값이면 나눌 폭이 없다", NumericBinning.autoBins(listOf(5f, 5f, 5f)).isEmpty())
    }

    @Test
    fun `폭이 좁아도 구간 라벨은 겹치지 않는다`() {
        // 라벨이 겹치면 분포 맵에서 뒤 구간이 앞 구간을 덮어써 건수가 조용히 사라진다.
        val bins = NumericBinning.autoBins(listOf(170.0f, 170.4f, 170.8f))
        assertEquals(5, bins.size)
        assertEquals(bins.size, bins.map { it.label }.toSet().size)
    }

    @Test
    fun `소수 자리로도 못 가르면 순번을 붙여서라도 유일하게 만든다`() {
        // 엑셀 왕복의 부동소수 잔차처럼 폭이 1e-4인 분포에서는 3자리로도 라벨이 겹친다.
        // 겹친 라벨을 맵 키로 쓰면 그 구간의 인원이 개수 고지도 없이 사라진다.
        val bins = NumericBinning.autoBins(listOf(170.0001f, 170.00015f, 170.0002f))
        assertEquals(5, bins.size)
        assertEquals("라벨은 어떤 경우에도 유일해야 한다", bins.size, bins.map { it.label }.toSet().size)
    }

    @Test
    fun `좁은 정수 범위에서도 모든 값이 한 구간에 담긴다`() {
        // 자체 5등분 + toInt() 라벨은 값 1,2,3에서 "1~1"이 두 번 나와 앞 구간을 덮어썼다.
        val values = listOf(1f, 2f, 3f)
        val bins = NumericBinning.autoBins(values)
        val counts = bins.associate { b -> b.label to values.count { b.contains(it) } }
        assertEquals(bins.size, counts.size)
        assertEquals(values.size, counts.values.sum())
    }

    @Test
    fun `파트 값은 구분자로 쪼갠 그 자리의 수치다`() {
        assertEquals(170f, NumericBinning.partValue("170-60-80", "-", 0)!!, 0.001f)
        assertEquals(60f, NumericBinning.partValue("170-60-80", "-", 1)!!, 0.001f)
        assertEquals(80f, NumericBinning.partValue(" 170 / 60 / 80 ", "/", 2)!!, 0.001f)
        assertNull(NumericBinning.partValue("170-60-80", "-", 3))
        assertNull("수치가 아니면 없는 것이다", NumericBinning.partValue("가-나", "-", 0))
        assertNull(NumericBinning.partValue("170-60", "-", -1))
    }

    @Test
    fun `구간 스펙은 그 구간과 같은 판정을 한다`() {
        val bins = NumericBinning.autoBins(listOf(150f, 160f, 170f, 180f, 190f, 200f))
        for (bin in bins) {
            val spec = FieldValueMatchSpec.of(bin, partIndex = 0, separator = "-")
            for (v in listOf(150f, 165f, 170f, 199f, 200f)) {
                val raw = "${v.toInt()}-60-80"
                assertEquals(
                    "구간과 스펙의 판정이 갈리면 조각 수치와 드릴다운 인원이 어긋난다",
                    bin.contains(v),
                    FieldValueMatcher.matches(spec, raw, emptyList())
                )
            }
        }
    }

    @Test
    fun `값 집합 스펙은 파싱된 값 중 하나라도 맞으면 일치한다`() {
        val spec = FieldValueMatchSpec.Values(setOf("검사", "궁수"))
        assertTrue(FieldValueMatcher.matches(spec, "검사, 마법사", listOf("검사", "마법사")))
        assertFalse(FieldValueMatcher.matches(spec, "마법사", listOf("마법사")))
        // 원문이 아니라 **파싱된 값**을 본다 — 라벨·별칭 접기가 반영된 값 공간이어야 한다.
        assertFalse(FieldValueMatcher.matches(spec, "검사", emptyList()))
    }

    @Test
    fun `구간 스펙은 파싱을 요구하지 않는다`() {
        // 값 하나를 확인하려고 전체를 토큰화하는 비용을 치르지 않는다.
        val spec = FieldValueMatchSpec.NumericPartRange(0, "-", 160f, 170f, false)
        var parsedCalls = 0
        val matched = FieldValueMatcher.matches(spec, "165-60-80") { parsedCalls++; emptyList() }
        assertTrue(matched)
        assertEquals(0, parsedCalls)
    }
}
