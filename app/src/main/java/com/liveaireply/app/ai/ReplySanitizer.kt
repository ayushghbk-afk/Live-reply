package com.liveaireply.app.ai

/**
 * Cleans model output into something a human would actually have typed.
 *
 * Models love to wrap a reply in quotes, add a "Reply:" prefix, or emit markdown fences.
 * Everything removed here is decoration, never content.
 */
object ReplySanitizer {

    private val FENCE = Regex("^```[a-zA-Z]*\\s*|\\s*```$")
    private val LEADING_LABEL = Regex(
        "^\\s*(reply|response|answer|message|me|you|assistant|ai|" +
            "[A-Z][A-Za-z]{1,20}(\\s[A-Z][A-Za-z]{1,20})?)\\s*[:\\-]\\s*",
        RegexOption.IGNORE_CASE
    )
    /**
     * Quote characters that must match in pairs, so “x” is unwrapped but "x' is left
     * alone. A back-reference regex cannot express “different opening and closing
     * characters belong together”, so the pairs are listed explicitly.
     */
    private val QUOTE_PAIRS = listOf(
        "\"" to "\"",
        "'" to "'",
        "`" to "`",
        "“" to "”",
        "‘" to "’",
        "«" to "»"
    )
    private val EMOJI = Regex(
        "[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}]"
    )

    /** Removes wrapper text; never changes the meaning of the reply. */
    fun sanitize(raw: String, maxEmojis: Int = Int.MAX_VALUE): String {
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // Strip markdown code fences.
        if (text.trimStart().startsWith("```")) {
            text = FENCE.replace(text.trim(), "").trim()
        }

        // Strip a single leading "Reply:" style label, repeatedly but bounded.
        var guard = 0
        while (guard < 3) {
            val replaced = LEADING_LABEL.replaceFirstMatch(text)
            if (replaced == text) break
            text = replaced
            guard++
        }

        text = text.trim()

        // Strip one layer of matching surrounding quotes.
        for ((openQuote, closeQuote) in QUOTE_PAIRS) {
            if (text.startsWith(openQuote) && text.endsWith(closeQuote) &&
                text.length > openQuote.length + closeQuote.length
            ) {
                val inner = text.substring(openQuote.length, text.length - closeQuote.length).trim()
                if (inner.isNotEmpty()) {
                    text = inner
                    break
                }
            }
        }

        // Collapse more than two consecutive blank lines.
        text = text.replace(Regex("\n{3,}"), "\n\n").trim()

        if (maxEmojis < Int.MAX_VALUE) text = limitEmojis(text, maxEmojis)

        return text.trim()
    }

    /**
     * Keeps the first [max] emoji and drops the rest.
     *
     * Iterates by code point: most emoji are surrogate pairs in UTF-16, so walking the
     * string char by char would split them and never match.
     */
    fun limitEmojis(text: String, max: Int): String {
        if (max < 0) return text
        val sb = StringBuilder()
        var seen = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val slice = text.substring(index, index + width)
            if (EMOJI.matches(slice)) {
                if (codePoint == 0xFE0F) {
                    // A variation selector belongs to the emoji before it.
                    if (sb.isNotEmpty()) {
                        index += width
                        continue
                    }
                } else {
                    seen++
                    if (seen > max) {
                        index += width
                        continue
                    }
                }
            }
            sb.append(slice)
            index += width
        }
        return sb.toString()
    }

    /**
     * Truncates to [maxChars], preferring a sentence or word boundary so the message
     * does not end mid-word.
     */
    fun clipToLength(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        val window = text.substring(0, maxChars)
        val sentenceEnd = maxOf(
            window.lastIndexOf('.'), window.lastIndexOf('!'), window.lastIndexOf('?'),
            window.lastIndexOf('\n')
        )
        if (sentenceEnd > maxChars * 0.5) return window.substring(0, sentenceEnd + 1).trim()
        val lastSpace = window.lastIndexOf(' ')
        if (lastSpace > maxChars * 0.5) return window.substring(0, lastSpace).trim()
        return window.trim()
    }

    private fun Regex.replaceFirstMatch(input: String): String {
        val match = find(input) ?: return input
        return input.removeRange(match.range)
    }
}
