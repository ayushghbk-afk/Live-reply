package com.liveaireply.app.security

/** Categories of confidential text that must not be sent to an AI provider. */
enum class SensitiveDataType(val placeholder: String) {
    OTP("[REDACTED OTP]"),
    PASSWORD("[REDACTED PASSWORD]"),
    PIN("[REDACTED PIN]"),
    PAYMENT_CARD("[REDACTED CARD NUMBER]"),
    CVV("[REDACTED CVV]"),
    BANK_ACCOUNT("[REDACTED BANK ACCOUNT]"),
    AUTHENTICATION_CODE("[REDACTED AUTHENTICATION CODE]")
}

data class SensitiveFilterResult(
    val text: String,
    val detected: Set<SensitiveDataType>
) {
    val changed: Boolean get() = detected.isNotEmpty()
}

/**
 * Redacts secret values before conversation text reaches an AI provider.
 *
 * This filter is intentionally conservative. A standalone 4-8 digit value is treated as
 * a possible PIN/OTP even without a label. That can occasionally redact an ordinary short
 * number, but sending a real authentication secret is the more serious failure.
 *
 * Labels are retained where possible so a model still understands the conversation, while
 * the value itself is replaced with a stable, non-secret placeholder. The filter never logs
 * or returns the original value separately.
 */
class SensitiveDataFilter {

    fun filter(input: String): SensitiveFilterResult {
        var output = input
        val detected = linkedSetOf<SensitiveDataType>()

        fun replace(type: SensitiveDataType, regex: Regex, replacement: (MatchResult) -> String) {
            output = regex.replace(output) { match ->
                detected += type
                replacement(match)
            }
        }

        // Labelled values run first so a six-digit OTP gets the specific OTP placeholder
        // rather than the generic possible-PIN placeholder below.
        replace(SensitiveDataType.PASSWORD, PASSWORD) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.PASSWORD.placeholder}"
        }
        replace(SensitiveDataType.CVV, CVV) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.CVV.placeholder}"
        }
        replace(SensitiveDataType.OTP, OTP) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.OTP.placeholder}"
        }
        replace(SensitiveDataType.PIN, PIN) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.PIN.placeholder}"
        }
        replace(SensitiveDataType.AUTHENTICATION_CODE, AUTH_CODE) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.AUTHENTICATION_CODE.placeholder}"
        }
        replace(SensitiveDataType.BANK_ACCOUNT, BANK_ACCOUNT) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}${SensitiveDataType.BANK_ACCOUNT.placeholder}"
        }
        replace(SensitiveDataType.BANK_ACCOUNT, IBAN) {
            SensitiveDataType.BANK_ACCOUNT.placeholder
        }

        // Card-like digit runs may contain spaces or dashes. We redact all 13-19 digit
        // candidates rather than relying only on Luhn: test cards and mistyped cards are
        // still payment information and must not leave the device.
        replace(SensitiveDataType.PAYMENT_CARD, CARD_NUMBER) {
            SensitiveDataType.PAYMENT_CARD.placeholder
        }

        // Last line of defence for unlabeled OTP/PIN/authentication values.
        replace(SensitiveDataType.AUTHENTICATION_CODE, STANDALONE_SHORT_CODE) {
            SensitiveDataType.AUTHENTICATION_CODE.placeholder
        }

        return SensitiveFilterResult(output, detected)
    }

    fun containsSensitiveData(input: String): Boolean = filter(input).changed

    companion object {
        private const val LABEL_SEPARATOR = "(\\s*(?:is|was|=|:|#|-)\\s*)"

        private val PASSWORD = Regex(
            "(?i)\\b(password|passwd|passphrase|pass[- ]?word|pwd)\\b" +
                LABEL_SEPARATOR +
                "([^\\s,;]{3,128})"
        )

        private val OTP = Regex(
            "(?i)\\b(otp|one[- ]?time(?:\\s+password|\\s+passcode|\\s+code)?|temporary code)\\b" +
                LABEL_SEPARATOR +
                "([A-Z0-9-]{3,16})"
        )

        private val PIN = Regex(
            "(?i)\\b(pin|mpin|upi pin|passcode)\\b" +
                LABEL_SEPARATOR +
                "([0-9]{3,12})"
        )

        private val CVV = Regex(
            "(?i)\\b(cvv2?|cvc2?|card security code)\\b" +
                LABEL_SEPARATOR +
                "([0-9]{3,4})"
        )

        private val AUTH_CODE = Regex(
            "(?i)\\b(authentication code|auth code|verification code|security code|" +
                "login code|sign[- ]?in code|2fa code|two[- ]?factor code|recovery code)\\b" +
                LABEL_SEPARATOR +
                "([A-Z0-9-]{3,24})"
        )

        private val BANK_ACCOUNT = Regex(
            "(?i)\\b(bank account(?: number)?|account number|account no\\.?|a/c(?: no\\.?)?|" +
                "routing number|sort code|swift|bic)\\b" +
                LABEL_SEPARATOR +
                "([A-Z0-9 -]{4,40})"
        )

        private val IBAN = Regex(
            "(?i)\\b[A-Z]{2}[0-9]{2}(?:[ ]?[A-Z0-9]){11,30}\\b"
        )

        private val CARD_NUMBER = Regex(
            "(?<![A-Za-z0-9])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9])"
        )

        private val STANDALONE_SHORT_CODE = Regex(
            "(?<![A-Za-z0-9])(?:[0-9][ -]?){3,7}[0-9](?![A-Za-z0-9])"
        )
    }
}
