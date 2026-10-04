package com.liveaireply.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageHasherTest {

    @Test
    fun normalisationIgnoresTrivialDifferences() {
        assertTrue(MessageHasher.sameContent("Hello", "  Hello  "))
        assertTrue(MessageHasher.sameContent("Hello   world", "Hello world"))
        assertTrue(MessageHasher.sameContent("Hello\u200B", "Hello"))
        assertTrue(MessageHasher.sameContent("Hello\nworld", "Hello world"))
    }

    @Test
    fun differentContentHashesDifferently() {
        assertNotEquals(MessageHasher.hash("Hello"), MessageHasher.hash("Hello!"))
    }

    @Test
    fun directionChangesTheStructuralHash() {
        val incoming = MessageHasher.structuralHash("Yeah, I'll be there", TurnDirection.INCOMING)
        val outgoing = MessageHasher.structuralHash("Yeah, I'll be there", TurnDirection.OUTGOING)
        assertNotEquals(incoming, outgoing)
    }

    @Test
    fun hashesAreStableAcrossCalls() {
        assertEquals(MessageHasher.hash("same text"), MessageHasher.hash("same text"))
    }

    @Test
    fun normalisesUnicodeToNfc() {
        val composed = "\u00E9"          // é as one code point
        val decomposed = "e\u0301"       // e + combining acute
        assertEquals(MessageHasher.hash(composed), MessageHasher.hash(decomposed))
    }

    @Test
    fun hashLengthIsBounded() {
        val long = "x".repeat(50_000)
        assertEquals(32, MessageHasher.hash(long).length)
        assertFalse(MessageHasher.hash(long).contains("x"))
    }
}
