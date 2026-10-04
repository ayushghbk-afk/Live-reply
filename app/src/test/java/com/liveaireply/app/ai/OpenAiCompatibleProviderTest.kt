package com.liveaireply.app.ai

import com.liveaireply.app.ai.openai.OpenAiCompatibleProvider
import com.liveaireply.app.ai.openai.OpenAiEndpointConfig
import com.liveaireply.app.ai.openai.OpenRouterProvider
import com.liveaireply.app.storage.Json
import com.liveaireply.app.storage.arr
import com.liveaireply.app.storage.asStringOrNull
import com.liveaireply.app.storage.get
import com.liveaireply.app.storage.toJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleProviderTest {

    private val config = OpenAiEndpointConfig(
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "sk-test-key-1234567890"
    )

    private fun request(model: String = "anthropic/claude-3.5-sonnet") = AiCompletionRequest(
        model = model,
        messages = listOf(
            ChatMessage.system("Be brief."),
            ChatMessage.user("Them: hi\n\nWrite the next message as Me.")
        ),
        temperature = 0.7f,
        maxTokens = 120,
        timeoutMs = 15_000L
    )

    @Test
    fun buildsAnOpenAiCompatiblePayload() {
        val transport = FakeTransport()
        transport.enqueueJson(200, """{"choices":[{"message":{"role":"assistant","content":"Hey!"}}]}""")
        val provider = OpenAiCompatibleProvider(transport = transport, configProvider = { config })

        val outcome = provider.complete(request())

        assertTrue(outcome is AiOutcome.Success)
        assertEquals("Hey!", (outcome as AiOutcome.Success).text)

        val sent = Json.parse(transport.lastCall().body!!)
        assertEquals("anthropic/claude-3.5-sonnet", sent["model"]?.asStringOrNull())
        assertEquals(120, (sent["max_tokens"] as? com.liveaireply.app.storage.JsonValue.JNum)?.value?.toInt())
        val messages = sent.arr("messages")
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].let { it["role"] }?.asStringOrNull())
        assertTrue(messages[1].let { it["content"] }?.asStringOrNull()!!.contains("Them: hi"))
    }

    @Test
    fun sendsTheBearerTokenAndJsonHeaders() {
        val transport = FakeTransport()
        transport.enqueueJson(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        OpenAiCompatibleProvider(transport = transport, configProvider = { config }).complete(request())

        val headers = transport.lastCall().headers
        assertEquals("Bearer sk-test-key-1234567890", headers["Authorization"])
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("https://openrouter.ai/api/v1/chat/completions", transport.lastCall().url)
    }

    @Test
    fun openRouterAddsAttributionHeaders() {
        val transport = FakeTransport()
        transport.enqueueJson(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        OpenRouterProvider(transport = transport, configProvider = { config }).complete(request())
        val headers = transport.lastCall().headers
        assertTrue(headers.containsKey("HTTP-Referer"))
        assertTrue(headers.containsKey("X-Title"))
    }

    @Test
    fun refusesToCallTheNetworkWithoutAKey() {
        val transport = FakeTransport()
        val provider = OpenAiCompatibleProvider(
            transport = transport,
            configProvider = { config.copy(apiKey = "") }
        )
        val outcome = provider.complete(request())
        assertTrue(outcome is AiOutcome.Failure)
        assertEquals(AiErrorKind.NOT_CONFIGURED, (outcome as AiOutcome.Failure).error.kind)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun refusesToCallTheNetworkWhenOffline() {
        val transport = FakeTransport(online = false)
        val provider = OpenAiCompatibleProvider(transport = transport, configProvider = { config })
        val outcome = provider.complete(request())
        assertEquals(AiErrorKind.NO_NETWORK, (outcome as AiOutcome.Failure).error.kind)
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun mapsHttpStatusesToUnderstandableErrors() {
        val cases = listOf(
            401 to AiErrorKind.AUTH,
            402 to AiErrorKind.QUOTA,
            404 to AiErrorKind.MODEL_UNAVAILABLE,
            408 to AiErrorKind.TIMEOUT,
            429 to AiErrorKind.RATE_LIMIT,
            500 to AiErrorKind.SERVER,
            503 to AiErrorKind.SERVER
        )
        for ((status, expected) in cases) {
            val transport = FakeTransport()
            transport.enqueueJson(status, """{"error":{"message":"boom"}}""")
            val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config })
                .complete(request())
            assertEquals("status $status", expected, (outcome as AiOutcome.Failure).error.kind)
        }
    }

    @Test
    fun readsTheProviderErrorBodyOnASuccessStatus() {
        val transport = FakeTransport()
        transport.enqueueJson(200, """{"error":{"message":"model overloaded","code":"rate_limit_exceeded"}}""")
        val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config }).complete(request())
        assertTrue(outcome is AiOutcome.Failure)
        assertEquals(AiErrorKind.RATE_LIMIT, (outcome as AiOutcome.Failure).error.kind)
    }

    @Test
    fun reportsMalformedResponsesAsBadResponse() {
        val transport = FakeTransport()
        transport.enqueueJson(200, "not json at all")
        val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config }).complete(request())
        assertEquals(AiErrorKind.BAD_RESPONSE, (outcome as AiOutcome.Failure).error.kind)

        val emptyChoices = FakeTransport()
        emptyChoices.enqueueJson(200, """{"choices":[]}""")
        val outcome2 = OpenAiCompatibleProvider(transport = emptyChoices, configProvider = { config }).complete(request())
        assertEquals(AiErrorKind.BAD_RESPONSE, (outcome2 as AiOutcome.Failure).error.kind)
    }

    @Test
    fun mapsTransportTimeouts() {
        val transport = FakeTransport()
        transport.enqueueFailure(TransportFailure.TIMEOUT)
        val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config }).complete(request())
        assertEquals(AiErrorKind.TIMEOUT, (outcome as AiOutcome.Failure).error.kind)
    }

    @Test
    fun parsesTheModelList() {
        val transport = FakeTransport()
        transport.enqueueJson(
            200,
            """{"data":[{"id":"openai/gpt-4o","name":"GPT-4o","context_length":128000},""" +
                """{"id":"anthropic/claude-3.5-sonnet"}]}"""
        )
        val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config }).listModels()
        assertTrue(outcome is ModelListOutcome.Success)
        val models = (outcome as ModelListOutcome.Success).models
        assertEquals(2, models.size)
        assertEquals("anthropic/claude-3.5-sonnet", models[0].id)
        assertEquals(128000, models[1].contextLength)
    }

    @Test
    fun errorMessagesAreHumanReadable() {
        val transport = FakeTransport()
        transport.enqueueJson(429, """{"error":{"message":"Rate limit reached"}}""")
        val outcome = OpenAiCompatibleProvider(transport = transport, configProvider = { config }).complete(request())
        val error = (outcome as AiOutcome.Failure).error
        assertFalse(error.headline().contains("Exception"))
        assertTrue(error.headline().contains("Rate limit"))
    }

    @Test
    fun endpointConfigJoinsUrlsSafely() {
        assertEquals("https://x.test/v1/chat/completions", OpenAiEndpointConfig.joinUrl("https://x.test/v1", "/chat/completions"))
        assertEquals("https://x.test/v1/chat/completions", OpenAiEndpointConfig.joinUrl("https://x.test/v1/", "chat/completions"))
        assertEquals("https://x.test/v1/models", OpenAiEndpointConfig.joinUrl("https://x.test/v1", "/models"))
    }

    @Test
    fun extraBodyFieldsAreIncluded() {
        val transport = FakeTransport()
        transport.enqueueJson(200, """{"choices":[{"message":{"content":"ok"}}]}""")
        val provider = OpenAiCompatibleProvider(
            transport = transport,
            configProvider = {
                config.copy(extraBodyFields = mapOf("top_p" to 0.9.toJson()))
            }
        )
        provider.complete(request())
        assertTrue(transport.lastCall().body!!.contains("\"top_p\":0.9"))
    }
}
