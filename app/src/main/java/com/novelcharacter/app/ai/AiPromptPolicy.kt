package com.novelcharacter.app.ai

/**
 * 프롬프트 적재량 설정의 범위·기본값·비용 추정 (순수 — 저장소 비의존).
 *
 * 슬라이더 눈금과 저장값의 단일 소스다. 두 곳에 따로 적으면 눈금 밖 값이 저장돼
 * Material Slider가 죽는다(출력 토큰 슬라이더에서 이미 겪은 문제 — AiTokenPolicy.snapToStep).
 */
object AiPromptPolicy {

    // ── 기존 사용값 예시 (짧은 값 추천) ──
    const val USAGE_EXAMPLES_DEFAULT = CharacterFieldAiSuggester.MAX_USAGE_EXAMPLES
    const val USAGE_EXAMPLES_MAX = 24
    const val USAGE_EXAMPLES_STEP = 2

    /** 예시 1개의 대략적 입력 토큰(짧은 값 + 구분자). 고지용 추정이지 계약이 아니다. */
    const val USAGE_TOKENS_PER_EXAMPLE = 5

    // ── 문체 참고 (서술형 작성) ──
    const val STYLE_SAMPLES_DEFAULT = 2
    const val STYLE_SAMPLES_MAX = 3

    /** 참고 1편의 대략적 입력 토큰 — 한국어 산문 [NarrativeFieldAiWriter.STYLE_SAMPLE_CHARS]자 기준. */
    const val STYLE_TOKENS_PER_SAMPLE = 400

    fun clampUsageExamples(value: Int): Int =
        snap(value.coerceIn(0, USAGE_EXAMPLES_MAX), USAGE_EXAMPLES_STEP)

    fun clampStyleSamples(value: Int): Int = value.coerceIn(0, STYLE_SAMPLES_MAX)

    /** 예시를 켰을 때 **필드 하나당** 늘어나는 입력 토큰 추정 */
    fun estimatedUsageTokensPerField(count: Int): Int =
        clampUsageExamples(count) * USAGE_TOKENS_PER_EXAMPLE

    /** 문체 참고를 켰을 때 **요청 하나당** 늘어나는 입력 토큰 추정 */
    fun estimatedStyleTokensPerRequest(count: Int): Int =
        clampStyleSamples(count) * STYLE_TOKENS_PER_SAMPLE

    // ── 받아올 추천의 근거 강도 (짧은 값 추천) ──

    /**
     * 기본값은 **전부 받기**(null).
     *
     * 최종 채택은 어차피 사용자가 항목별로 체크해서 하므로, 앱이 미리 걸러 봐야 사용자의
     * 선택지만 줄어든다. 넓게 받아 놓고 고르는 편이 못 받은 것을 다시 요청하는 것보다 싸다
     * (자율성 우선 — 기능의 쓸모는 사용자가 가린다). 추측이 거슬리는 사용자는 올려서 쓴다.
     */
    val CONFIDENCE_DEFAULT: CharacterFieldAiSuggester.Confidence? = null

    /** 저장 표기 ↔ 값. 빈 문자열이 '전부 받기'다 (SharedPreferences에 null을 못 담는다) */
    fun confidenceToWire(value: CharacterFieldAiSuggester.Confidence?): String = value?.wire ?: ""

    fun confidenceFromWire(raw: String?): CharacterFieldAiSuggester.Confidence? =
        CharacterFieldAiSuggester.Confidence.fromWire(raw)

    /** 슬라이더 눈금에 맞춘 값 — 저장값이 눈금 밖이면 슬라이더가 예외로 죽는다 */
    private fun snap(value: Int, step: Int): Int = (value / step) * step
}
