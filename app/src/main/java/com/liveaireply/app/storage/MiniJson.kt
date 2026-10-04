package com.liveaireply.app.storage

/**
 * A tiny, dependency-free JSON reader/writer.
 *
 * Why this exists instead of kotlinx.serialization or org.json:
 *  - It has no compiler plugin and no Android-only classes, so the exact same code
 *    runs in plain JVM unit tests (app/src/test) and in the offline verifier
 *    (tools/jvm-verify) without Gradle, AGP or the Android SDK.
 *  - The app only needs two JSON shapes: OpenAI-compatible chat payloads and small
 *    local preference blobs. A dedicated codec removes a whole class of build/version
 *    risk from a project that must build on any machine.
 *
 * It is a strict RFC 8259 subset implementation: objects, arrays, strings (with the
 * full escape set including \uXXXX and surrogate pairs), numbers, true/false/null.
 * Malformed input throws [JsonParseException] rather than silently returning null.
 */
sealed class JsonValue {
    data class JStr(val value: String) : JsonValue()
    data class JNum(val value: Double) : JsonValue()
    data class JBool(val value: Boolean) : JsonValue()
    data object JNull : JsonValue()
    data class JArr(val items: List<JsonValue>) : JsonValue()
    data class JObj(val entries: Map<String, JsonValue>) : JsonValue()
}

class JsonParseException(message: String, val position: Int) :
    RuntimeException("$message (at offset $position)")

object Json {

    // ------------------------------------------------------------------ parsing

    fun parse(input: String): JsonValue {
        val p = Parser(input)
        p.skipWhitespace()
        val value = p.readValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonParseException("Trailing content after JSON value", p.pos)
        return value
    }

    /** Parses and returns null instead of throwing. Use for remote/user supplied data. */
    fun parseOrNull(input: String?): JsonValue? =
        if (input.isNullOrBlank()) null else runCatching { parse(input) }.getOrNull()

    private class Parser(private val src: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length) {
                val c = src[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else break
            }
        }

        fun readValue(): JsonValue {
            if (atEnd()) throw JsonParseException("Unexpected end of input", pos)
            val c = src[pos]
            return when {
                c == '{' -> readObject()
                c == '[' -> readArray()
                c == '"' -> JsonValue.JStr(readString())
                c == 't' -> readLiteral("true", JsonValue.JBool(true))
                c == 'f' -> readLiteral("false", JsonValue.JBool(false))
                c == 'n' -> readLiteral("null", JsonValue.JNull)
                c == '-' || c in '0'..'9' -> readNumber()
                else -> throw JsonParseException("Unexpected character '$c'", pos)
            }
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue {
            if (src.regionMatches(pos, word, 0, word.length)) {
                pos += word.length
                return value
            }
            throw JsonParseException("Expected '$word'", pos)
        }

        private fun readObject(): JsonValue.JObj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == '}') {
                pos++
                return JsonValue.JObj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                entries[key] = readValue()
                skipWhitespace()
                val c = peekOrThrow()
                when (c) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.JObj(entries)
                    }
                    else -> throw JsonParseException("Expected ',' or '}' but found '$c'", pos)
                }
            }
        }

        private fun readArray(): JsonValue.JArr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == ']') {
                pos++
                return JsonValue.JArr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                val c = peekOrThrow()
                when (c) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.JArr(items)
                    }
                    else -> throw JsonParseException("Expected ',' or ']' but found '$c'", pos)
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("Unterminated string", pos)
                val c = src[pos++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (atEnd()) throw JsonParseException("Unterminated escape", pos)
                val esc = src[pos++]
                when (esc) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> sb.append(readUnicodeEscape())
                    else -> throw JsonParseException("Invalid escape '\\$esc'", pos - 1)
                }
            }
        }

        private fun readUnicodeEscape(): Char {
            if (pos + 4 > src.length) throw JsonParseException("Truncated \\u escape", pos)
            val hex = src.substring(pos, pos + 4)
            val code = hex.toIntOrNull(16)
                ?: throw JsonParseException("Invalid \\u escape '\\u$hex'", pos)
            pos += 4
            return code.toChar()
        }

        private fun readNumber(): JsonValue.JNum {
            val start = pos
            if (!atEnd() && src[pos] == '-') pos++
            while (!atEnd() && src[pos] in '0'..'9') pos++
            if (!atEnd() && src[pos] == '.') {
                pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull()
                ?: throw JsonParseException("Malformed number '$text'", start)
            return JsonValue.JNum(value)
        }

        private fun peek(): Char? = if (atEnd()) null else src[pos]

        private fun peekOrThrow(): Char =
            peek() ?: throw JsonParseException("Unexpected end of input", pos)

        private fun expect(c: Char) {
            if (atEnd() || src[pos] != c) {
                throw JsonParseException("Expected '$c' but found '${peek() ?: "EOF"}'", pos)
            }
            pos++
        }
    }

    // ------------------------------------------------------------------ writing

    fun stringify(value: JsonValue, pretty: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: JsonValue, pretty: Boolean, depth: Int) {
        when (value) {
            is JsonValue.JNull -> sb.append("null")
            is JsonValue.JBool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.JNum -> sb.append(formatNumber(value.value))
            is JsonValue.JStr -> writeString(sb, value.value)
            is JsonValue.JArr -> {
                if (value.items.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    if (pretty) newline(sb, depth + 1)
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append(']')
            }
            is JsonValue.JObj -> {
                if (value.entries.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                var first = true
                for ((key, item) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, depth + 1)
                    writeString(sb, key)
                    sb.append(':')
                    if (pretty) sb.append(' ')
                    write(sb, item, pretty, depth + 1)
                }
                if (pretty) newline(sb, depth)
                sb.append('}')
            }
        }
    }

    private fun newline(sb: StringBuilder, depth: Int) {
        sb.append('\n')
        repeat(depth) { sb.append("  ") }
    }

    private fun formatNumber(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value &&
            value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()
        ) {
            asLong.toString()
        } else {
            value.toString()
        }
    }

    private fun writeString(sb: StringBuilder, text: String) {
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
    }
}

// ------------------------------------------------------------------- builders

fun jsonObj(vararg pairs: Pair<String, JsonValue?>): JsonValue.JObj {
    val entries = LinkedHashMap<String, JsonValue>()
    for ((key, value) in pairs) {
        if (value != null) entries[key] = value
    }
    return JsonValue.JObj(entries)
}

fun jsonArr(vararg items: JsonValue): JsonValue.JArr = JsonValue.JArr(items.toList())

fun jsonArr(items: List<JsonValue>): JsonValue.JArr = JsonValue.JArr(items)

fun String.toJson(): JsonValue.JStr = JsonValue.JStr(this)
fun Int.toJson(): JsonValue.JNum = JsonValue.JNum(this.toDouble())
fun Long.toJson(): JsonValue.JNum = JsonValue.JNum(this.toDouble())
fun Double.toJson(): JsonValue.JNum = JsonValue.JNum(this)
fun Boolean.toJson(): JsonValue.JBool = JsonValue.JBool(this)

val jsonNull: JsonValue = JsonValue.JNull

// ------------------------------------------------------------------ accessors

operator fun JsonValue.get(key: String): JsonValue? =
    (this as? JsonValue.JObj)?.entries?.get(key)

fun JsonValue.obj(key: String): JsonValue.JObj? = this[key] as? JsonValue.JObj

fun JsonValue.arr(key: String): List<JsonValue> =
    (this[key] as? JsonValue.JArr)?.items ?: emptyList()

fun JsonValue.str(key: String): String? = (this[key] as? JsonValue.JStr)?.value

fun JsonValue.str(key: String, default: String): String = str(key) ?: default

fun JsonValue.int(key: String, default: Int = 0): Int =
    (this[key] as? JsonValue.JNum)?.value?.toInt() ?: default

fun JsonValue.dbl(key: String, default: Double = 0.0): Double =
    (this[key] as? JsonValue.JNum)?.value ?: default

fun JsonValue.bool(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonValue.JBool)?.value ?: default

fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.JStr)?.value

fun JsonValue.asIntOrNull(): Int? = (this as? JsonValue.JNum)?.value?.toInt()

fun JsonValue.asObjectOrNull(): JsonValue.JObj? = this as? JsonValue.JObj

fun JsonValue.asArrayOrNull(): List<JsonValue>? = (this as? JsonValue.JArr)?.items
