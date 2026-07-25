package com.novelcharacter.app.ai

import androidx.annotation.StringRes
import com.novelcharacter.app.R

/**
 * 프로바이더 프리셋 템플릿. 등록 시 출발점일 뿐이며 생성된 설정은 전부 편집 가능하다(원칙 01).
 * 새 서비스 지원 = 여기에 항목 1개 추가(데이터 주도). OpenAI 호환 프로토콜 덕에 목록에 없는
 * 서비스도 CUSTOM으로 즉시 연결된다.
 *
 * 모델명은 제공사들이 수시로 바꾸므로 '추천값'일 뿐이고 필드는 항상 자유 입력이다.
 * 가이드 문자열이 "최신 모델명은 제공사 문서를 확인"을 안내한다(변수 제어).
 */
data class AiPreset(
    val id: String,
    /** 브랜드명은 번역 대상이 아니므로 리터럴 유지. */
    val displayName: String,
    val protocol: AiProtocol,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    /** API 키 발급 콘솔 URL — 가이드의 '발급 페이지 열기'가 연다. */
    val consoleUrl: String,
    /** 최신 모델명 확인 문서 URL — 가이드의 '모델 문서 보기'가 연다. */
    val modelDocsUrl: String = "",
    /** 단계별 발급 안내 문자열 리소스. */
    @StringRes val guideRes: Int,
    /** 무료 티어 존재 여부 — 목록에서 배지로 표시해 진입 장벽을 낮춘다. */
    val hasFreeTier: Boolean = false
)

object AiPresets {

    const val CUSTOM_ID = "custom"

    val ALL: List<AiPreset> = listOf(
        AiPreset(
            id = "anthropic",
            displayName = "Anthropic Claude",
            protocol = AiProtocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            defaultModel = "claude-opus-4-8",
            suggestedModels = listOf(
                "claude-opus-4-8", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5"
            ),
            consoleUrl = "https://console.anthropic.com/settings/keys",
            modelDocsUrl = "https://platform.claude.com/docs/en/about-claude/models/overview",
            guideRes = R.string.ai_guide_anthropic
        ),
        AiPreset(
            id = "openai",
            displayName = "OpenAI (GPT)",
            protocol = AiProtocol.OPENAI_COMPAT,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-5.1",
            suggestedModels = listOf("gpt-5.1", "gpt-5-mini", "gpt-4.1-mini"),
            consoleUrl = "https://platform.openai.com/api-keys",
            modelDocsUrl = "https://platform.openai.com/docs/models",
            guideRes = R.string.ai_guide_openai
        ),
        AiPreset(
            id = "gemini",
            displayName = "Google Gemini",
            protocol = AiProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            defaultModel = "gemini-2.5-flash",
            suggestedModels = listOf("gemini-3-pro-preview", "gemini-2.5-pro", "gemini-2.5-flash"),
            consoleUrl = "https://aistudio.google.com/apikey",
            modelDocsUrl = "https://ai.google.dev/gemini-api/docs/models",
            guideRes = R.string.ai_guide_gemini,
            hasFreeTier = true
        ),
        AiPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            protocol = AiProtocol.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1",
            defaultModel = "anthropic/claude-sonnet-4.5",
            suggestedModels = listOf(
                "anthropic/claude-sonnet-4.5", "openai/gpt-5.1", "google/gemini-2.5-flash"
            ),
            consoleUrl = "https://openrouter.ai/settings/keys",
            modelDocsUrl = "https://openrouter.ai/models",
            guideRes = R.string.ai_guide_openrouter
        ),
        AiPreset(
            id = "groq",
            displayName = "Groq",
            protocol = AiProtocol.OPENAI_COMPAT,
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "llama-3.3-70b-versatile",
            suggestedModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
            consoleUrl = "https://console.groq.com/keys",
            modelDocsUrl = "https://console.groq.com/docs/models",
            guideRes = R.string.ai_guide_groq,
            hasFreeTier = true
        ),
        AiPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            protocol = AiProtocol.OPENAI_COMPAT,
            baseUrl = "https://api.deepseek.com/v1",
            defaultModel = "deepseek-chat",
            suggestedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            consoleUrl = "https://platform.deepseek.com/api_keys",
            modelDocsUrl = "https://api-docs.deepseek.com/quick_start/pricing",
            guideRes = R.string.ai_guide_deepseek
        ),
        AiPreset(
            id = CUSTOM_ID,
            displayName = "", // 표시명은 리소스(ai_preset_custom)에서 — 유일하게 번역 대상
            protocol = AiProtocol.OPENAI_COMPAT,
            baseUrl = "",
            defaultModel = "",
            suggestedModels = emptyList(),
            consoleUrl = "",
            guideRes = R.string.ai_guide_custom
        )
    )

    fun byId(id: String?): AiPreset? = ALL.firstOrNull { it.id == id }
}
