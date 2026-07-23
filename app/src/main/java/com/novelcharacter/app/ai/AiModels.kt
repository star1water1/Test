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
    val updatedAt: Long = 0L
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
        val outputTokens: Int? = null
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
