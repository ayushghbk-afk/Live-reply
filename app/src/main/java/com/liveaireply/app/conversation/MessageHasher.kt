package com.liveaireply.app.conversation

import java.security.MessageDigest
import java.text.Normalizer

/**
 * Content hashing used for duplicate detection and loop prevention.
 *
 * Hashes are one-way (SHA-256) and are the only form of message content that is ever
 * persisted, which keeps raw conversation text out of storage unless the user
 * explicitly turns history logging on.
 */
object MessageHasher {

    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u00AD]")
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * Normalisation used before hashing so that trivially different renderings of the
     * same bubble ("Hello" vs "Hello " vs "Hello\u200B") collapse to one identity.
     */
    fun normalize(raw: String): String {
        var out = raw
        if (Normalizer.isNormalized(out, Normalizer.Form.NFC).not()) {
            out = Normalizer.normalize(out, Normalizer.Form.NFC)
        }
        out = ZERO_WIDTH.replace(out, "")
        out = out.replace('\u00A0', ' ')
        out = WHITESPACE_RUN.replace(out, " ")
        return out.trim()
    }

    fun hash(text: String): String = sha256Hex(normalize(text)).substring(0, 32)

    /** Direction-aware hash: the same words from "them" and from "you" differ. */
    fun structuralHash(text: String, direction: TurnDirection): String =
        sha256Hex("${direction.name}|${normalize(text)}").substring(0, 32)

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(HEX[(b.toInt() shr 4) and 0x0F])
            sb.append(HEX[b.toInt() and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** True when two raw strings are the same message after normalisation. */
    fun sameContent(a: String, b: String): Boolean = normalize(a) == normalize(b)
}
