package com.novelcharacter.app.ai

import android.content.Context
import com.novelcharacter.app.util.AppLogger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val usageStore = AiUsageStore(appContext)

    /**
     * 활성 프로바이더(또는 지정 [config])로 요청을 수행한다.
     * 네트워크는 IO 디스패처에서 실행되므로 어디서든 suspend 호출만 하면 된다.
     *
     * **한도에 걸리면 다음 프로바이더로 이어간다** (B-108) — 순서·방아쇠·쿨다운의 판정은
     * [AiProviderFallback]이 단일 소스이고 여기서는 그 답을 실행만 한다. 전환을 관문에 둔 것이
     * 이 구현의 요점이다: 인앱 기능 여섯이 각자 폴백을 짜면 여섯 벌이 되고, **한 벌만 빠뜨려도
     * 그 기능에서만 조용히 종전처럼 죽는다.** 관문에 있으면 이미 나온 답(앞선 청크)은 그대로
     * 살고 덜 나온 것만 새 프로바이더로 이어진다 — 확정 ⓕ가 요구한 *"필드 단위로 가른다"*가
     * 호출부에 새 장치 없이 성립하는 이유다.
     */
    suspend fun complete(request: AiRequest, config: AiProviderConfig? = null): AiResult =
        // **관문이 디스패처도 책임진다.** 위 KDoc이 *"네트워크는 IO에서 실행되므로 어디서든
        // suspend 호출만 하면 된다"*고 약속하는데, 종전에는 그 약속이 **네트워크에만** 참이었다:
        // 아래 앞머리(프로바이더 SharedPreferences 해독 + AndroidKeyStore AES-GCM 복호화 —
        // 키스토어 데몬과의 **바인더 왕복**이다)가 `withContext` **밖**이라 호출자의 디스패처에서
        // 돌았고, 인앱 AI 진입점 다수가 그것을 `Dispatchers.Main.immediate`에서 부른다.
        // 메인 스레드에서 도는 디스크·IPC라 프레임을 떨어뜨리며, 프로바이더가 여럿이면
        // 폴백 후보마다 `hasKey`·`getKey`가 한 번씩 더 돈다.
        //
        // **`execute`가 이미 IO로 가는 것으로는 덮이지 않는다** — 그 안쪽은 네트워크뿐이다.
        withContext(Dispatchers.IO) { completeOnIo(request, config) }

    private suspend fun completeOnIo(request: AiRequest, config: AiProviderConfig?): AiResult {
        // 실패에는 **어느 프로바이더였는지**를 반드시 새겨 내보낸다 (B-150). 새기는 자리를
        // 관문 하나로 둔 이유: 호출부에 맡기면 8곳이 되고 빠뜨린 자리는 종전과 똑같이 조용하다.
        // 활성이 안 풀리는 이유는 둘이고, 사용자가 할 일이 다르다 (B-153 ⓑ) — 등록이 0건이면
        // *추가*해야 하고, 등록은 있는데 활성이 매달렸으면 *목록에서 하나 누르면* 된다.
        // 종전에는 둘 다 "설정된 AI 프로바이더가 없습니다"라, 눈앞에 프로바이더를 두고 없다는
        // 말을 듣는 사용자는 무엇을 고쳐야 하는지 알 수 없었다.
        val resolved = config ?: providerStore.active()
            ?: return AiResult.Failure( // 프로바이더가 정해지지 않았으니 표식으로 새길 것도 없다
                if (providerStore.list().isEmpty()) AiErrorKind.NO_PROVIDER
                else AiErrorKind.ACTIVE_NOT_SET
            )
        val apiKey = keyStore.getKey(resolved.id)
            ?: return AiResult.Failure(AiErrorKind.NO_KEY, provider = resolved.ref())

        // **명시 지정은 전환하지 않는다.** 그 인자는 *"이것으로 해 보라"*는 뜻이라(연결 테스트가
        // 저장 전 설정을 넘기는 자리) 다른 곳의 성공으로 답하면 검사 자체가 거짓이 된다.
        if (config != null) return execute(resolved, apiKey, request).withProvider(resolved.ref())

        return completeWithFallback(resolved, apiKey, request)
    }

    /**
     * 자동 전환 루프 (B-108). [first]는 관문이 이미 키까지 확인한 활성 프로바이더다.
     *
     * 실패를 **마지막 것으로** 돌려주는 이유: 사용자가 고칠 것은 경로가 아니라 지금 막힌 자리이고,
     * 전부 한도면 마지막 한도 오류가 그 사실을 그대로 말한다. 표식(B-150)이 붙어 있어 어느
     * 프로바이더의 실패인지도 함께 나간다.
     */
    private suspend fun completeWithFallback(
        first: AiProviderConfig, firstKey: String, request: AiRequest
    ): AiResult {
        val now = System.currentTimeMillis()
        val chain = AiProviderFallback.order(
            configs = providerStore.list(),
            active = first,
            hasKey = { keyStore.hasKey(it) },
            nowMillis = now
        )
        // 활성이 쿨다운 중이면 순서가 다른 곳부터 시작한다 — 그 자체가 전환이므로 고지 대상이다.
        var switchedFrom: AiProviderRef? =
            if (chain.firstOrNull()?.id != first.id) first.ref() else null
        var last: AiResult.Failure? = null

        for (candidate in chain) {
            val apiKey =
                if (candidate.id == first.id) firstKey else keyStore.getKey(candidate.id) ?: continue
            var retriesUsed = 0
            while (true) {
                val result = execute(candidate, apiKey, request)
                if (result !is AiResult.Failure) {
                    // 지금 되는 것이 쿨다운이 낡았다는 증거다 — 남아 있으면 지운다.
                    // 만료를 쓸어 담는 별도 경로를 두지 않는 이유: 성공한 자리에서만 지우면
                    // 요청마다 저장소를 쓰지 않으면서도 낡은 값이 쌓이지 않는다.
                    if (candidate.cooldownUntilMillis != null) clearCooldown(candidate.id)
                    return result.withProvider(candidate.ref()).withSwitchedFrom(switchedFrom)
                }
                last = result.copy(provider = candidate.ref())
                when (AiProviderFallback.dispositionOf(result.kind, retriesUsed)) {
                    AiProviderFallback.Disposition.RETRY_SAME -> {
                        retriesUsed++
                        delay(AiProviderFallback.RATE_LIMIT_BACKOFF_MILLIS)
                    }
                    AiProviderFallback.Disposition.SWITCH -> {
                        rememberCooldown(candidate.id, AiProviderFallback.cooldownUntil(now))
                        // 고지가 가리킬 곳은 **처음 밀린 곳**이다 — 판정은 순수 계층이 든다.
                        switchedFrom =
                            AiProviderFallback.firstSwitchSource(switchedFrom, candidate.ref())
                        break
                    }
                    AiProviderFallback.Disposition.STOP -> return last
                }
            }
        }
        // chain이 비는 경우는 없다(활성이 언제나 들어간다). 그래도 값으로 돌려준다 —
        // 여기서 예외를 던지면 관문의 계약(실패는 값이다)이 깨진다.
        return last ?: AiResult.Failure(AiErrorKind.UNKNOWN, provider = first.ref())
    }

    /**
     * 연결 테스트 — 최소 요청 1건을 실제로 보내 키·주소·모델을 한 번에 검증한다.
     * [keyOverride]는 저장 전 다이얼로그에서 방금 입력한 키를 검사할 때 사용.
     * 성공 판정은 HTTP 성공 기준(본문이 짧거나 비어도 인증·모델이 유효하면 성공으로 본다).
     */
    suspend fun testConnection(config: AiProviderConfig, keyOverride: String? = null): AiResult {
        val apiKey = keyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: keyStore.getKey(config.id)
            ?: return AiResult.Failure(AiErrorKind.NO_KEY, provider = config.ref())
        val ping = AiRequest(userText = "Reply with only the word OK.", maxTokens = TEST_MAX_TOKENS)
        val result = execute(config, apiKey, ping)
        // 테스트 목적상 '응답은 왔지만 텍스트가 빈' 경우도 인증 성공이므로 성공으로 승격.
        return if (result is AiResult.Failure && result.kind == AiErrorKind.EMPTY_RESPONSE) {
            // 승격한 성공도 관문의 출구다 — 표식을 빼면 *"관문을 떠나는 결과에는 표식이 있다"*가
            // 여기 하나에서만 깨지고, 그런 예외는 다음 사람이 믿는 순간 결함이 된다 (R-44).
            AiResult.Success(text = "", model = config.model).withProvider(config.ref())
        } else result.withProvider(config.ref())
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
                is RawResponse.NetworkError ->
                    AiModelListResult.Failure(raw.failure.copy(provider = config.ref()))
                is RawResponse.Http -> if (raw.code in 200..299) {
                    val models = AiProtocolCodec.parseModelInfos(config.protocol, raw.body.orEmpty())
                    if (models.isEmpty()) {
                        AiModelListResult.Failure(
                            AiResult.Failure(AiErrorKind.EMPTY_RESPONSE, provider = config.ref())
                        )
                    } else {
                        AiModelListResult.Success(models)
                    }
                } else {
                    AppLogger.error(TAG, "모델 목록 조회 실패 HTTP ${raw.code} (${config.protocol.name})", null)
                    AiModelListResult.Failure(
                        AiProtocolCodec.parseError(raw.code, raw.body).copy(provider = config.ref())
                    )
                }
            }
        }

    /**
     * HTTP 실행 + **성공 사용량 기록**. 기록을 이 출구 하나에 두는 이유는 표식(B-150)·전환
     * 고지(B-108)와 같다 — 호출부 여덟이 각자 기록하면 여덟 벌이 되고 빠뜨린 자리는 조용하다.
     * 연결 테스트·명시 지정 호출도 실제로 과금되므로 함께 센다. 실패는 기록하지 않는다 —
     * 과금 축은 usage이고, 실패에는 usage가 없다.
     */
    private suspend fun execute(
        config: AiProviderConfig, apiKey: String, request: AiRequest
    ): AiResult = withContext(Dispatchers.IO) {
        val result = executeUnrecorded(config, apiKey, request)
        if (result is AiResult.Success) {
            usageStore.record(
                providerId = config.id,
                displayName = config.displayName,
                // 응답이 보고한 실제 모델이다 — 요청 모델과 다를 수 있고(별칭·라우터),
                // 집계가 답할 질문은 "실제로 누가 썼는가"다.
                model = result.model,
                inputTokens = result.inputTokens,
                outputTokens = result.outputTokens
            )
        }
        result
    }

    private suspend fun executeUnrecorded(
        config: AiProviderConfig, apiKey: String, request: AiRequest
    ): AiResult = withContext(Dispatchers.IO) {
        // 상한 셋의 **교집합**을 쓴다: 이 요청이 요구한 값 ∩ 사용자 설정 ∩ 탐지된 모델 상한.
        // 요청값으로 정책을 덮어쓰지 않는 이유 — 그러면 `effectiveMaxTokens()`를 부르지 않은
        // 호출부가 사용자의 슬라이더 설정(특히 **낮춰 둔** 비용 상한)을 조용히 무시한다.
        // 이 저장소가 반복해서 겪은 "한 경로만 고쳐지고 나머지에 조용한 실패가 남는" 형태다.
        val ceiling = AiTokenPolicy.effective(config)
        val effective = minOf(request.maxTokens, ceiling).coerceAtLeast(AiTokenPolicy.FLOOR)
        val bounded = if (effective == request.maxTokens) request else request.copy(maxTokens = effective)

        // 학습된 temperature 미지원 모델에는 애초에 싣지 않는다 (A-4 — 같은 400을 반복하지 않는다)
        val saved = resolved(config)
        val request1 =
            if (bounded.temperature != null && saved.temperatureUnsupported == true) {
                bounded.copy(temperature = null)
            } else bounded

        // 이미지도 같은 태도다 (A-7) — 받지 않는다고 이미 배운 모델에는 애초에 싣지 않는다.
        // 뺐다는 사실은 성공 결과에 실어 보내야 한다: 그러지 않으면 사용자는 그림을 붙였는데
        // 결과가 그대로인 이유를 영영 모른다.
        val strippedUpfront = request1.hasImages() && saved.imagesUnsupported == true
        val request0 = if (strippedUpfront) request1.withoutImages() else request1

        // **사전 제거 고지는 어느 출구로 나가든 붙는다.** 종전에는 첫 호출 성공 분기에만
        // 붙어 있어, 사전 제거된 요청이 재시도 경로(①②③)로 성공하면 고지가 조용히
        // 빠졌다 — 사용자는 그림을 붙였는데 결과가 그대로인 이유를 영영 모른다(A-7의
        // 그 부류). 출구마다 손으로 붙이면 다음 출구가 또 빠뜨리므로 한 함수로 모은다.
        fun noted(result: AiResult): AiResult =
            if (strippedUpfront && result is AiResult.Success && !result.imagesOmitted) {
                result.copy(imagesOmitted = true)
            } else result

        val spec = AiProtocolCodec.buildRequest(config, apiKey, request0)
        val first = call(spec, config.protocol, config.model)
        if (first !is AiResult.Failure) {
            return@withContext noted(first)
        }

        // ① OpenAI 신형 모델의 max_tokens **파라미터 이름** 거부 → max_completion_tokens 로 1회 재시도.
        if (config.protocol == AiProtocol.OPENAI_COMPAT &&
            AiProtocolCodec.isMaxTokensParamError(first.httpCode ?: 0, first.detail)
        ) {
            val retry = AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(config, apiKey, request0)
            val second = call(retry, config.protocol, config.model)
            // **성공한 재시도는 기억한다** — 안 그러면 그 모델로 가는 모든 요청이 400 → 재시도의
            // 2회 왕복으로 남는다(지연이 배가 되고 레이트리밋도 두 배로 두드린다). 기억되면
            // [AiProtocolCodec.buildRequest]가 첫 요청부터 max_completion_tokens로 조립한다.
            // 형제 ②·③·④와 같은 형태다: 재시도가 통해야 배운 것이다. R-23 대상.
            if (second is AiResult.Success) {
                rememberMaxTokensParamUnsupported(config.id)
                return@withContext noted(second)
            }
            // 추론 모델은 파라미터 이름과 temperature를 **둘 다** 거부한다 — 이름을 고친 재시도가
            // temperature 거부로 떨어지면 여기서 ②를 이어 준다. 이 연쇄를 끊으면 창작도를 켠
            // 사용자는 그 모델에서 영영 400만 받는다(한 번 성공하면 둘 다 기억되어 다음부터 1회 호출).
            if (second is AiResult.Failure && request0.temperature != null &&
                AiProtocolCodec.isTemperatureUnsupportedError(second.httpCode ?: 0, second.detail)
            ) {
                val third = call(
                    AiProtocolCodec.buildOpenAiRetryWithMaxCompletionTokens(
                        config, apiKey, request0.copy(temperature = null)
                    ),
                    config.protocol, config.model
                )
                if (third is AiResult.Success) {
                    rememberMaxTokensParamUnsupported(config.id)
                    rememberTemperatureUnsupported(config.id)
                    return@withContext noted(third.copy(temperatureOmitted = true))
                }
                return@withContext third
            }
            return@withContext second
        }

        // ② 모델이 temperature 자체를 거부 → 빼고 1회 재시도, 성공하면 기억한다 (A-4).
        //    max_tokens → max_completion_tokens 자동 재시도와 같은 형태의 유연한 교정이다.
        //    기억은 R-23에 따라 모델·주소가 바뀌면 함께 버려진다.
        if (request0.temperature != null &&
            AiProtocolCodec.isTemperatureUnsupportedError(first.httpCode ?: 0, first.detail)
        ) {
            val retrySpec = AiProtocolCodec.buildRequest(config, apiKey, request0.copy(temperature = null))
            val second = call(retrySpec, config.protocol, config.model)
            if (second is AiResult.Success) {
                rememberTemperatureUnsupported(config.id)
                return@withContext noted(second.copy(temperatureOmitted = true))
            }
            return@withContext second
        }

        // ④ 모델이 **이미지 자체**를 거부 → 빼고 1회 재시도, 성공하면 기억한다 (A-7).
        //    ②와 같은 형태의 유연한 교정이다. 여기서 물러서지 않으면 비전 미지원 모델을 쓰는
        //    사용자는 첨부를 켠 순간부터 400만 받게 되고, 그 원인이 이미지라는 것도 알 수 없다.
        //    학습값은 R-23에 따라 모델·주소가 바뀌면 함께 버려진다.
        if (request0.hasImages() &&
            AiProtocolCodec.isImagesUnsupportedError(first.httpCode ?: 0, first.detail)
        ) {
            val retrySpec = AiProtocolCodec.buildRequest(config, apiKey, request0.withoutImages())
            val second = call(retrySpec, config.protocol, config.model)
            if (second is AiResult.Success) {
                rememberImagesUnsupported(config.id)
                return@withContext second.copy(imagesOmitted = true)
            }
            return@withContext second
        }

        // ③ 상한 **값** 초과 → 오류가 알려준 실제 상한으로 1회 재시도하고, **그 재시도가
        //    성공했을 때만** 그 값을 기억한다. 정적 표 없이 모델별 상한을 배우는 경로다
        //    (표는 새 모델마다 낡는다).
        //
        //    성공 전에 기억하면 안 되는 이유 (B-151): `parseMaxTokensLimitFromError`가 400 본문에서
        //    엉뚱한 숫자를 집었을 때 그 값이 `detectedOutputLimit`으로 굳고, [AiTokenPolicy.effective]가
        //    **이후 모든 요청을 거기 맞춰 깎는다** — 사용자가 슬라이더를 올려도 그 합성이 이긴다.
        //    되돌릴 길은 R-23 초기화뿐인데 그것은 모델이나 주소를 **바꿔야** 도는지라, 값만 잘못
        //    배운 경우에는 바꿀 것이 없어 그 프로바이더가 영구히 절뚝인다. 증상은 잘린 응답이나
        //    `EMPTY_RESPONSE`로 나타나 원인을 짚기도 어렵다.
        //    형제인 ②·④가 이미 쓰는 형태 그대로 맞춘다 — 재시도가 통해야 배운 것이다.
        if (first.httpCode == 400) {
            val learned = AiProtocolCodec.parseMaxTokensLimitFromError(first.detail, request0.maxTokens)
            if (learned != null) {
                val retrySpec = AiProtocolCodec.buildRequest(
                    config, apiKey, request0.copy(maxTokens = learned)
                )
                val second = call(retrySpec, config.protocol, config.model)
                if (second is AiResult.Success) rememberDetectedLimit(config.id, learned)
                return@withContext noted(second)
            }
        }
        first
    }

    /** 지정/활성 설정의 최신 저장본 — 학습값(temperatureUnsupported 등)은 저장소가 진실이다. */
    private fun resolved(config: AiProviderConfig): AiProviderConfig =
        providerStore.get(config.id) ?: config

    /**
     * 탐지한 모델 출력 상한을 설정에 기록한다(다음 요청부터 바로 맞는 값으로 나간다).
     * 사용자가 슬라이더로 정한 값은 건드리지 않는다 — [AiTokenPolicy.effective]가 둘을 합성한다.
     */
    fun rememberDetectedLimit(configId: String, limit: Int) {
        val current = providerStore.get(configId) ?: return
        if (current.detectedOutputLimit == limit) return
        providerStore.save(current.copy(detectedOutputLimit = limit))
    }

    /**
     * `max_tokens` **파라미터 이름** 거부를 학습해 기록한다 (OPENAI_COMPAT 전용, R-23 대상).
     * 다음 요청부터 [AiProtocolCodec.buildRequest]가 첫 호출부터 max_completion_tokens로 조립한다.
     */
    fun rememberMaxTokensParamUnsupported(configId: String) {
        val current = providerStore.get(configId) ?: return
        if (current.maxTokensParamUnsupported == true) return
        providerStore.save(current.copy(maxTokensParamUnsupported = true))
    }

    /** temperature 거부를 학습해 기록한다 — 다음 요청부터 싣지 않는다 (A-4, R-23 대상). */
    fun rememberTemperatureUnsupported(configId: String) {
        val current = providerStore.get(configId) ?: return
        if (current.temperatureUnsupported == true) return
        providerStore.save(current.copy(temperatureUnsupported = true))
    }

    /**
     * 한도로 밀린 프로바이더를 쿨다운에 넣는다 (B-108, R-23 대상).
     * 없애는 것이 아니라 **뒤로 미루는** 것이다 — [AiProviderFallback.order]가 맨 뒤에 둔다.
     */
    fun rememberCooldown(configId: String, untilMillis: Long) {
        val current = providerStore.get(configId) ?: return
        if (current.cooldownUntilMillis == untilMillis) return
        providerStore.save(current.copy(cooldownUntilMillis = untilMillis))
    }

    /** 쿨다운을 지운다 — 그 프로바이더가 실제로 응답한 순간이 유일한 근거다 (B-108). */
    fun clearCooldown(configId: String) {
        val current = providerStore.get(configId) ?: return
        if (current.cooldownUntilMillis == null) return
        providerStore.save(current.copy(cooldownUntilMillis = null))
    }

    /** 이미지 거부를 학습해 기록한다 — 다음 요청부터 싣지 않는다 (A-7, R-23 대상). */
    fun rememberImagesUnsupported(configId: String) {
        val current = providerStore.get(configId) ?: return
        if (current.imagesUnsupported == true) return
        providerStore.save(current.copy(imagesUnsupported = true))
    }

    /**
     * 활성 모델이 이미지를 거부한다고 학습했는가 — 첨부 고지·결과 고지용 (A-7).
     * 다이얼로그가 이 값을 읽어 **붙이기 전에** 미리 말할 수 있다: 이미 아는 사실을
     * 알리지 않고 요청부터 보내면 사용자는 같은 실망을 매번 되풀이한다.
     */
    fun isImagesUnsupported(): Boolean =
        providerStore.active()?.imagesUnsupported == true

    /**
     * 활성 프로바이더에 실을 창작도 샘플링 값 (A-4). null = 싣지 않는다:
     * '균형'(프로바이더 기본값 유지)이거나, 이 모델이 temperature를 거부한다고 학습했거나,
     * 활성 프로바이더가 없는 경우다. 프로토콜별 상한 반영은 [AiCreativity]가 전담한다.
     */
    fun temperatureFor(creativity: AiCreativity): Double? {
        val active = providerStore.active() ?: return null
        if (active.temperatureUnsupported == true) return null
        return creativity.temperatureFor(active.protocol)
    }

    /** 활성 모델이 temperature를 거부한다고 학습했는가 — 창작도 결과 고지용 (A-4 §6-5 ④). */
    fun isTemperatureUnsupported(): Boolean =
        providerStore.active()?.temperatureUnsupported == true

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
