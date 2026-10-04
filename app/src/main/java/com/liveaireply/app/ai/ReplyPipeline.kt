package com.liveaireply.app.ai

import com.liveaireply.app.settings.AppSettings

/** Injectable delay so retry/backoff can be tested without waiting. */
interface Sleeper {
    fun sleep(millis: Long)

    companion object {
        val REAL: Sleeper = object : Sleeper {
            override fun sleep(millis: Long) {
                if (millis > 0) runCatching { Thread.sleep(millis) }
            }
        }

        /** Records the delays instead of sleeping. */
        fun recording(): RecordingSleeper = RecordingSleeper()
    }
}

class RecordingSleeper : Sleeper {
    val delays = ArrayList<Long>()
    override fun sleep(millis: Long) {
        delays += millis
    }
}

/** Exponential backoff with a ceiling, honouring a provider's retry-after hint. */
data class RetryPolicy(
    val maxAttemptsPerModel: Int = 2,
    val baseDelayMs: Long = 700L,
    val maxDelayMs: Long = 4_000L
) {
    fun delayFor(attemptIndex: Int, retryAfterMs: Long?): Long {
        retryAfterMs?.let { return it.coerceIn(0L, maxDelayMs) }
        var delay = baseDelayMs
        repeat(attemptIndex) { delay *= 2 }
        return delay.coerceIn(0L, maxDelayMs)
    }

    companion object {
        fun fromSettings(settings: AppSettings): RetryPolicy =
            RetryPolicy(maxAttemptsPerModel = (settings.retryCount + 1).coerceIn(1, 4))
    }
}

data class ModelAttempt(
    val model: String,
    val attemptIndex: Int,
    val outcome: AiOutcome
)

sealed interface ReplyGenerationResult {
    data class Generated(
        val text: String,
        val model: String,
        val latencyMs: Long,
        val prompt: BuiltPrompt,
        val attempts: List<ModelAttempt>
    ) : ReplyGenerationResult

    /** The model answered, but the answer must not be sent. */
    data class Invalid(
        val validation: ValidationResult,
        val model: String,
        val prompt: BuiltPrompt,
        val attempts: List<ModelAttempt>
    ) : ReplyGenerationResult

    data class Failed(
        val error: AiError,
        val prompt: BuiltPrompt,
        val attempts: List<ModelAttempt>
    ) : ReplyGenerationResult
}

/**
 * Turns a [BuiltPrompt] into a validated reply.
 *
 * Implements the resilience rules from the specification:
 *  - transient failures (timeout, 5xx, rate limit) are retried a bounded number of
 *    times with backoff, never indefinitely;
 *  - a model-specific failure moves on to the next configured fallback model;
 *  - an invalid API key stops immediately instead of burning through every fallback;
 *  - no internet stops immediately and reports a single clear message;
 *  - a reply that fails validation is returned as [ReplyGenerationResult.Invalid] so
 *    the UI can show it and offer Regenerate, and it is never auto-sent.
 */
class ReplyPipeline(
    private val provider: AiProvider,
    private val validator: ReplyValidator = ReplyValidator(),
    private val sleeper: Sleeper = Sleeper.REAL,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {

    fun generate(
        prompt: BuiltPrompt,
        settings: AppSettings,
        onAttempt: (ModelAttempt) -> Unit = {}
    ): ReplyGenerationResult {
        val models = settings.modelChain()
        if (models.isEmpty()) {
            return ReplyGenerationResult.Failed(
                AiError(AiErrorKind.NOT_CONFIGURED, "No model configured"),
                prompt,
                emptyList()
            )
        }

        val attempts = ArrayList<ModelAttempt>()
        var lastError = AiError(AiErrorKind.UNKNOWN, "No model produced a reply")

        models.forEachIndexed { modelIndex, model ->
            var attemptIndex = 0
            while (true) {
                val request = AiCompletionRequest(
                    model = model,
                    messages = prompt.messages,
                    temperature = settings.temperature.coerceIn(0f, 2f),
                    maxTokens = settings.effectiveMaxTokens(),
                    timeoutMs = settings.timeoutMs.coerceIn(2_000L, 180_000L)
                )
                val outcome = provider.complete(request)
                val attempt = ModelAttempt(model, attemptIndex, outcome)
                attempts += attempt
                onAttempt(attempt)

                if (outcome is AiOutcome.Success) {
                    val validation = validator.validate(
                        rawReply = outcome.text,
                        incomingText = prompt.transcript.substringAfterLast("Them:", "").trim(),
                        maxChars = prompt.maxReplyChars,
                        maxEmojis = settings.maxEmojisPerReply
                    )
                    return if (validation.accepted) {
                        ReplyGenerationResult.Generated(
                            text = validation.text,
                            model = outcome.model,
                            latencyMs = outcome.latencyMs,
                            prompt = prompt,
                            attempts = attempts
                        )
                    } else {
                        ReplyGenerationResult.Invalid(validation, outcome.model, prompt, attempts)
                    }
                }

                val error = (outcome as AiOutcome.Failure).error
                lastError = error

                // Never hammer a device that is offline, and never burn every fallback
                // model on a credential problem.
                if (error.kind == AiErrorKind.NO_NETWORK ||
                    error.kind == AiErrorKind.NOT_CONFIGURED ||
                    error.kind == AiErrorKind.AUTH
                ) {
                    return ReplyGenerationResult.Failed(error, prompt, attempts)
                }

                val canRetrySameModel = error.kind.isTransient &&
                    attemptIndex + 1 < retryPolicy.maxAttemptsPerModel
                if (canRetrySameModel) {
                    sleeper.sleep(retryPolicy.delayFor(attemptIndex, error.retryAfterMs))
                    attemptIndex++
                    continue
                }

                val hasNextModel = modelIndex + 1 < models.size
                if (error.kind.shouldTryNextModel && hasNextModel) break
                if (!hasNextModel) return ReplyGenerationResult.Failed(error, prompt, attempts)
                break
            }
        }

        return ReplyGenerationResult.Failed(lastError, prompt, attempts)
    }
}
