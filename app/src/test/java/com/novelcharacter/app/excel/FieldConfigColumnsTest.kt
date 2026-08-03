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
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldAiPolicy.suggestMode(merged))
        assertEquals("새 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnPresent_appliesToPlainConfig() {
        val merged = merge("{}", aiCell = "N", descCell = "설명 A")
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(merged))
        assertEquals("설명 A", FieldDescription.fromConfig(merged))
    }

    @Test
    fun aiColumnBlank_meansDefaultOn() {
        // 빈칸 = config 키 없음(기본값 켜짐)에 대응. 끄기는 드롭다운 "N"이 말한다.
        val merged = merge("""{"aiSuggest":false}""", aiCell = "", descCell = "d")
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldAiPolicy.suggestMode(merged))
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
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(merged))
        assertEquals("셀 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnAbsent_jsonKeyWinsOverExisting() {
        // 구버전 파일이 명시한 값이 기존 DB 값보다 우선한다 (파일이 편집 의도다)
        val merged = merge(
            """{"aiSuggest":false}""",
            existing = """{"description":"DB 설명"}"""
        )
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(merged))
        // aiSuggest는 JSON 키 유지, description은 키가 없으므로 기존 DB 값 보존
        assertEquals("DB 설명", FieldDescription.fromConfig(merged))
    }

    // ===== ③ 열 없음 + 키 없음 → 기존 DB 값 보존 (무통보 유실 방지의 핵심) =====

    @Test
    fun columnAbsent_noKey_existingPreserved() {
        val existing = """{"aiSuggest":false,"description":"살아남아야 하는 설명"}"""
        val merged = merge("{}", existing = existing)
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(merged))
        assertEquals("살아남아야 하는 설명", FieldDescription.fromConfig(merged))
    }

    @Test
    fun columnAbsent_noKey_newField_defaults() {
        val merged = merge("{}", existing = null)
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldAiPolicy.suggestMode(merged))
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

    // ===== B-80: AI추천 열의 3단 =====

    /** 내보내기 값 — **Y/N은 2단 시절과 같다**(옛 파일과 새 파일이 같은 말을 쓴다). */
    @Test
    fun aiCell_writesLegacyYNAndNewManualLabel() {
        assertEquals("Y", FieldConfigColumns.aiCellOf("{}"))
        assertEquals("N", FieldConfigColumns.aiCellOf("""{"aiSuggest":false}"""))
        assertEquals("개별만", FieldConfigColumns.aiCellOf("""{"aiSuggest":"manual"}"""))
    }

    /** 드롭다운 선택지와 내보내기 값이 갈리면 사용자가 고른 값이 다시 안 들어온다. */
    @Test
    fun aiCell_dropdownOptionsMatchWrittenValues() {
        val written = FieldAiPolicy.SuggestMode.entries.map { mode ->
            FieldConfigColumns.aiCellOf(FieldAiPolicy.applyMode("{}", mode))
        }
        assertEquals(FieldConfigColumns.AI_CELL_OPTIONS.toSet(), written.toSet())
    }

    /** 옛 파일의 Y/N이 그대로 읽혀야 한다 — 이 열은 2단 시절부터 있었다. */
    @Test
    fun parseAiCell_acceptsLegacyAndLenientValues() {
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldConfigColumns.parseAiCell("Y"))
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldConfigColumns.parseAiCell("예"))
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldConfigColumns.parseAiCell("N"))
        assertEquals(FieldAiPolicy.SuggestMode.MANUAL_ONLY, FieldConfigColumns.parseAiCell("개별만"))
        assertEquals(FieldAiPolicy.SuggestMode.MANUAL_ONLY, FieldConfigColumns.parseAiCell(" manual "))
        assertEquals(FieldAiPolicy.SuggestMode.MANUAL_ONLY, FieldConfigColumns.parseAiCell("M"))
        // 빈칸은 기본(전부) — 종전 규약 그대로
        assertEquals(FieldAiPolicy.SuggestMode.ALL, FieldConfigColumns.parseAiCell(""))
        // 모르는 값은 끄기 — parseSheetBoolean의 종전 성질을 바꾸지 않는다
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldConfigColumns.parseAiCell("아무거나"))
    }

    /** 세 상태 모두 내보내기 → 가져오기에서 값이 보존되어야 한다(개발 의도 4번). */
    @Test
    fun roundTrip_allThreeModesSurvive() {
        for (mode in FieldAiPolicy.SuggestMode.entries) {
            val original = FieldAiPolicy.applyMode("""{"options":["A"]}""", mode)
            val merged = merge(
                FieldConfigColumns.stripPortableKeys(original),
                aiCell = FieldConfigColumns.aiCellOf(original),
                descCell = FieldDescription.fromConfig(original),
                existing = original
            )
            assertEquals("$mode 가 왕복에서 바뀌었다", mode, FieldAiPolicy.suggestMode(merged))
        }
    }

    /** 열을 지운 파일은 기존 DB 값을 유지한다 — '개별만'도 예외가 아니다. */
    @Test
    fun columnAbsent_keepsExistingManualOnly() {
        val existing = FieldAiPolicy.applyMode("{}", FieldAiPolicy.SuggestMode.MANUAL_ONLY)
        val merged = merge("{}", existing = existing)
        assertEquals(FieldAiPolicy.SuggestMode.MANUAL_ONLY, FieldAiPolicy.suggestMode(merged))
    }

    // ===== 왕복: 내보내기(strip + 전용 열) → 가져오기(merge) =====

    @Test
    fun roundTrip_preservesToggleAndDescription() {
        val original = """{"aiSuggest":false,"description":"왕복 설명","options":["A"]}"""
        // 내보내기: JSON 셀은 strip, 전용 열은 값에서 파생 (ExcelExporter와 같은 규칙)
        val jsonCell = FieldConfigColumns.stripPortableKeys(original)
        val aiCell = FieldConfigColumns.aiCellOf(original)
        val descCell = FieldDescription.fromConfig(original)
        // 가져오기(기존 행 갱신)
        val merged = merge(jsonCell, aiCell = aiCell, descCell = descCell, existing = original)
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(merged))
        assertEquals("왕복 설명", FieldDescription.fromConfig(merged))
        assertEquals(1, JSONObject(merged).getJSONArray("options").length())
    }
}
