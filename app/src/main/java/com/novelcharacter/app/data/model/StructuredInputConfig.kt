package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 구조화 입력 설정.
 * FieldDefinition.config JSON의 "structuredInput" 객체에 저장됨.
 *
 * 예: 신체 사이즈 필드 → parts: [Part("키","cm","number"), Part("체중","kg","number")]
 * 예: 마법 속성 → parts: [Part("속성명","","text"), Part("위력","","number")]
 *
 * 입력 시 각 파트별 개별 입력 필드가 생성되고,
 * 저장 시 separator로 합쳐져 단일 문자열로 저장됨.
 * 통계 시 각 파트별로 분리 분석 가능.
 */
data class StructuredInputConfig(
    val enabled: Boolean = false,
    val separator: String = "-",
    val parts: List<Part> = emptyList()
) {
    data class Part(
        val label: String,
        val suffix: String = "",
        val inputType: String = "text"  // "text", "number"
    )

    /** 합쳐진 값을 파트별로 분리하여 (라벨, 값) 목록으로 반환 */
    fun splitValue(combinedValue: String): List<Pair<String, String>> {
        if (!enabled || parts.isEmpty()) return emptyList()
        val sep = separator.ifEmpty { "-" }
        // limit으로 파트 수만큼만 분리 — 마지막 파트에 separator가 포함되어도 보존
        val rawParts = combinedValue.split(sep, limit = parts.size).map { it.trim() }
        return parts.mapIndexed { idx, part ->
            part.label to (rawParts.getOrNull(idx) ?: "")
        }
    }

    /** 파트별 값을 separator로 합쳐 단일 문자열로 반환 */
    fun joinValues(partValues: List<String>): String {
        return partValues.joinToString(separator.ifEmpty { "-" })
    }

    /** 통계용: 파트별 라벨 붙은 값 목록 반환. 파트가 2개 이상이면 전체 값은 제외한다. */
    fun labeledParts(combinedValue: String): List<String> {
        if (!enabled || parts.isEmpty()) return listOf(combinedValue)
        val sep = separator.ifEmpty { "-" }
        val rawParts = combinedValue.split(sep, limit = parts.size).map { it.trim() }
        if (rawParts.size >= 2) {
            // 파트별 값만 반환 (전체 조합 값 제외 — 전체 값은 무의미한 문자열 분포를 만듦)
            return parts.mapIndexedNotNull { idx, part ->
                rawParts.getOrNull(idx)?.takeIf { it.isNotEmpty() }?.let { "${part.label}:$it" }
            }
        }
        return listOf(combinedValue.trim())
    }

    companion object {
        private const val KEY = "structuredInput"

        fun fromConfig(configJson: String): StructuredInputConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject(KEY) ?: return StructuredInputConfig()

                val enabled = obj.optBoolean("enabled", false)
                val separator = obj.optString("separator", "-")

                val parts = mutableListOf<Part>()
                val partsArr = obj.optJSONArray("parts")
                if (partsArr != null) {
                    for (i in 0 until partsArr.length()) {
                        val p = partsArr.getJSONObject(i)
                        parts.add(Part(
                            label = p.optString("label", ""),
                            suffix = p.optString("suffix", ""),
                            inputType = p.optString("inputType", "text")
                        ))
                    }
                }

                StructuredInputConfig(enabled, separator, parts)
            } catch (_: Exception) {
                StructuredInputConfig()
            }
        }

        fun applyToConfig(existingConfig: String, structuredConfig: StructuredInputConfig): String {
            val root = try {
                JSONObject(existingConfig)
            } catch (_: Exception) {
                JSONObject()
            }

            if (structuredConfig.enabled && structuredConfig.parts.isNotEmpty()) {
                val obj = JSONObject().apply {
                    put("enabled", true)
                    put("separator", structuredConfig.separator)
                    val partsArr = JSONArray()
                    for (part in structuredConfig.parts) {
                        partsArr.put(JSONObject().apply {
                            put("label", part.label)
                            if (part.suffix.isNotEmpty()) put("suffix", part.suffix)
                            if (part.inputType != "text") put("inputType", part.inputType)
                        })
                    }
                    put("parts", partsArr)
                }
                root.put(KEY, obj)
            } else {
                root.remove(KEY)
            }

            return root.toString()
        }
    }
}
