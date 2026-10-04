package com.liveaireply.app.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFilterTest {

    @Test
    fun timestampsAreNoise() {
        assertTrue(NoiseFilter.isNoise("16:43"))
        assertTrue(NoiseFilter.isNoise("4:05 PM"))
        assertTrue(NoiseFilter.isNoise("23:59:01"))
    }

    @Test
    fun dateHeadersAreNoise() {
        assertTrue(NoiseFilter.isNoise("Today"))
        assertTrue(NoiseFilter.isNoise("Yesterday"))
        assertTrue(NoiseFilter.isNoise("12/03/2026"))
        assertTrue(NoiseFilter.isNoise("March 3"))
    }

    @Test
    fun receiptsAndTypingIndicatorsAreNoise() {
        assertTrue(NoiseFilter.isNoise("✓✓"))
        assertTrue(NoiseFilter.isNoise("Delivered"))
        assertTrue(NoiseFilter.isNoise("Read"))
        assertTrue(NoiseFilter.isNoise("typing..."))
        assertTrue(NoiseFilter.isNoise("3 unread messages"))
    }

    @Test
    fun realMessagesAreNotNoise() {
        assertFalse(NoiseFilter.isNoise("Are you coming tomorrow?"))
        assertFalse(NoiseFilter.isNoise("ok"))
        assertFalse(NoiseFilter.isNoise("I'll be there at 5"))
        assertFalse(NoiseFilter.isNoise("Kal aa raha hai kya?"))
    }

    @Test
    fun sensitiveHintsAreDetected() {
        assertTrue(NoiseFilter.hasSensitiveHint("Enter your OTP"))
        assertTrue(NoiseFilter.hasSensitiveHint(null, "Card number"))
        assertTrue(NoiseFilter.hasSensitiveHint("UPI PIN"))
        assertFalse(NoiseFilter.hasSensitiveHint("Message"))
    }

    @Test
    fun bankingPackagesAreAlwaysSensitive() {
        assertTrue(NoiseFilter.isSensitivePackage("com.icici.bank"))
        assertTrue(NoiseFilter.isSensitivePackage("com.google.android.apps.authenticator2"))
        assertFalse(NoiseFilter.isSensitivePackage("com.whatsapp"))
    }

    @Test
    fun passwordClassNamesAreDetected() {
        assertTrue(NoiseFilter.isSensitiveClassName("android.widget.NumberPasswordEditText"))
        assertFalse(NoiseFilter.isSensitiveClassName("android.widget.EditText"))
    }
}
