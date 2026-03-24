package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 커스텀 필드와 시스템 특수 필드(__birth, __death 등)를 연동하는 역할 정의.
 * FieldDefinition.config JSON에 "semanticRole" 키로 저장됨.
 */
enum class SemanticRole(
    val key: String,
    val label: String,
    val linkedKey: String,
    val description: String
) {
    BIRTH_YEAR("birth_year", "출생연도", "__birth", "생일 알림, 위젯, 나이 자동계산, 생존기간 통계"),
    BIRTH_DATE("birth_date", "생일(월/일)", "__birth", "생일 알림, 오늘의 캐릭터 위젯"),
    DEATH_YEAR("death_year", "사망연도", "__death", "생존 여부 판정, 생존기간 통계"),
    ALIVE("alive", "생존 여부", "__alive", "사망연도 연동, 생존/사망 상태 추적"),
    AGE("age", "나이", "__age", "표준 년도 기반 자동 나이 계산"),
    HEIGHT("height", "키(신장)", "__height", "체형 분석 연동"),
    WEIGHT("weight", "체중", "__weight", "체형 분석, BMI 연동"),
    BODY_SIZE("body_size", "신체 사이즈", "__body_size", "체형 분석, 컵사이즈 연동");

    companion object {
        private const val CONFIG_KEY = "semanticRole"

        fun fromKey(key: String?): SemanticRole? =
            entries.find { it.key == key }

        fun fromConfig(configJson: String): SemanticRole? {
            return try {
                val json = JSONObject(configJson)
                fromKey(json.optString(CONFIG_KEY, null))
            } catch (_: Exception) {
                null
            }
        }

        fun applyToConfig(existing: String, role: SemanticRole?): String {
            return try {
                val json = JSONObject(existing)
                if (role != null) {
                    json.put(CONFIG_KEY, role.key)
                } else {
                    json.remove(CONFIG_KEY)
                }
                json.toString()
            } catch (_: Exception) {
                if (role != null) {
                    """{"$CONFIG_KEY":"${role.key}"}"""
                } else {
                    existing
                }
            }
        }

        fun labels(): List<String> = entries.map { it.label }
    }
}
