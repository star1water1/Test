package com.novelcharacter.app.data

import com.novelcharacter.app.data.model.FieldAiPolicy
import com.novelcharacter.app.data.model.FieldDescription
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FieldAiPolicy(A-1) · FieldDescription(A-2) — config JSON 키 계약.
 * 핵심: 기본값은 저장하지 않고(키 제거), 손상 JSON은 관대하게 기본값으로 읽되
 * 쓰기에서는 원문을 파괴하지 않으며, 왕복(쓰기→읽기)이 값을 보존한다.
 */
class FieldConfigPolicyTest {

    // ===== FieldAiPolicy (B-80: 3단) =====

    private val ALL = FieldAiPolicy.SuggestMode.ALL
    private val MANUAL = FieldAiPolicy.SuggestMode.MANUAL_ONLY
    private val OFF = FieldAiPolicy.SuggestMode.OFF

    @Test
    fun aiPolicy_missingKey_meansAll() {
        assertEquals(ALL, FieldAiPolicy.suggestMode("{}"))
        assertEquals(ALL, FieldAiPolicy.suggestMode("""{"options":["A"]}"""))
    }

    @Test
    fun aiPolicy_corruptJson_lenientDefault() {
        assertEquals(ALL, FieldAiPolicy.suggestMode(""))
        assertEquals(ALL, FieldAiPolicy.suggestMode("not json"))
    }

    @Test
    fun aiPolicy_defaultNotStored() {
        // 기본값(전부)을 쓰면 키가 제거된다 — NarrativeMode.applyToConfig와 같은 관행
        val off = FieldAiPolicy.applyMode("{}", OFF)
        assertEquals(OFF, FieldAiPolicy.suggestMode(off))
        val backToAll = FieldAiPolicy.applyMode(off, ALL)
        assertFalse(JSONObject(backToAll).has(FieldAiPolicy.CONFIG_KEY))
        assertEquals(ALL, FieldAiPolicy.suggestMode(backToAll))
    }

    /**
     * **왕복 무결성의 핵심** — OFF는 2단 시절과 같은 불리언 `false`로 저장된다.
     * 표현을 바꿨다면 이미 꺼 둔 필드가 다음 저장에서 표현만 달라지고,
     * 그전에 내보낸 엑셀·월드패키지·백업이 전부 옛 표현을 담게 된다.
     */
    @Test
    fun aiPolicy_offIsStoredAsLegacyBoolean() {
        val off = FieldAiPolicy.applyMode("{}", OFF)
        assertEquals(false, JSONObject(off).get(FieldAiPolicy.CONFIG_KEY))
    }

    @Test
    fun aiPolicy_manualOnlyIsStoredAsString() {
        val manual = FieldAiPolicy.applyMode("{}", MANUAL)
        assertEquals("manual", JSONObject(manual).getString(FieldAiPolicy.CONFIG_KEY))
        assertEquals(MANUAL, FieldAiPolicy.suggestMode(manual))
    }

    /** 2단 시절 파일·DB가 지금도 들어온다 — `false`는 끄기, `true`는 전부로 읽어야 한다. */
    @Test
    fun aiPolicy_readsLegacyBooleanValues() {
        assertEquals(OFF, FieldAiPolicy.suggestMode("""{"aiSuggest":false}"""))
        assertEquals(ALL, FieldAiPolicy.suggestMode("""{"aiSuggest":true}"""))
    }

    /** 외부에서 손으로 고쳐 온 값도 받는다. 모르는 값은 기본으로 — 이유 없이 AI에서 빠지지 않게. */
    @Test
    fun aiPolicy_readsLenientStrings() {
        assertEquals(OFF, FieldAiPolicy.suggestMode("""{"aiSuggest":"off"}"""))
        assertEquals(OFF, FieldAiPolicy.suggestMode("""{"aiSuggest":"N"}"""))
        assertEquals(MANUAL, FieldAiPolicy.suggestMode("""{"aiSuggest":"MANUAL"}"""))
        assertEquals(MANUAL, FieldAiPolicy.suggestMode("""{"aiSuggest":"개별만"}"""))
        assertEquals(ALL, FieldAiPolicy.suggestMode("""{"aiSuggest":"all"}"""))
        assertEquals(ALL, FieldAiPolicy.suggestMode("""{"aiSuggest":"알 수 없는 값"}"""))
    }

    /** 3단이 두 경로에 각각 답한다 — 이것이 B-80이 메우려던 공백이다. */
    @Test
    fun aiPolicy_modesAnswerBothPathsIndependently() {
        assertTrue(ALL.inBulk); assertTrue(ALL.inlineSparkle)
        assertFalse(MANUAL.inBulk); assertTrue(MANUAL.inlineSparkle)
        assertFalse(OFF.inBulk); assertFalse(OFF.inlineSparkle)
    }

    @Test
    fun aiPolicy_predicatesFollowMode() {
        val manual = FieldAiPolicy.applyMode("{}", MANUAL)
        assertFalse(FieldAiPolicy.isBulkTarget(manual))
        assertTrue(FieldAiPolicy.isInlineSparkleEnabled(manual))
        val off = FieldAiPolicy.applyMode("{}", OFF)
        assertFalse(FieldAiPolicy.isBulkTarget(off))
        assertFalse(FieldAiPolicy.isInlineSparkleEnabled(off))
    }

    @Test
    fun aiPolicy_roundTripsEveryMode() {
        for (mode in FieldAiPolicy.SuggestMode.entries) {
            assertEquals(mode, FieldAiPolicy.suggestMode(FieldAiPolicy.applyMode("{}", mode)))
        }
    }

    @Test
    fun aiPolicy_preservesOtherKeys() {
        val config = """{"options":["A","B"],"narrativeMode":"short"}"""
        for (mode in FieldAiPolicy.SuggestMode.entries) {
            val written = FieldAiPolicy.applyMode(config, mode)
            val json = JSONObject(written)
            assertEquals(2, json.getJSONArray("options").length())
            assertEquals("short", json.getString("narrativeMode"))
            assertEquals(mode, FieldAiPolicy.suggestMode(written))
        }
    }

    @Test
    fun aiPolicy_corruptJson_writeKeepsOriginal() {
        // 손상 JSON에 쓰기를 시도해도 다른 데이터를 파괴하지 않고 원문을 돌려준다
        assertEquals("not json", FieldAiPolicy.applyMode("not json", OFF))
    }

    @Test
    fun aiPolicy_blankConfig_write() {
        assertEquals(OFF, FieldAiPolicy.suggestMode(FieldAiPolicy.applyMode("", OFF)))
        assertEquals(MANUAL, FieldAiPolicy.suggestMode(FieldAiPolicy.applyMode("", MANUAL)))
    }

    // ===== FieldDescription =====

    @Test
    fun description_missingKey_meansEmpty() {
        assertEquals("", FieldDescription.fromConfig("{}"))
        assertEquals("", FieldDescription.fromConfig("not json"))
        assertEquals("", FieldDescription.fromConfig(""))
    }

    @Test
    fun description_roundTrip() {
        val text = "이 세계의 마나를 몸에 받아들이는 정도. 0~100 수치."
        val config = FieldDescription.applyToConfig("{}", text)
        assertEquals(text, FieldDescription.fromConfig(config))
    }

    @Test
    fun description_emptyRemovesKey() {
        val withDesc = FieldDescription.applyToConfig("{}", "설명")
        val cleared = FieldDescription.applyToConfig(withDesc, "")
        assertFalse(JSONObject(cleared).has(FieldDescription.CONFIG_KEY))
        // 공백뿐인 입력도 제거로 취급한다
        val clearedBlank = FieldDescription.applyToConfig(withDesc, "   ")
        assertFalse(JSONObject(clearedBlank).has(FieldDescription.CONFIG_KEY))
    }

    @Test
    fun description_storageCapEnforced() {
        val long = "가".repeat(FieldDescription.MAX_CHARS + 200)
        val config = FieldDescription.applyToConfig("{}", long)
        assertEquals(FieldDescription.MAX_CHARS, FieldDescription.fromConfig(config).length)
    }

    @Test
    fun description_preservesOtherKeys() {
        val config = """{"aiSuggest":false,"options":["A"]}"""
        val withDesc = FieldDescription.applyToConfig(config, "설명문")
        assertEquals(FieldAiPolicy.SuggestMode.OFF, FieldAiPolicy.suggestMode(withDesc))
        assertEquals(1, JSONObject(withDesc).getJSONArray("options").length())
        assertEquals("설명문", FieldDescription.fromConfig(withDesc))
    }

    @Test
    fun description_corruptJson_writeKeepsOriginal() {
        assertEquals("broken{", FieldDescription.applyToConfig("broken{", "설명"))
    }
}
