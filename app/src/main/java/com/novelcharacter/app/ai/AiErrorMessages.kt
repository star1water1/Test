package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.R

/**
 * 오류 분류 → 사용자 안내문(+교정 경로) 변환. 모든 인앱 AI 기능이 공통으로 사용해
 * 오류 안내가 화면마다 달라지지 않게 한다.
 */
object AiErrorMessages {

    /** 분류별 안내문. 제공사 원문(detail)이 있으면 줄바꿈 후 병기해 투명하게 보여준다. */
    fun of(context: Context, failure: AiResult.Failure): String {
        val base = context.getString(baseRes(failure.kind))
        val withCode = failure.httpCode?.let { "$base (HTTP $it)" } ?: base
        return failure.detail?.takeIf { it.isNotBlank() }
            ?.let { "$withCode\n\n$it" }
            ?: withCode
    }

    private fun baseRes(kind: AiErrorKind): Int = when (kind) {
        AiErrorKind.NO_PROVIDER -> R.string.ai_error_no_provider
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
