package com.liveaireply.app.ai

/**
 * A callable language model backend.
 *
 * [complete] is intentionally blocking: the engine calls it from an IO dispatcher, and
 * blocking keeps the class trivially fakeable in tests. Do not call it on the main
 * thread.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val defaultBaseUrl: String

    fun complete(request: AiCompletionRequest): AiOutcome

    /** Model discovery ("Fetch models" button in AI settings). */
    fun listModels(): ModelListOutcome = ModelListOutcome.Failure(
        AiError(AiErrorKind.UNKNOWN, "This provider does not expose a model list")
    )
}
