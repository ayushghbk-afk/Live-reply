package com.liveaireply.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesObjectsArraysAndScalars() {
        val parsed = Json.parse(
            """{"a":1,"b":"two","c":true,"d":null,"e":[1,2,3],"f":{"g":-1.5e2}}"""
        )
        assertEquals(1, parsed.int("a"))
        assertEquals("two", parsed.str("b"))
        assertTrue(parsed.bool("c"))
        assertEquals(JsonValue.JNull, parsed["d"])
        assertEquals(3, parsed.arr("e").size)
        assertEquals(-150.0, parsed.obj("f")!!.dbl("g"), 0.0001)
    }

    @Test
    fun roundTripsEscapesAndUnicode() {
        val original = jsonObj(
            "quote" to "she said \"hi\"".toJson(),
            "newline" to "line1\nline2".toJson(),
            "tab" to "a\tb".toJson(),
            "backslash" to "C:\\path".toJson(),
            "emoji" to "okay 😄".toJson(),
            "control" to "bell\u0007".toJson(),
            "hindi" to "Haan, aa raha hoon".toJson()
        )
        val encoded = Json.stringify(original)
        val decoded = Json.parse(encoded)
        assertEquals("she said \"hi\"", decoded.str("quote"))
        assertEquals("line1\nline2", decoded.str("newline"))
        assertEquals("a\tb", decoded.str("tab"))
        assertEquals("C:\\path", decoded.str("backslash"))
        assertEquals("okay 😄", decoded.str("emoji"))
        assertEquals("bell\u0007", decoded.str("control"))
        assertEquals("Haan, aa raha hoon", decoded.str("hindi"))
    }

    @Test
    fun parsesUnicodeEscapesIncludingSurrogatePairs() {
        val parsed = Json.parse("""{"emoji":"\ud83d\ude04","plain":"\u0041"}""")
        assertEquals("\uD83D\uDE04", parsed.str("emoji"))
        assertEquals("A", parsed.str("plain"))
    }

    @Test
    fun writesIntegralDoublesWithoutDecimalPoint() {
        val encoded = Json.stringify(jsonObj("n" to 5.toJson(), "m" to 5.5.toJson()))
        assertTrue(encoded.contains("\"n\":5"))
        assertTrue(encoded.contains("\"m\":5.5"))
    }

    @Test
    fun rejectsMalformedInputInsteadOfReturningNull() {
        assertNull(Json.parseOrNull("""{"a":}"""))
        assertNull(Json.parseOrNull("""{"a":1"""))
        assertNull(Json.parseOrNull("""[1,2"""))
        assertNull(Json.parseOrNull("nope"))
        assertNull(Json.parseOrNull("""{"a":1} trailing"""))
        assertNull(Json.parseOrNull(null))
        assertNull(Json.parseOrNull("   "))
    }

    @Test
    fun accessorsFallBackToDefaults() {
        val parsed = Json.parse("""{"a":"x"}""")
        assertEquals("fallback", parsed.str("missing", "fallback"))
        assertEquals(7, parsed.int("missing", 7))
        assertFalse(parsed.bool("missing"))
        assertTrue(parsed.arr("missing").isEmpty())
        assertNull(parsed.obj("missing"))
    }

    @Test
    fun emptyContainersSerialiseCompactly() {
        assertEquals("{}", Json.stringify(jsonObj()))
        assertEquals("[]", Json.stringify(jsonArr()))
    }

    @Test
    fun handlesDeeplyNestedStructures() {
        var node: JsonValue = jsonObj("leaf" to "value".toJson())
        repeat(50) { node = jsonObj("child" to node) }
        val encoded = Json.stringify(node)
        var cursor = Json.parse(encoded)
        repeat(50) { cursor = cursor.obj("child")!! }
        assertEquals("value", cursor.str("leaf"))
    }
}
