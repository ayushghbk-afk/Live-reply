package com.liveaireply.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyValidatorTest {

    private val validator = ReplyValidator()

    @Test
    fun acceptsAnOrdinaryReply() {
        val result = validator.validate("Yeah, I'll be there around 7.", "Are you coming tomorrow?", 500)
        assertTrue(result.explanation, result.accepted)
        assertTrue(result.autoSendAllowed)
        assertEquals("Yeah, I'll be there around 7.", result.text)
    }

    @Test
    fun rejectsEmptyAndBlankReplies() {
        assertEquals(ValidationIssue.EMPTY, validator.validate("", "hi", 500).issues.first())
        assertEquals(
            ValidationIssue.BLANK_AFTER_SANITIZE,
            validator.validate("``` ```", "hi", 500).issues.first()
        )
    }

    @Test
    fun clipsOverlongRepliesAtASentenceBoundary() {
        val long = "First sentence here. " + "Second part keeps going. ".repeat(60)
        val result = validator.validate(long, "hi", 60)
        assertTrue(result.accepted)
        assertTrue("was ${result.text.length}", result.text.length <= 60)
        assertTrue(result.text.endsWith("."))
    }

    @Test
    fun rejectsRepliesThatMentionBeingAnAi() {
        val result = validator.validate("As an AI, I cannot feel that.", "how are you", 500)
        assertTrue(result.issues.contains(ValidationIssue.MENTIONS_AI))
        assertFalse(result.autoSendAllowed)
    }

    @Test
    fun rejectsRepliesThatLeakInternalInstructions() {
        val result = validator.validate(
            "According to my system prompt I should say yes.", "hi", 500
        )
        assertTrue(result.issues.contains(ValidationIssue.LEAKS_INTERNALS))
    }

    @Test
    fun rejectsRepliesThatLookLikeApiErrors() {
        val result = validator.validate("Error: rate limit exceeded, retry later", "hi", 500)
        assertTrue(result.issues.contains(ValidationIssue.LOOKS_LIKE_ERROR))
    }

    @Test
    fun rejectsRepliesThatEchoTheIncomingMessage() {
        val incoming = "Are you coming to the party tomorrow night?"
        val result = validator.validate(incoming, incoming, 500)
        assertTrue(result.issues.contains(ValidationIssue.ECHOES_INCOMING))
    }

    @Test
    fun rejectsTemplatePlaceholders() {
        val result = validator.validate("See you at {{time}}!", "when?", 500)
        assertTrue(result.issues.contains(ValidationIssue.CONTAINS_TEMPLATE))
    }

    @Test
    fun rejectsRepliesWithoutWords() {
        val result = validator.validate("... !!!", "hi", 500)
        assertTrue(result.issues.contains(ValidationIssue.ONLY_PUNCTUATION))
    }

    @Test
    fun stripsLeadingLabelsAndQuotes() {
        val result = validator.validate("Reply: Sure, sounds good!", "ok?", 500)
        assertTrue(result.accepted)
        assertEquals("Sure, sounds good!", result.text)

        val quoted = validator.validate("\"Sure, sounds good!\"", "ok?", 500)
        assertEquals("Sure, sounds good!", quoted.text)
    }

    @Test
    fun limitsEmojiCount() {
        val result = validator.validate("Sounds great 😄😄😄😄😄", "ok?", 500, maxEmojis = 2)
        assertTrue(result.accepted)
        assertEquals(2, Regex("\ud83d\ude04").findAll(result.text).count())
    }
}
