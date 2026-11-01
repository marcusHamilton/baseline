package dev.baseline.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineLabelTest {
    @Test
    fun nanoLabelIsOnDevice() {
        assertEquals("On-device", EngineLabel(EngineKind.NANO, "nano-v4").text)
    }

    @Test
    fun cloudLabelShowsTheModelAfterTheLastSlash() {
        assertEquals("Cloud · deepseek-v4.1-flash", EngineLabel(EngineKind.CLOUD, "deepseek/deepseek-v4.1-flash").text)
        assertEquals("Cloud · gpt-x", EngineLabel(EngineKind.CLOUD, "gpt-x").text)
    }

    @Test
    fun chatMessagesAreTheSpecWording() {
        assertEquals("Your API key was rejected. Check it in Settings.", AiFailure.KeyRejected.chatMessage())
        assertEquals("Couldn't reach openrouter.ai.", AiFailure.Unreachable("openrouter.ai").chatMessage())
        assertEquals("openrouter.ai is busy. Try again shortly.", AiFailure.Busy("openrouter.ai").chatMessage())
        assertEquals("Something went wrong reading the answer.", AiFailure.Malformed.chatMessage())
        assertEquals("No credit left at openrouter.ai.", AiFailure.NoCredit("openrouter.ai").chatMessage())
        assertEquals("This model is no longer available. Pick another in Settings.", AiFailure.ModelGone.chatMessage())
        assertEquals("You're offline.", AiFailure.Offline.chatMessage())
        assertEquals("On-device AI is busy. Try again in a moment.", AiFailure.NanoBusy.chatMessage())
        assertEquals("On-device AI has reached today's limit.", AiFailure.NanoQuota.chatMessage())
        assertEquals("", AiFailure.NanoBackground.chatMessage())
        assertEquals("On-device AI isn't available on this phone right now.", AiFailure.NanoUnavailable.chatMessage())
    }

    @Test
    fun insightMessagesAreTheSpecWording() {
        assertEquals("API key rejected", AiFailure.KeyRejected.insightMessage())
        assertEquals("No credit left at openrouter.ai", AiFailure.NoCredit("openrouter.ai").insightMessage())
        assertEquals("This model is no longer available. Pick another in Settings.", AiFailure.ModelGone.insightMessage())
        assertEquals("openrouter.ai is busy. Try again shortly.", AiFailure.Busy("openrouter.ai").insightMessage())
        assertEquals("Couldn't reach openrouter.ai", AiFailure.Unreachable("openrouter.ai").insightMessage())
        assertEquals("You're offline", AiFailure.Offline.insightMessage())
        assertEquals("Couldn't generate", AiFailure.Malformed.insightMessage())
        assertEquals("On-device AI is busy. Try again in a moment.", AiFailure.NanoBusy.insightMessage())
        assertEquals("On-device AI has reached today's limit.", AiFailure.NanoQuota.insightMessage())
        assertEquals("", AiFailure.NanoBackground.insightMessage())
        assertEquals("On-device AI isn't available on this phone right now.", AiFailure.NanoUnavailable.insightMessage())
    }

    @Test
    fun failuresNeverCarryAMessage() {
        val all =
            listOf(
                AiFailure.KeyRejected,
                AiFailure.NoCredit("h"),
                AiFailure.ModelGone,
                AiFailure.Busy("h"),
                AiFailure.Unreachable("h"),
                AiFailure.Offline,
                AiFailure.Malformed,
                AiFailure.NanoBusy,
                AiFailure.NanoQuota,
                AiFailure.NanoBackground,
                AiFailure.NanoUnavailable,
            )
        all.forEach { assertNull(it::class.simpleName, it.message) }
    }
}
