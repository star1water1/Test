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

    // ── 캐릭터 이미지 첨부 (A-7 · 설계 feature_roadmap 2-1·2-2) ──

    /**
     * 한 요청에 붙일 수 있는 이미지 최대 장수. 0 = 안 보냄.
     *
     * 상한의 근거는 출력이 아니라 **입력 비용**이다 — 장당 수백~천몇백 토큰이 붙고
     * 짧은 값 추천은 대상 필드를 청킹해 **요청마다 같은 이미지를 다시 싣는다**.
     */
    const val ATTACH_IMAGES_MAX = 10

    /**
     * 기본값 (Q3 사용자 확정 — 1장).
     *
     * 비용이 붙는 기본값은 보수적으로 두고 올리는 것을 사용자 선택으로 남긴다
     * (자율성 우선의 비용판). 0이 아닌 이유는 1장이 이 기능의 값을 실제로 보여 주는
     * 최소치이고, 켜져 있지 않으면 사용자가 기능의 존재조차 모르기 때문이다.
     */
    const val ATTACH_IMAGES_DEFAULT = 1

    /** '대표 이미지 반드시 포함' 기본값 — 지정한 대표가 있으면 그것이 첫 장이다. */
    const val ATTACH_REPRESENTATIVE_DEFAULT = true

    /**
     * 전송용 축소의 긴 변 상한(px). 저장용 압축([util.ImageImportHelper])과 **별개**다 —
     * 그쪽은 로컬 용량이 목적이라 8192px까지 허용하지만, 이쪽은 요청 바이트가 곧 돈이다.
     *
     * **확정값이다 (2026.08.18, B-126 — 확정 19장 4번).** 설계 2-1은 *"제안값이며 실호출로
     * 품질·비용을 재 본 뒤 확정한다"*였고, **실사용자가 이 값으로 써 본 결과가 그 실호출이다**
     * (*"요금이 과하다고 느낀 적은 없다"*).
     *
     * **무엇으로 확정됐는지를 적어 두는 이유:** 이것은 벤치마크가 아니라 **실사용 판정**이다.
     * 나중에 *화질*이 부족하다는 말이 나오면 이 값부터 의심할 자리인데, "재고 확정한 값"으로
     * 읽히면 엉뚱한 데를 판다. 비용 쪽은 답이 나왔고 **품질 쪽은 불만이 없었을 뿐**이다.
     * 곁 근거는 그대로다 — "세 제공사가 공통으로 이 부근에서 타일 하나를 소비한다".
     */
    const val SEND_LONG_EDGE_PX = 1024

    /** 전송용 JPEG 품질. 축소된 그림에서 이보다 낮추면 질감이 뭉개져 값이 되레 떨어진다. */
    const val SEND_JPEG_QUALITY = 82

    /**
     * 이미지 1장의 입력 토큰 추정 하한·상한. 제공사마다 계산법이 달라 **범위로만** 말한다
     * — 하나의 수를 고지하면 어느 제공사에서든 거짓이 된다.
     */
    const val IMAGE_TOKENS_MIN = 250
    const val IMAGE_TOKENS_MAX = 1600

    fun clampAttachImages(value: Int): Int = value.coerceIn(0, ATTACH_IMAGES_MAX)

    /**
     * 이 실행 전체에 실릴 이미지 **연인원**. 짧은 값 추천은 대상 필드를 청킹하므로
     * 같은 그림이 요청 수만큼 다시 나간다 — 고지가 이 수를 말하지 않으면
     * 사용자는 1장 값을 예상하고 N배를 낸다(R-4 사전 고지 정확성).
     */
    fun imageSendCount(imageCount: Int, requestCount: Int): Int =
        clampAttachImages(imageCount) * requestCount.coerceAtLeast(0)

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

    // ── AI 이미지 태그 (설계 image_folder_tag_ai 3장) ──

    /**
     * 한 요청에 담는 폴더 수. 폴더 이름은 짧지만 응답이 폴더마다 태그 배열을 되돌리므로
     * **출력**이 먼저 찬다 — 상한의 근거는 입력이 아니라 출력 예산이다.
     */
    const val IMAGE_TAG_FOLDERS_PER_REQUEST = 40

    /** 프롬프트에 싣는 기존 이미지 태그 어휘 개수(빈도 상위). */
    const val IMAGE_TAG_VOCAB_MAX = 120

    /** '어휘에 포함'을 켠 필드 **하나당** 싣는 값 개수. */
    const val IMAGE_TAG_VALUES_PER_FIELD = 24

    /** 필드에서 온 값 어휘의 총 상한 — 켠 필드가 많아도 프롬프트가 폭발하지 않게 한다. */
    const val IMAGE_TAG_VALUES_TOTAL_MAX = 200

    /** 사용자가 쓴 기조 문구의 길이 상한. 넘으면 자르고 **자른 사실을 고지한다**(R-14). */
    const val IMAGE_TAG_POLICY_MAX_CHARS = 600

    /** 한 폴더에 받아들일 태그 수 — 초과분은 버리고 개수를 보고한다. */
    const val IMAGE_TAG_MAX_PER_FOLDER = 6

    /** 태그 하나의 길이 상한 — 넘으면 태그가 아니라 문장이다. */
    const val IMAGE_TAG_MAX_LENGTH = 24

    fun clampImageTagPolicy(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.length <= IMAGE_TAG_POLICY_MAX_CHARS) trimmed
        else trimmed.take(IMAGE_TAG_POLICY_MAX_CHARS)
    }

    /** 폴더 수 → 요청 수. 비용 고지의 단일 소스(고지와 실제가 갈리면 안 된다). */
    fun imageTagRequestCount(folderCount: Int): Int =
        if (folderCount <= 0) 0
        else (folderCount + IMAGE_TAG_FOLDERS_PER_REQUEST - 1) / IMAGE_TAG_FOLDERS_PER_REQUEST

    // ── 이미지 내용 일괄 태깅 (B-121 · 설계 feature_roadmap 2-3) ──

    /**
     * 한 요청에 실을 이미지 장수의 범위·기본값. **사용자가 정한다**(설계 2-3 — 원문이 그렇다).
     *
     * 상한이 [ATTACH_IMAGES_MAX]와 같은 10인 것은 우연이 아니다 — 둘 다 *한 요청의 이미지 수*이고
     * 비용이 붙는 축도 같다. 다만 **기본값은 다르다**: 첨부(B-120)는 곁들이라 1장이 보수적이지만,
     * 이쪽은 이미지 태깅 그 자체라 1장이면 이미지 수만큼 요청이 나가 되레 비싸다.
     */
    const val IMAGE_TAG_BATCH_MIN = 1
    const val IMAGE_TAG_BATCH_MAX = 10
    const val IMAGE_TAG_BATCH_DEFAULT = 5

    /**
     * 이미지 **한 장**에 받아들일 태그 수 — 폴더([IMAGE_TAG_MAX_PER_FOLDER], 6)보다 크다.
     *
     * 폴더는 이름 한 줄이 근거의 전부지만 이미지는 인물·복장·배경·구도·분위기가 한 장에
     * 함께 있어 분류축이 실제로 더 많다. 같은 6으로 두면 모델이 뽑은 유효한 축이 상한에서
     * 잘리고, 그 잘림은 드롭 집계의 수로만 남아 **사용자는 무엇이 잘렸는지 볼 수 없다**.
     */
    const val IMAGE_TAG_MAX_PER_IMAGE = 8

    /**
     * 검토 화면에서 한 행에 펼쳐 보일 칩 수 — 넘으면 접고 `외 N개`로 말한다(설계 2-3 조작 셋).
     *
     * [IMAGE_TAG_MAX_PER_IMAGE]보다 **작아야 접기가 실제로 일어난다.** 둘을 같게 두면
     * 접기 코드가 영원히 안 도는 죽은 길이 된다.
     */
    const val IMAGE_TAG_ROW_COLLAPSE_AT = 6

    fun clampImageTagBatch(value: Int): Int =
        value.coerceIn(IMAGE_TAG_BATCH_MIN, IMAGE_TAG_BATCH_MAX)

    /** 이미지 수 → 요청 수. 폴더판([imageTagRequestCount])과 달리 **장수를 사용자가 정한다**. */
    fun imageTagBatchRequestCount(imageCount: Int, perRequest: Int): Int {
        if (imageCount <= 0) return 0
        val per = clampImageTagBatch(perRequest)
        return (imageCount + per - 1) / per
    }

    // ── AI 이름 추천 (B-123 · 설계 feature_roadmap 7장) ──

    /**
     * 한 라운드에 받을 이름 개수의 범위·기본값. **사용자가 정한다**(설계 7-2 — *"한번에 많이"*).
     *
     * 이미지 배치([IMAGE_TAG_BATCH_MAX], 10)보다 상한이 큰 것은 **비용 축이 다르기 때문**이다:
     * 저쪽은 장수가 곧 입력 토큰(이미지)이라 요청이 무거워지지만, 이쪽은 짧은 문자열 N개라
     * 30개도 한 요청에 들어간다. 기본 10은 설계가 정한 값이다.
     */
    const val NAME_SUGGEST_BATCH_MIN = 1
    const val NAME_SUGGEST_BATCH_MAX = 30
    const val NAME_SUGGEST_BATCH_DEFAULT = 10

    fun clampNameSuggestBatch(value: Int): Int =
        value.coerceIn(NAME_SUGGEST_BATCH_MIN, NAME_SUGGEST_BATCH_MAX)

    /** 슬라이더 눈금에 맞춘 값 — 저장값이 눈금 밖이면 슬라이더가 예외로 죽는다 */
    private fun snap(value: Int, step: Int): Int = (value / step) * step
}
