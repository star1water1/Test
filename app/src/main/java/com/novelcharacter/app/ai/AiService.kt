package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * AI 호출의 **단일 관문**. 앞으로의 모든 인앱 AI 기능(필드 제안, 설명 초안, 정합성 해설,
 * 이름 생성, 작업 보조 등)은 이 클래스의 [complete] 하나만 호출한다 — 프로바이더/프로토콜/키
 * 관리가 전부 이 뒤에 숨는다(원칙 05 — 유기적 연결, 기능은 provider처럼 얹는다).
 *
 * 실패는 예외가 아니라 [AiResult.Failure]로 돌아온다. 호출측 UI는 결과를 반드시 사용자에게
 * 보여줄 것 — 조용히 버리는 것은 금지(변수 제어).
 */
class AiService(context: Context) {

    private val appContext = context.applicationContext
    private val providerStore = AiProviderStore(appContext)
    private val keyStore = AiKeyStore(appContext)

    /**
     * 활성 프로바이더(또는 지정 [config])로 요청을 수행한다.
     * 네트워크는 IO 디스패처에서 실행되므로 어디서든 suspend 호출만 하면 된다.
     */
    suspend fun complete(request: AiRequest, config: AiProviderConfig? = null): AiResult {
        val resolved = config ?: providerStore.active()
            ?: return AiResult.Failure(AiErrorKind.NO_PROVIDER)
        val apiKey = keyStore.getKey(resolved.id)
            ?: return AiResult.Failure(AiErrorKind.NO_KEY)
        return execute(resolved, apiKey, request)
    }

    /**
     * 연결 테스트 — 최소 요청 1건을 실제로 보내 키·주소·모델을 한 번에 검증한다.
     * [keyOverride]는 저장 전 다이얼로그에서 방금 입력한 키를 검사할 때 사용.
     * 성공 판정은 HTTP 성공 기준(본문이 짧거나 비어도 인증·모델이 유효하면 성공으로 본다).
     */
    suspend fun testConnection(config: AiProviderConfig, keyOverride: String? = null): AiResult {
        val apiKey = keyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: keyStore.getKey(config.id)
            ?: return AiResult.Failure(AiErrorKind.NO_KEY)
        val ping = AiRequest(userText = "Reply with only the word OK.", maxTokens = TEST_MAX_TOKENS)
        val result = execute(config, apiKey, ping)
        // 테스트 목적상 '응답은 왔지만 텍스트가 빈' 경우도 인증 성공이므로 성공으로 승격.
        return if (result is AiResult.Failure && result.kind == AiErrorKind.EMPTY_RESPONSE) {
            AiResult.Success(text = "", model = config.model)
        } else result
    }

    /** 인앱 기능들이 진입 전에 안내를 띄울 수 있도록 노출하는 상태 조회. */
    fun hasUsableProvider(): Boolean {
        val active = providerStore.active() ?: return false
        return keyStore.hasKey(active.id)
    }

    /**
     * 프로바이더가 지금 실제로 제공하는 모델 목록을 조회한다. 설정 화면의 '모델 선택'이
     * 앱에 박제된 하드코딩 추천값 대신 살아있는 목록을 보여주는 데 쓰인다(변수 제어 —
     * 낡은 모델명 추천 방지). 실패하면 호출측이 정적 추천값으로 폴백한다.
     */
    suspend fun listModels(config: AiProviderConfig, apiKey: String): AiModelListResult =
        withContext(Dispatchers.IO) {
            val spec = AiProtocolCodec.buildModelListRequest(config, apiKey)
            when (val raw = executeHttp(spec)) {
                is RawResponse.NetworkError -> AiModelListResult.Failure(raw.failure)
                is RawResponse.Http -> if (raw.code in 200..299) {
                    val models = AiProtocolCodec.parseModelList(config.protocol, raw.body.orEmpty())
                    if (models.isEmpty()) {
                        AiModelListResult.Failure(AiResult.Failure(AiErrorKind.EMPTY_RESPONSE))
                    } else {
                        AiModelListResult.Success(models)
                    }
                } else {
                    AppLogger.error(TAG, "모델 목록 조회 실패 HTTP ${raw.code} (${config.protocol.name})", null)
                    AiModelListResult.Failure(AiProtocolCodec.parseError(raw.code, raw.body))
                }
            }
        }

    private suspend fun execute(
        config: AiProviderConfig, apiKey: String, request: AiRequest
    ): AiResult = withContext(Dispatchers.IO) {
        val spec = AiProtocolCodec.buildRequest(config, apiKey, request)
        val first = call(spec, config.protocol, config.model)
        // OpenAI 신형 모델의 max_tokens 거부 → max_completion_tokens 로 1회 자동 교정 재시도.
        if (config.protocol == AiProtocol.OPENAI_COMPAT &&
            first is AiResult.Failure &&
            AiProtocolCodec.isMaxTokensParamError(first.httpCode ?: 0, first.detail)
        ) {
            val retry = AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(config, apiKey, request)
            return@withContext call(retry, config.protocol, config.model)
        }
        first
    }

    private fun call(
        spec: AiProtocolCodec.HttpSpec, protocol: AiProtocol, requestedModel: String
    ): AiResult = when (val raw = executeHttp(spec)) {
        is RawResponse.NetworkError -> raw.failure
        is RawResponse.Http -> if (raw.code in 200..299) {
            AiProtocolCodec.parseSuccess(protocol, raw.body.orEmpty(), requestedModel)
        } else {
            // 키·본문은 절대 로그에 남기지 않는다 — 상태 코드만.
            AppLogger.error(TAG, "AI 호출 실패 HTTP ${raw.code} (${protocol.name})", null)
            AiProtocolCodec.parseError(raw.code, raw.body)
        }
    }

    /** 완료·모델목록 두 호출 경로가 공유하는 HTTP 실행 + 예외 분류. */
    private sealed class RawResponse {
        data class Http(val code: Int, val body: String?) : RawResponse()
        data class NetworkError(val failure: AiResult.Failure) : RawResponse()
    }

    private fun executeHttp(spec: AiProtocolCodec.HttpSpec): RawResponse {
        val httpRequest = Request.Builder()
            .url(spec.url)
            .apply { spec.headers.forEach { (k, v) -> header(k, v) } }
            .let { if (spec.method == "GET") it.get() else it.post(spec.bodyJson.toRequestBody(JSON_MEDIA_TYPE)) }
            .build()
        return try {
            client.newCall(httpRequest).execute().use { response ->
                RawResponse.Http(response.code, response.body?.string())
            }
        } catch (e: SocketTimeoutException) {
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.TIMEOUT, detail = e.message))
        } catch (e: InterruptedIOException) {
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.TIMEOUT, detail = e.message))
        } catch (e: UnknownHostException) {
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        } catch (e: SSLException) {
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        } catch (e: IOException) {
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        } catch (e: Exception) {
            AppLogger.error(TAG, "AI 호출 중 예기치 못한 오류", e)
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.UNKNOWN, detail = e.message))
        }
    }

    companion object {
        private const val TAG = "AiService"
        private const val TEST_MAX_TOKENS = 256
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // LLM 응답은 길어질 수 있어 read 타임아웃을 넉넉히 잡는다. 커넥션 풀 공유를 위해 싱글턴.
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
        }
    }
}
