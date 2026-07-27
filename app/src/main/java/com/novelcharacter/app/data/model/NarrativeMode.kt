package com.novelcharacter.app.data.model

import org.json.JSONObject

/**
 * 이 필드가 **서술형(긴 글)**인지에 대한 사용자 설정.
 * `FieldDefinition.config` JSON의 `"narrativeMode"` 키에 저장된다.
 *
 * 왜 [DisplayFormat.MULTILINE]을 그대로 쓰지 않는가:
 * DisplayFormat은 **표시 방식**이다. 거기에 AI 동작을 묶으면 표시를 바꾸는 순간 AI 동작이
 * 딸려 바뀌고, 그 반대도 성립한다("묶는 것은 의미를 부여하는 일이다" — R-8의 교훈).
 * 한 줄로 보여주고 싶지만 초안은 AI로 쓰고 싶은 필드, 여러 줄로 보여주지만 짧은 목록인 필드가
 * 둘 다 실재한다. 그래서 축을 나누되, [AUTO]가 표시 형식을 **기본 근거**로 삼아
 * 아무 설정 없이도 바로 동작하게 한다(원칙 04 — 존재를 알 수 없는 기능이 있으면 안 된다).
 *
 * 자율성 우선(개발 의도): 가능성은 넓게 열되 실사용은 기본값으로 간편하게.
 */
enum class NarrativeMode(val key: String, val label: String) {
    /** 표시 형식으로 판단 — '여러 줄'이면 서술형. 기본값. */
    AUTO("auto", "자동 (표시 형식으로 판단)"),

    /** 항상 서술형 — 초안·이어쓰기·다듬기 경로를 쓴다. */
    NARRATIVE("narrative", "서술형 (긴 글)"),

    /** 항상 짧은 값 — 값 추천 경로를 쓴다. */
    SHORT("short", "짧은 값");

    companion object {
        private const val CONFIG_KEY = "narrativeMode"

        fun fromKey(key: String?): NarrativeMode = entries.find { it.key == key } ?: AUTO

        fun fromConfig(configJson: String): NarrativeMode = try {
            fromKey(JSONObject(configJson).optString(CONFIG_KEY, AUTO.key))
        } catch (_: Exception) {
            AUTO
        }

        /** [AUTO]는 키를 지운다 — "설정 안 함"과 "자동으로 설정함"을 구분할 이유가 없다. */
        fun applyToConfig(existing: String, mode: NarrativeMode): String = try {
            val json = if (existing.isBlank()) JSONObject() else JSONObject(existing)
            if (mode == AUTO) json.remove(CONFIG_KEY) else json.put(CONFIG_KEY, mode.key)
            json.toString()
        } catch (_: Exception) {
            existing
        }

        fun labels(): List<String> = entries.map { it.label }

        /**
         * 서술형 설정을 **제시할 수 있는** 타입인가.
         *
         * SELECT·GRADE는 값이 옵션 중 하나여야 하고(산문은 그 계약을 만족할 수 없다),
         * NUMBER·BODY_SIZE·CALCULATED는 값의 형태가 정해져 있으며, MULTI_TEXT는 산문이 아니라
         * 토큰 목록이다. 기능 간소화가 아니라 **타입 계약**이라 여기서 가른다.
         */
        fun isEligibleType(type: String?): Boolean = type == FieldType.TEXT.name

        /**
         * 이 필드가 실제로 서술형인가 — 폼의 ✨ 경로 분기 기준(단일 소스).
         *
         * 구조화 입력(파트 위젯)과 시맨틱 역할(생일 등)은 값의 형식이 강제되므로
         * 사용자가 [NARRATIVE]로 못 박아도 서술형이 될 수 없다. 설정을 무시하는 것이 아니라
         * **애초에 성립하지 않는 조합**이고, 그 사실은 편집 화면에서 설정이 보이지 않는 것으로 드러난다.
         */
        fun isNarrative(field: FieldDefinition): Boolean {
            if (!isEligibleType(field.type)) return false
            if (SemanticRole.fromConfig(field.config) != null) return false
            if (StructuredInputConfig.fromConfig(field.config).enabled) return false
            return when (fromConfig(field.config)) {
                NARRATIVE -> true
                SHORT -> false
                AUTO -> DisplayFormat.fromConfig(field.config) == DisplayFormat.MULTILINE
            }
        }
    }
}
