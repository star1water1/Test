package com.novelcharacter.app.ai

import android.content.Context

/**
 * AI 프롬프트에 **얼마나 실을 것인가**의 사용자 설정 (설정 → AI 연동 → 'AI 추천 일관성').
 *
 * 왜 설정으로 여는가: 기존 값 예시·문체 참고는 추천의 일관성을 올리지만 입력 토큰을 쓴다.
 * 그 교환비가 이득인지는 데이터 규모·모델·과금 방식마다 다르므로 개발자가 정할 수 없다
 * (자율성 우선 — 기능의 쓸모는 사용자가 가린다). 기본값은 켜져 있고, 0으로 두면 끈다.
 *
 * 값 범위·기본값·토큰 추정은 [AiPromptPolicy]에 순수 함수로 두어 단위 테스트한다
 * (SharedPreferences는 JVM 러너에서 실행할 수 없다).
 */
class AiPromptSettings(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 짧은 값 추천에 실을 필드별 기존 사용값 예시 개수. 0이면 싣지 않는다. */
    var usageExampleCount: Int
        get() = AiPromptPolicy.clampUsageExamples(
            sp.getInt(KEY_USAGE_EXAMPLES, AiPromptPolicy.USAGE_EXAMPLES_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_USAGE_EXAMPLES, AiPromptPolicy.clampUsageExamples(value)).apply()
        }

    /** 서술형 작성에 실을 같은 필드의 다른 캐릭터 글(문체 참고) 개수. 0이면 싣지 않는다. */
    var styleSampleCount: Int
        get() = AiPromptPolicy.clampStyleSamples(
            sp.getInt(KEY_STYLE_SAMPLES, AiPromptPolicy.STYLE_SAMPLES_DEFAULT)
        )
        set(value) {
            sp.edit().putInt(KEY_STYLE_SAMPLES, AiPromptPolicy.clampStyleSamples(value)).apply()
        }

    /**
     * 받아올 추천의 **최소 근거 강도**. null이면 강도와 무관하게 전부 받는다(기본값).
     *
     * 프롬프트(모델이 애초에 안 만들게)와 파싱(그래도 오면 사유를 달아 제외)의 양쪽에 쓰인다.
     * 제외된 것은 조용히 사라지지 않고 `MissingCause.BELOW_CONFIDENCE`로 고지되므로,
     * 사용자는 "설정 때문에 빠졌다"는 사실과 되받는 방법을 함께 본다 (변수 제어).
     */
    var minConfidence: CharacterFieldAiSuggester.Confidence?
        get() = AiPromptPolicy.confidenceFromWire(
            sp.getString(KEY_MIN_CONFIDENCE, AiPromptPolicy.confidenceToWire(AiPromptPolicy.CONFIDENCE_DEFAULT))
        )
        set(value) {
            sp.edit().putString(KEY_MIN_CONFIDENCE, AiPromptPolicy.confidenceToWire(value)).apply()
        }

    companion object {
        /** 키를 담지 않는다 — 이 파일은 `ai_keys`·`ai_providers`와 달리 민감 정보가 없다. */
        const val PREFS_NAME = "ai_prompt_settings"
        private const val KEY_USAGE_EXAMPLES = "usageExampleCount"
        private const val KEY_STYLE_SAMPLES = "styleSampleCount"
        private const val KEY_MIN_CONFIDENCE = "minConfidence"
    }
}
