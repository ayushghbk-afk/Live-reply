package com.liveaireply.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun detectsDevanagariAsHindi() {
        assertEquals("Hindi", LanguageDetector.detect("कल आ रहा हो क्या?"))
    }

    @Test
    fun detectsHinglishFromLatinScript() {
        assertEquals("Hinglish", LanguageDetector.detect("Kal aa raha hai kya?"))
        assertEquals("Hinglish", LanguageDetector.detect("kaise ho yaar"))
    }

    @Test
    fun detectsSpanishFrenchAndGerman() {
        assertEquals("Spanish", LanguageDetector.detect("¿Vienes mañana? Gracias por todo"))
        assertEquals("French", LanguageDetector.detect("Bonjour, tu viens demain aussi ?"))
        assertEquals("German", LanguageDetector.detect("Hallo, kommst du morgen auch?"))
    }

    @Test
    fun returnsNullWhenItCannotTell() {
        assertNull(LanguageDetector.detect("ok"))
        assertNull(LanguageDetector.detect(""))
    }

    @Test
    fun detectsOtherScripts() {
        assertEquals("Arabic", LanguageDetector.detect("هل أنت قادم غدا"))
        assertEquals("Chinese", LanguageDetector.detect("你明天来吗"))
        assertEquals("Russian", LanguageDetector.detect("Ты придёшь завтра?"))
    }
}
