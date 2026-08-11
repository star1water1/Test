package com.novelcharacter.app.ai

/**
 * 필드 값 추천의 **프롬프트 조립 전략** — 축(캐릭터·사건)마다 갈리는 유일한 자리 (B-43).
 *
 * ## 왜 갈랐는가
 *
 * 사용자 확정 16번의 ㄴ2가 *"사건 전용 프롬프트를 따로 둔다"*이다. 근거는 R-13이 집계에
 * 대해 세운 것과 같다 — **셀 단위가 다르면 함수를 나눈다.** 캐릭터 프롬프트는 태그·메모·
 * 소속·관계·대결 우열을 근거로 삼으라고 지시하는데, 사건에는 그 칸이 아예 없다. 같은
 * 프롬프트에 섹션만 바꿔 끼우면 **존재하지 않는 근거를 대라는 지시**가 그대로 나간다.
 *
 * ## 왜 이것만 갈랐는가
 *
 * 프롬프트 **밖**은 축을 타지 않는다 — 응답 스키마, 검증·정규화, 결손 사유 분류,
 * 청킹·부분 실패 격리·토큰 집계가 전부 *"필드 하나의 값"*이라는 같은 단위를 다룬다.
 * 그쪽까지 갈라 두면 한쪽 축에서만 검증이 느슨해지고, **그 사실이 어디에도 드러나지 않는다**
 * (이 저장소가 반복해 겪은 모양이다 — 같은 규칙이 두 벌이 되면 한쪽이 반드시 낡는다).
 * 그래서 실행 규칙은 [CharacterFieldAiSuggester.suggest]가 한 벌로 들고, 축은 이 인터페이스
 * 하나만 구현해 들어온다.
 */
interface FieldPromptSource {

    /** 시스템 프롬프트 — 응답 스키마와 축별 근거 규칙. */
    fun system(
        minConfidence: CharacterFieldAiSuggester.Confidence?,
        creativity: AiCreativity
    ): String

    /**
     * 사용자 프롬프트 — 축의 컨텍스트 블록 + 공통 `[추천할 필드]` 절.
     *
     * 뒤쪽 절은 반드시 [CharacterFieldAiSuggester.appendTargetSection]으로 붙인다.
     * 그 절의 형태가 곧 [CharacterFieldAiSuggester.parseResponse]가 읽는 계약이다.
     */
    fun user(targets: List<CharacterFieldAiSuggester.FieldSpec>): CharacterFieldAiSuggester.PromptBuild
}
