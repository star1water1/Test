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
    companion object { private val LOCK=Any() }
}
