package com.novelcharacter.app.speech

import android.content.Context

/** Device-local preferences. API keys stay exclusively in AiKeyStore. */
class SpeechSettings(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("speech_input",Context.MODE_PRIVATE)
    fun read()=SpeechConfig(
        mode=SpeechMode.entries.firstOrNull {it.name==prefs.getString("mode",null)} ?: SpeechMode.CLOUD,
        providerId=prefs.getString("provider","").orEmpty(),
        model=prefs.getString("model","gpt-transcribe").orEmpty(),
        language=prefs.getString("language","ko").orEmpty(),
        sendHints=prefs.getBoolean("hints",true)
    )
    fun save(config: SpeechConfig) {
        prefs.edit().putString("mode",config.mode.name).putString("provider",config.providerId)
            .putString("model",config.model.trim()).putString("language",config.language.trim())
            .putBoolean("hints",config.sendHints).apply()
    }
    /** Seconds are kept separate from text-token prices to avoid a misleading cost estimate. */
    fun recordUsage(seconds: Long) = synchronized(LOCK) {
        prefs.edit().putLong("requests",prefs.getLong("requests",0)+1)
            .putLong("seconds",prefs.getLong("seconds",0)+seconds.coerceAtLeast(0)).apply()
    }
    fun usage(): Pair<Long,Long> = prefs.getLong("requests",0) to prefs.getLong("seconds",0)
    /** Earlier installs only kept these unclassified totals. New requests live in AiUsageStore. */
    fun clearLegacyUsage() = synchronized(LOCK) {
        prefs.edit().remove("requests").remove("seconds").apply()
    }

    /** Optional price for the exact configured provider and model, never a generic token price. */
    fun pricePerMinute(config: SpeechConfig, providerAddress: String?): Double? {
        if (prefs.getString("price_provider", null) != config.providerId ||
            prefs.getString("price_model", null) != config.model.trim() ||
            prefs.getString("price_address", null) != providerAddress?.trim()?.trimEnd('/')) return null
        val value = prefs.getString("price_per_minute", null)?.toDoubleOrNull()
        return value?.takeIf { it.isFinite() && it >= 0.0 }
    }

    fun savePrice(config: SpeechConfig, providerAddress: String?, value: Double?) {
        prefs.edit().putString("price_provider", config.providerId)
            .putString("price_model", config.model.trim())
            .putString("price_address", providerAddress?.trim()?.trimEnd('/'))
            .putString("price_per_minute", value?.toString()).apply()
    }
    companion object { private val LOCK=Any() }
}
