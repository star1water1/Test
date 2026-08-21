package com.novelcharacter.app.data.model

import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

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

        // 통계가 값 하나마다 부르는 자리라 파싱 결과를 기억한다 — 근거와 안전 조건은
        // ConfigParseCache의 KDoc. enum이라 공유해도 되고, 키가 config 문자열이라 낡지 않는다.
        private val cache = ConfigParseCache { config ->
            try {
                fromKey(JSONObject(config).stringOr("displayFormat", "plain"))
            } catch (_: Exception) {
                PLAIN
            }
        }

        fun fromConfig(config: String): DisplayFormat = cache.get(config)

        fun labels(): List<String> = entries.map { it.label }
    }
}
