package com.novelcharacter.app.speech

import java.net.URI
import org.json.JSONObject

enum class SpeechMode { CLOUD, ON_DEVICE }

data class SpeechConfig(
    val mode: SpeechMode = SpeechMode.CLOUD,
    /** References AiKeyStore through the existing provider; never contains a key. */
    val providerId: String = "",
    val model: String = "gpt-transcribe",
    val language: String = "ko",
    val sendHints: Boolean = true
)

sealed class SpeechResult {
    data class Success(val text: String, val model: String) : SpeechResult()
    data class Failure(val kind: SpeechError, val httpCode: Int? = null) : SpeechResult()
}

enum class SpeechError(val message: String) {
    PERMISSION("마이크 권한이 필요합니다. 권한을 허용하거나 직접 입력하세요."),
    NO_PROVIDER("음성 전사 제공자를 선택하세요. 음성 설정에서 AI 연동에 등록한 제공자를 고를 수 있습니다."),
    NO_KEY("전사 제공자의 API 키를 읽을 수 없습니다. AI 연동에서 키를 다시 등록하세요."),
    CONFIG("음성 전사 주소나 모델 설정을 확인하세요. HTTPS 주소와 전사 모델이 필요합니다."),
    FILE("녹음 파일을 읽을 수 없습니다. 다시 녹음하세요."),
    TOO_LARGE("음성이 전송 가능한 크기를 넘었습니다. 나누어 녹음하세요."),
    EMPTY("인식된 말이 없습니다. 마이크와 녹음 내용을 확인한 뒤 다시 시도하세요."),
    AUTH("전사 API 인증에 실패했습니다. 제공자의 키와 접근 권한을 확인하세요."),
    RATE_LIMIT("전사 요청 한도에 도달했습니다. 제공자의 사용량을 확인하고 잠시 뒤 다시 시도하세요."),
    UNSUPPORTED("선택한 서버나 모델이 이 전사 요청을 지원하지 않습니다. 음성 설정을 확인하세요."),
    NETWORK("전사 응답을 받지 못했습니다. 과금 여부는 제공자에서 확인하세요. 재시도는 추가 요청입니다."),
    SERVER("전사 서버 오류가 발생했습니다. 기존 녹음을 보관했으니 잠시 뒤 다시 시도할 수 있습니다."),
    DEVICE_UNAVAILABLE("이 기기에서 온디바이스 음성 인식을 사용할 수 없습니다. 외부 전사나 직접 입력을 선택하세요."),
    RECORDING("녹음을 시작하거나 마칠 수 없습니다. 마이크를 사용하는 다른 앱을 닫고 다시 시도하세요.")
}

object SpeechProtocol {
    /** Transfer preferences only, excluding keys, recordings, transcripts and local usage counters. */
    fun encodeConfig(config: SpeechConfig): String = JSONObject()
        .put("mode",config.mode.name).put("providerId",config.providerId).put("model",config.model)
        .put("language",config.language).put("sendHints",config.sendHints).toString()

    fun decodeConfig(raw: String): SpeechConfig? = try {
        val json=JSONObject(raw)
        val mode=SpeechMode.entries.firstOrNull {it.name==json.opt("mode")}
        val provider=json.opt("providerId") as? String
        val model=json.opt("model") as? String
        val language=json.opt("language") as? String
        val hints=json.opt("sendHints") as? Boolean
        if(mode==null || provider==null || model.isNullOrBlank() || language==null || hints==null) null
        else SpeechConfig(mode,provider,model,language,hints)
    } catch(_: Exception) {null}

    /** Import and transport share provider checks; device recognition never needs a cloud provider. */
    fun providerError(config: SpeechConfig, compatible: Boolean, baseUrl: String?): SpeechError? = when {
        config.mode==SpeechMode.ON_DEVICE -> null
        config.providerId.isBlank() || !compatible -> SpeechError.NO_PROVIDER
        baseUrl==null || endpoint(baseUrl)==null -> SpeechError.CONFIG
        else -> null
    }

    const val MAX_AUDIO_BYTES = 20_000_000L
    const val MAX_RECORDING_MS = 15 * 60 * 1000

    fun endpoint(baseUrl: String): String? = try {
        val uri=URI(baseUrl.trim())
        if(uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) null else {
            val base=baseUrl.trim().trimEnd('/')
            if(base.endsWith("/audio/transcriptions")) base
            else if(base.endsWith("/v1")) "$base/audio/transcriptions"
            else "$base/v1/audio/transcriptions"
        }
    } catch (_: Exception) { null }

    /** Model-specific fields are sent only for the explicitly selected supported model family. */
    fun parameters(config: SpeechConfig, terms: List<String>): List<Pair<String,String>> = buildList {
        val bounded=SpeechVocabulary.select(terms.mapIndexed {index,term->SpeechVocabulary.Term(term,index)},
            if(config.model.trim()=="gpt-transcribe") 1200 else 220).terms
        add("model" to config.model.trim())
        if(config.model.trim()=="gpt-transcribe") {
            if(config.language.isNotBlank()) add("languages[]" to config.language.trim())
            if(config.sendHints) bounded.forEach { add("keywords[]" to it) }
        } else {
            add("response_format" to "json")
            if(config.language.isNotBlank()) add("language" to config.language.trim())
            if(config.sendHints && bounded.isNotEmpty()) add("prompt" to bounded.joinToString(", "))
        }
    }
    fun parse(code: Int, body: String, model: String): SpeechResult {
        if(code !in 200..299) return SpeechResult.Failure(when(code) {
            401,403->SpeechError.AUTH
            429->SpeechError.RATE_LIMIT
            413->SpeechError.TOO_LARGE
            400,404,415,422->SpeechError.UNSUPPORTED
            else->SpeechError.SERVER
        },code)
        return try {
            val text=JSONObject(body).opt("text") as? String
            if(text.isNullOrBlank()) SpeechResult.Failure(SpeechError.EMPTY)
            else SpeechResult.Success(text,model)
        } catch (_: Exception) { SpeechResult.Failure(SpeechError.EMPTY) }
    }
}
