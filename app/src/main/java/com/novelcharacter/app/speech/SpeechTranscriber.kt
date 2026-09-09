package com.novelcharacter.app.speech

import android.content.Context
import com.novelcharacter.app.ai.AiKeyStore
import com.novelcharacter.app.ai.AiProviderStore
import com.novelcharacter.app.util.ImagePathMatch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/** Dedicated audio capability. No provider fallback, redirect or automatic paid retry. */
class SpeechTranscriber(context: Context) {
    private val app=context.applicationContext
    private val client=OkHttpClient.Builder().connectTimeout(20,TimeUnit.SECONDS)
        .readTimeout(120,TimeUnit.SECONDS).callTimeout(150,TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    suspend fun transcribe(config: SpeechConfig, file: File, audioRoot: File,
        terms: List<String>, seconds: Long): SpeechResult = withContext(Dispatchers.IO) {
        val provider=AiProviderStore(app).get(config.providerId)
            ?: return@withContext SpeechResult.Failure(SpeechError.NO_PROVIDER)
        if(config.mode!=SpeechMode.CLOUD) return@withContext SpeechResult.Failure(SpeechError.CONFIG)
        SpeechProtocol.providerError(config,provider.protocol==com.novelcharacter.app.ai.AiProtocol.OPENAI_COMPAT,
            provider.baseUrl)?.let { return@withContext SpeechResult.Failure(it) }
        val endpoint=SpeechProtocol.endpoint(provider.baseUrl)
            ?: return@withContext SpeechResult.Failure(SpeechError.CONFIG)
        if(config.model.isBlank()) return@withContext SpeechResult.Failure(SpeechError.CONFIG)
        if(!ImagePathMatch.isInside(file.path,audioRoot) || !file.isFile || file.length()==0L)
            return@withContext SpeechResult.Failure(SpeechError.FILE)
        if(file.length()>SpeechProtocol.MAX_AUDIO_BYTES) return@withContext SpeechResult.Failure(SpeechError.TOO_LARGE)
        val key=AiKeyStore(app).getKey(provider.id)
            ?: return@withContext SpeechResult.Failure(SpeechError.NO_KEY)
        try {
            val body=MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file","voice.m4a",file.asRequestBody("audio/mp4".toMediaType()))
            SpeechProtocol.parameters(config,terms).forEach {(name,value)->body.addFormDataPart(name,value)}
            val request=Request.Builder().url(endpoint).header("Authorization","Bearer $key").post(body.build()).build()
            client.newCall(request).execute().use { response ->
                // Error bodies may echo credentials or private audio/context. Never log or surface them.
                val result=SpeechProtocol.parse(response.code,response.peekBody(2_000_000).string(),config.model)
                if(result is SpeechResult.Success) SpeechSettings(app).recordUsage(seconds)
                result
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { SpeechResult.Failure(SpeechError.NETWORK) }
    }
}
