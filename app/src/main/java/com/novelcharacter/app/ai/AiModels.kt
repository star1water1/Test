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
     * 사용자가 슬라이더로 정한 출력 상한. null이면 자동([AiTokenPolicy.DEFAULT_REQUEST]).
     * 모델이 실제로 허용하는 값을 넘길 수는 없다 — [AiTokenPolicy.effective]가 [detectedOutputLimit]로 깎는다.
     */
    val maxOutputTokens: Int? = null,
    /**
     * 이 모델이 실제로 허용하는 출력 상한 — **탐지값**이다. 두 경로로 학습한다:
     * ① 모델 목록 조회가 알려주는 경우(Gemini의 `outputTokenLimit`)
     * ② 상한 초과 400 오류가 본문에 적어 주는 경우(Anthropic·OpenAI)
     * 정적 표를 두지 않는 이유: 표는 새 모델이 나올 때마다 낡는다(AiPresets 모델 추천의 기존 한계).
     */
    val detectedOutputLimit: Int? = null
)

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
    /** 프로바이더가 알려준 출력 토큰 상한. 모르면 null(Anthropic·OpenAI는 목록에 없다). */
    val outputTokenLimit: Int? = null
)

/** 대화 메시지 역할. 미래의 다중 턴 인앱 보조 기능을 위해 처음부터 목록형으로 설계한다. */
enum class AiRole { USER, ASSISTANT }

data class AiMessage(val role: AiRole, val text: String)

/**
 * 프로토콜 중립 요청. 인앱 기능들은 이 형태로만 요청을 만들고,
 * 프로토콜별 직렬화는 [AiProtocolCodec]이 전담한다.
 */
data class AiRequest(
    val system: String? = null,
    val messages: List<AiMessage>,
    val maxTokens: Int = DEFAULT_MAX_TOKENS
) {
    constructor(system: String? = null, userText: String, maxTokens: Int = DEFAULT_MAX_TOKENS) :
        this(system, listOf(AiMessage(AiRole.USER, userText)), maxTokens)

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
        val truncated: Boolean = false
    ) : AiResult()

    data class Failure(
        val kind: AiErrorKind,
        /** 제공사가 돌려준 원문 메시지(있으면). 사용자 안내문 뒤에 상세로 병기한다. */
        val detail: String? = null,
        val httpCode: Int? = null
    ) : AiResult()
}

/**
 * 오류 분류 — 잘못된 상태를 조용히 삼키지 않고, 각 분류마다 사용자 안내문과
 * 교정 경로(키 재등록·모델명 확인 등)를 문자열 리소스로 제공한다(변수 제어).
 */
enum class AiErrorKind {
    /** 활성 프로바이더가 아예 없음 → 설정 화면으로 안내 */
    NO_PROVIDER,

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
