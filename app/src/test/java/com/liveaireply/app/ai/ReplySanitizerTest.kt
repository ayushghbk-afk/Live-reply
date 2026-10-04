package com.liveaireply.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySanitizerTest {

    @Test
    fun stripsCodeFences() {
        assertEquals("hello", ReplySanitizer.sanitize("```\nhello\n```"))
        assertEquals("hello", ReplySanitizer.sanitize("```text\nhello\n```"))
    }

    @Test
    fun stripsLeadingLabels() {
        assertEquals("Sure thing", ReplySanitizer.sanitize("Reply: Sure thing"))
        assertEquals("Sure thing", ReplySanitizer.sanitize("Assistant - Sure thing"))
        assertEquals("Sure thing", ReplySanitizer.sanitize("Me: Sure thing"))
    }

    @Test
    fun stripsSurroundingQuotes() {
        assertEquals("Sure thing", ReplySanitizer.sanitize("\"Sure thing\""))
        assertEquals("Sure thing", ReplySanitizer.sanitize("“Sure thing”"))
    }

    @Test
    fun collapsesBlankLines() {
        assertEquals("a\n\nb", ReplySanitizer.sanitize("a\n\n\n\n\nb"))
    }

    @Test
    fun clipsAtWordBoundaryWhenNoSentenceFits() {
        val clipped = ReplySanitizer.clipToLength("one two three four five", 13)
        assertTrue(clipped.length <= 13)
        assertFalse(clipped.endsWith(" "))
    }

    @Test
    fun leavesShortTextAlone() {
        assertEquals("ok", ReplySanitizer.clipToLength("ok", 500))
        assertEquals("ok", ReplySanitizer.sanitize("ok"))
    }

    @Test
    fun keepsEmojiWhenTheLimitIsHigh() {
        assertEquals("hi 😄", ReplySanitizer.sanitize("hi 😄", maxEmojis = 5))
    }
}
