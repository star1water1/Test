package com.novelcharacter.app.ai

/**
 * AI 연동의 공통 데이터 모델.
 *
 * 설계 원칙(개발 의도 — 받쳐주는 확장성): 특정 회사가 아니라 **와이어 프로토콜** 단위로 추상화한다.
 * OPENAI_COMPAT 하나로 OpenAI·OpenRouter·Groq·DeepSeek·로컬 서버(vLLM 등) 전부를 수용하므로
 * "가능한 모든 AI"가 프리셋 나열이 아니라 구조적으로 열려 있다(원칙 01).
 */
enum class AiProtocol {
    /** Anthropic Messages API — POST {base}/v1/messages */
    ANTHROPIC,

    /** OpenAI Chat Completions 호환 — POST {base}/v1/chat/completions */
    OPENAI_COMPAT,

    /** Google Gemini generateContent — POST {base}/v1beta/models/{model}:generateContent */
    GEMINI
}

/**
 * 사용자가 등록한 프로바이더 설정 1건. 프리셋에서 생성되더라도 이후 전 필드가 자유롭게
 * 편집/삭제 가능하다(원칙 01 — 프리셋은 읽기 전용이 아니다). API 키는 이 객체에 절대 담지
 * 않는다 — [AiKeyStore]가 [id]를 키로 별도 암호화 보관한다(백업·엑셀과 완전 분리).
 */
data class AiProviderConfig(
    val id: String,
    val protocol: AiProtocol,
    val displayName: String,
    val baseUrl: String,
    val model: String,
    /** 생성 시 사용한 프리셋 id — 발급 가이드 재표시용. 커스텀이거나 알 수 없으면 null. */
    val presetId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * 자동 전환 우선순위 — **작을수록 먼저** (B-108, 확정 ⓒ: 전역 하나). 사용자가 설정 화면에서
     * 끌어 놓아 정한다([AiProviderFallback.withPriorities]).
     *
     * 기본값 0이 회귀를 막는다: 한 번도 손대지 않은 사용자는 전부 0이라 정렬이 [createdAt]으로
     * 떨어져 **종전 목록 순서 그대로**다. 그래서 이 칸이 늘어도 마이그레이션이 없다
     * (프로바이더 설정은 Room이 아니라 SharedPreferences + [AiProviderCodec]이다).
     *
     * **학습값이 아니다** — 사용자가 정한 것이므로 R-23 초기화 대상이 아니고
     * [hasLearnedFacts]에도 들어가지 않는다. 모델을 바꿨다고 사용자가 정한 순서를 지우면
     * 그것이야말로 조용한 데이터 유실이다.
     */
    val priority: Int = 0,
    /**
     * 한도로 밀려 **이 시각까지 뒤로 미루는** 프로바이더인가 (B-108, 확정 ⓔ — 10분).
     * null이면 쿨다운 없음. 판정은 [AiProviderFallback.isCoolingDown]이 단일 소스다
     * (벽시계가 어긋난 경우까지 거기서 처리한다).
     *
     * **학습한 사실이다** — *"이 키는 한도에 걸렸다"*는 그 모델·그 서버에서 배운 것이라
     * [hasLearnedFacts]에 등재한다(확정 7-1의 착수 지시). 등재하지 않으면 R-23 초기화 고지에서
     * 빠져, 모델을 바꿨는데도 옛 쿨다운 때문에 그 프로바이더가 뒤로 밀리는 이유를
     * 사용자가 볼 수 없다.
     */
    val cooldownUntilMillis: Long? = null,
    /**
     * 사용자가 슬라이더로 정한 출력 상한. null이면 자동([AiTokenPolicy.DEFAULT_REQUEST]).
     * 모델이 실제로 허용하는 값을 넘길 수는 없다 — [AiTokenPolicy.effective]가 [detectedOutputLimit]로 깎는다.
     */
    val maxOutputTokens: Int? = null,
    /**
     * 이 모델이 실제로 허용하는 출력 상한 — **탐지값**이다. 두 경로로 학습한다:
     * ① 모델 목록 조회가 알려주는 경우(Gemini `outputTokenLimit` · Anthropic `max_tokens`)
     * ② 상한 초과 400 오류가 본문에 적어 주는 경우(OpenAI 호환 등 목록이 침묵하는 곳)
     * 정적 표를 두지 않는 이유: 표는 새 모델이 나올 때마다 낡는다(AiPresets 모델 추천의 기존 한계).
     */
    val detectedOutputLimit: Int? = null,
    /**
     * 이 모델이 `temperature` 파라미터를 거부한다고 **학습**했는가 (A-4).
     * 일부 OpenAI 호환 추론 모델이 400으로 거부한다 — 한 번 확인되면 다음부터 싣지 않고,
     * 창작도는 지시 문구로만 적용된다(그 사실은 결과 고지 한 줄로 알린다 — 조용한 실패 금지).
     * null = 모름(정상 가정). R-23에 따라 모델·주소가 바뀌면 함께 버린다.
     */
    val temperatureUnsupported: Boolean? = null,
    /**
     * 이 모델이 `max_tokens`라는 **파라미터 이름**을 거부한다고 학습했는가 (OPENAI_COMPAT 전용).
     * OpenAI 신형(추론) 모델은 같은 값을 `max_completion_tokens`로 받는다.
     *
     * 종전에는 이 사실을 기억하지 않아 **그 모델로 가는 모든 요청이 400 → 재시도의 2회
     * 왕복**이었다 — 문서는 *"성공하면 기억되어 다음부터는 1회 호출"*이라 적었는데 기억되는
     * 것은 temperature뿐이었다. 학습되면 [AiProtocolCodec.buildRequest]가 첫 요청부터
     * `max_completion_tokens`로 조립한다. null = 모름(종전 이름 사용).
     * [temperatureUnsupported]와 같은 성격이라 R-23을 함께 탄다.
     */
    val maxTokensParamUnsupported: Boolean? = null,
    /**
     * 이 모델이 **이미지를 받지 않는다**고 학습했는가 (A-7).
     * 같은 프로토콜 안에서도 비전 지원은 모델마다 갈리고, 목록 조회는 그 사실을 알려주지
     * 않는다 — 알 수 있는 경로는 400뿐이다. 한 번 확인되면 다음부터 싣지 않고 글만 보내며,
     * 그 사실은 결과 고지 한 줄로 알린다(조용한 실패 금지).
     * null = 모름(정상 가정). [temperatureUnsupported]와 같은 성격이라 R-23을 함께 탄다.
     */
    val imagesUnsupported: Boolean? = null
) {
    /**
     * R-23 — 오류·조회 응답에서 **학습한** 사실이 하나라도 있는가.
     * 학습값은 그 모델·주소에 한정된 사실이라, `model`·`baseUrl`이 바뀌면 같은 저장 시점에
     * 전부 null로 되돌리고 사용자에게 고지한다(다음 요청이 다시 배운다 — 손실 없음).
     * 새 학습값이 생기면 반드시 여기에도 등재할 것 — 초기화 고지 판정의 단일 소스다.
     */
    fun hasLearnedFacts(): Boolean =
        detectedOutputLimit != null || temperatureUnsupported != null ||
            maxTokensParamUnsupported != null ||
            imagesUnsupported != null || cooldownUntilMillis != null

    /** 오류 문구에 실을 표식 (B-150). 설정 전체가 아니라 **사용자가 알아볼 두 값**만 넘긴다. */
    fun ref(): AiProviderRef = AiProviderRef(displayName, model)
}

/**
 * 실패가 **어느 프로바이더에서 났는지**를 가리키는 표식 (B-150).
 *
 * 종전의 실패는 분류·HTTP 코드·제공사 원문만 들고 왔고 *누가 그랬는지*는 말하지 않았다.
 * 프로바이더가 둘 이상이면 그 침묵이 진단을 통째로 막는다 — 한도에 걸린 옛 프로바이더로
 * 계속 나가고 있어도 문구가 똑같아 알아챌 방법이 없다(2026.08.07 사용자 보고의 경로다).
 * 표식은 [AiService]가 관문에서 새긴다 — **호출부가 넘기게 하면 빠뜨리는 자리가 8곳이 되고,
 * 빠뜨린 자리는 종전과 똑같이 조용하다.**
 */
data class AiProviderRef(val displayName: String, val model: String)

/**
 * 출력 토큰 상한 정책 — **순수 판정**(JVM 테스트 대상).
 *
 * 상한을 아예 두지 않는 선택지는 없다: Anthropic Messages API는 `max_tokens`가 **필수**다.
 * 그래서 문제는 "둘 것인가"가 아니라 "무엇을 근거로 둘 것인가"이고, 근거는 셋이다 —
 * 사용자 설정 > 탐지된 모델 상한 > 기본값.
 */
object AiTokenPolicy {
    /** 슬라이더 하한. 이보다 낮으면 어떤 응답도 쓸모가 없다. */
    const val FLOOR = 256

    /** 탐지 전 슬라이더 상한. 탐지되면 그 값이 상한이 된다. */
    const val FALLBACK_MAX = 8192

    /** 사용자 미설정 시 요청값 — 종전 두 기능의 하드코딩 값과 동일해 **회귀가 없다**. */
    const val DEFAULT_REQUEST = 4096

    /** 슬라이더 눈금 단위. */
    const val STEP = 256

    /**
     * 실제로 요청에 실을 값.
     *
     * 사용자가 무엇을 정했든 **탐지된 모델 상한을 넘기지 않는다** — 넘기면 400이고,
     * 그 400은 사용자가 고칠 수 없는 실패다(슬라이더가 허용한 값이므로).
     */
    fun effective(config: AiProviderConfig): Int {
        val want = config.maxOutputTokens ?: DEFAULT_REQUEST
        val capped = config.detectedOutputLimit?.let { minOf(want, it) } ?: want
        return capped.coerceAtLeast(FLOOR)
    }

    /**
     * 슬라이더가 허용할 최대값 — 탐지됐으면 그 값, 아니면 [FALLBACK_MAX].
     *
     * **반드시 [FLOOR]에서 [STEP] 배수만큼 떨어진 값이어야 한다.** Material Slider는
     * `(valueTo - valueFrom) % stepSize != 0`이면 예외를 던지며 죽는다 — 탐지값은
     * 프로바이더가 주는 임의의 수(오류 본문에서 학습한 값 포함)라 256의 배수라는 보장이 없다.
     * 탐지값보다 **크지 않은** 쪽으로 내림해 모델 상한을 넘기지도 않는다.
     */
    fun sliderMax(config: AiProviderConfig): Int {
        val raw = config.detectedOutputLimit ?: FALLBACK_MAX
        val steps = ((raw - FLOOR) / STEP).coerceAtLeast(1)
        return FLOOR + steps * STEP
    }

    /** 슬라이더에 실을 현재값 — 눈금에 맞추고 범위 안으로 가둔다(off-grid 값은 Slider가 거부한다). */
    fun snapToStep(value: Int, max: Int): Int {
        val bounded = value.coerceIn(FLOOR, max)
        val steps = (bounded - FLOOR) / STEP
        return (FLOOR + steps * STEP).coerceAtMost(max)
    }

    /**
     * 상한이 바뀌면 그에 맞춰 요청당 대상 수도 달라져야 한다 — 종전의 상수 15는
     * "4096 기준"이라는 주석만 있고 상한이 바뀌면 근거를 잃었다. 이제 파생값이다.
     *
     * [tokensPerItem]은 항목 1건(키+값+근거 한 문장)의 출력 토큰 추정치다.
     */
    fun itemsPerRequest(maxTokens: Int, tokensPerItem: Int, hardMax: Int): Int =
        (maxTokens / tokensPerItem).coerceIn(1, hardMax)
}

/**
 * 모델 목록 항목. id만 쓰던 것을 확장했다 — Gemini는 목록 응답에 `outputTokenLimit`을
 * **이미 실어 보내는데** 종전 파서가 id만 뽑고 버렸다.
 */
data class AiModelInfo(
    val id: String,
    /**
     * 프로바이더가 알려준 출력 토큰 상한. 모르면 null — Gemini(`outputTokenLimit`)와
     * Anthropic(`max_tokens`, 2026-03부터)은 목록이 알려주고, OpenAI 호환 목록에는 없다.
     */
    val outputTokenLimit: Int? = null
)

/** 대화 메시지 역할. 미래의 다중 턴 인앱 보조 기능을 위해 처음부터 목록형으로 설계한다. */
enum class AiRole { USER, ASSISTANT }

/**
 * 요청에 함께 싣는 이미지 1장 (A-7). 파일 경로가 아니라 **전송 직전에 축소·인코딩된 바이트**다 —
 * 프로토콜 계층이 파일을 읽지 않게 하려는 것이며(순수 유지), 그 덕에 세 직렬화 모양을
 * JVM 하네스로 고정할 수 있다.
 *
 * 축소·인코딩은 [com.novelcharacter.app.util.AiImagePreparer]가 전담하고, 크기 상수는
 * [AiPromptPolicy]가 단일 소스다.
 */
data class AiImage(
    /** 이미지 바이트의 base64 (줄바꿈 없음 — data URI·JSON 양쪽에 그대로 들어간다). */
    val base64: String,
    /** `image/jpeg` 등. 세 프로토콜이 각자의 자리에 그대로 싣는다. */
    val mediaType: String = DEFAULT_MEDIA_TYPE
) {
    companion object {
        /** 전송용은 JPEG 재인코딩이 기본이다 ([AiPromptPolicy.SEND_LONG_EDGE_PX] 참조). */
        const val DEFAULT_MEDIA_TYPE = "image/jpeg"
    }
}

/**
 * 대화 메시지 1건. [images]가 비어 있으면 직렬화 결과가 **글자 그대로 종전과 같다** —
 * 기본값을 둔 이유가 그것이다(이미지를 안 쓰는 기존 호출부는 무변경이고 회귀도 없다).
 */
data class AiMessage(
    val role: AiRole,
    val text: String,
    val images: List<AiImage> = emptyList()
)

/**
 * 프로토콜 중립 요청. 인앱 기능들은 이 형태로만 요청을 만들고,
 * 프로토콜별 직렬화는 [AiProtocolCodec]이 전담한다.
 */
data class AiRequest(
    val system: String? = null,
    val messages: List<AiMessage>,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /**
     * 샘플링 온도 (A-4 창작도). **null이면 파라미터를 아예 싣지 않는다** — 프로바이더 기본값
     * 그대로이며 종전 요청과 바이트 단위로 동일하다(회귀 없음). 값 산출은 [AiCreativity]
     * (프로토콜별 상한 반영), 직렬화는 [AiProtocolCodec]이 전담한다.
     */
    val temperature: Double? = null,
    /**
     * 이미지가 실릴 때만 시스템 프롬프트 끝에 붙는 절 (A-7 — *"이미지 N장이 순서대로 함께
     * 실려 있다"*). **[system]에 미리 이어 붙이지 않는 것이 이 필드의 존재 이유다.**
     *
     * 이미지가 빠지는 경로는 둘이고(`AiService`의 ④ 거부 재시도 · `strippedUpfront` 사전 제거)
     * 붙여 두면 그 둘이 각자 문자열을 도로 걷어내야 한다. 하나만 빠뜨리면 **모델은 있지도 않은
     * 그림을 근거로 삼아 날조된 출처를 낸다**(B-139 — 실제로 둘 다 빠뜨리고 있었다).
     * 갈라 두면 [effectiveSystem]이 [hasImages]로 판정하므로 **빠뜨릴 자리 자체가 없다.**
     */
    val imageSystemRule: String? = null
) {
    constructor(
        system: String? = null,
        userText: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double? = null,
        images: List<AiImage> = emptyList(),
        imageSystemRule: String? = null
    ) : this(
        system, listOf(AiMessage(AiRole.USER, userText, images)), maxTokens, temperature,
        imageSystemRule
    )

    /** 이 요청이 이미지를 싣고 있는가 — 거부 재시도·고지 판정의 단일 소스 (A-7). */
    fun hasImages(): Boolean = messages.any { it.images.isNotEmpty() }

    /**
     * 프로토콜 계층이 실제로 싣는 시스템 프롬프트 — **이미지가 없으면 이미지 절도 없다** (B-139).
     *
     * [AiProtocolCodec]의 세 프로토콜은 [system]이 아니라 반드시 이것을 읽는다
     * (`tools/check_ai_image_rule.sh`가 그것을 기계로 잠근다). 실수의 방향이 **빼는 쪽**이라
     * 새 경로가 이것을 안 쓰더라도 모델이 속지는 않는다.
     */
    fun effectiveSystem(): String? =
        if (imageSystemRule.isNullOrBlank() || !hasImages()) system
        else (system ?: "") + imageSystemRule

    /**
     * 이미지를 전부 뺀 사본. 모델이 이미지를 거부했을 때의 재시도가 쓴다.
     *
     * [imageSystemRule]은 **일부러 지우지 않는다** — [effectiveSystem]이 [hasImages]로
     * 판정하므로 이미 나가지 않고, 남겨 두면 *"이 요청이 원래 무엇을 실으려 했는가"*가
     * 사본에도 남는다. 지우는 것에 기대면 이 함수를 거치지 않는 경로가 생기는 순간
     * 다시 새기 시작한다(B-139가 그렇게 났다).
     */
    fun withoutImages(): AiRequest =
        if (!hasImages()) this
        else copy(messages = messages.map { if (it.images.isEmpty()) it else it.copy(images = emptyList()) })

    companion object {
        const val DEFAULT_MAX_TOKENS = 2048
    }
}

/** 호출 결과. 실패는 예외가 아니라 값으로 돌려 UI가 반드시 사용자에게 알리게 한다(변수 제어). */
sealed class AiResult {
    data class Success(
        val text: String,
        /** 응답이 보고한 실제 모델명(없으면 요청 모델). */
        val model: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        /**
         * 출력이 상한에 걸려 **잘렸는지**(stop_reason=max_tokens / finish_reason=length /
         * finishReason=MAX_TOKENS). 종전에는 이 신호를 **텍스트가 빈 경우에만** 읽어서,
         * 잘린 응답이 Success로 흘렀고 그 뒤 JSON 파싱이 실패해 "형식 오류 — 다시 시도해 주세요"라는
         * **오진**이 떴다(재시도해도 결정적으로 같은 결과). 호출측은 이 플래그를 보고
         * 원인과 교정 경로(대상 줄이기·상한 올리기)를 안내해야 한다.
         */
        val truncated: Boolean = false,
        /**
         * 모델이 temperature를 거부해 **빼고 재시도**한 성공인가 (A-4).
         * 이 사실을 고지하지 않으면 사용자는 창작도를 올렸는데 아무 변화가 없는 이유를
         * 영영 모른다 — 호출측은 결과 고지에 한 줄을 남겨야 한다.
         */
        val temperatureOmitted: Boolean = false,
        /**
         * 모델이 이미지를 거부해 **빼고 재시도**한 성공인가 (A-7).
         * 고지하지 않으면 사용자는 그림을 붙였는데 결과가 달라지지 않은 이유를 모른 채
         * 이미지 값만 계속 낸다 — [temperatureOmitted]와 같은 이유로 반드시 한 줄 남긴다.
         */
        val imagesOmitted: Boolean = false,
        /**
         * 이 답을 **실제로 낸** 프로바이더 (B-108). [AiService]가 관문에서 새긴다.
         *
         * 종전에는 표식이 실패에만 붙었다(B-150) — 성공은 늘 사용자가 고른 그곳에서 왔으므로
         * 물을 것이 없었기 때문이다. 자동 전환이 들어오면서 **그 전제가 깨졌다**: 성공도
         * 어디서 왔는지 말할 수 있어야 [switchedFrom]과 짝지어 고지 한 줄이 성립한다.
         */
        val provider: AiProviderRef? = null,
        /**
         * 한도로 밀려 **넘어오기 전** 프로바이더 (B-108, 확정 ⓑ). null이면 전환이 없었다.
         *
         * 고지는 [AiProviderFallback.switchNoteOf]가 단일 소스다 — 호출부는 그 한 줄을
         * 자기 고지 채널에 얹기만 한다.
         */
        val switchedFrom: AiProviderRef? = null
    ) : AiResult()

    data class Failure(
        val kind: AiErrorKind,
        /** 제공사가 돌려준 원문 메시지(있으면). 사용자 안내문 뒤에 상세로 병기한다. */
        val detail: String? = null,
        val httpCode: Int? = null,
        /**
         * 이 실패를 낸 프로바이더 (B-150). [AiService]가 관문에서 새긴다 —
         * null은 프로바이더가 정해지기 **전에** 난 실패뿐이다([AiErrorKind.NO_PROVIDER]).
         */
        val provider: AiProviderRef? = null
    ) : AiResult()
}

/**
 * 관문을 떠나는 결과에 프로바이더 표식을 새긴다 (B-150 — 실패 · B-108 — 성공까지 확장).
 *
 * [AiService]의 출구 전부가 이 한 함수를 지나게 해 둔 것이 요점이다 — 새 인앱 기능이
 * 늘어도 표식은 자동으로 붙고, 붙이는 것을 잊을 자리가 애초에 없다.
 *
 * 성공까지 새기게 된 것은 자동 전환 때문이다: *"누가 답했는가"*가 종전에는 물을 필요 없는
 * 것(사용자가 고른 그곳)이었지만, 이제는 한도로 밀려 **다른 곳**이 답했을 수 있다.
 */
fun AiResult.withProvider(ref: AiProviderRef): AiResult = when (this) {
    is AiResult.Failure -> copy(provider = ref)
    is AiResult.Success -> copy(provider = ref)
}

/**
 * 성공에 *"어디서 밀려 왔는가"*를 새긴다 (B-108). [from]이 null이면 전환이 없었으므로 그대로 둔다.
 * 실패에는 붙이지 않는다 — 전부 실패했으면 사용자가 볼 것은 마지막 오류이지 경로가 아니다.
 */
fun AiResult.withSwitchedFrom(from: AiProviderRef?): AiResult =
    if (from != null && this is AiResult.Success) copy(switchedFrom = from) else this

/**
 * 오류 분류 — 잘못된 상태를 조용히 삼키지 않고, 각 분류마다 사용자 안내문과
 * 교정 경로(키 재등록·모델명 확인 등)를 문자열 리소스로 제공한다(변수 제어).
 */
enum class AiErrorKind {
    /** 프로바이더가 **하나도 등록돼 있지 않음** → 설정 화면에서 추가하라고 안내 */
    NO_PROVIDER,

    /**
     * 등록된 프로바이더는 있는데 **쓸 것이 지정되지 않았음** → 목록에서 하나 누르라고 안내 (B-153).
     *
     * [NO_PROVIDER]와 가른 이유는 **문구가 사실과 달랐기 때문이다.** 종전에는 둘 다
     * *"설정된 AI 프로바이더가 없습니다"*였는데, 활성 id가 매달린 상태(가리키는 항목이 이미
     * 지워졌다)에서는 목록에 키까지 등록된 프로바이더가 그대로 서 있다 — 화면이 없다고 말하는
     * 것이 눈앞에 있으니 사용자는 무엇을 고쳐야 하는지 알 수 없다.
     *
     * **승계(B-153 ⓐ)가 이 분류를 없애지는 못한다** — 삭제 경로는 이제 이어받지만, 활성 id가
     * 매달리는 길은 그 하나가 아니다(엑셀로 들여온 설정·손상된 prefs 등). 그래서 확정은
     * 승계와 문구 가르기를 **둘 다** 택했다(13-1).
     */
    ACTIVE_NOT_SET,

    /** 프로바이더는 있으나 키 미등록/복호화 불가 → 키 등록 안내 */
    NO_KEY,

    /** 401/403 — 키가 틀렸거나 권한 없음 */
    INVALID_KEY,

    /** 429(rate limit) — 잠시 후 재시도 */
    RATE_LIMITED,

    /** 402 또는 잔액/할당량 소진 — 결제 확인 */
    QUOTA_EXCEEDED,

    /** 404 — 모델명(또는 주소) 오류 */
    MODEL_NOT_FOUND,

    /** 400 등 요청 형식 문제 */
    BAD_REQUEST,

    /**
     * 400인데 **본문이 요청의 어떤 항목을 지목해** 거부한 것 (B-161).
     *
     * [BAD_REQUEST]와 가른 이유는 [ACTIVE_NOT_SET]을 [NO_PROVIDER]와 가른 것과 같다 —
     * **문구가 사실과 달랐다.** 일반 400 안내는 *"모델명과 서버 주소를 확인해 주세요"*인데,
     * 파라미터를 지목하는 400에서 그것은 거짓이라 **멀쩡한 두 칸을 고치라고 시킨다.**
     *
     * 판정은 [AiProtocolCodec.isParameterRejectedError]가 단일 소스이고, 좁게 잡는다
     * (지목 표현과 거부 표현이 함께 있어야 참).
     */
    UNSUPPORTED_PARAM,

    /** 연결 불가(호스트/SSL 포함) */
    NETWORK,

    /** 시간 초과 */
    TIMEOUT,

    /** 5xx/529 제공사 서버 문제 */
    SERVER,

    /** 200이지만 본문에 텍스트가 없음(안전 필터·refusal 등) */
    EMPTY_RESPONSE,

    UNKNOWN
}

/**
 * 프로바이더의 실시간 모델 목록 조회 결과. 설정 화면의 '모델 선택'이 앱에 박제된 하드코딩
 * 추천값 대신, 지금 그 서버가 실제로 제공하는 모델을 보여줄 수 있게 한다(변수 제어 —
 * 낡은 모델명을 추천하지 않음). 실패해도 앱이 막히지 않도록 호출측은 정적 추천값으로
 * 폴백한다.
 */
sealed class AiModelListResult {
    data class Success(val models: List<AiModelInfo>) : AiModelListResult() {
        /** 기존 호출부(칩·목록 표시)는 id만 쓴다. */
        val ids: List<String> get() = models.map { it.id }
    }
    data class Failure(val failure: AiResult.Failure) : AiModelListResult()
}
