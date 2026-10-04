package com.liveaireply.app.engine

import com.liveaireply.app.adapters.ChatAdapterRegistry
import com.liveaireply.app.adapters.SendTargetKind
import com.liveaireply.app.ai.ChatMessage
import com.liveaireply.app.ai.PromptBuilder
import com.liveaireply.app.ai.PromptInput
import com.liveaireply.app.ai.ReplyGenerationResult
import com.liveaireply.app.ai.ReplyPipeline
import com.liveaireply.app.ai.Sleeper
import com.liveaireply.app.automation.AutomationContext
import com.liveaireply.app.automation.AutomationController
import com.liveaireply.app.automation.AutomationDecision
import com.liveaireply.app.automation.AutomationGuard
import com.liveaireply.app.conversation.ChatTurn
import com.liveaireply.app.conversation.ConversationDetector
import com.liveaireply.app.conversation.ConversationSnapshot
import com.liveaireply.app.conversation.DetectionOutcome
import com.liveaireply.app.conversation.DetectionPolicy
import com.liveaireply.app.conversation.TurnDirection
import com.liveaireply.app.conversation.TurnSource
import com.liveaireply.app.personas.Persona
import com.liveaireply.app.security.SensitiveScreenVerdict
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import com.liveaireply.app.util.MillisClock

/** One accessibility snapshot plus the context the engine cannot derive on its own. */
data class SnapshotInput(
    val snapshot: ConversationSnapshot,
    val userIsTyping: Boolean = false,
    val sensitive: SensitiveScreenVerdict = SensitiveScreenVerdict.CLEAR,
    val otherPartyName: String? = null,
    val detectedLanguage: String? = null
)

/**
 * Facts the automation guard needs that come from the extraction step rather than from
 * settings. Passing them explicitly is what keeps [AutomationGuard] honest - none of
 * these values may be assumed.
 */
data class GenerationFacts(
    val detectionConfidence: Float,
    val usedOcr: Boolean,
    val ocrConfidence: Float,
    val sensitiveScreen: Boolean,
    val userIsTyping: Boolean,
    val replyPassedValidation: Boolean,
    val activePackage: String,
    val screenLabel: String?,
    val adapterIsWebChat: Boolean
)

/**
 * The assistant brain.
 *
 * Everything platform specific is injected ([AutomationController], [EngineHost],
 * [Cancellation], [Sleeper], [MillisClock]), which is why this class - the piece that
 * decides whether a message is answered, suggested or ignored - is fully covered by JVM
 * unit tests, including the AI-replies-to-itself loop scenario.
 *
 * Threading contract: [handleSnapshot] and [handleAction] may block (AI call, reply
 * delay). Call them from a background dispatcher, never from the UI thread or from an
 * accessibility event callback.
 */
class ReplyEngine(
    private val detector: ConversationDetector,
    private val pipeline: ReplyPipeline,
    private val automation: AutomationController,
    private val host: EngineHost,
    private val settingsProvider: () -> AppSettings,
    private val personaProvider: () -> Persona,
    private val conversationPauses: ConversationPauseController,
    private val promptBuilder: PromptBuilder = PromptBuilder(),
    private val guard: AutomationGuard = AutomationGuard(),
    private val adapters: ChatAdapterRegistry = ChatAdapterRegistry(),
    private val clock: MillisClock = MillisClock.SYSTEM,
    private val sleeper: Sleeper = Sleeper.REAL,
    private val cancellation: Cancellation = Cancellation.NEVER
) {

    @Volatile
    private var inFlight = false

    @Volatile
    private var running = false

    private var overlay: OverlayState = OverlayState()
    private var pendingSuggestion: PendingSuggestion? = null
    private var lastIncomingText: String? = null
    private var lastConversationId: String? = null
    private var lastFacts: GenerationFacts? = null

    /** What the overlay currently shows. */
    @Synchronized
    fun state(): OverlayState = overlay

    @Synchronized
    fun isRunning(): Boolean = running

    @Synchronized
    fun currentSuggestion(): PendingSuggestion? = pendingSuggestion

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun start(settings: AppSettings = settingsProvider()): EngineResult {
        running = true
        inFlight = false
        detector.resetAll()
        log(LogSeverity.INFO, "Assistant started in ${settings.mode.label} mode", "lifecycle")
        publish(
            overlay.copy(
                status = AssistantStatus.MONITORING,
                statusDetail = "Watching enabled apps",
                mode = settings.mode,
                monitoring = true,
                errorMessage = null
            )
        )
        host.onStatusChanged(AssistantStatus.MONITORING, "Watching enabled apps")
        return EngineResult.Nothing("Started")
    }

    /**
     * Emergency stop. Idempotent, immediate and sticky: nothing restarts until the user
     * calls [start] again.
     */
    @Synchronized
    fun stopAll(reason: String = "Stopped by user"): EngineResult {
        running = false
        inFlight = false
        pendingSuggestion = null
        log(LogSeverity.WARN, reason, "lifecycle")
        publish(
            OverlayState(
                status = AssistantStatus.STOPPED,
                statusDetail = reason,
                mode = settingsProvider().mode,
                monitoring = false,
                expanded = overlay.expanded
            )
        )
        host.onStatusChanged(AssistantStatus.STOPPED, reason)
        host.onEngineStopped()
        return EngineResult.Stopped
    }

    /** Pauses detection without tearing the service down. */
    @Synchronized
    fun pauseAll(reason: String = "Paused by user"): EngineResult {
        running = false
        inFlight = false
        pendingSuggestion = null
        log(LogSeverity.INFO, reason, "lifecycle")
        publish(
            overlay.copy(
                status = AssistantStatus.PAUSED,
                statusDetail = reason,
                monitoring = false,
                replyText = null,
                canSend = false
            )
        )
        host.onStatusChanged(AssistantStatus.PAUSED, reason)
        return EngineResult.Nothing(reason)
    }

    @Synchronized
    fun resume(): EngineResult = start(settingsProvider())

    // ------------------------------------------------------------------ detection

    fun handleSnapshot(input: SnapshotInput): EngineResult {
        val settings = settingsProvider()

        if (settings.emergencyStopped || !running) {
            return EngineResult.Nothing("Assistant is not running")
        }
        if (!settings.monitoringEnabled) {
            updateStatus(AssistantStatus.IDLE, "Monitoring is off")
            return EngineResult.Nothing("Monitoring is off")
        }

        val snapshot = input.snapshot
        if (input.sensitive.sensitive) {
            log(LogSeverity.INFO, "Skipped sensitive screen: ${input.sensitive.reason}", "privacy")
            updateStatus(AssistantStatus.MONITORING, "Looking away - sensitive screen")
            return EngineResult.Nothing(input.sensitive.reason ?: "Sensitive screen")
        }
        if (settings.isExcluded(snapshot.packageName)) {
            return EngineResult.Nothing("${snapshot.packageName} is excluded")
        }

        val conversationId = snapshot.conversationId
        val policy = DetectionPolicy(
            monitoringActive = true,
            appEnabled = settings.isPackageEnabled(snapshot.packageName),
            conversationPaused = conversationPauses.isPaused(conversationId),
            sensitiveScreen = false,
            userIsTyping = input.userIsTyping,
            generationInFlight = inFlight
        )

        val outcome = detector.process(snapshot, policy)
        lastConversationId = conversationId

        return when (outcome) {
            is DetectionOutcome.Ignored -> {
                log(LogSeverity.DEBUG, "Ignored: ${outcome.reason} (${outcome.detail})", "detect")
                if (overlay.hasSuggestion) {
                    updateStatus(AssistantStatus.REPLY_READY, "Reply ready")
                } else {
                    updateStatus(AssistantStatus.MONITORING, appLabel(snapshot.packageName, settings))
                }
                EngineResult.Nothing(outcome.detail)
            }

            is DetectionOutcome.OwnMessageSent -> {
                log(LogSeverity.INFO, "You sent a message; automation paused for this chat", "detect")
                lastIncomingText = null
                updateStatus(AssistantStatus.MONITORING, "You replied by hand")
                EngineResult.ManualReplyNoted(conversationId)
            }

            is DetectionOutcome.NewIncoming -> {
                lastIncomingText = outcome.turn.text
                log(
                    LogSeverity.INFO,
                    "New incoming message (${outcome.context.size} turns of context)",
                    "detect"
                )
                val adapter = adapters.forPackage(snapshot.packageName)
                lastFacts = GenerationFacts(
                    detectionConfidence = outcome.turn.confidence,
                    usedOcr = snapshot.source == TurnSource.OCR,
                    ocrConfidence = snapshot.extractionConfidence,
                    sensitiveScreen = false,
                    userIsTyping = input.userIsTyping,
                    replyPassedValidation = true,
                    activePackage = snapshot.packageName,
                    screenLabel = snapshot.screenLabel,
                    adapterIsWebChat = adapter.id == "browser"
                )
                generateForIncoming(outcome, input, settings, adapter.promptAddendum())
            }
        }
    }

    // ----------------------------------------------------------------- generation

    private fun generateForIncoming(
        incoming: DetectionOutcome.NewIncoming,
        input: SnapshotInput,
        settings: AppSettings,
        appAddendum: String?
    ): EngineResult {
        if (inFlight) return EngineResult.Nothing("A reply is already being generated")
        inFlight = true
        updateStatus(AssistantStatus.THINKING, "Reading the conversation")

        val prompt = promptBuilder.build(
            PromptInput(
                context = incoming.context,
                newestIncomingText = incoming.turn.text,
                persona = personaProvider(),
                settings = settings,
                otherPartyName = input.otherPartyName ?: automation.chatTitle(),
                myName = null,
                appAddendum = appAddendum,
                detectedLanguage = input.detectedLanguage
            )
        )

        log(
            LogSeverity.INFO,
            "Sending request to ${settings.primaryModel.ifBlank { "the configured model" }}",
            "ai"
        )

        val result = runCatching {
            pipeline.generate(prompt, settings) { attempt ->
                log(
                    LogSeverity.DEBUG,
                    "Model ${attempt.model} attempt ${attempt.attemptIndex + 1}: " +
                        (attempt.outcome::class.simpleName ?: "?"),
                    "ai"
                )
            }
        }.getOrElse { throwable ->
            inFlight = false
            val message = "AI request failed: ${throwable.message ?: throwable::class.simpleName}"
            log(LogSeverity.ERROR, message, "ai")
            host.onError("AI request failed", message)
            return EngineResult.AiFailed("AI request failed", message)
        }

        inFlight = false
        if (cancellation.isCancelled()) return EngineResult.Nothing("Cancelled")

        return when (result) {
            is ReplyGenerationResult.Invalid -> {
                val explanation = result.validation.explanation
                log(LogSeverity.WARN, "Reply rejected by validation: $explanation", "ai")
                publish(
                    overlay.copy(
                        status = AssistantStatus.ERROR,
                        statusDetail = "Reply rejected",
                        errorMessage = explanation,
                        replyText = null,
                        canSend = false,
                        latestIncomingText = incoming.turn.text
                    )
                )
                host.onError("The AI reply was not usable", explanation)
                EngineResult.InvalidReply(result.validation.text, explanation)
            }

            is ReplyGenerationResult.Failed -> {
                val headline = result.error.headline()
                log(LogSeverity.ERROR, "AI failed (${result.error.kind}): ${result.error.message}", "ai")
                publish(
                    overlay.copy(
                        status = AssistantStatus.ERROR,
                        statusDetail = headline,
                        errorMessage = result.error.message,
                        replyText = null,
                        canSend = false,
                        latestIncomingText = incoming.turn.text
                    )
                )
                host.onError(headline, result.error.message)
                EngineResult.AiFailed(headline, result.error.message)
            }

            is ReplyGenerationResult.Generated -> {
                log(LogSeverity.INFO, "Reply generated in ${result.latencyMs} ms", "ai")
                dispatchReply(result.text, result.model, result.latencyMs, incoming, settings)
            }
        }
    }

    private fun dispatchReply(
        reply: String,
        model: String,
        latencyMs: Long,
        incoming: DetectionOutcome.NewIncoming,
        settings: AppSettings
    ): EngineResult {
        val sendBlockedReason = incoming.autoSendBlockedReason
        val mode = settings.mode

        pendingSuggestion = PendingSuggestion(
            replyText = reply,
            incomingText = incoming.turn.text,
            conversationId = incoming.conversationId,
            messageHash = incoming.messageHash,
            model = model,
            latencyMs = latencyMs
        )

        if (mode != AssistantMode.AUTO || !incoming.autoSendAllowed) {
            showSuggestion(settings, model, latencyMs, incoming.turn.text, sendBlockedReason)
            return EngineResult.Suggested(reply, incoming.conversationId, incoming.turn.text, sendBlockedReason)
        }

        val facts = lastFacts ?: return EngineResult.Suggested(
            reply, incoming.conversationId, incoming.turn.text, "No extraction facts available"
        )

        val decision = evaluateAutomation(settings, reply, facts)
        if (decision is AutomationDecision.Refuse) {
            log(LogSeverity.WARN, "Auto send refused: ${decision.reason}", "automation")
            showSuggestion(settings, model, latencyMs, incoming.turn.text, decision.reason)
            return EngineResult.SuggestedInsteadOfAuto(reply, decision.reason)
        }

        val delayMs = settings.effectiveReplyDelayMs()
        if (delayMs > 0) {
            log(LogSeverity.DEBUG, "Waiting ${delayMs}ms before sending", "automation")
            sleeper.sleep(delayMs)
        }
        if (cancellation.isCancelled() || !running) {
            showSuggestion(settings, model, latencyMs, incoming.turn.text, "Stopped before sending")
            return EngineResult.SuggestedInsteadOfAuto(reply, "Stopped before sending")
        }

        // Re-check immediately before touching the other app: the user may have switched
        // windows or started typing during the delay.
        val recheck = evaluateAutomation(settings, reply, facts)
        if (recheck is AutomationDecision.Refuse) {
            showSuggestion(settings, model, latencyMs, incoming.turn.text, recheck.reason)
            return EngineResult.SuggestedInsteadOfAuto(reply, recheck.reason)
        }

        return performSend(reply, incoming.conversationId, settings, automatic = true)
    }

    private fun performSend(
        reply: String,
        conversationId: String,
        settings: AppSettings,
        automatic: Boolean
    ): EngineResult {
        // Register first: even if sending fails halfway, this text must never be
        // treated as a new incoming message later.
        detector.registerSelfReply(reply)

        val insert = automation.insertText(
            text = reply,
            simulateTyping = settings.simulateTyping,
            charsPerSecond = settings.typingSpeedCharsPerSecond.coerceIn(5, 200)
        )
        if (!insert.success) {
            log(LogSeverity.WARN, "Insert failed: ${insert.reason}", "automation")
            host.onError("Could not enter the reply", insert.reason)
            return EngineResult.AutomationFailed(reply, insert.reason)
        }
        log(LogSeverity.INFO, "Reply entered into the chat box", "automation")

        if (insert.verifiedText != null && !sameText(insert.verifiedText, reply)) {
            log(LogSeverity.WARN, "Inserted text differs from the reply; not sending", "automation")
            host.onError(
                "Could not verify the typed text",
                "The chat box does not contain what the AI wrote, so nothing was sent."
            )
            return EngineResult.AutomationFailed(reply, "Inserted text could not be verified")
        }

        val send = automation.sendComposer()
        if (!send.success) {
            log(LogSeverity.WARN, "Send failed: ${send.reason}", "automation")
            publish(
                overlay.copy(
                    status = AssistantStatus.REPLY_READY,
                    statusDetail = "Typed but not sent",
                    replyText = reply,
                    canSend = true,
                    sendBlockedReason = send.reason
                )
            )
            return EngineResult.AutomationFailed(reply, send.reason)
        }

        log(LogSeverity.INFO, "Reply sent${if (send.usedGesture) " (configured tap)" else ""}", "automation")
        pendingSuggestion = null
        publish(
            overlay.copy(
                status = AssistantStatus.MONITORING,
                statusDetail = "Reply sent",
                replyText = null,
                canSend = false,
                sendBlockedReason = null,
                errorMessage = null
            )
        )
        return if (automatic) EngineResult.AutoSent(reply, conversationId)
        else EngineResult.SentByUser(reply, conversationId)
    }

    private fun evaluateAutomation(
        settings: AppSettings,
        reply: String,
        facts: GenerationFacts
    ): AutomationDecision {
        val composerFound = automation.hasComposer()
        val sendKind = when {
            !composerFound -> SendTargetKind.UNAVAILABLE
            automation.usesGestureFallback() -> SendTargetKind.GESTURE_POINT
            else -> SendTargetKind.NODE_ACTION
        }
        val foreground = automation.foregroundPackage()
        val recipientKnown = !facts.screenLabel.isNullOrBlank() ||
            !automation.chatTitle().isNullOrBlank() ||
            (composerFound && !facts.adapterIsWebChat)

        return guard.decide(
            AutomationContext(
                settings = settings,
                composerFound = composerFound,
                composerConfidence = automation.composerConfidence(),
                sendTargetKind = sendKind,
                sendConfidence = automation.sendConfidence(),
                replyPassedValidation = facts.replyPassedValidation,
                replyNonEmpty = reply.isNotBlank(),
                sensitiveScreen = facts.sensitiveScreen,
                userIsTyping = facts.userIsTyping,
                activeAppChanged = foreground != null && foreground != facts.activePackage,
                recipientClear = recipientKnown,
                detectionConfidence = facts.detectionConfidence,
                ocrConfidence = facts.ocrConfidence,
                usedOcr = facts.usedOcr,
                conversationPaused = lastConversationId?.let { conversationPauses.isPaused(it) } ?: false,
                emergencyStopped = false
            )
        )
    }

    // -------------------------------------------------------------------- actions

    fun handleAction(action: OverlayAction, editedText: String? = null): EngineResult {
        val settings = settingsProvider()
        return when (action) {
            OverlayAction.STOP -> stopAll("Stopped from the overlay")
            OverlayAction.PAUSE_ALL -> pauseAll("Paused from the overlay")
            OverlayAction.RESUME, OverlayAction.START -> resume()

            OverlayAction.TOGGLE_EXPAND -> {
                publish(overlay.copy(expanded = !overlay.expanded))
                EngineResult.Nothing("Toggled overlay")
            }

            OverlayAction.COPY -> {
                val text = editedText ?: pendingSuggestion?.replyText
                if (text.isNullOrBlank()) {
                    EngineResult.Nothing("Nothing to copy")
                } else {
                    host.onCopyToClipboard(text)
                    log(LogSeverity.INFO, "Reply copied to clipboard", "overlay")
                    EngineResult.Nothing("Copied")
                }
            }

            OverlayAction.REJECT -> {
                pendingSuggestion = null
                publish(
                    overlay.copy(
                        status = AssistantStatus.MONITORING,
                        statusDetail = "Reply dismissed",
                        replyText = null,
                        replyEditable = false,
                        canSend = false,
                        errorMessage = null
                    )
                )
                EngineResult.Nothing("Rejected")
            }

            OverlayAction.EDIT -> {
                if (pendingSuggestion == null) {
                    EngineResult.Nothing("Nothing to edit")
                } else {
                    // The user's edits are never overwritten: while a suggestion is in
                    // edit mode nothing regenerates or re-types it.
                    publish(overlay.copy(replyEditable = true, expanded = true))
                    EngineResult.Nothing("Editing")
                }
            }

            OverlayAction.PAUSE_CHAT -> {
                val conversationId = lastConversationId
                if (conversationId.isNullOrBlank()) {
                    EngineResult.Nothing("No active chat")
                } else {
                    conversationPauses.pause(conversationId)
                    pendingSuggestion = null
                    log(LogSeverity.INFO, "Paused chat $conversationId", "privacy")
                    publish(
                        overlay.copy(
                            status = AssistantStatus.MONITORING,
                            statusDetail = "This chat is paused",
                            replyText = null,
                            canSend = false
                        )
                    )
                    EngineResult.Nothing("Chat paused")
                }
            }

            OverlayAction.REGENERATE -> regenerate(settings)

            OverlayAction.SEND -> {
                val suggestion = pendingSuggestion
                when {
                    suggestion == null -> EngineResult.Nothing("Nothing to send")
                    else -> {
                        val text = (editedText ?: suggestion.replyText).trim()
                        when {
                            text.isEmpty() -> EngineResult.Nothing("The reply is empty")
                            !automation.hasComposer() ->
                                EngineResult.AutomationFailed(text, "Could not find the chat input field")
                            else -> {
                                if (editedText != null) {
                                    pendingSuggestion = suggestion.copy(replyText = text)
                                }
                                // A user initiated send bypasses the AUTO guard, but never
                                // the "is there a composer" check above.
                                performSend(text, suggestion.conversationId, settings, automatic = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun regenerate(settings: AppSettings): EngineResult {
        val suggestion = pendingSuggestion ?: return EngineResult.Nothing("Nothing to regenerate")
        if (inFlight) return EngineResult.Nothing("Already generating")
        inFlight = true
        updateStatus(AssistantStatus.THINKING, "Regenerating")

        val adapter = adapters.forPackage(detector.state.activePackageName.orEmpty())
        val prompt = promptBuilder.build(
            PromptInput(
                context = detector.state.lastContext,
                newestIncomingText = suggestion.incomingText,
                persona = personaProvider(),
                settings = settings,
                otherPartyName = automation.chatTitle(),
                appAddendum = adapter.promptAddendum()
            )
        )
        // Ask for something different from what was already produced.
        val messages = prompt.messages +
            ChatMessage.assistant(suggestion.replyText) +
            ChatMessage.user("Write a different reply to the same message. Do not reuse the previous wording.")
        val revised = prompt.copy(messages = messages)

        val result = pipeline.generate(revised, settings)
        inFlight = false

        return when (result) {
            is ReplyGenerationResult.Generated -> {
                pendingSuggestion = suggestion.copy(replyText = result.text, model = result.model)
                publish(
                    overlay.copy(
                        status = AssistantStatus.REPLY_READY,
                        statusDetail = "Regenerated",
                        replyText = result.text,
                        errorMessage = null,
                        modelUsed = result.model,
                        latencyMs = result.latencyMs
                    )
                )
                EngineResult.Suggested(result.text, suggestion.conversationId, suggestion.incomingText, null)
            }

            is ReplyGenerationResult.Invalid -> {
                publish(overlay.copy(status = AssistantStatus.ERROR, errorMessage = result.validation.explanation))
                EngineResult.InvalidReply(result.validation.text, result.validation.explanation)
            }

            is ReplyGenerationResult.Failed -> {
                publish(overlay.copy(status = AssistantStatus.ERROR, errorMessage = result.error.message))
                host.onError(result.error.headline(), result.error.message)
                EngineResult.AiFailed(result.error.headline(), result.error.message)
            }
        }
    }

    /**
     * Built-in test console: runs the whole pipeline for a typed message without
     * touching another application.
     */
    fun runSimulation(incomingText: String, conversationLines: List<String> = emptyList()): EngineResult {
        val settings = settingsProvider()
        val now = clock.now()
        val context = conversationLines.mapIndexed { index, line ->
            ChatTurn(
                text = line,
                direction = if (index % 2 == 0) TurnDirection.INCOMING else TurnDirection.OUTGOING,
                source = TurnSource.SIMULATED,
                capturedAtMs = now
            )
        } + ChatTurn(
            text = incomingText,
            direction = TurnDirection.INCOMING,
            source = TurnSource.SIMULATED,
            capturedAtMs = now
        )

        val prompt = promptBuilder.build(
            PromptInput(
                context = context,
                newestIncomingText = incomingText,
                persona = personaProvider(),
                settings = settings
            )
        )
        updateStatus(AssistantStatus.THINKING, "Test request")
        return when (val result = pipeline.generate(prompt, settings)) {
            is ReplyGenerationResult.Generated -> {
                publish(
                    overlay.copy(
                        status = AssistantStatus.REPLY_READY,
                        statusDetail = "Test reply",
                        replyText = result.text,
                        latestIncomingText = incomingText,
                        modelUsed = result.model,
                        latencyMs = result.latencyMs,
                        errorMessage = null
                    )
                )
                EngineResult.Suggested(result.text, "simulation", incomingText, "Test mode")
            }

            is ReplyGenerationResult.Invalid ->
                EngineResult.InvalidReply(result.validation.text, result.validation.explanation)

            is ReplyGenerationResult.Failed -> {
                host.onError(result.error.headline(), result.error.message)
                EngineResult.AiFailed(result.error.headline(), result.error.message)
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun showSuggestion(
        settings: AppSettings,
        model: String,
        latencyMs: Long,
        incomingText: String,
        blockedReason: String?
    ) {
        val reply = pendingSuggestion?.replyText.orEmpty()
        val composerUsable = automation.hasComposer() &&
            automation.composerConfidence() >= AutomationGuard.MIN_COMPOSER_CONFIDENCE

        // In Approve mode the reply is staged in the composer so it can be read in the
        // real chat UI. Send is never pressed here.
        if (settings.mode == AssistantMode.APPROVE && composerUsable) {
            val insert = automation.insertText(
                text = reply,
                simulateTyping = false,
                charsPerSecond = settings.typingSpeedCharsPerSecond
            )
            if (insert.success) log(LogSeverity.INFO, "Reply staged in the chat box for review", "automation")
        }

        publish(
            overlay.copy(
                status = AssistantStatus.REPLY_READY,
                statusDetail = if (blockedReason != null) "Suggested only - $blockedReason" else "Reply ready",
                mode = settings.mode,
                replyText = reply,
                latestIncomingText = incomingText,
                canSend = automation.hasComposer(),
                sendBlockedReason = if (automation.hasComposer()) blockedReason else "Chat input field not found",
                modelUsed = model,
                latencyMs = latencyMs,
                errorMessage = null,
                conversationId = pendingSuggestion?.conversationId
            )
        )
    }

    private fun updateStatus(status: AssistantStatus, detail: String) {
        publish(overlay.copy(status = status, statusDetail = detail))
        host.onStatusChanged(status, detail)
    }

    private fun publish(state: OverlayState) {
        overlay = state
        host.onOverlayStateChanged(state)
    }

    private fun log(severity: LogSeverity, message: String, tag: String) {
        host.onLog(EngineLogEntry(clock.now(), severity, message, tag))
    }

    private fun appLabel(packageName: String, settings: AppSettings): String {
        val adapter = adapters.forPackage(packageName)
        val enabled = settings.isPackageEnabled(packageName)
        return "Watching ${adapter.displayName}" + if (enabled) "" else " (not enabled)"
    }

    private fun sameText(a: String, b: String): Boolean =
        a.replace("\u00A0", " ").trim() == b.replace("\u00A0", " ").trim()
}

/** A reply waiting for the user's decision. */
data class PendingSuggestion(
    val replyText: String,
    val incomingText: String,
    val conversationId: String,
    val messageHash: String,
    val model: String?,
    val latencyMs: Long
)
