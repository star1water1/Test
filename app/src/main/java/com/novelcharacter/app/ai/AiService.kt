package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.util.AppLogger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
     * 활성 프로바이더에 실제로 적용될 출력 상한. 인앱 기능이 **청킹 크기와 비용 고지**를
     * 이 값에서 파생시키도록 노출한다 — 상수로 박아 두면 사용자가 상한을 올려도 요청 수가
     * 그대로여서 설정이 무의미해진다.
     */
    fun effectiveMaxTokens(): Int =
        providerStore.active()?.let { AiTokenPolicy.effective(it) } ?: AiTokenPolicy.DEFAULT_REQUEST

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
                    val models = AiProtocolCodec.parseModelInfos(config.protocol, raw.body.orEmpty())
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
        // 상한 셋의 **교집합**을 쓴다: 이 요청이 요구한 값 ∩ 사용자 설정 ∩ 탐지된 모델 상한.
        // 요청값으로 정책을 덮어쓰지 않는 이유 — 그러면 `effectiveMaxTokens()`를 부르지 않은
        // 호출부가 사용자의 슬라이더 설정(특히 **낮춰 둔** 비용 상한)을 조용히 무시한다.
        // 이 저장소가 반복해서 겪은 "한 경로만 고쳐지고 나머지에 조용한 실패가 남는" 형태다.
        val ceiling = AiTokenPolicy.effective(config)
        val effective = minOf(request.maxTokens, ceiling).coerceAtLeast(AiTokenPolicy.FLOOR)
        val bounded = if (effective == request.maxTokens) request else request.copy(maxTokens = effective)

        val spec = AiProtocolCodec.buildRequest(config, apiKey, bounded)
        val first = call(spec, config.protocol, config.model)
        if (first !is AiResult.Failure) return@withContext first

        // ① OpenAI 신형 모델의 max_tokens **파라미터 이름** 거부 → max_completion_tokens 로 1회 재시도.
        if (config.protocol == AiProtocol.OPENAI_COMPAT &&
            AiProtocolCodec.isMaxTokensParamError(first.httpCode ?: 0, first.detail)
        ) {
            val retry = AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(config, apiKey, bounded)
            return@withContext call(retry, config.protocol, config.model)
        }

        // ② 상한 **값** 초과 → 오류가 알려준 실제 상한으로 1회 재시도하고 그 값을 기억한다.
        //    정적 표 없이 모델별 상한을 배우는 경로다(표는 새 모델마다 낡는다).
        if (first.httpCode == 400) {
            val learned = AiProtocolCodec.parseMaxTokensLimitFromError(first.detail, bounded.maxTokens)
            if (learned != null) {
                rememberDetectedLimit(config.id, learned)
                val retrySpec = AiProtocolCodec.buildRequest(
                    config, apiKey, bounded.copy(maxTokens = learned)
                )
                return@withContext call(retrySpec, config.protocol, config.model)
            }
        }
        first
    }

    /**
     * 탐지한 모델 출력 상한을 설정에 기록한다(다음 요청부터 바로 맞는 값으로 나간다).
     * 사용자가 슬라이더로 정한 값은 건드리지 않는다 — [AiTokenPolicy.effective]가 둘을 합성한다.
     */
    fun rememberDetectedLimit(configId: String, limit: Int) {
        val current = providerStore.get(configId) ?: return
        if (current.detectedOutputLimit == limit) return
        providerStore.save(current.copy(detectedOutputLimit = limit))
    }

    private suspend fun call(
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

    /**
     * 취소 연동 HTTP 실행 — enqueue + suspendCancellableCoroutine.
     * 호출 코루틴이 취소되면(화면 최종 이탈 등) OkHttp 콜을 즉시 cancel해
     * 응답 대기(readTimeout 최대 180초) 동안 스레드를 붙들지 않는다.
     */
    private suspend fun executeHttp(spec: AiProtocolCodec.HttpSpec): RawResponse {
        val httpRequest = Request.Builder()
            .url(spec.url)
            .apply { spec.headers.forEach { (k, v) -> header(k, v) } }
            .let { if (spec.method == "GET") it.get() else it.post(spec.bodyJson.toRequestBody(JSON_MEDIA_TYPE)) }
            .build()
        return suspendCancellableCoroutine { cont ->
            val httpCall = client.newCall(httpRequest)
            cont.invokeOnCancellation { httpCall.cancel() }
            httpCall.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    // 본문 스트리밍 중 타임아웃 등도 기존과 동일하게 분류한다 (TIMEOUT 유지)
                    val raw = try {
                        response.use { RawResponse.Http(it.code, it.body?.string()) }
                    } catch (e: Exception) {
                        classifyNetworkException(e)
                    }
                    if (cont.isActive) cont.resume(raw)
                }

                override fun onFailure(call: Call, e: IOException) {
                    // 취소로 인한 실패는 resume 대상이 아니다 — isActive 가드가 무시한다
                    if (cont.isActive) cont.resume(classifyNetworkException(e))
                }
            })
        }
    }

    /** HTTP 예외 → 실패 분류 (executeHttp의 onResponse/onFailure 공용 — 분류 회귀 방지) */
    private fun classifyNetworkException(e: Exception): RawResponse.NetworkError = when (e) {
        is SocketTimeoutException ->
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.TIMEOUT, detail = e.message))
        is InterruptedIOException ->
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.TIMEOUT, detail = e.message))
        is UnknownHostException ->
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        is SSLException ->
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        is IOException ->
            RawResponse.NetworkError(AiResult.Failure(AiErrorKind.NETWORK, detail = e.message))
        else -> {
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
