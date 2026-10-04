package com.liveaireply.app.personas

/**
 * A saved character/persona.
 *
 * The same model covers the three things the spec asks for:
 *  - a plain "personality" (Friendly, Funny, Professional, ...) with [personaName] left
 *    blank - used for ordinary conversations;
 *  - a full roleplay character with name, background, relationship, style and rules;
 *  - a completely custom persona where the user writes [extraInstructions] themselves.
 */
data class Persona(
    val id: String,
    val label: String,
    val personaName: String = "",
    val personality: String = "",
    val background: String = "",
    val relationship: String = "",
    val speakingStyle: String = "",
    val rules: List<String> = emptyList(),
    val extraInstructions: String = "",
    val isRoleplay: Boolean = false,
    val builtIn: Boolean = false
) {
    val isBlank: Boolean
        get() = personaName.isBlank() && personality.isBlank() && background.isBlank() &&
            relationship.isBlank() && speakingStyle.isBlank() && rules.isEmpty() &&
            extraInstructions.isBlank()

    /** Deterministic id for user created personas. */
    companion object {
        fun newId(label: String): String =
            "persona-" + label.lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .ifBlank { "custom" } + "-" + System.currentTimeMillis().toString(36)
    }
}

/** Tone presets offered in the setup wizard and persona picker. */
enum class PersonalityStyle(val label: String) {
    FRIENDLY("Friendly"),
    CASUAL("Casual"),
    FUNNY("Funny"),
    ROMANTIC("Romantic"),
    FLIRTY("Flirty"),
    PROFESSIONAL("Professional"),
    SHORT("Short"),
    DETAILED("Detailed"),
    ROLEPLAY("Roleplay"),
    CUSTOM("Custom")
}

/** How long a reply should be. */
enum class ReplyLength(val label: String, val maxChars: Int, val targetWords: Int) {
    VERY_SHORT("Very short", 90, 10),
    SHORT("Short", 220, 25),
    NORMAL("Normal", 500, 55),
    DETAILED("Detailed", 1200, 130),
    /** Uses [com.liveaireply.app.settings.AppSettings.maxReplyChars] instead of maxChars. */
    CUSTOM("Custom", 0, 0)
}

/** Built-in personas that ship with the app. Users can edit or delete their own copies. */
object PersonaPresets {

    val FRIENDLY = Persona(
        id = "preset-friendly",
        label = "Friendly",
        personality = "warm, easy going, genuinely interested in the other person",
        speakingStyle = "everyday language, short sentences, occasional light emoji",
        rules = listOf("Never mention being an AI.", "Never explain why you chose the reply."),
        builtIn = true
    )

    val CASUAL = Persona(
        id = "preset-casual",
        label = "Casual",
        personality = "relaxed, informal, types the way people actually text",
        speakingStyle = "lowercase friendly, contractions, no formal phrasing",
        rules = listOf("Keep it brief.", "No corporate tone.", "Never mention being an AI."),
        builtIn = true
    )

    val FUNNY = Persona(
        id = "preset-funny",
        label = "Funny",
        personality = "witty, playful, quick with a light joke",
        speakingStyle = "short punchy lines, timing over length",
        rules = listOf("Do not force a joke into a serious conversation.", "Never mention being an AI."),
        builtIn = true
    )

    val ROMANTIC = Persona(
        id = "preset-romantic",
        label = "Romantic",
        personality = "affectionate, attentive, emotionally present",
        speakingStyle = "soft, warm, unhurried",
        rules = listOf("Stay respectful.", "Never mention being an AI.", "Match the other person's warmth."),
        builtIn = true
    )

    val FLIRTY = Persona(
        id = "preset-flirty",
        label = "Flirty",
        personality = "teasing, confident, complimentary without being crude",
        speakingStyle = "playful short lines",
        rules = listOf("Keep it tasteful and consensual in tone.", "Never mention being an AI."),
        builtIn = true
    )

    val PROFESSIONAL = Persona(
        id = "preset-professional",
        label = "Professional",
        personality = "clear, competent, polite",
        speakingStyle = "concise business language, no slang",
        rules = listOf("Never invent facts.", "Never mention being an AI."),
        builtIn = true
    )

    /** Example roleplay character, editable - shows the shape of a full persona. */
    val KAELEN = Persona(
        id = "preset-kaelen",
        label = "Kaelen (roleplay example)",
        personaName = "Kaelen",
        personality = "quiet, mysterious, emotionally guarded, poetic",
        background = "A night-cartographer who maps places that only exist after dark.",
        relationship = "slow-burn romance, building slowly and carefully",
        speakingStyle = "short poetic dialogue, restrained emotion, imagery over explanation",
        rules = listOf(
            "Never break character.",
            "Do not mention AI.",
            "Do not explain the roleplay.",
            "Respond naturally."
        ),
        isRoleplay = true,
        builtIn = true
    )

    val ALL: List<Persona> = listOf(FRIENDLY, CASUAL, FUNNY, ROMANTIC, FLIRTY, PROFESSIONAL, KAELEN)

    fun byId(id: String): Persona? = ALL.firstOrNull { it.id == id }

    fun default(): Persona = FRIENDLY
}

/** Storage contract; the Android implementation is backed by DataStore. */
interface PersonaRepository {
    fun all(): List<Persona>

    /** Returns null when the id is unknown - never silently substitutes another persona. */
    fun byId(id: String?): Persona?

    /** The persona currently in use. */
    fun selected(): Persona

    fun save(persona: Persona)
    fun delete(id: String)
    fun selectedId(): String
    fun select(id: String)
}

/** In-memory implementation used by tests and by the setup wizard's preview. */
class InMemoryPersonaRepository(
    initial: List<Persona> = PersonaPresets.ALL,
    private var selected: String = PersonaPresets.default().id
) : PersonaRepository {

    private val items = LinkedHashMap<String, Persona>(initial.associateBy { it.id })

    override fun all(): List<Persona> = items.values.toList()

    override fun byId(id: String?): Persona? = id?.let { items[it] }

    override fun selected(): Persona = items[selected] ?: PersonaPresets.default()

    override fun save(persona: Persona) {
        items[persona.id] = persona
    }

    override fun delete(id: String) {
        if (items[id]?.builtIn == true) return
        items.remove(id)
        if (selected == id) selected = PersonaPresets.default().id
    }

    override fun selectedId(): String = selected

    override fun select(id: String) {
        if (items.containsKey(id)) selected = id
    }
}
