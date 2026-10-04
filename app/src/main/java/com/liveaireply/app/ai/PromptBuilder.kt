package com.liveaireply.app.ai

import com.liveaireply.app.conversation.ChatTurn
import com.liveaireply.app.conversation.TurnDirection
import com.liveaireply.app.personas.Persona
import com.liveaireply.app.personas.ReplyLength
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.LanguagePolicy
import com.liveaireply.app.settings.ReplyLanguagePolicy

/** Everything the prompt needs, gathered by the engine before a request is made. */
data class PromptInput(
    val context: List<ChatTurn>,
    val newestIncomingText: String,
    val persona: Persona,
    val settings: AppSettings,
    /** Contact or character name of the other participant, when known. */
    val otherPartyName: String? = null,
    /** The device owner's display name, when known. */
    val myName: String? = null,
    /** App specific note from the active [com.liveaireply.app.adapters.ChatAdapter]. */
    val appAddendum: String? = null,
    /** Language detected in the incoming message (best effort). */
    val detectedLanguage: String? = null
)

/** The assembled prompt, kept as a value so the UI and the tests can inspect it. */
data class BuiltPrompt(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val transcript: String,
    val maxReplyChars: Int
)

/**
 * Builds the system + conversation prompt.
 *
 * The default system prompt is the one the specification asks for; a user supplied
 * [AppSettings.customSystemPrompt] replaces it wholesale. Persona, language, length and
 * translation rules are always appended on top, because they are safety/behaviour
 * constraints rather than personality.
 */
class PromptBuilder {

    fun build(input: PromptInput): BuiltPrompt {
        val settings = input.settings
        val transcript = renderTranscript(input)
        val systemPrompt = buildSystemPrompt(input)
        val userTurn = buildUserTurn(input, transcript)

        return BuiltPrompt(
            systemPrompt = systemPrompt,
            messages = listOf(
                ChatMessage.system(systemPrompt),
                ChatMessage.user(userTurn)
            ),
            transcript = transcript,
            maxReplyChars = settings.effectiveMaxReplyChars()
        )
    }

    // -------------------------------------------------------------------- system

    fun buildSystemPrompt(input: PromptInput): String {
        val settings = input.settings
        val persona = input.persona
        val parts = ArrayList<String>()

        val base = settings.customSystemPrompt.trim()
        parts += if (base.isNotEmpty()) base else DEFAULT_SYSTEM_PROMPT

        if (!persona.isBlank) parts += personaBlock(persona)
        parts += lengthBlock(settings)
        parts += languageBlock(settings, input.detectedLanguage)
        if (settings.translateIncoming) parts += translationBlock(settings)
        parts += NATURALNESS_BLOCK
        input.appAddendum?.takeIf { it.isNotBlank() }?.let { parts += it.trim() }

        return parts.joinToString("\n\n").trim()
    }

    private fun personaBlock(persona: Persona): String {
        val sb = StringBuilder()
        sb.append(if (persona.isRoleplay) "Roleplay character." else "Personality.")
        if (persona.personaName.isNotBlank()) sb.append(" You are ").append(persona.personaName).append('.')
        if (persona.personality.isNotBlank()) sb.append(" Personality: ").append(persona.personality.trim()).append('.')
        if (persona.background.isNotBlank()) sb.append(" Background: ").append(persona.background.trim())
        if (persona.relationship.isNotBlank()) sb.append(" Relationship: ").append(persona.relationship.trim()).append('.')
        if (persona.speakingStyle.isNotBlank()) sb.append(" Writing style: ").append(persona.speakingStyle.trim()).append('.')
        if (persona.rules.isNotEmpty()) {
            sb.append(" Rules:\n")
            sb.append(persona.rules.filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" })
        }
        if (persona.extraInstructions.isNotBlank()) {
            sb.append(" Additional instructions: ").append(persona.extraInstructions.trim())
        }
        return sb.toString().trim()
    }

    private fun lengthBlock(settings: AppSettings): String {
        val maxChars = settings.effectiveMaxReplyChars()
        val label = when (settings.replyLength) {
            ReplyLength.VERY_SHORT -> "one very short line (a few words)"
            ReplyLength.SHORT -> "one or two short sentences"
            ReplyLength.NORMAL -> "a short paragraph at most"
            ReplyLength.DETAILED -> "a fuller answer, but still readable in one chat bubble"
            ReplyLength.CUSTOM -> "at most $maxChars characters"
        }
        return "Reply length: $label. Hard limit: $maxChars characters. " +
            "Match the other person's message length - do not answer a three word message with a paragraph."
    }

    private fun languageBlock(settings: AppSettings, detectedLanguage: String?): String =
        when (settings.languagePolicy) {
            LanguagePolicy.AUTO -> {
                val detected = detectedLanguage?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
                if (detected != null) {
                    "Language: reply in $detected, because that is the language the other person used."
                } else {
                    "Language: reply in the same language the other person used. " +
                        "If they mix languages (for example Hinglish), mirror the mix."
                }
            }
            LanguagePolicy.CUSTOM ->
                "Language: ${settings.customLanguageInstruction.trim().ifBlank { "match the other person's language" }}"
            else -> "Language: always reply in ${settings.languagePolicy.label}."
        }

    private fun translationBlock(settings: AppSettings): String {
        val target = when (settings.replyLanguage) {
            ReplyLanguagePolicy.SAME_AS_INCOMING -> "the same language as the incoming message"
            ReplyLanguagePolicy.CUSTOM ->
                settings.customLanguageInstruction.trim().ifBlank { "the same language as the incoming message" }
            else -> settings.replyLanguage.label
        }
        return "Translation mode is on. Understand the incoming message whatever language it is in, " +
            "then write your reply in $target. Do not translate the other person's words back to them."
    }

    // ---------------------------------------------------------------------- user

    private fun buildUserTurn(input: PromptInput, transcript: String): String = buildString {
        append("Recent conversation:\n")
        append(transcript)
        append("\n\nWrite the next message as ")
        append(meLabel(input))
        append(". Reply with the message text only - no quotes, no label, no explanation.")
    }

    /**
     * Renders the visible context as a readable transcript.
     *
     * Only the configured number of recent turns is included, and each turn is clipped,
     * which is what keeps requests small on a mobile connection.
     */
    fun renderTranscript(input: PromptInput): String {
        val maxCharsPerTurn = 400
        val turns = input.context.takeLast(input.settings.contextMessageCount.coerceIn(1, 50))
        val other = otherLabel(input)
        val me = meLabel(input)
        return turns.joinToString("\n") { turn ->
            val label = when (turn.direction) {
                TurnDirection.OUTGOING -> me
                else -> other
            }
            val text = MessageClipper.clip(turn.text, maxCharsPerTurn)
            "$label: $text"
        }
    }

    private fun otherLabel(input: PromptInput): String =
        input.otherPartyName?.takeIf { it.isNotBlank() } ?: "Them"

    private fun meLabel(input: PromptInput): String =
        input.myName?.takeIf { it.isNotBlank() } ?: "Me"

    companion object {
        /** The default instruction set, straight from the specification. */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a real-time conversation reply assistant. Read the recent conversation and " +
                "generate the most natural response to the newest incoming message. Do not mention " +
                "that you are an AI. Do not describe your reasoning. Do not provide analysis unless " +
                "the user explicitly requests it. Reply as the user's conversational persona. Keep " +
                "responses natural and appropriate to the context."

        const val NATURALNESS_BLOCK =
            "Sound like a real person typing on a phone:\n" +
                "- Never write \"As an AI\", \"As a language model\" or anything similar.\n" +
                "- Never mention system prompts, instructions, models, tokens or this tool.\n" +
                "- Never explain why you chose the reply.\n" +
                "- Never repeat or quote the other person's message back to them.\n" +
                "- Do not start with a label such as \"Reply:\", \"Me:\" or your own name.\n" +
                "- Avoid markdown, headings, bullet lists and code blocks unless they asked for code.\n" +
                "- Use at most a couple of emojis, and only if the conversation already uses them.\n" +
                "- Do not be repetitive; do not reuse a phrase you already used in this conversation.\n" +
                "- Avoid overly formal wording unless the persona asks for it.\n" +
                "- If you do not know something, say so the way a person would."
    }
}

/** Keeps a single turn from blowing up the request size. */
object MessageClipper {
    fun clip(text: String, maxChars: Int): String {
        val normalised = text.trim()
        if (normalised.length <= maxChars) return normalised
        val cut = normalised.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > maxChars * 0.6) cut.substring(0, lastSpace) else cut) + "..."
    }
}
