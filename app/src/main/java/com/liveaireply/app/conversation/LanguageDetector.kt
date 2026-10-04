package com.liveaireply.app.conversation

/**
 * Best-effort language hint for the "reply in the same language" rule.
 *
 * This is deliberately a lightweight script + keyword heuristic, not a language
 * identification model: it is free, offline and fast, and it only needs to be right
 * often enough to pick the correct script. Anything it cannot place returns "unknown"
 * and the prompt falls back to "match whatever they used".
 */
object LanguageDetector {

    private data class LatinLanguage(val code: String, val name: String, val markers: List<String>)

    private val LATIN_LANGUAGES = listOf(
        LatinLanguage("es", "Spanish", listOf("hola", "que", "qué", "estás", "mañana", "gracias", "porque", "cuando", "cuándo", "vienes", "buenos días", "también")),
        LatinLanguage("fr", "French", listOf("bonjour", "ça", "être", "très", "demain", "merci", "pourquoi", "quand", "viens", "aussi", "s'il")),
        LatinLanguage("de", "German", listOf("hallo", "bist", "morgen", "danke", "warum", "wann", "kommst", "auch", "nicht", "wie")),
        LatinLanguage("pt", "Portuguese", listOf("olá", "você", "amanhã", "obrigado", "porque", "quando", "vem")),
        LatinLanguage("it", "Italian", listOf("ciao", "domani", "grazie", "perché", "quando", "vieni"))
    )

    private val HINGLISH_MARKERS = listOf(
        "kya", "kyun", "kyu", "hai", "ho", "hoon", "hun", "raha", "rahi", "kaise", "kaisa",
        "kal", "aa", "jaa", "acha", "accha", "theek", "thik", "nahi", "nahin", "mat", "bhai",
        "yaar", "yar", "karo", "karna", "kar", "bol", "bolo", "samjha", "samjhi", "dekho", "haan"
    )

    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val sample = text.take(400)

        if (sample.any { it.code in 0x0900..0x097F }) return "Hindi"
        if (sample.any { it.code in 0x0600..0x06FF }) return "Arabic"
        if (sample.any { it.code in 0x0400..0x04FF }) return "Russian"
        if (sample.any { it.code in 0x4E00..0x9FFF }) return "Chinese"
        if (sample.any { it.code in 0x3040..0x30FF }) return "Japanese"
        if (sample.any { it.code in 0xAC00..0xD7AF }) return "Korean"
        if (sample.any { it.code in 0x0E00..0x0E7F }) return "Thai"
        if (sample.any { it.code in 0x0590..0x05FF }) return "Hebrew"
        if (sample.any { it.code in 0x0370..0x03FF }) return "Greek"

        val words = sample.lowercase().split(Regex("[^\\p{L}\\p{M}'’]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val hinglishHits = words.count { HINGLISH_MARKERS.contains(it) }
        if (hinglishHits >= 2 || (words.size <= 4 && hinglishHits >= 1)) return "Hinglish"

        val best = LATIN_LANGUAGES.map { language ->
            language to words.count { word -> language.markers.any { word == it || word.startsWith(it) } }
        }.maxByOrNull { it.second }

        return if (best != null && best.second >= 2) best.first.name else null
    }
}
