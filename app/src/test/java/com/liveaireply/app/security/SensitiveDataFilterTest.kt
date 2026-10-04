package com.liveaireply.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataFilterTest {

    private val filter = SensitiveDataFilter()

    @Test
    fun `redacts every required sensitive category`() {
        val original = """
            password: hunter2
            OTP is 654321
            PIN = 7788
            card 4111 1111 1111 1111
            CVV: 123
            bank account number: 001234567890
            authentication code: AB12-CD34
        """.trimIndent()

        val result = filter.filter(original)

        assertFalse(result.text.contains("hunter2"))
        assertFalse(result.text.contains("654321"))
        assertFalse(result.text.contains("7788"))
        assertFalse(result.text.contains("4111 1111 1111 1111"))
        assertFalse(result.text.contains("123"))
        assertFalse(result.text.contains("001234567890"))
        assertFalse(result.text.contains("AB12-CD34"))
        assertTrue(result.detected.contains(SensitiveDataType.PASSWORD))
        assertTrue(result.detected.contains(SensitiveDataType.OTP))
        assertTrue(result.detected.contains(SensitiveDataType.PIN))
        assertTrue(result.detected.contains(SensitiveDataType.PAYMENT_CARD))
        assertTrue(result.detected.contains(SensitiveDataType.CVV))
        assertTrue(result.detected.contains(SensitiveDataType.BANK_ACCOUNT))
        assertTrue(result.detected.contains(SensitiveDataType.AUTHENTICATION_CODE))
    }

    @Test
    fun `redacts unlabeled short numeric authentication code conservatively`() {
        val result = filter.filter("Use 902144 to finish signing in")

        assertFalse(result.text.contains("902144"))
        assertTrue(result.text.contains(SensitiveDataType.AUTHENTICATION_CODE.placeholder))
    }

    @Test
    fun `redacts iban and non luhn card candidates`() {
        val result = filter.filter("IBAN GB82 WEST 1234 5698 7654 32, card 1234-5678-9012-3456")

        assertFalse(result.text.contains("GB82 WEST 1234 5698 7654 32"))
        assertFalse(result.text.contains("1234-5678-9012-3456"))
        assertTrue(result.detected.contains(SensitiveDataType.BANK_ACCOUNT))
        assertTrue(result.detected.contains(SensitiveDataType.PAYMENT_CARD))
    }

    @Test
    fun `ordinary conversation is unchanged`() {
        val text = "Are we still meeting near the station tomorrow morning?"
        assertEquals(text, filter.filter(text).text)
        assertFalse(filter.filter(text).changed)
    }
}
