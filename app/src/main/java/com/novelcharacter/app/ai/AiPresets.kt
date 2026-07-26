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
 *
 * **[suggestedModels]는 최신 목록의 출처가 아니다.** 키가 있으면 추천 칩과 모델 선택 모두
 * 서버에 실시간으로 물어보고([AiModelSuggestions]), 이 목록은 (a) 키 입력 전·조회 실패 시의
 * 폴백과 (b) 실시간 목록의 정렬 우선순위(사람이 고른 순서)로만 쓰인다. 그래서 이 목록이
 * 조금 낡아도 사용자는 새 모델을 볼 수 있다 — 낡은 스냅샷이 최신 모델을 가리던 문제의 해법.
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
            defaultModel = "claude-opus-5",
            suggestedModels = listOf(
                "claude-opus-5", "claude-sonnet-5", "claude-fable-5", "claude-haiku-4-5"
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
            // gpt-5.6은 sol(플래그십) 별칭. terra=균형, luna=고빈도·저비용.
            defaultModel = "gpt-5.6",
            suggestedModels = listOf("gpt-5.6", "gpt-5.6-terra", "gpt-5.6-luna"),
            consoleUrl = "https://platform.openai.com/api-keys",
            modelDocsUrl = "https://platform.openai.com/docs/models",
            guideRes = R.string.ai_guide_openai
        ),
        AiPreset(
            id = "gemini",
            displayName = "Google Gemini",
            protocol = AiProtocol.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            // gemini-3-pro-preview는 2026-03 종료됨. Pro 계열은 아직 preview만 있다.
            defaultModel = "gemini-3.6-flash",
            suggestedModels = listOf("gemini-3.6-flash", "gemini-3.1-pro-preview", "gemini-3.5-flash"),
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
            defaultModel = "anthropic/claude-opus-5",
            suggestedModels = listOf(
                "anthropic/claude-opus-5", "openai/gpt-5.6", "google/gemini-3.6-flash"
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
            // llama-3.3-70b-versatile·llama-3.1-8b-instant는 2026-06 폐기 예고됨(무료·개발자 티어).
            defaultModel = "openai/gpt-oss-120b",
            suggestedModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.6-27b"),
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
            // deepseek-chat·deepseek-reasoner는 2026-07-24 폐기 완료 — 그대로 두면 호출이 실패한다.
            defaultModel = "deepseek-v4-flash",
            suggestedModels = listOf("deepseek-v4-flash", "deepseek-v4-pro"),
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
