package com.novelcharacter.app.ai

/**
 * 하네스 전용 AiService 스텁 (tools/run_jvm_tests.sh).
 *
 * 실제 AiService는 okhttp·android.content.Context에 의존해 순수 JVM 하네스로 컴파일할 수 없다.
 * CharacterFieldAiSuggester·FieldLibraryAiOrganizer의 순수 로직(프롬프트 조립·응답 파싱·검증)은
 * AiService를 호출하지 않으므로, 타입 해석용 선언만 있으면 컴파일·실행 검증이 가능하다.
 *
 * 실제 complete 시그니처가 바뀌면 이 스텁도 함께 갱신할 것 — 어긋나면 하네스 컴파일이 깨진다
 * (그것이 목적이다: StatsHarnessStubs와 같은 취지).
 */
class AiService {
    suspend fun complete(request: AiRequest, config: AiProviderConfig? = null): AiResult =
        throw UnsupportedOperationException("하네스 스텁 — 실제 호출 금지")

    /** 청킹·비용 고지가 이 값에서 파생된다. 스텁은 정책 기본값을 그대로 돌려준다. */
    fun effectiveMaxTokens(): Int = AiTokenPolicy.DEFAULT_REQUEST

    /** 창작도 샘플링 (A-4). 스텁은 활성 프로바이더가 없는 상태와 동일하게 null(미전송). */
    fun temperatureFor(creativity: AiCreativity): Double? = null

    /** temperature 미지원 학습 여부 (A-4). 스텁은 학습된 것이 없는 상태. */
    fun isTemperatureUnsupported(): Boolean = false

    /**
     * 이미지 미지원 학습 여부 (A-7). 기본은 **학습된 것이 없는 상태**라 실제 기본 동작과 같다.
     *
     * 형제들과 달리 이 하나만 값을 바꿀 수 있게 둔 것은 **B-157 가드가 이 값에서 갈리기
     * 때문**이다. 켜 두고 [ImageBatchTagSuggester.suggest]를 부르면 요청 경로가 [complete]에
     * 닿는 순간 위 예외로 죽으므로, *"요청을 만들지 않는다"*가 시험에서 그대로 증명된다 —
     * 반환값을 꾸며 낸 가짜 성공으로는 얻을 수 없는 증명이다.
     */
    var imagesUnsupported: Boolean = false

    fun isImagesUnsupported(): Boolean = imagesUnsupported
}
