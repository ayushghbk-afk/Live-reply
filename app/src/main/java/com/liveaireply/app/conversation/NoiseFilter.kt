package com.liveaireply.app.conversation

/**
 * Drops the chrome that surrounds real chat bubbles: timestamps, read receipts,
 * date headers, typing indicators, message counters and app toolbars.
 *
 * Without this the detector would happily treat "16:43" or "Delivered" as a new
 * incoming message - which is exactly the "do NOT assume every text change is a new
 * message" requirement.
 */
object NoiseFilter {

    private val TIME_ONLY = Regex("""^\s*\d{1,2}:\d{2}(:\d{2})?\s*(am|pm|a\.m\.|p\.m\.)?\s*$""", RegexOption.IGNORE_CASE)
    private val DATE_HEADER = Regex(
        """^\s*(today|yesterday|monday|tuesday|wednesday|thursday|friday|saturday|sunday|""" +
            """\d{1,2}[./-]\d{1,2}([./-]\d{2,4})?|""" +
            """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}(,?\s+\d{4})?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val RECEIPT = Regex("""^[\s\u2713\u2714\u2705\u2611\uFE0F\u2022]+$""")
    private val DELIVERY_WORD = Regex(
        """^\s*(sent|delivered|read|seen|unread|sending|failed to send|""" +
            """message not sent|tap to retry|\d+\s+unread messages?|\d+\s+new messages?|""" +
            """missed (voice )?call|call ended|you missed a call)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val TYPING_INDICATOR = Regex(
        """^\s*(typing\.{0,3}|\.{1,3}|is typing\.{0,3}|online|last seen .*|active now)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val ENCRYPTION_BANNER = Regex(
        """^\s*(messages and calls are end-to-end encrypted|""" +
            """end-to-end encrypted|secure messages|chats are encrypted|""" +
            """your personal messages are end-to-end encrypted)\b.*$""",
        RegexOption.IGNORE_CASE
    )
    private val CONTACT_HEADER = Regex("""^\s*(\+?\d[\d\s\-()]{6,})$""")

    /** Words that mark a screen as sensitive in titles, hints and content descriptions. */
    val SENSITIVE_HINTS = listOf(
        "password", "passwd", "passcode", "pin", "otp", "one time", "one-time",
        "verification code", "security code", "credit card", "debit card", "card number",
        "cvv", "cvc", "expiry", "bank", "iban", "swift", "account number", "upi pin",
        "mpin", "secret key", "private key", "recovery phrase", "seed phrase",
        "authentication", "two-factor", "2fa", "biometric", "fingerprint",
        "net banking", "payment", "wallet balance", "aadhaar", "ssn", "social security"
    )

    /** Packages that are always treated as sensitive regardless of what is on screen. */
    val ALWAYS_SENSITIVE_PACKAGE_SUFFIXES = listOf(
        "bank", "banking", "wallet", "payment", "pay.", ".pay", "money", "invest",
        "broker", "trading", "crypto", "authenticator", "password", "keyguard"
    )

    fun isNoise(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return TIME_ONLY.matches(t) ||
            DATE_HEADER.matches(t) ||
            RECEIPT.matches(t) ||
            DELIVERY_WORD.matches(t) ||
            TYPING_INDICATOR.matches(t) ||
            ENCRYPTION_BANNER.matches(t) ||
            CONTACT_HEADER.matches(t)
    }

    fun looksLikeSystemBanner(text: String): Boolean = ENCRYPTION_BANNER.matches(text.trim())

    /** True when a node's text/hint/description advertises secret input. */
    fun hasSensitiveHint(vararg values: String?): Boolean {
        for (value in values) {
            if (value.isNullOrBlank()) continue
            val lowered = value.lowercase()
            if (SENSITIVE_HINTS.any { lowered.contains(it) }) return true
        }
        return false
    }

    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lowered = packageName.lowercase()
        return ALWAYS_SENSITIVE_PACKAGE_SUFFIXES.any { lowered.contains(it) }
    }

    /** Android sets this on password/PIN fields; treat the whole screen as off limits. */
    fun isSensitiveClassName(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        val lowered = className.lowercase()
        return lowered.contains("passwordedittext") || lowered.contains("numberpassword")
    }
}
