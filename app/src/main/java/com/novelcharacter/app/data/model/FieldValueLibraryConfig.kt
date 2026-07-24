package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 필드별 값 라이브러리 입력 모드 설정.
 * FieldDefinition.config JSON의 "valueLibrary" 객체에 저장됨.
 *
 * - SUGGEST(기본): 자유 입력 + 라이브러리 제안(자동완성)
 * - FREE: 제안 끔 (자유 입력만)
 * - RESTRICTED: 라이브러리 값만 허용 — 저장 시 검증하되 차단 대신
 *   사유 + 교정 경로(값 추가/수정)를 제공한다 (변수 제어).
 */
data class FieldValueLibraryConfig(
    val inputMode: String = MODE_SUGGEST
) {
    val isRestricted: Boolean get() = inputMode == MODE_RESTRICTED
    val isSuggestEnabled: Boolean get() = inputMode != MODE_FREE

    companion object {
        const val MODE_SUGGEST = "suggest"
        const val MODE_FREE = "free"
        const val MODE_RESTRICTED = "restricted"

        private const val KEY = "valueLibrary"

        val MODES = listOf(MODE_SUGGEST, MODE_FREE, MODE_RESTRICTED)

        fun fromConfig(configJson: String): FieldValueLibraryConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject(KEY) ?: return FieldValueLibraryConfig()
                val mode = obj.optString("inputMode", MODE_SUGGEST)
                FieldValueLibraryConfig(if (mode in MODES) mode else MODE_SUGGEST)
            } catch (_: Exception) {
                FieldValueLibraryConfig()
            }
        }

        fun applyToConfig(existingConfig: String, config: FieldValueLibraryConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }
            if (config.inputMode == MODE_SUGGEST) {
                root.remove(KEY)  // 기본값은 저장하지 않음
            } else {
                root.put(KEY, JSONObject().put("inputMode", config.inputMode))
            }
            return root.toString()
        }
    }
}
