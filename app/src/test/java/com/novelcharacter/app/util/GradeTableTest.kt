package com.novelcharacter.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 등급 표 편집 로직 (B-69).
 *
 * 이 로직이 지키는 것 둘: ① config가 담은 등급을 **하나도 빠뜨리지 않고** 화면 행으로
 * 돌려준다(종전 C·B·A·S 4칸은 그 밖을 숨겼다 — 원칙 04) ② 화면 행을 저장할 때 이상한
 * 입력을 조용히 버리거나 기본값으로 바꾸지 않는다(변수 제어).
 */
class GradeTableTest {

    // ── fromConfigRows ──

    @Test
    fun `config의 등급 전부가 행이 된다 - CBAS 밖 키 포함`() {
        val rows = GradeTable.fromConfigRows(
            """{"grades":{"C":1,"B":2,"A":3,"S":4,"SS":6,"D":0.5},"allowNegative":false}"""
        )
        // 수치 오름차순 — 입력 스피너(parseGradeOptions)와 같은 순서
        assertEquals(listOf("D", "C", "B", "A", "S", "SS"), rows.map { it.first })
    }

    @Test
    fun `정수 등급은 소수점 없이 돌려준다`() {
        // Gson이 JSON 정수를 Double로 읽어 "1.0"으로 불어나면, 저장만 눌러도 config가 바뀐다
        val rows = GradeTable.fromConfigRows("""{"grades":{"C":1,"B":0.5}}""")
        assertEquals(listOf("B" to "0.5", "C" to "1"), rows)
    }

    @Test
    fun `해석 불가 수치도 행으로 돌려준다 - 원문 그대로 뒤에`() {
        // 손으로 고친 엑셀 JSON의 손상 값 — 여기서 걸러 버리면 저장 시 무통보 유실이다.
        // 화면에 보여 주고 build가 오류로 잡아 사용자가 바로잡는다.
        val rows = GradeTable.fromConfigRows("""{"grades":{"S":3,"X":"많이","C":1}}""")
        assertEquals(listOf("C" to "1", "S" to "3", "X" to "많이"), rows)
    }

    @Test
    fun `등급 표가 없거나 config가 깨졌으면 빈 목록`() {
        assertTrue(GradeTable.fromConfigRows("{}").isEmpty())
        assertTrue(GradeTable.fromConfigRows("").isEmpty())
        assertTrue(GradeTable.fromConfigRows("{broken").isEmpty())
    }

    // ── build ──

    @Test
    fun `행 순서 그대로 표가 된다`() {
        val outcome = GradeTable.build(listOf("D" to "0.5", "C" to "1", "SS" to "6"))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(listOf("D", "C", "SS"), outcome.grades.keys.toList())
        assertEquals(0.5, outcome.grades["D"]!!, 0.0)
    }

    @Test
    fun `양쪽 다 빈 행만 조용히 건너뛴다`() {
        val outcome = GradeTable.build(listOf("C" to "1", "" to "", "S" to "3"))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(2, outcome.grades.size)
    }

    @Test
    fun `수치만 있고 이름이 빈 행은 오류다 - 조용한 유실 금지`() {
        val outcome = GradeTable.build(listOf("" to "5"))
        assertEquals(listOf(GradeTable.Problem.BlankLabel("5")), outcome.problems)
    }

    @Test
    fun `해석 불가 수치는 오류다 - 기본값 대체 금지`() {
        // 종전 코드는 toDoubleOrNull() ?: 0.5 로 조용히 기본값을 넣었다
        val outcome = GradeTable.build(listOf("S" to "많이"))
        assertEquals(listOf<GradeTable.Problem>(GradeTable.Problem.BadNumber("S", "많이")), outcome.problems)
    }

    @Test
    fun `겹치는 이름은 오류다 - 뒤가 앞을 덮어쓰지 않는다`() {
        val outcome = GradeTable.build(listOf("S" to "3", "S" to "4"))
        assertEquals(listOf<GradeTable.Problem>(GradeTable.Problem.DuplicateLabel("S")), outcome.problems)
        assertEquals(3.0, outcome.grades["S"]!!, 0.0)   // 먼저 온 값이 남는다
    }

    @Test
    fun `대소문자는 다른 등급이다`() {
        // 라이브러리 토크나이저와 같은 규칙 — 등급 S와 s의 오병합 방지
        val outcome = GradeTable.build(listOf("S" to "3", "s" to "1"))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(2, outcome.grades.size)
    }

    @Test
    fun `부호로 시작하는 이름은 오류다 - 죽은 행 방지`() {
        // GradeValueResolver가 조회 전에 -와 +를 벗기므로 이런 이름은 어떤 값과도 매칭되지 않는다
        val outcome = GradeTable.build(listOf("-S" to "3"))
        assertEquals(listOf<GradeTable.Problem>(GradeTable.Problem.SignPrefixedLabel("-S")), outcome.problems)
    }

    @Test
    fun `음수 수치는 허용된다`() {
        val outcome = GradeTable.build(listOf("저주" to "-2"))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(-2.0, outcome.grades["저주"]!!, 0.0)
    }

    @Test
    fun `빈 표는 오류다`() {
        assertEquals(listOf<GradeTable.Problem>(GradeTable.Problem.Empty), GradeTable.build(emptyList()).problems)
        assertEquals(listOf<GradeTable.Problem>(GradeTable.Problem.Empty), GradeTable.build(listOf("" to "")).problems)
    }

    @Test
    fun `여백은 다듬는다`() {
        val outcome = GradeTable.build(listOf(" S " to " 3 "))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(3.0, outcome.grades["S"]!!, 0.0)
    }

    // ── 왕복 ──

    @Test
    fun `기본 표 왕복 - 종전 고정 UI의 기본값과 같다`() {
        val outcome = GradeTable.build(GradeTable.DEFAULT_ROWS)
        assertTrue(outcome.problems.isEmpty())
        assertEquals(
            mapOf("C" to 0.5, "B" to 1.0, "A" to 2.0, "S" to 3.0),
            outcome.grades as Map<String, Double>
        )
    }

    @Test
    fun `행에서 표로, 표에서 행으로 - 값이 변하지 않는다`() {
        val rows = listOf("D" to "0.5", "C" to "1", "B" to "2", "A" to "3", "S" to "4", "SS" to "6")
        val outcome = GradeTable.build(rows)
        assertTrue(outcome.problems.isEmpty())
        val json = com.google.gson.Gson().toJson(mapOf("grades" to outcome.grades))
        assertEquals(rows, GradeTable.fromConfigRows(json))
    }
    // ── duplicateValues (2026.08.25 — 체계 부착이 조용히 만든 겹침) ──

    /** 겹치는 것이 없으면 빈 목록이다 — 정상 표에 잔소리를 달지 않는다. */
    @Test
    fun `수치가 전부 다르면 겹침이 없다`() {
        assertTrue(
            GradeTable.duplicateValues(mapOf("F" to 0.5, "E" to 1.0, "D" to 2.0)).isEmpty()
        )
    }

    /**
     * 사용자 파일에서 실제로 나온 모양 — `{C:0.5,B:1,A:2,S:3}`짜리 필드를 `F~SSS` 체계에
     * 붙이면 실효 표가 이렇게 된다. 무리는 숫자 오름차순, 라벨은 표에 있던 차례 그대로다.
     */
    @Test
    fun `체계 부착이 만든 겹침 셋을 전부 낸다`() {
        val effective = linkedMapOf(
            "F" to 0.5, "E" to 1.0, "D" to 2.0,
            "C" to 0.5, "B" to 1.0, "A" to 2.0,
            "S" to 3.0, "SS" to 7.0, "SSS" to 8.0
        )
        val groups = GradeTable.duplicateValues(effective)
        assertEquals(listOf(0.5, 1.0, 2.0), groups.map { it.value })
        assertEquals(listOf("F", "C"), groups[0].labels)
        assertEquals(listOf("E", "B"), groups[1].labels)
        assertEquals(listOf("D", "A"), groups[2].labels)
    }

    /** 셋 이상이 같은 숫자면 한 무리로 묶인다. */
    @Test
    fun `셋이 같은 숫자면 한 무리다`() {
        val groups = GradeTable.duplicateValues(linkedMapOf("A" to 1.0, "B" to 1.0, "C" to 1.0))
        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B", "C"), groups.single().labels)
    }

    /**
     * 겹침은 [GradeTable.Problem]이 아니다 — 행은 멀쩡하므로 [GradeTable.build]가 막지 않고
     * 값도 그대로 담는다. 막았다면 이미 겹친 표를 가진 사용자가 다른 칸도 못 고친다.
     */
    @Test
    fun `겹침은 저장을 막지 않는다`() {
        val outcome = GradeTable.build(listOf("F" to "0.5", "C" to "0.5"))
        assertTrue(outcome.problems.isEmpty())
        assertEquals(mapOf("F" to 0.5, "C" to 0.5), outcome.grades)
        assertEquals(1, GradeTable.duplicateValues(outcome.grades).size)
    }

}
