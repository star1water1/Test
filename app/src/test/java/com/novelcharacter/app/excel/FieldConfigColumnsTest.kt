package com.novelcharacter.app.excel

import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDescription
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * '필드 정의' 시트의 config 파생 전용 열(AI추천·필드설명) 왕복 규칙 (A-2 §4-5).
 * 핵심 계약: 열 있음/없음 × JSON 키 있음/없음 4분기 — 특히 **열 없음 + 키 없음에서
 * 기존 DB 값 보존**(빠뜨리면 전용 열을 지운 파일에서 설명이 무통보 유실된다).
 */
class FieldConfigColumnsTest {

    private fun merge(
        sheetConfig: String,
        aiCell: String? = null,          // null = 열 없음
        descCell: String? = null,        // null = 열 없음
        existing: String? = null
    ) = FieldConfigColumns.merge(
        sheetConfig = sheetConfig,
        aiColumnPresent = aiCell != null, aiCellText = aiCell ?: "",
        descriptionColumnPresent = descCell != null, descriptionCellText = descCell ?: "",
        existingConfig = existing
    )

    // ===== ① 열이 있으면 열이 값이다 =====

    @Test
    fun columnPresent_overridesJsonKey() {
        // JSON 셀에 반대값이 남아 있어도(비정상 편집) 전용 열이 이긴다
        val merged = merge("""{"aiSuggest":false}""", aiCell = "Y", descCell = "새 설명")
        assertTrue(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("새 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnPresent_appliesToPlainConfig() {
        val merged = merge("{}", aiCell = "N", descCell = "설명 A")
        assertFalse(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("설명 A", FieldDescription.fromConfig(merged))
    }

    @Test
    fun aiColumnBlank_meansDefaultOn() {
        // 빈칸 = config 키 없음(기본값 켜짐)에 대응. 끄기는 드롭다운 "N"이 말한다.
        val merged = merge("""{"aiSuggest":false}""", aiCell = "", descCell = "d")
        assertTrue(FieldAiPolicy.isSuggestEnabled(merged))
        assertFalse(JSONObject(merged).has(FieldAiPolicy.CONFIG_KEY))
    }

    @Test
    fun descriptionColumnBlank_removesDescription() {
        // 내보내기는 설명 없는 필드를 빈칸으로 쓰므로, 빈칸 = 설명 없음이어야 왕복이 맞는다.
        // 기존 DB에 설명이 있어도 열이 존재하면 셀이 값이다(셀을 지운 것은 지우겠다는 편집이다).
        val merged = merge("{}", aiCell = "Y", descCell = "", existing = """{"description":"옛 설명"}""")
        assertEquals("", FieldDescription.fromConfig(merged))
    }

    // ===== ② 열 없음 + JSON 키 있음 → 구버전 파일의 키 유지 =====

    @Test
    fun columnAbsent_jsonKeyKept() {
        val merged = merge("""{"aiSuggest":false,"description":"셀 설명"}""")
        assertFalse(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("셀 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnAbsent_jsonKeyWinsOverExisting() {
        // 구버전 파일이 명시한 값이 기존 DB 값보다 우선한다 (파일이 편집 의도다)
        val merged = merge(
            """{"aiSuggest":false}""",
            existing = """{"description":"DB 설명"}"""
        )
        assertFalse(FieldAiPolicy.isSuggestEnabled(merged))
        // aiSuggest는 JSON 키 유지, description은 키가 없으므로 기존 DB 값 보존
        assertEquals("DB 설명", FieldDescription.fromConfig(merged))
    }

    // ===== ③ 열 없음 + 키 없음 → 기존 DB 값 보존 (무통보 유실 방지의 핵심) =====

    @Test
    fun columnAbsent_noKey_existingPreserved() {
        val existing = """{"aiSuggest":false,"description":"살아남아야 하는 설명"}"""
        val merged = merge("{}", existing = existing)
        assertFalse(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("살아남아야 하는 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnAbsent_noKey_newField_defaults() {
        val merged = merge("{}", existing = null)
        assertTrue(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("", FieldDescription.fromConfig(merged))
        assertEquals(0, JSONObject(merged).length())
    }

    @Test
    fun merge_keepsUnrelatedKeys() {
        val merged = merge(
            """{"options":["A","B"],"narrativeMode":"short"}""",
            aiCell = "N", descCell = "d",
            existing = "{}"
        )
        val json = JSONObject(merged)
        assertEquals(2, json.getJSONArray("options").length())
        assertEquals("short", json.getString("narrativeMode"))
    }

    // ===== 내보내기: stripPortableKeys =====

    @Test
    fun strip_removesOnlyPortableKeys() {
        val config = """{"aiSuggest":false,"description":"설명","options":["A"]}"""
        val stripped = FieldConfigColumns.stripPortableKeys(config)
        val json = JSONObject(stripped)
        assertFalse(json.has(FieldAiPolicy.CONFIG_KEY))
        assertFalse(json.has(FieldDescription.CONFIG_KEY))
        assertEquals(1, json.getJSONArray("options").length())
    }

    @Test
    fun strip_corruptJsonUnchanged() {
        assertEquals("broken{", FieldConfigColumns.stripPortableKeys("broken{"))
    }

    // ===== 왕복: 내보내기(strip + 전용 열) → 가져오기(merge) =====

    @Test
    fun roundTrip_preservesToggleAndDescription() {
        val original = """{"aiSuggest":false,"description":"왕복 설명","options":["A"]}"""
        // 내보내기: JSON 셀은 strip, 전용 열은 값에서 파생 (ExcelExporter와 같은 규칙)
        val jsonCell = FieldConfigColumns.stripPortableKeys(original)
        val aiCell = if (FieldAiPolicy.isSuggestEnabled(original)) "Y" else "N"
        val descCell = FieldDescription.fromConfig(original)
        // 가져오기(기존 행 갱신)
        val merged = merge(jsonCell, aiCell = aiCell, descCell = descCell, existing = original)
        assertFalse(FieldAiPolicy.isSuggestEnabled(merged))
        assertEquals("왕복 설명", FieldDescription.fromConfig(merged))
        assertEquals(1, JSONObject(merged).getJSONArray("options").length())
    }
}
