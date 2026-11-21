package com.novelcharacter.app.data.model

import org.json.JSONArray
import org.json.JSONObject
import com.novelcharacter.app.util.stringOr

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

    /**
     * 읽기 화면에 그릴 값 — 파트마다 이름·단위를 붙이고, **빈 파트는 [EMPTY_PART]로 자리를 지킨다.**
     *
     * 저장 형식은 파트를 구분자로 이은 한 문자열이라(`79--103.2-`) 그대로 그리면 어느 칸이
     * 비었는지 읽을 수 없다(S8 관찰). 빈 칸을 빼고 붙이면 반대로 *비었다는 사실*이 사라진다 —
     * 그래서 모든 파트를 순서대로 보이고 빈 것은 표시로 남긴다.
     *
     * null이면 구조화 표시를 하지 않는다 — 원문을 그대로 그린다:
     * 설정이 꺼졌거나, 값이 비었거나, **파트가 둘 이상인데 값에 구분자가 없을 때**다.
     * 마지막 경우는 구조화를 켜기 전에 적은 자유 글이라 첫 파트로 몰아 그리면 뜻을 바꾼다.
     */
    fun displayValue(combinedValue: String): String? {
        if (!enabled || parts.isEmpty()) return null
        val trimmed = combinedValue.trim()
        if (trimmed.isEmpty()) return null
        if (parts.size >= 2 && !trimmed.contains(separator.ifEmpty { "-" })) return null
        return splitValue(trimmed).mapIndexed { idx, (label, value) ->
            val suffix = parts[idx].suffix
            val shown = when {
                value.isEmpty() -> EMPTY_PART
                suffix.isEmpty() || value.endsWith(suffix) -> value
                else -> value + suffix
            }
            if (label.isBlank()) shown else "$label $shown"
        }.joinToString(" · ")
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

        /** [displayValue]가 빈 파트 자리에 그리는 표시. */
        const val EMPTY_PART = "–"

        // 통계가 값 하나마다 부르는 자리라 파싱 결과를 기억한다 — 근거와 안전 조건은
        // ConfigParseCache의 KDoc. 이 클래스와 Part가 전부 불변이라 인스턴스를 공유해도 된다.
        private val cache = ConfigParseCache { configJson -> parseUncached(configJson) }

        fun fromConfig(configJson: String): StructuredInputConfig = cache.get(configJson)

        /** 신체 사이즈 필드에 구조화 설정이 없을 때 폼이 세우는 파트(B-W-H). */
        val DEFAULT_BODY_SIZE = StructuredInputConfig(
            enabled = true,
            separator = "-",
            parts = listOf(Part("B", "cm", "number"), Part("W", "cm", "number"), Part("H", "cm", "number"))
        )

        /** 생일 역할 필드에 구조화 설정이 없을 때 폼이 세우는 파트(월-일). */
        val DEFAULT_BIRTH_DATE = StructuredInputConfig(
            enabled = true,
            separator = "-",
            parts = listOf(Part("월", "", "number"), Part("일", "", "number"))
        )

        /**
         * **폼이 실제로 그리는 구조** — 입력 폼·AI 형식 힌트·읽기 화면이 같은 답을 본다.
         *
         * 구조화는 파트 위젯을 렌더하는 타입(TEXT·BODY_SIZE)에만 선다. 설정이 없으면
         * 신체 사이즈는 B-W-H, 생일 역할은 월-일이 기본이다. 종전에는 이 기본값을 폼과
         * AI 제안이 각자 적었고 읽기 화면은 몰라서, 폼에서 칸으로 적은 값이 상세에서는
         * 저장 문자열 그대로(`79--103.2-`) 그려졌다(S8 관찰).
         */
        fun effectiveFor(field: FieldDefinition): StructuredInputConfig {
            val type = field.fieldType
            if (type != FieldType.TEXT && type != FieldType.BODY_SIZE) return StructuredInputConfig()
            val own = fromConfig(field.config)
            // 기본값은 **설정이 꺼져 있을 때만** 선다 — 폼이 종전에 쓰던 조건 그대로다.
            if (own.enabled) return own
            if (type == FieldType.BODY_SIZE) return DEFAULT_BODY_SIZE
            if (SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE) return DEFAULT_BIRTH_DATE
            return own
        }

        /**
         * 읽기 화면(상세·연표 슬라이더·목록 카드)이 한 줄로 그릴 구조화 표시. null이면 원문을 그린다.
         *
         * **생일 역할은 빼고 원문을 둔다** — 월-일 칸은 입력을 돕는 장치이고 저장 표기 `MM-DD`가
         * 이미 앱 전체의 표시 표기다(`03-15`를 `월 03 · 일 15`로 풀면 오히려 덜 읽힌다).
         */
        fun readDisplay(field: FieldDefinition, value: String): String? {
            if (SemanticRole.fromConfig(field.config) == SemanticRole.BIRTH_DATE) return null
            return effectiveFor(field).displayValue(value)
        }

        private fun parseUncached(configJson: String): StructuredInputConfig {
            return try {
                val root = JSONObject(configJson)
                val obj = root.optJSONObject(KEY) ?: return StructuredInputConfig()

                val enabled = obj.optBoolean("enabled", false)
                val separator = obj.stringOr("separator", "-")

                val parts = mutableListOf<Part>()
                val partsArr = obj.optJSONArray("parts")
                if (partsArr != null) {
                    for (i in 0 until partsArr.length()) {
                        val p = partsArr.getJSONObject(i)
                        parts.add(Part(
                            label = p.stringOr("label", ""),
                            suffix = p.stringOr("suffix", ""),
                            inputType = p.stringOr("inputType", "text")
                        ))
                    }
                }

                // toList()는 방어적 복제다. 캐시가 생기면서 이 인스턴스를 **여러 호출부가
                // 공유**하게 됐는데, 위 mutableListOf를 그대로 넘기면 List로 선언돼 있어도
                // 캐스팅해 고칠 수 있는 객체가 공유된다. 파싱은 config당 한 번뿐이라 값싸다.
                StructuredInputConfig(enabled, separator, parts.toList())
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
