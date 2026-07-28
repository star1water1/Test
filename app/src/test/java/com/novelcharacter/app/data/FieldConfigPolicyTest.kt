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

    // ===== FieldAiPolicy =====

    @Test
    fun aiPolicy_missingKey_meansEnabled() {
        assertTrue(FieldAiPolicy.isSuggestEnabled("{}"))
        assertTrue(FieldAiPolicy.isSuggestEnabled("""{"options":["A"]}"""))
    }

    @Test
    fun aiPolicy_corruptJson_lenientDefault() {
        assertTrue(FieldAiPolicy.isSuggestEnabled(""))
        assertTrue(FieldAiPolicy.isSuggestEnabled("not json"))
    }

    @Test
    fun aiPolicy_defaultNotStored() {
        // true(기본값)를 쓰면 키가 제거된다 — NarrativeMode.applyToConfig와 같은 관행
        val disabled = FieldAiPolicy.applyToConfig("{}", false)
        assertFalse(FieldAiPolicy.isSuggestEnabled(disabled))
        val reEnabled = FieldAiPolicy.applyToConfig(disabled, true)
        assertFalse(JSONObject(reEnabled).has(FieldAiPolicy.CONFIG_KEY))
        assertTrue(FieldAiPolicy.isSuggestEnabled(reEnabled))
    }

    @Test
    fun aiPolicy_preservesOtherKeys() {
        val config = """{"options":["A","B"],"narrativeMode":"short"}"""
        val disabled = FieldAiPolicy.applyToConfig(config, false)
        val json = JSONObject(disabled)
        assertEquals(2, json.getJSONArray("options").length())
        assertEquals("short", json.getString("narrativeMode"))
        assertFalse(FieldAiPolicy.isSuggestEnabled(disabled))
    }

    @Test
    fun aiPolicy_corruptJson_writeKeepsOriginal() {
        // 손상 JSON에 쓰기를 시도해도 다른 데이터를 파괴하지 않고 원문을 돌려준다
        assertEquals("not json", FieldAiPolicy.applyToConfig("not json", false))
    }

    @Test
    fun aiPolicy_blankConfig_write() {
        val disabled = FieldAiPolicy.applyToConfig("", false)
        assertFalse(FieldAiPolicy.isSuggestEnabled(disabled))
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
        assertFalse(FieldAiPolicy.isSuggestEnabled(withDesc))
        assertEquals(1, JSONObject(withDesc).getJSONArray("options").length())
        assertEquals("설명문", FieldDescription.fromConfig(withDesc))
    }

    @Test
    fun description_corruptJson_writeKeepsOriginal() {
        assertEquals("broken{", FieldDescription.applyToConfig("broken{", "설명"))
    }
}
