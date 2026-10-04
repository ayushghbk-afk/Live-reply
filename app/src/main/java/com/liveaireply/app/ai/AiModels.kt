package com.liveaireply.app.ai

/** One message in an OpenAI-compatible chat payload. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) {
        SYSTEM("system"), USER("user"), ASSISTANT("assistant")
    }

    companion object {
        fun system(content: String) = ChatMessage(Role.SYSTEM, content)
        fun user(content: String) = ChatMessage(Role.USER, content)
        fun assistant(content: String) = ChatMessage(Role.ASSISTANT, content)
    }
}

data class AiCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.8f,
    val maxTokens: Int = 220,
    val timeoutMs: Long = 20_000L,
    /** Provider specific extras (top_p, presence_penalty, ...) - kept minimal on purpose. */
    val extra: Map<String, String> = emptyMap()
) {
    /** Rough request size in characters; used by the network-efficiency guard. */
    val payloadChars: Int get() = messages.sumOf { it.content.length }
}

enum class AiErrorKind {
    NOT_CONFIGURED,
    NO_NETWORK,
    TIMEOUT,
    RATE_LIMIT,
    AUTH,
    QUOTA,
    MODEL_UNAVAILABLE,
    SERVER,
    BAD_RESPONSE,
    CONTENT_FILTERED,
    CANCELLED,
    UNKNOWN;

    /** Worth trying the exact same model again. */
    val isTransient: Boolean
        get() = this == TIMEOUT || this == RATE_LIMIT || this == SERVER || this == NO_NETWORK

    /** Worth moving on to the next configured model. */
    val shouldTryNextModel: Boolean
        get() = this == RATE_LIMIT || this == TIMEOUT || this == SERVER ||
            this == MODEL_UNAVAILABLE || this == QUOTA || this == CONTENT_FILTERED

    /** A human sentence for the error screen / overlay. Never a stack trace. */
    fun userFacingAdvice(): String = when (this) {
        NOT_CONFIGURED -> "Add an API key in AI settings first."
        NO_NETWORK -> "No internet connection. AI reply unavailable."
        TIMEOUT -> "The model took too long to answer. Try again or pick a faster model."
        RATE_LIMIT -> "Rate limit reached. Try another model or wait a moment."
        AUTH -> "The API key was rejected. Check it in AI settings."
        QUOTA -> "Your API credits are used up or the plan does not allow this model."
        MODEL_UNAVAILABLE -> "That model is not available on this endpoint. Pick another one."
        SERVER -> "The AI service had a temporary error. It is worth one retry."
        BAD_RESPONSE -> "The model returned something unusable. Try again."
        CONTENT_FILTERED -> "The provider refused this message."
        CANCELLED -> "The request was cancelled."
        UNKNOWN -> "The request failed for an unexpected reason."
    }
}

data class AiError(
    val kind: AiErrorKind,
    val message: String,
    val httpStatus: Int? = null,
    val providerDetail: String? = null,
    val retryAfterMs: Long? = null
) {
    /** Shown to the user; deliberately short and free of technical noise. */
    fun headline(): String = kind.userFacingAdvice()
}

sealed interface AiOutcome {
    data class Success(
        val text: String,
        val model: String,
        val latencyMs: Long,
        val promptChars: Int
    ) : AiOutcome

    data class Failure(val error: AiError) : AiOutcome
}

/** A model discovered from the provider's /models endpoint. */
data class DiscoveredModel(
    val id: String,
    val name: String?,
    val contextLength: Int? = null
)

sealed interface ModelListOutcome {
    data class Success(val models: List<DiscoveredModel>) : ModelListOutcome
    data class Failure(val error: AiError) : ModelListOutcome
}
