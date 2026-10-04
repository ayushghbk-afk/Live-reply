package com.liveaireply.app.conversation

/** Who a chat bubble belongs to. */
enum class TurnDirection {
    /** Sent by the other participant - this is what the assistant may answer. */
    INCOMING,

    /** Sent by the device owner (either typed by hand or inserted by this app). */
    OUTGOING,

    /** System notice such as "Messages are end-to-end encrypted". */
    SYSTEM,

    /** Direction could not be established with enough confidence. */
    UNKNOWN
}

/** Where a turn's text came from. */
enum class TurnSource {
    /** Read from the accessibility node tree (cheap, preferred). */
    ACCESSIBILITY,

    /** Recovered with on-device OCR (fallback when no text nodes exist). */
    OCR,

    /** Injected by the user from the built-in test console. */
    SIMULATED
}

/**
 * One chat bubble.
 *
 * @param confidence 0f..1f. Accessibility derived turns are usually 1f; OCR derived
 *   turns carry the recogniser's confidence, and geometry-guessed directions are
 *   penalised. Anything below the configured threshold must never trigger AUTO send.
 */
data class ChatTurn(
    val text: String,
    val direction: TurnDirection,
    val source: TurnSource,
    val capturedAtMs: Long,
    val confidence: Float = 1f,
    val bounds: RectView = RectView.EMPTY,
    /** View id resource name, or a stable OCR block key. Used for structural dedupe. */
    val nodeSignature: String? = null,
    /** True when a password/PIN/banking style hint was attached to this node. */
    val sensitive: Boolean = false
) {
    val isIncoming: Boolean get() = direction == TurnDirection.INCOMING
    val isOutgoing: Boolean get() = direction == TurnDirection.OUTGOING
}

/**
 * Everything the app knows about the chat that is currently on screen.
 *
 * A snapshot is immutable and cheap to compare, which is what the debounce and
 * duplicate-detection logic needs.
 */
data class ConversationSnapshot(
    val packageName: String,
    val activityName: String? = null,
    /** Chat title / contact name when the active adapter could read it. */
    val screenLabel: String? = null,
    val turns: List<ChatTurn> = emptyList(),
    val capturedAtMs: Long = 0L,
    val hasEditableInput: Boolean = false,
    /** Signature (view id or bounds) of the located composer field. */
    val editableFieldSignature: String? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val source: TurnSource = TurnSource.ACCESSIBILITY,
    /** Aggregate OCR confidence, 1f for accessibility snapshots. */
    val extractionConfidence: Float = 1f,
    /** True when the visible screen is one the user excluded, or a password screen. */
    val sensitiveScreen: Boolean = false
) {
    val isEmpty: Boolean get() = turns.isEmpty()

    val newestTurn: ChatTurn? get() = turns.lastOrNull()

    val incomingTurns: List<ChatTurn> get() = turns.filter { it.isIncoming }

    /** Stable id for "this conversation": app + chat title when available. */
    val conversationId: String
        get() = buildString {
            append(packageName)
            if (!screenLabel.isNullOrBlank()) {
                append("::")
                append(screenLabel.trim())
            } else if (!activityName.isNullOrBlank()) {
                append("::")
                append(activityName)
            }
        }
}
