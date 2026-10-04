package com.liveaireply.app.ai

import com.liveaireply.app.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplyPipelineTest {

    private lateinit var provider: FakeProvider
    private lateinit var sleeper: RecordingSleeper
    private lateinit var pipeline: ReplyPipeline
    private val prompt = BuiltPrompt(
        systemPrompt = "system",
        messages = listOf(ChatMessage.system("system"), ChatMessage.user("Them: hi")),
        transcript = "Them: hi",
        maxReplyChars = 500
    )

    @Before
    fun setUp() {
        provider = FakeProvider()
        sleeper = Sleeper.recording()
        pipeline = ReplyPipeline(provider, sleeper = sleeper, retryPolicy = RetryPolicy(maxAttemptsPerModel = 2))
    }

    private fun settings(vararg models: String) = AppSettings(
        primaryModel = models.firstOrNull().orEmpty(),
        fallbackModels = models.drop(1),
        retryCount = 1
    )

    @Test
    fun returnsAValidReply() {
        provider.replyWith("Hey! Yes I am free tomorrow.")
        val result = pipeline.generate(prompt, settings("model-a"))
        assertTrue(result is ReplyGenerationResult.Generated)
        assertEquals("Hey! Yes I am free tomorrow.", (result as ReplyGenerationResult.Generated).text)
        assertEquals(1, provider.requests.size)
        assertEquals("model-a", provider.requests[0].model)
    }

    @Test
    fun reportsAConfigurationErrorWithoutCallingTheNetwork() {
        val result = pipeline.generate(prompt, AppSettings(primaryModel = ""))
        assertTrue(result is ReplyGenerationResult.Failed)
        assertEquals(AiErrorKind.NOT_CONFIGURED, (result as ReplyGenerationResult.Failed).error.kind)
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun retriesATimeoutThenSucceeds() {
        provider.failWith(AiErrorKind.TIMEOUT, "timed out")
        provider.replyWith("Sorry, that took a moment.")
        val result = pipeline.generate(prompt, settings("model-a"))
        assertTrue(result is ReplyGenerationResult.Generated)
        assertEquals(2, provider.requests.size)
        assertEquals(1, sleeper.delays.size)
    }

    @Test
    fun neverRetriesForever() {
        repeat(10) { provider.failWith(AiErrorKind.TIMEOUT, "timed out") }
        val result = pipeline.generate(prompt, settings("model-a"))
        assertTrue(result is ReplyGenerationResult.Failed)
        assertEquals(
            "attempts must be bounded by the retry policy",
            2,
            provider.requests.size
        )
    }

    @Test
    fun fallsBackToTheNextModelOnRateLimit() {
        provider.failWith(AiErrorKind.RATE_LIMIT, "slow down")
        provider.failWith(AiErrorKind.RATE_LIMIT, "slow down")
        provider.replyWith("Answer from the fallback.")
        val result = pipeline.generate(prompt, settings("model-a", "model-b", "model-c"))
        assertTrue(result is ReplyGenerationResult.Generated)
        assertEquals(listOf("model-a", "model-a", "model-b"), provider.requests.map { it.model })
    }

    @Test
    fun doesNotFallBackForAnInvalidApiKey() {
        provider.failWith(AiErrorKind.AUTH, "invalid api key")
        val result = pipeline.generate(prompt, settings("model-a", "model-b"))
        assertTrue(result is ReplyGenerationResult.Failed)
        assertEquals(AiErrorKind.AUTH, (result as ReplyGenerationResult.Failed).error.kind)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun stopsImmediatelyWhenOffline() {
        provider.failWith(AiErrorKind.NO_NETWORK, "offline")
        val result = pipeline.generate(prompt, settings("model-a", "model-b"))
        assertTrue(result is ReplyGenerationResult.Failed)
        assertEquals(1, provider.requests.size)
        assertEquals(0, sleeper.delays.size)
    }

    @Test
    fun honoursRetryAfterFromTheProvider() {
        provider.failWith(AiErrorKind.RATE_LIMIT, "slow down", retryAfterMs = 1_500L)
        provider.replyWith("ok")
        pipeline.generate(prompt, settings("model-a"))
        assertEquals(listOf(1_500L), sleeper.delays)
    }

    @Test
    fun surfacesInvalidRepliesInsteadOfSendingThem() {
        provider.replyWith("As an AI, I cannot help with that.")
        val result = pipeline.generate(prompt, settings("model-a"))
        assertTrue(result is ReplyGenerationResult.Invalid)
        assertFalse((result as ReplyGenerationResult.Invalid).validation.autoSendAllowed)
    }

    @Test
    fun passesTemperatureAndTokenLimitsThrough() {
        provider.replyWith("ok")
        pipeline.generate(prompt, AppSettings(primaryModel = "m", temperature = 0.2f, maxTokens = 64, timeoutMs = 8_000))
        val request = provider.requests.single()
        assertEquals(0.2f, request.temperature, 0.0001f)
        assertEquals(64, request.maxTokens)
        assertEquals(8_000L, request.timeoutMs)
    }
}
