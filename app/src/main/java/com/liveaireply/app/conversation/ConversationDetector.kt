package com.liveaireply.app.conversation

import com.liveaireply.app.util.MillisClock

/** Tuning for [ConversationDetector]. All values are user configurable. */
data class DetectorConfig(
    val minIncomingTextLength: Int = 2,
    val maxTurnTextLength: Int = 4000,
    /** Below this confidence the app suggests but never auto-sends. */
    val minIncomingConfidence: Float = 0.30f,
    val contextMessageCount: Int = 10,
    /** After the human types by hand, automatic sending stays off for this long. */
    val manualReplyCooldownMs: Long = 120_000L
)

/** Everything outside the detector that can veto processing. Recomputed per snapshot. */
data class DetectionPolicy(
    val monitoringActive: Boolean = true,
    val appEnabled: Boolean = true,
    val conversationPaused: Boolean = false,
    val sensitiveScreen: Boolean = false,
    val userIsTyping: Boolean = false,
    /** True while a reply for this conversation is already being generated. */
    val generationInFlight: Boolean = false
) {
    companion object {
        val ALLOW_ALL = DetectionPolicy()
    }
}

enum class IgnoreReason {
    MONITORING_PAUSED,
    APP_NOT_ENABLED,
    CONVERSATION_PAUSED,
    SENSITIVE_SCREEN,
    EMPTY_SNAPSHOT,
    CONVERSATION_CHANGED,
    FIRST_OBSERVATION,
    SCROLL_OR_REPEAT,
    DUPLICATE,
    SELF_REPLY_ECHO,
    SYSTEM_NOTICE,
    DIRECTION_UNKNOWN,
    LOW_CONFIDENCE,
    USER_TYPING,
    GENERATION_IN_FLIGHT,
    TEXT_TOO_SHORT,
    NO_CANDIDATE_TURNS
}

sealed interface DetectionOutcome {

    /** A new message from the other person that the assistant may answer. */
    data class NewIncoming(
        val turn: ChatTurn,
        val context: List<ChatTurn>,
        val conversationId: String,
        val messageHash: String,
        /** False when a human-override rule says "suggest only, do not auto send". */
        val autoSendAllowed: Boolean,
        val autoSendBlockedReason: String?
    ) : DetectionOutcome

    /** The device owner sent a message by hand - conversation state is updated. */
    data class OwnMessageSent(
        val turn: ChatTurn,
        val conversationId: String,
        val messageHash: String
    ) : DetectionOutcome

    data class Ignored(
        val reason: IgnoreReason,
        val detail: String,
        val conversationId: String? = null
    ) : DetectionOutcome
}

/** Mutable per-conversation memory held by the detector. */
data class ConversationState(
    var activePackageName: String? = null,
    var conversationId: String? = null,
    var lastProcessedMessageHash: String? = null,
    var lastIncomingMessageText: String? = null,
    var lastReplyHash: String? = null,
    var lastOwnMessageAtMs: Long = 0L,
    var lastSnapshotAtMs: Long = 0L,
    var observationSeeded: Boolean = false,
    var lastContext: List<ChatTurn> = emptyList()
)

/**
 * The heart of "did something new actually arrive?".
 *
 * It is a pure state machine over immutable snapshots: no Android types, no threads,
 * no coroutines, so every rule in the specification (scrolling, repeats, own
 * messages, self-echo, chat switching, low confidence) is covered by plain unit tests.
 */
class ConversationDetector(
    private val duplicateGuard: DuplicateGuard = DuplicateGuard(),
    private val clock: MillisClock = MillisClock.SYSTEM,
    var config: DetectorConfig = DetectorConfig()
) {
    var state: ConversationState = ConversationState()
        private set

    fun process(snapshot: ConversationSnapshot, policy: DetectionPolicy = DetectionPolicy.ALLOW_ALL): DetectionOutcome {
        val conversationId = snapshot.conversationId

        if (!policy.monitoringActive) {
            return DetectionOutcome.Ignored(IgnoreReason.MONITORING_PAUSED, "Monitoring is paused", conversationId)
        }
        if (snapshot.sensitiveScreen || policy.sensitiveScreen) {
            return DetectionOutcome.Ignored(IgnoreReason.SENSITIVE_SCREEN, "Excluded or sensitive screen", conversationId)
        }
        if (!policy.appEnabled) {
            return DetectionOutcome.Ignored(IgnoreReason.APP_NOT_ENABLED, "${snapshot.packageName} is not enabled", conversationId)
        }
        if (policy.conversationPaused) {
            return DetectionOutcome.Ignored(IgnoreReason.CONVERSATION_PAUSED, "This chat is paused", conversationId)
        }
        // A different chat (or app) came to the foreground: reseed memory instead of
        // treating the backlog as brand new messages. This must run before the
        // empty-snapshot check so that opening a chat seeds its memory.
        val conversationChanged = state.conversationId != null && state.conversationId != conversationId
        if (conversationChanged || state.activePackageName != snapshot.packageName) {
            resetForConversation(snapshot)
            return DetectionOutcome.Ignored(
                IgnoreReason.CONVERSATION_CHANGED,
                "Switched to ${snapshot.packageName}",
                conversationId
            )
        }

        if (snapshot.isEmpty) {
            state.observationSeeded = true
            return DetectionOutcome.Ignored(IgnoreReason.EMPTY_SNAPSHOT, "No chat bubbles visible", conversationId)
        }

        val usableTurns = snapshot.turns
            .filter { !it.sensitive }
            .filter { it.text.isNotBlank() }
            .map { if (it.text.length > config.maxTurnTextLength) it.copy(text = it.text.take(config.maxTurnTextLength)) else it }

        if (usableTurns.isEmpty()) {
            return DetectionOutcome.Ignored(IgnoreReason.NO_CANDIDATE_TURNS, "Only sensitive or empty bubbles", conversationId)
        }

        val hashes = usableTurns.map { MessageHasher.structuralHash(it.text, it.direction) }
        val firstObservation = !state.observationSeeded

        // Anything not seen before is now known, whichever branch we take below.
        val unseenIndices = hashes.indices.filter { !duplicateGuard.hasSeen(hashes[it]) }

        state.lastSnapshotAtMs = snapshot.capturedAtMs
        state.lastContext = usableTurns.takeLast(config.contextMessageCount)

        if (firstObservation) {
            state.observationSeeded = true
            duplicateGuard.rememberAll(hashes)
            return DetectionOutcome.Ignored(
                IgnoreReason.FIRST_OBSERVATION,
                "Seeded ${hashes.size} existing bubbles",
                conversationId
            )
        }

        // Only the newest visible bubble can be "the new message". Older bubbles that
        // appear because the list scrolled up are just remembered.
        val newestIndex = usableTurns.lastIndex
        val newest = usableTurns[newestIndex]
        val newestHash = hashes[newestIndex]

        // Loop prevention comes first: a bubble that is one of our own replies is never
        // a trigger, whether or not it has been "seen" already.
        if (duplicateGuard.isSelfReply(newestHash)) {
            state.lastReplyHash = newestHash
            duplicateGuard.rememberAll(hashes)
            return DetectionOutcome.Ignored(
                IgnoreReason.SELF_REPLY_ECHO,
                "This bubble is a reply the assistant already sent",
                conversationId
            )
        }

        if (unseenIndices.isEmpty()) {
            return DetectionOutcome.Ignored(
                IgnoreReason.SCROLL_OR_REPEAT,
                "No unseen bubbles (scroll or redraw)",
                conversationId
            )
        }

        duplicateGuard.rememberAll(hashes)

        if (duplicateGuard.hasSeen(newestHash) && unseenIndices.none { it == newestIndex }) {
            return DetectionOutcome.Ignored(
                IgnoreReason.SCROLL_OR_REPEAT,
                "Newest bubble already known",
                conversationId
            )
        }

        val context = buildContext(usableTurns)

        return when (newest.direction) {
            TurnDirection.SYSTEM -> {
                DetectionOutcome.Ignored(IgnoreReason.SYSTEM_NOTICE, "System notice", conversationId)
            }

            TurnDirection.OUTGOING -> {
                if (duplicateGuard.isSelfReply(newestHash)) {
                    state.lastReplyHash = newestHash
                    DetectionOutcome.Ignored(
                        IgnoreReason.SELF_REPLY_ECHO,
                        "This bubble is a reply the assistant already sent",
                        conversationId
                    )
                } else {
                    state.lastOwnMessageAtMs = clock.now()
                    state.lastProcessedMessageHash = newestHash
                    DetectionOutcome.OwnMessageSent(newest, conversationId, newestHash)
                }
            }

            TurnDirection.UNKNOWN -> {
                DetectionOutcome.Ignored(
                    IgnoreReason.DIRECTION_UNKNOWN,
                    "Could not tell incoming from outgoing",
                    conversationId
                )
            }

            TurnDirection.INCOMING -> {
                val normalisedLength = MessageHasher.normalize(newest.text).length
                if (normalisedLength < config.minIncomingTextLength) {
                    return DetectionOutcome.Ignored(
                        IgnoreReason.TEXT_TOO_SHORT,
                        "Message shorter than ${config.minIncomingTextLength} characters",
                        conversationId
                    )
                }
                if (duplicateGuard.isSelfReply(newestHash)) {
                    state.lastReplyHash = newestHash
                    return DetectionOutcome.Ignored(
                        IgnoreReason.SELF_REPLY_ECHO,
                        "Incoming bubble matches a reply the assistant sent",
                        conversationId
                    )
                }
                if (state.lastProcessedMessageHash == newestHash) {
                    return DetectionOutcome.Ignored(
                        IgnoreReason.DUPLICATE,
                        "Same message already processed",
                        conversationId
                    )
                }

                val manualCooldownActive = state.lastOwnMessageAtMs > 0L &&
                    (clock.now() - state.lastOwnMessageAtMs) < config.manualReplyCooldownMs
                val autoBlockedReason = when {
                    policy.userIsTyping -> "You are typing"
                    manualCooldownActive -> "You replied by hand recently"
                    policy.generationInFlight -> "A reply is already being generated"
                    else -> null
                }

                state.lastProcessedMessageHash = newestHash
                state.lastIncomingMessageText = newest.text

                DetectionOutcome.NewIncoming(
                    turn = newest,
                    context = context,
                    conversationId = conversationId,
                    messageHash = newestHash,
                    autoSendAllowed = autoBlockedReason == null,
                    autoSendBlockedReason = autoBlockedReason
                )
            }
        }
    }

    /**
     * Registers a reply the assistant produced, so the echo of that text appearing in
     * the chat tree can never start a new generation cycle.
     */
    fun registerSelfReply(replyText: String) {
        duplicateGuard.markSelfReply(replyText)
        state.lastReplyHash = MessageHasher.hash(replyText)
    }

    fun registerPending(hash: String) = duplicateGuard.markPending(hash)

    fun clearPending(hash: String) = duplicateGuard.clearPending(hash)

    fun isPending(hash: String): Boolean = duplicateGuard.isPending(hash)

    /** Call when the user asks to re-arm a conversation (or after a manual restart). */
    fun resetForConversation(snapshot: ConversationSnapshot) {
        state = ConversationState(
            activePackageName = snapshot.packageName,
            conversationId = snapshot.conversationId,
            observationSeeded = true,
            lastSnapshotAtMs = snapshot.capturedAtMs,
            lastContext = snapshot.turns.takeLast(config.contextMessageCount)
        )
        duplicateGuard.rememberAll(
            snapshot.turns
                .filter { !it.sensitive && it.text.isNotBlank() }
                .map { MessageHasher.structuralHash(it.text, it.direction) }
        )
    }

    fun resetAll() {
        state = ConversationState()
        duplicateGuard.reset()
    }

    /** Human readable one-liner for the diagnostics screen. */
    fun describeState(): String =
        "chat=${state.conversationId ?: "-"} lastMsg=${state.lastProcessedMessageHash?.take(8) ?: "-"} " +
            "own=${state.lastOwnMessageAtMs} seeded=${state.observationSeeded}"

    private fun buildContext(turns: List<ChatTurn>): List<ChatTurn> =
        turns.filter { it.direction == TurnDirection.INCOMING || it.direction == TurnDirection.OUTGOING }
            .takeLast(config.contextMessageCount)
}
