package com.liveaireply.app.security

import com.liveaireply.app.conversation.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun masksBearerTokens() {
        val redacted = LogRedactor.redact("Authorization: Bearer sk-abcdef1234567890")
        assertFalse(redacted.contains("sk-abcdef1234567890"))
        assertTrue(redacted.contains("Bearer"))
    }

    @Test
    fun masksKeyAssignments() {
        val redacted = LogRedactor.redact("api_key=supersecretvalue123")
        assertFalse(redacted.contains("supersecretvalue123"))
        assertTrue(redacted.contains("api_key"))
    }

    @Test
    fun masksKeyLikeStrings() {
        val redacted = LogRedactor.redact("Using key sk-or-v1-9f8e7d6c5b4a3210")
        assertFalse(redacted.contains("sk-or-v1-9f8e7d6c5b4a3210"))
    }

    @Test
    fun masksValidCardNumbers() {
        val redacted = LogRedactor.redact("card 4111 1111 1111 1111 on file")
        assertFalse(redacted.contains("4111 1111 1111 1111"))
        assertTrue(redacted.contains("1111"))
    }

    @Test
    fun leavesOrdinaryNumbersAlone() {
        assertEquals("meeting at 12345 main street", LogRedactor.redact("meeting at 12345 main street"))
    }

    @Test
    fun summarisesMessageContentUnlessDebugIsOn() {
        val text = "This is a fairly long personal message about tomorrow evening"
        assertTrue(LogRedactor.summariseMessage(text, debug = false).length <= 40)
        assertEquals(text, LogRedactor.summariseMessage(text, debug = true))
    }
}

class SensitiveScreenPolicyTest {

    private val policy = SensitiveScreenPolicy()

    @Test
    fun clearScreensPassThrough() {
        val verdict = policy.evaluate(
            packageName = "com.whatsapp",
            activityName = "com.whatsapp.Conversation",
            root = TestFixtures.screen(listOf(TestFixtures.incoming("hello", 400), TestFixtures.composer())),
            excludedPackages = emptyList()
        )
        assertFalse(verdict.sensitive)
    }

    @Test
    fun bankingPackagesAreAlwaysSensitive() {
        assertTrue(policy.evaluate("com.hdfc.bank", null, null, emptyList()).sensitive)
    }

    @Test
    fun excludedPackagesAreSensitive() {
        val verdict = policy.evaluate("com.private.app", null, null, listOf("com.private.app"))
        assertTrue(verdict.sensitive)
        assertTrue(verdict.reason!!.contains("excluded"))
    }

    @Test
    fun passwordFieldsMakeTheScreenSensitive() {
        val password = TestFixtures.textNode(
            text = "", left = 100, top = 800, right = 900, bottom = 880,
            editable = true, password = true, className = "android.widget.NumberPasswordEditText"
        )
        val verdict = policy.evaluate(
            "com.some.app", "com.some.app.Main",
            TestFixtures.screen(listOf(password)), emptyList()
        )
        assertTrue(verdict.sensitive)
    }

    @Test
    fun otpAndCardHintsMakeTheScreenSensitive() {
        val otp = TestFixtures.textNode("Enter the OTP sent to your phone", 100, 800, 900, 880)
        assertTrue(
            policy.evaluate("com.some.app", null, TestFixtures.screen(listOf(otp)), emptyList()).sensitive
        )
    }

    @Test
    fun loginActivitiesAreSensitive() {
        assertTrue(policy.evaluate("com.some.app", "com.some.app.LoginActivity", null, emptyList()).sensitive)
    }
}
