package com.novelcharacter.app.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** App-owned request evidence, independent of any model response. No keys or image bytes. */
data class AiInputSource(
    val briefing: String,
    val instructions: Map<String, String>,
    val contextNotes: List<String>
)
data class AiInputReceipt(
    val id: String,
    val model: String,
    val protocol: AiProtocol,
    val bodySha256: String,
    val sentText: List<String>,
    val source: AiInputSource,
    val imageCount: Int,
    val budget: AiInputBudget
)
data class AiInputBudget(
    val inputTokens: Long? = null,
    val inputLimit: Long? = null,
    val outputReserve: Long? = null,
    val outputLimit: Long? = null,
    val sharedWindow: Boolean = false
) {
    val known: Boolean get() = inputTokens != null && inputLimit != null && outputLimit != null && outputReserve != null
    val exceeded: Boolean get() =
        (inputTokens != null && inputLimit != null &&
            (inputTokens > inputLimit ||
                (sharedWindow && outputReserve != null && outputReserve > inputLimit - inputTokens))) ||
            (outputReserve != null && outputLimit != null && outputReserve > outputLimit)
    fun notice(): String = when {
        !known -> "입력 한도를 확인할 수 없습니다. 원문은 줄이지 않고 전송했으며, 서버의 전체 처리 여부는 확인할 수 없습니다."
        else -> "입력 약 ${inputTokens}토큰 · ${if (sharedWindow) "입력·출력 합계" else "입력"} 한도 ${inputLimit}토큰 · 출력 여유 ${outputReserve}토큰. 제공자 계산값이며 내용의 완전한 이해를 보장하지 않습니다."
    }
}

/** Only documented counting endpoints on the selected provider receive the final wire input.
 * Unknown compatible servers keep working, with an explicit unknown result; no guessed token table.
 */
object AiInputPreflight {
    const val SEND_NOTICE = "브리핑·보완 지시는 줄이지 않습니다. 지원 제공자에는 같은 자료로 입력량을 먼저 조회합니다. 한도 초과가 확인되면 생성을 멈추며, 한도를 확인할 수 없는 서버는 원문 그대로 요청합니다."
    data class Reply(val code: Int, val body: String)

    fun supported(config: AiProviderConfig): Boolean = try {
        val uri = URI(config.baseUrl)
        uri.scheme == "https" && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.path.orEmpty().trimEnd('/').isEmpty() &&
            config.model.matches(Regex("[a-zA-Z0-9._-]+")) && when (config.protocol) {
                AiProtocol.GEMINI -> uri.host == "generativelanguage.googleapis.com"
                AiProtocol.ANTHROPIC -> uri.host == "api.anthropic.com"
                else -> false
            }
    } catch (_: Exception) { false }

    fun modelSpec(config: AiProviderConfig, wire: AiProtocolCodec.HttpSpec) =
        wire.copy(url = when (config.protocol) {
            AiProtocol.GEMINI -> wire.url.substringBefore(":generateContent")
            else -> config.baseUrl.trimEnd('/') + "/v1/models/" + config.model
        }, bodyJson = "", method = "GET")

    fun countSpec(config: AiProviderConfig, wire: AiProtocolCodec.HttpSpec): AiProtocolCodec.HttpSpec {
        val body = JsonParser.parseString(wire.bodyJson).asJsonObject
        return when (config.protocol) {
            AiProtocol.GEMINI -> wire.copy(
                url = wire.url.replace(":generateContent", ":countTokens"),
                bodyJson = JsonObject().apply {
                    body.addProperty("model", "models/" + config.model)
                    add("generateContentRequest", body)
                }.toString())
            AiProtocol.ANTHROPIC -> wire.copy(
                url = wire.url + "/count_tokens",
                bodyJson = JsonObject().apply {
                    for (key in listOf("model", "system", "messages")) body.get(key)?.let { add(key, it) }
                }.toString())
            else -> error("No documented counter")
        }
    }

    private fun number(body: String, key: String): Long? = try {
        val value = JsonParser.parseString(body).asJsonObject.get(key)
        if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isNumber)
            value.asString.toLongOrNull()?.takeIf { it > 0 } else null
    } catch (_: Exception) { null }

    fun budget(protocol: AiProtocol, modelBody: String, countBody: String, reserve: Long): AiInputBudget =
        AiInputBudget(
            inputTokens = number(countBody, if (protocol == AiProtocol.GEMINI) "totalTokens" else "input_tokens"),
            inputLimit = number(modelBody, if (protocol == AiProtocol.GEMINI) "inputTokenLimit" else "max_input_tokens"),
            outputReserve = reserve,
            outputLimit = number(modelBody, if (protocol == AiProtocol.GEMINI) "outputTokenLimit" else "max_tokens"),
            sharedWindow = protocol == AiProtocol.ANTHROPIC
        )

    /** Invocation is inside each actual candidate/retry, after image/output adjustments. */
    suspend fun send(
        config: AiProviderConfig,
        wire: AiProtocolCodec.HttpSpec,
        source: AiInputSource,
        inspect: suspend (AiProtocolCodec.HttpSpec) -> Reply?,
        generate: suspend () -> AiResult
    ): AiResult {
        val json = JsonParser.parseString(wire.bodyJson).asJsonObject
        val reserve = if (config.protocol == AiProtocol.GEMINI)
            json.getAsJsonObject("generationConfig").get("maxOutputTokens").asLong
        else (json.get("max_completion_tokens") ?: json.get("max_tokens")).asLong
        var budget = AiInputBudget(outputReserve = reserve)
        if (supported(config)) {
            val model = inspect(modelSpec(config, wire))
            if (model != null && model.code in 200..299) {
                val count = inspect(countSpec(config, wire))
                if (count != null && AiProtocolCodec.parseError(count.code, count.body).kind == AiErrorKind.INPUT_TOO_LARGE)
                    return AiResult.Failure(AiErrorKind.INPUT_TOO_LARGE,
                        detail = "입력량 조회에서 한도 초과가 확인되어 생성 요청은 보내지 않았습니다.")
                if (count != null && count.code in 200..299)
                    budget = budget(config.protocol, model.body, count.body, reserve)
            }
        }
        if (budget.exceeded) return AiResult.Failure(AiErrorKind.INPUT_TOO_LARGE,
            detail = "입력 약 ${budget.inputTokens}토큰, 입력 한도 ${budget.inputLimit}토큰, 출력 여유 ${reserve}토큰. 생성 요청은 보내지 않았습니다.")
        val receipt = AiInputReceipt(UUID.randomUUID().toString(), config.model, config.protocol,
            MessageDigest.getInstance("SHA-256").digest(wire.bodyJson.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            sentText(json), source, imageCount(json), budget)
        return when (val result = generate()) {
            is AiResult.Success -> result.copy(inputReceipt = receipt)
            is AiResult.Failure -> result
        }
    }

    private fun imageCount(node: com.google.gson.JsonElement): Int = when {
        node.isJsonArray -> node.asJsonArray.sumOf { imageCount(it) }
        node.isJsonObject -> {
            val obj = node.asJsonObject
            if (obj.has("inline_data") || obj.get("type")?.let {
                it.isJsonPrimitive && it.asString in listOf("image", "image_url")
            } == true) 1 else obj.entrySet().sumOf { imageCount(it.value) }
        }
        else -> 0
    }

    /** Retain only text content from the actual protocol body, not generation settings or media. */
    private fun sentText(node: com.google.gson.JsonElement): List<String> = when {
        node.isJsonArray -> node.asJsonArray.flatMap { sentText(it) }
        node.isJsonObject -> node.asJsonObject.entrySet().flatMap { (key, value) ->
            when {
                key in listOf("inline_data", "source", "image_url") -> emptyList()
                key in listOf("text", "system", "content") && value.isJsonPrimitive &&
                    value.asJsonPrimitive.isString -> listOf(value.asString)
                value.isJsonObject || value.isJsonArray -> sentText(value)
                else -> emptyList()
            }
        }
        else -> emptyList()
    }
}
