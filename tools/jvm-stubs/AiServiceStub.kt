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

    /** 이미지 미지원 학습 여부 (A-7). 스텁은 학습된 것이 없는 상태. */
    fun isImagesUnsupported(): Boolean = false
}

/*
 * ⚠️ **이 스텁은 실제 API를 *비추기만* 한다 — 시험이 기댈 표면을 여기서 늘리지 말 것.**
 *
 * 2026.08.12(B-157)에 한 번 어겼다. 시험이 값을 뒤집을 수 있게 `var imagesUnsupported`를
 * 여기 더하고 시험이 `AiService()`를 세웠는데, **그 둘은 이 스텁에만 있다** — 진짜는
 * `AiService(context: Context)`이고 그런 속성도 없다. 하네스는 초록, **Gradle(CI)은 컴파일
 * 실패**였다(`No value passed for parameter 'context'` · `Unresolved reference`).
 *
 * 갈림의 뿌리는 고칠 수 없다(진짜는 `Context`·okhttp에 묶여 순수 하네스에 못 온다).
 * 그러니 **시험이 이 클래스를 아예 부르지 않게 만드는 것**이 답이다 — 그때 택한 모양은
 * `ImageBatchTagSuggester`가 쓰는 몫 셋을 람다로 받고 `AiService`용 보조 생성자를 두는 것이다.
 * 덕분에 그 시험은 두 환경에서 같은 것을 보고 **CI에서도 돈다**(그쪽이 더 강하다).
 */
