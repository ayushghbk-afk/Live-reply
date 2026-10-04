package com.liveaireply.app.ai

/** Why a generated reply was rejected or downgraded. */
enum class ValidationIssue(val userMessage: String) {
    EMPTY("The model returned an empty reply."),
    BLANK_AFTER_SANITIZE("The model returned only formatting, no message."),
    TOO_LONG("The reply was longer than your configured maximum."),
    ONLY_PUNCTUATION("The reply had no readable words."),
    ECHOES_INCOMING("The reply just repeated the other person's message."),
    LEAKS_INTERNALS("The reply mentioned internal instructions or the system prompt."),
    MENTIONS_AI("The reply said it was an AI, which your settings forbid."),
    LOOKS_LIKE_ERROR("The reply looks like an API error message, not a chat message."),
    CONTAINS_TEMPLATE("The reply still contains a template placeholder.")
}

data class ValidationResult(
    val accepted: Boolean,
    val text: String,
    val issues: List<ValidationIssue>,
    /** True only when nothing about this reply makes automatic sending risky. */
    val autoSendAllowed: Boolean,
    val explanation: String
) {
    companion object {
        fun reject(text: String, issues: List<ValidationIssue>): ValidationResult =
            ValidationResult(
                accepted = false,
                text = text,
                issues = issues,
                autoSendAllowed = false,
                explanation = issues.joinToString(" ") { it.userMessage }
            )
    }
}

/**
 * Last line of defence before a reply reaches the chat.
 *
 * A rejected reply is never auto-sent; the engine shows it as an error and lets the
 * user regenerate.
 */
class ReplyValidator(
    private val forbidAiMentions: Boolean = true
) {

    fun validate(
        rawReply: String,
        incomingText: String,
        maxChars: Int,
        maxEmojis: Int = Int.MAX_VALUE
    ): ValidationResult {
        val issues = ArrayList<ValidationIssue>()
        val trimmedRaw = rawReply.trim()

        if (trimmedRaw.isEmpty()) {
            return ValidationResult.reject("", listOf(ValidationIssue.EMPTY))
        }

        var text = ReplySanitizer.sanitize(trimmedRaw, maxEmojis)
        if (text.isEmpty()) {
            return ValidationResult.reject(trimmedRaw, listOf(ValidationIssue.BLANK_AFTER_SANITIZE))
        }

        if (text.length > maxChars) {
            val clipped = ReplySanitizer.clipToLength(text, maxChars)
            if (clipped.isBlank()) {
                issues += ValidationIssue.TOO_LONG
            } else {
                text = clipped
            }
        }

        if (text.isBlank() || !text.any { it.isLetterOrDigit() }) {
            issues += ValidationIssue.ONLY_PUNCTUATION
        }

        if (looksLikeErrorMessage(text)) issues += ValidationIssue.LOOKS_LIKE_ERROR
        if (leaksInternals(text)) issues += ValidationIssue.LEAKS_INTERNALS
        if (forbidAiMentions && mentionsBeingAi(text)) issues += ValidationIssue.MENTIONS_AI
        if (containsTemplate(text)) issues += ValidationIssue.CONTAINS_TEMPLATE
        if (echoesIncoming(text, incomingText)) issues += ValidationIssue.ECHOES_INCOMING

        val accepted = issues.isEmpty()
        return ValidationResult(
            accepted = accepted,
            text = text,
            issues = issues,
            autoSendAllowed = accepted,
            explanation = if (accepted) "Reply passed validation"
            else issues.joinToString(" ") { it.userMessage }
        )
    }

    fun looksLikeErrorMessage(text: String): Boolean {
        val lowered = text.lowercase()
        return ERROR_MARKERS.any { lowered.startsWith(it) } ||
            (lowered.contains("rate limit") && lowered.contains("error")) ||
            (lowered.contains("invalid api key")) ||
            (lowered.startsWith("{") && lowered.endsWith("}")) ||
            (lowered.startsWith("<") && lowered.contains("error"))
    }

    fun leaksInternals(text: String): Boolean {
        val lowered = text.lowercase()
        return LEAK_MARKERS.any { lowered.contains(it) }
    }

    fun mentionsBeingAi(text: String): Boolean =
        AI_MARKERS.any { text.lowercase().contains(it) }

    fun containsTemplate(text: String): Boolean =
        text.contains("{{") || text.contains("}}") ||
            TEMPLATE_PLACEHOLDERS.any { text.contains(it, ignoreCase = true) }

    /** True when the "reply" is essentially the other person's message back at them. */
    fun echoesIncoming(reply: String, incoming: String): Boolean {
        val a = normaliseForComparison(reply)
        val b = normaliseForComparison(incoming)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length > 12 && b.length > 12 && (a.contains(b) || b.contains(a))
    }

    private fun normaliseForComparison(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    companion object {
        private val ERROR_MARKERS = listOf(
            "error:", "api error", "http 4", "http 5", "request failed",
            "rate limit", "internal server error", "bad gateway", "service unavailable"
        )
        private val LEAK_MARKERS = listOf(
            "system prompt", "as instructed", "my instructions", "the prompt",
            "developer message", "role: system", "you are a real-time conversation reply assistant"
        )
        private val AI_MARKERS = listOf(
            "as an ai", "i am an ai", "i'm an ai", "as a language model",
            "i am a language model", "i'm a language model", "as an artificial intelligence",
            "i cannot feel", "i don't have feelings because i am"
        )
        private val TEMPLATE_PLACEHOLDERS = listOf(
            "[insert", "[name]", "[your name]", "<reply>", "{reply}", "lorem ipsum"
        )
    }
}
