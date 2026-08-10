package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.R

/**
 * 오류 분류 → 사용자 안내문(+교정 경로) 변환. 모든 인앱 AI 기능이 공통으로 사용해
 * 오류 안내가 화면마다 달라지지 않게 한다.
 */
object AiErrorMessages {

    /**
     * 분류별 안내문. 제공사 원문(detail)이 있으면 줄바꿈 후 병기해 투명하게 보여준다.
     * 실패가 프로바이더 표식을 들고 있으면 **맨 앞 줄**로 세운다 (B-150) —
     * 조립 규칙 자체는 [AiErrorText]가 단일 소스다(순수라 시험이 잠근다).
     */
    fun of(context: Context, failure: AiResult.Failure): String = AiErrorText.compose(
        base = context.getString(baseRes(failure.kind)),
        httpCode = failure.httpCode,
        detail = failure.detail,
        providerLine = failure.provider?.let {
            context.getString(R.string.ai_error_provider_line, it.displayName, it.model)
        }
    )

    private fun baseRes(kind: AiErrorKind): Int = when (kind) {
        AiErrorKind.NO_PROVIDER -> R.string.ai_error_no_provider
        AiErrorKind.ACTIVE_NOT_SET -> R.string.ai_error_active_not_set
        AiErrorKind.NO_KEY -> R.string.ai_error_no_key
        AiErrorKind.INVALID_KEY -> R.string.ai_error_invalid_key
        AiErrorKind.RATE_LIMITED -> R.string.ai_error_rate_limited
        AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_error_quota
        AiErrorKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
        AiErrorKind.BAD_REQUEST -> R.string.ai_error_bad_request
        AiErrorKind.NETWORK -> R.string.ai_error_network
        AiErrorKind.TIMEOUT -> R.string.ai_error_timeout
        AiErrorKind.SERVER -> R.string.ai_error_server
        AiErrorKind.EMPTY_RESPONSE -> R.string.ai_error_empty
        AiErrorKind.UNKNOWN -> R.string.ai_error_unknown
    }
}
