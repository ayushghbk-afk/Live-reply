package com.liveaireply.app.security

/**
 * Keeps secrets out of the diagnostics log.
 *
 * Applied to every log line before it is stored, not only to the API key: message text
 * is replaced by hashes unless debug logging is explicitly enabled, and anything that
 * looks like a key, card number or one-time code is masked even in debug mode.
 */
object LogRedactor {

    private val BEARER = Regex("(?i)(bearer\\s+)([A-Za-z0-9._\\-]+)")
    private val KEY_ASSIGNMENT = Regex(
        "(?i)\\b(api[_-]?key|apikey|authorization|token|secret|password|passwd|access[_-]?key)\\b" +
            "\\s*[:=]\\s*([\"']?)([^\\s\"',}]+)\\2"
    )
    private val LONG_KEY_LIKE = Regex("\\b(sk|pk|key|rk)-[A-Za-z0-9_\\-]{12,}\\b")
    private val CARD_NUMBER = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val OTP_CODE = Regex("(?i)\\b(code|otp|pin)\\b\\s*[:#]?\\s*\\d{3,8}\\b")

    fun redact(line: String): String {
        var out = BEARER.replace(line) { match ->
            match.groupValues[1] + mask(match.groupValues[2])
        }
        out = KEY_ASSIGNMENT.replace(out) { match ->
            val value = match.groupValues[3]
            // "Authorization: Bearer <token>" - the scheme word is not the secret.
            if (value.equals("bearer", ignoreCase = true)) {
                match.value
            } else {
                "${match.groupValues[1]}${if (match.groupValues[2].isNotEmpty()) "=" else ": "}" +
                    match.groupValues[2] + mask(value) + match.groupValues[2]
            }
        }
        out = LONG_KEY_LIKE.replace(out) { match -> mask(match.value) }
        out = OTP_CODE.replace(out) { match ->
            val parts = match.value.split(Regex("\\s+"))
            parts.dropLast(1).joinToString(" ") + " " + "•".repeat(parts.last().length.coerceAtMost(8))
        }
        out = CARD_NUMBER.replace(out) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length in 13..19 && digits.luhnValid()) {
                "•".repeat(digits.length - 4) + digits.takeLast(4)
            } else {
                match.value
            }
        }
        return out
    }

    /** Partially hides a secret, keeping enough to recognise which one it is. */
    fun mask(secret: String): String = when {
        secret.length <= 4 -> "•".repeat(secret.length)
        secret.length <= 10 -> secret.take(2) + "•".repeat(secret.length - 4) + secret.takeLast(2)
        else -> secret.take(4) + "•".repeat(8) + secret.takeLast(4)
    }

    /** Truncates message content for non-debug logs. */
    fun summariseMessage(text: String, debug: Boolean, maxChars: Int = 40): String {
        if (debug) return redact(text.take(400))
        val singleLine = text.replace('\n', ' ').trim()
        return if (singleLine.length <= maxChars) singleLine
        else singleLine.take(maxChars - 1) + "…"
    }

    private fun String.luhnValid(): Boolean {
        var sum = 0
        var double = false
        for (ch in reversed()) {
            val digit = ch.digitToIntOrNull() ?: return false
            val value = if (double) {
                val doubled = digit * 2
                if (doubled > 9) doubled - 9 else doubled
            } else digit
            sum += value
            double = !double
        }
        return sum % 10 == 0
    }
}
