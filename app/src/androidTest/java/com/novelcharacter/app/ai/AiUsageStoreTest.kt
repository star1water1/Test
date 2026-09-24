package com.novelcharacter.app.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novelcharacter.app.speech.SpeechConfig
import com.novelcharacter.app.speech.SpeechSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the instrumentation APK's private preferences, never the user's app records. */
@RunWith(AndroidJUnit4::class)
class AiUsageStoreTest {
    @Test fun storesBothModalitiesAndClearsLegacyCountersWithoutChangingSpeechConfig() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val usage = AiUsageStore(context)
        val speech = SpeechSettings(context)
        usage.clear()
        val original = speech.read()
        try {
            val config = SpeechConfig(providerId = "usage-test", model = "speech-a")
            speech.save(config)
            speech.savePrice(config, "https://example.test/v1", 0.5)
            speech.recordUsage(17)
            usage.record("provider", "Provided", "model-a", 100, 50, 1.0, 2.0)
            usage.recordSpeech("provider", "Provided", "speech-a", 90,
                speech.pricePerMinute(config, "https://example.test/v1"))
            val data = AiUsageStore(context).snapshot()
            assertEquals(1, data.totals.size)
            assertEquals(1, data.speechTotals.size)
            assertEquals(0.75, data.speechTotals.single().pricedCost, 0.0001)
            assertNull(speech.pricePerMinute(config.copy(model = "speech-b"), "https://example.test/v1"))
            assertNull(speech.pricePerMinute(config, "https://another.test/v1"))
            usage.clear()
            assertEquals(AiUsageLedger.Data(), usage.snapshot())
            assertEquals(0L to 0L, speech.usage())
            assertEquals(config, speech.read())
        } finally {
            usage.clear()
            speech.save(original)
            speech.savePrice(original, null, null)
        }
    }
}
