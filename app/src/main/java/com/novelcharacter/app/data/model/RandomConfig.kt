package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 필드의 랜덤 값 생성 설정.
 * FieldDefinition.config JSON의 "random" 키에 저장.
 * NUMBER/SELECT/GRADE 타입에서 사용 가능.
 */
data class RandomConfig(
    val enabled: Boolean = false,
    val min: Double? = null,        // NUMBER 전용: 최솟값
    val max: Double? = null,        // NUMBER 전용: 최댓값
    val decimalPlaces: Int = 0      // NUMBER 전용: 소수점 자릿수 (0=정수)
) {
    companion object {
        fun fromConfig(configJson: String): RandomConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject("random") ?: return RandomConfig()
                RandomConfig(
                    enabled = obj.optBoolean("enabled", false),
                    min = if (obj.has("min")) obj.optDouble("min") else null,
                    max = if (obj.has("max")) obj.optDouble("max") else null,
                    decimalPlaces = obj.optInt("decimalPlaces", 0)
                )
            } catch (_: Exception) {
                RandomConfig()
            }
        }

        fun applyToConfig(existingConfig: String, randomConfig: RandomConfig): String {
            val root = try { JSONObject(existingConfig) } catch (_: Exception) { JSONObject() }
            if (randomConfig.enabled) {
                val obj = JSONObject().apply {
                    put("enabled", true)
                    randomConfig.min?.let { put("min", it) }
                    randomConfig.max?.let { put("max", it) }
                    if (randomConfig.decimalPlaces > 0) put("decimalPlaces", randomConfig.decimalPlaces)
                }
                root.put("random", obj)
            } else {
                root.remove("random")
            }
            return root.toString()
        }
    }
}
