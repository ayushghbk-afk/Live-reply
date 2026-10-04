package com.liveaireply.app.ai

import com.liveaireply.app.security.SensitiveDataFilter
import com.liveaireply.app.security.SensitiveDataType

/**
 * Last outbound boundary before an AI provider.
 *
 * Every message is filtered here immediately before [delegate] serialises or transmits it.
 * Keeping this as an [AiProvider] decorator means normal generation, regeneration, test
 * mode, and future call sites all receive the same protection. Model discovery contains no
 * conversation text and is passed through unchanged.
 */
class PrivacyFilteringAiProvider(
    private val delegate: AiProvider,
    private val filter: SensitiveDataFilter = SensitiveDataFilter(),
    private val onFiltered: (Set<SensitiveDataType>) -> Unit = {}
) : AiProvider {

    override val id: String get() = delegate.id
    override val displayName: String get() = delegate.displayName
    override val defaultBaseUrl: String get() = delegate.defaultBaseUrl

    override fun complete(request: AiCompletionRequest): AiOutcome {
        val detected = linkedSetOf<SensitiveDataType>()
        val safeMessages = request.messages.map { message ->
            val result = filter.filter(message.content)
            detected += result.detected
            if (result.changed) message.copy(content = result.text) else message
        }
        if (detected.isNotEmpty()) onFiltered(detected)
        return delegate.complete(request.copy(messages = safeMessages))
    }

    override fun listModels(): ModelListOutcome = delegate.listModels()
}
