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

    /** 슬라이더 눈금에 맞춘 값 — 저장값이 눈금 밖이면 슬라이더가 예외로 죽는다 */
    private fun snap(value: Int, step: Int): Int = (value / step) * step
}
