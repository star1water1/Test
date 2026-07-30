package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.GradeSystemRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 등급 체계 참조(U-1)의 config 표현 계약 — [GradeSystemRef]가 단일 소스다.
 *
 * 핵심 계약 셋:
 * 1. **실효 표 물질화** — 참조 필드의 `grades`는 항상 체계 기본 + 재정의의 결과라, 체계를
 *    조회할 수 없는 곳(GradeValueResolver·엑셀만 든 파일)에서도 필드가 그대로 동작한다.
 * 2. **수술적 편집** — materialize/demote는 자기 키만 만지고 다른 config 키를 보존한다.
 * 3. **강등 무손실** — demote는 실효 표를 남긴다(체계 삭제·관대 수용이 값을 잃지 않는 근거).
 */
class GradeSystemRefTest {

    private val systemGrades = linkedMapOf("C" to 0.5, "B" to 1.0, "A" to 2.0, "S" to 3.0)

    // ── 읽기 ──

    @Test
    fun `codeFromConfig - 키 없음·빈 문자열·손상 JSON은 전부 null`() {
        assertEquals("gs01", GradeSystemRef.codeFromConfig("""{"gradeSystem":"gs01"}"""))
        assertNull(GradeSystemRef.codeFromConfig("{}"))
        assertNull(GradeSystemRef.codeFromConfig("""{"gradeSystem":""}"""))
        assertNull(GradeSystemRef.codeFromConfig("not json"))
    }

    @Test
    fun `gradesFromJson - 숫자가 아닌 값은 건너뛰되 숫자 문자열은 해석한다`() {
        val grades = GradeSystemRef.gradesFromJson("""{"C":0.5,"B":"1","A":"abc","S":3}""")
        assertEquals(mapOf("C" to 0.5, "B" to 1.0, "S" to 3.0), grades)
        assertTrue(GradeSystemRef.gradesFromJson("broken").isEmpty())
    }

    // ── 실효 표 계산 ──

    @Test
    fun `effective - 라벨 집합은 체계가 정하고 숫자는 재정의가 이긴다`() {
        val effective = GradeSystemRef.effective(systemGrades, mapOf("S" to 5.0, "X" to 9.0))
        assertEquals(linkedMapOf("C" to 0.5, "B" to 1.0, "A" to 2.0, "S" to 5.0), effective)
        assertFalse("체계에 없는 라벨은 실효 표에 없다", effective.containsKey("X"))
    }

    @Test
    fun `deriveOverrides - 기본과 다른 라벨만 남고 체계 밖 라벨은 재정의가 아니다`() {
        val overrides = GradeSystemRef.deriveOverrides(
            mapOf("C" to 0.5, "B" to 2.0, "S" to 3.0, "SS" to 9.0), systemGrades
        )
        assertEquals(mapOf("B" to 2.0), overrides)
    }

    @Test
    fun `renameOverrides - 개명이 재정의를 데리고 간다`() {
        val renamed = GradeSystemRef.renameOverrides(mapOf("S" to 5.0, "B" to 2.0), mapOf("S" to "SS"))
        assertEquals(mapOf("SS" to 5.0, "B" to 2.0), renamed)
    }

    // ── materialize / demote ──

    @Test
    fun `materialize - 참조·재정의·실효 표를 쓰고 다른 키는 보존한다`() {
        val config = """{"allowNegative":true,"statsEnabled":false}"""
        val out = GradeSystemRef.materialize(config, "gs01", systemGrades, mapOf("S" to 5.0))
        val json = JSONObject(out)
        assertEquals("gs01", json.getString("gradeSystem"))
        assertEquals(5.0, json.getJSONObject("gradeOverrides").getDouble("S"), 0.0)
        assertEquals(5.0, json.getJSONObject("grades").getDouble("S"), 0.0)
        assertEquals(0.5, json.getJSONObject("grades").getDouble("C"), 0.0)
        assertTrue("무관한 키가 보존된다", json.getBoolean("allowNegative"))
        assertFalse("무관한 키가 보존된다", json.getBoolean("statsEnabled"))
    }

    @Test
    fun `materialize - 재정의가 비면 키를 남기지 않고 체계 밖 재정의는 잘린다`() {
        val out = GradeSystemRef.materialize("{}", "gs01", systemGrades, mapOf("X" to 9.0))
        val json = JSONObject(out)
        assertFalse(json.has("gradeOverrides"))
        assertEquals(systemGrades, GradeSystemRef.gradesFromConfig(out))
    }

    @Test
    fun `materialize - 손상 JSON은 원문 그대로 - 다른 키를 파괴하지 않는다`() {
        assertEquals("broken{", GradeSystemRef.materialize("broken{", "gs01", systemGrades, emptyMap()))
    }

    @Test
    fun `demote - 참조·재정의만 지우고 실효 표는 남는다 - 강등 무손실`() {
        val config = GradeSystemRef.materialize(
            """{"allowNegative":true}""", "gs01", systemGrades, mapOf("S" to 5.0)
        )
        val out = GradeSystemRef.demote(config)
        val json = JSONObject(out)
        assertFalse(json.has("gradeSystem"))
        assertFalse(json.has("gradeOverrides"))
        assertEquals(
            "실효 표가 그대로 남아 필드는 어제와 똑같이 동작한다",
            linkedMapOf("C" to 0.5, "B" to 1.0, "A" to 2.0, "S" to 5.0),
            GradeSystemRef.gradesFromConfig(out)
        )
        assertTrue(json.getBoolean("allowNegative"))
    }

    // ── code 재발급 재배선 ──

    @Test
    fun `remapCode - 재발급된 code만 바꾸고 나머지는 그대로다`() {
        val config = """{"gradeSystem":"old1","grades":{"S":3}}"""
        assertEquals(
            "new1",
            GradeSystemRef.codeFromConfig(GradeSystemRef.remapCode(config, mapOf("old1" to "new1")))
        )
        assertEquals(config, GradeSystemRef.remapCode(config, mapOf("other" to "new2")))
        assertEquals("{}", GradeSystemRef.remapCode("{}", mapOf("old1" to "new1")))
    }

    // ── 엑셀 가져오기 병합 ──

    @Test
    fun `mergeResolved - 파일의 grades가 사용자가 보고 고친 값이라 재정의의 근거다`() {
        val sheetConfig = """{"grades":{"C":0.5,"B":9.0,"A":2,"S":3}}"""
        val merge = GradeSystemRef.mergeResolved(sheetConfig, "gs01", """{"C":0.5,"B":1,"A":2,"S":3}""")
        assertEquals(mapOf("B" to 9.0), GradeSystemRef.overridesFromConfig(merge.config))
        assertTrue(merge.droppedLabels.isEmpty())
    }

    @Test
    fun `mergeResolved - 체계에 없는 라벨은 빠지되 목록으로 돌려준다 - 조용한 좁힘 금지`() {
        val sheetConfig = """{"grades":{"C":0.5,"SS":9.0}}"""
        val merge = GradeSystemRef.mergeResolved(sheetConfig, "gs01", """{"C":0.5,"B":1}""")
        assertEquals(listOf("SS"), merge.droppedLabels)
        assertFalse(GradeSystemRef.gradesFromConfig(merge.config).containsKey("SS"))
    }

    @Test
    fun `mergeResolved - grades가 없으면 gradeOverrides 키를 쓴다 - 손편집 파일`() {
        val sheetConfig = """{"gradeOverrides":{"S":7.0}}"""
        val merge = GradeSystemRef.mergeResolved(sheetConfig, "gs01", """{"C":0.5,"S":3}""")
        assertEquals(7.0, GradeSystemRef.gradesFromConfig(merge.config).getValue("S"), 0.0)
        assertEquals(mapOf("S" to 7.0), GradeSystemRef.overridesFromConfig(merge.config))
    }

    @Test
    fun `mergeResolved - 둘 다 없으면 체계 기본 그대로다`() {
        val merge = GradeSystemRef.mergeResolved("{}", "gs01", """{"C":0.5,"S":3}""")
        assertEquals(mapOf("C" to 0.5, "S" to 3.0), GradeSystemRef.gradesFromConfig(merge.config))
        assertFalse(JSONObject(merge.config).has("gradeOverrides"))
    }

    // ── 왕복 ──

    @Test
    fun `gradesToJson 왕복 - 정수 등급이 불어나지 않는다`() {
        val json = GradeSystemRef.gradesToJson(linkedMapOf("C" to 0.5, "B" to 1.0))
        assertEquals(linkedMapOf("C" to 0.5, "B" to 1.0), GradeSystemRef.gradesFromJson(json))
    }
}
