package com.liveaireply.app.ai

import com.liveaireply.app.conversation.ChatTurn
import com.liveaireply.app.conversation.TurnDirection
import com.liveaireply.app.conversation.TurnSource
import com.liveaireply.app.personas.PersonaPresets
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.LanguagePolicy
import com.liveaireply.app.settings.ReplyLanguagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private val builder = PromptBuilder()

    private fun input(
        settings: AppSettings = AppSettings(),
        lines: List<Pair<String, TurnDirection>> = listOf(
            "Hey, are you coming tomorrow?" to TurnDirection.INCOMING,
            "Maybe. Why?" to TurnDirection.OUTGOING,
            "I wanted to ask you something." to TurnDirection.INCOMING
        )
    ) = PromptInput(
        context = lines.map { (text, direction) ->
            ChatTurn(text, direction, TurnSource.ACCESSIBILITY, 1L)
        },
        newestIncomingText = lines.last().first,
        persona = PersonaPresets.default(),
        settings = settings
    )

    @Test
    fun includesTheDefaultSystemPrompt() {
        val prompt = builder.build(input())
        assertTrue(prompt.systemPrompt.contains("real-time conversation reply assistant"))
        assertTrue(prompt.systemPrompt.contains("Do not mention that you are an AI"))
    }

    @Test
    fun customSystemPromptReplacesTheDefault() {
        val settings = AppSettings(customSystemPrompt = "You are Hellen's terse assistant.")
        val prompt = builder.build(input(settings))
        assertTrue(prompt.systemPrompt.contains("You are Hellen's terse assistant."))
        assertFalse(prompt.systemPrompt.contains("real-time conversation reply assistant"))
    }

    @Test
    fun rendersTheTranscriptWithTheConfiguredContextWindow() {
        val settings = AppSettings(contextMessageCount = 2)
        val prompt = builder.build(input(settings))
        assertFalse(prompt.transcript.contains("Hey, are you coming tomorrow?"))
        assertTrue(prompt.transcript.contains("Maybe. Why?"))
        assertTrue(prompt.transcript.contains("I wanted to ask you something."))
        assertTrue(prompt.transcript.contains("Them:"))
        assertTrue(prompt.transcript.contains("Me:"))
    }

    @Test
    fun roleplayPersonasCarryTheirRulesIntoThePrompt() {
        val prompt = builder.build(input().copy(persona = PersonaPresets.KAELEN))
        assertTrue(prompt.systemPrompt.contains("Kaelen"))
        assertTrue(prompt.systemPrompt.contains("Never break character."))
        assertTrue(prompt.systemPrompt.contains("slow-burn romance"))
    }

    @Test
    fun languagePolicyIsApplied() {
        val hindi = builder.build(input(AppSettings(languagePolicy = LanguagePolicy.HINDI)))
        assertTrue(hindi.systemPrompt.contains("always reply in Hindi"))

        val autoUnknown = builder.build(input(AppSettings()))
        assertTrue(autoUnknown.systemPrompt.contains("same language the other person used"))

        val autoDetected = builder.build(input(AppSettings()).copy(detectedLanguage = "Hinglish"))
        assertTrue(autoDetected.systemPrompt.contains("reply in Hinglish"))
    }

    @Test
    fun translationModeIsExplained() {
        val settings = AppSettings(translateIncoming = true, replyLanguage = ReplyLanguagePolicy.ENGLISH)
        val prompt = builder.build(input(settings))
        assertTrue(prompt.systemPrompt.contains("Translation mode is on"))
        assertTrue(prompt.systemPrompt.contains("write your reply in English"))
    }

    @Test
    fun replyLengthLimitIsStatedInCharacters() {
        val prompt = builder.build(
            input(AppSettings(replyLength = com.liveaireply.app.personas.ReplyLength.CUSTOM, maxReplyChars = 500))
        )
        assertTrue(prompt.systemPrompt.contains("500 characters"))
    }

    @Test
    fun presetReplyLengthsStateTheirOwnLimit() {
        val prompt = builder.build(input(AppSettings(replyLength = com.liveaireply.app.personas.ReplyLength.VERY_SHORT)))
        assertTrue(prompt.systemPrompt.contains("90 characters"))
    }

    @Test
    fun naturalnessRulesAreAlwaysPresent() {
        val prompt = builder.build(input())
        assertTrue(prompt.systemPrompt.contains("Never write \"As an AI\""))
        assertTrue(prompt.systemPrompt.contains("Do not start with a label"))
    }

    @Test
    fun messagesAreSystemPlusUserOnly() {
        val prompt = builder.build(input())
        assertEquals(2, prompt.messages.size)
        assertEquals(ChatMessage.Role.SYSTEM, prompt.messages[0].role)
        assertEquals(ChatMessage.Role.USER, prompt.messages[1].role)
        assertTrue(prompt.messages[1].content.contains("Recent conversation:"))
    }

    @Test
    fun longTurnsAreClippedSoRequestsStaySmall() {
        val huge = "x".repeat(5_000)
        val prompt = builder.build(
            input(lines = listOf(huge to TurnDirection.INCOMING))
        )
        assertTrue("transcript was ${prompt.transcript.length}", prompt.transcript.length < 600)
    }
}
