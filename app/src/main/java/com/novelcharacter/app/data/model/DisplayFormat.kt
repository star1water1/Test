package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 필드 값의 표시 방식을 결정하는 포맷.
 * FieldDefinition.config JSON의 "displayFormat" 키에 저장됨.
 */
enum class DisplayFormat(val key: String, val label: String) {
    /** 기본 텍스트 한 줄 */
    PLAIN("plain", "기본 (한 줄)"),
    /** 콤마로 구분된 값을 칩/태그로 표시 */
    COMMA_LIST("comma_list", "콤마 구분 목록 (칩)"),
    /** 줄바꿈 구분 여러 줄 텍스트 */
    MULTILINE("multiline", "여러 줄"),
    /** 콤마 구분 값을 bullet list로 표시 */
    BULLET_LIST("bullet_list", "콤마 구분 목록 (글머리)");

    companion object {
        fun fromKey(key: String?): DisplayFormat =
            entries.find { it.key == key } ?: PLAIN

        fun fromConfig(config: String): DisplayFormat {
            return try {
                val json = JSONObject(config)
                fromKey(json.optString("displayFormat", "plain"))
            } catch (_: Exception) {
                PLAIN
            }
        }

        fun labels(): List<String> = entries.map { it.label }
    }
}
