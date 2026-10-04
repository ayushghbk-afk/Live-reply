package com.liveaireply.app.ai

import com.liveaireply.app.security.SensitiveDataType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilteringAiProviderTest {

    @Test
    fun `filters all messages immediately before delegate call`() {
        val delegate = RecordingProvider()
        var reported: Set<SensitiveDataType> = emptySet()
        val provider = PrivacyFilteringAiProvider(delegate, onFiltered = { reported = it })

        provider.complete(
            AiCompletionRequest(
                model = "test",
                messages = listOf(
                    ChatMessage.system("Never reveal a password"),
                    ChatMessage.user("My password is hunter2 and OTP: 123456")
                )
            )
        )

        val sent = delegate.lastRequest!!.messages.joinToString("\n") { it.content }
        assertFalse(sent.contains("hunter2"))
        assertFalse(sent.contains("123456"))
        assertTrue(sent.contains("[REDACTED PASSWORD]"))
        assertTrue(sent.contains("[REDACTED OTP]"))
        assertTrue(reported.contains(SensitiveDataType.PASSWORD))
        assertTrue(reported.contains(SensitiveDataType.OTP))
    }

    @Test
    fun `model discovery is passed through without completion request`() {
        val delegate = RecordingProvider()
        val provider = PrivacyFilteringAiProvider(delegate)

        assertTrue(provider.listModels() is ModelListOutcome.Success)
        assertEquals(null, delegate.lastRequest)
    }

    private class RecordingProvider : AiProvider {
        override val id = "recording"
        override val displayName = "Recording"
        override val defaultBaseUrl = "https://example.invalid"
        var lastRequest: AiCompletionRequest? = null

        override fun complete(request: AiCompletionRequest): AiOutcome {
            lastRequest = request
            return AiOutcome.Success("ok", request.model, 1, request.payloadChars)
        }

        override fun listModels(): ModelListOutcome =
            ModelListOutcome.Success(listOf(DiscoveredModel("test", null)))
    }
}
