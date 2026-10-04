package com.liveaireply.app.conversation

/**
 * A candidate chat bubble before its direction is known.
 *
 * Produced by [com.liveaireply.app.conversation.BubbleExtractor] from either the
 * accessibility tree or OCR blocks, then turned into a [ChatTurn] by
 * [DirectionClassifier].
 */
data class RawBubble(
    val text: String,
    val bounds: RectView = RectView.EMPTY,
    val nodeSignature: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val sensitive: Boolean = false,
    val source: TurnSource = TurnSource.ACCESSIBILITY,
    /** Recogniser confidence; 1f for accessibility derived bubbles. */
    val textConfidence: Float = 1f
)

/** Result of deciding who a bubble belongs to. */
data class DirectionDecision(
    val direction: TurnDirection,
    val confidence: Float,
    val reason: String
)

/**
 * Per-app knowledge about how to tell "their" message from "your" message.
 *
 * Adapters fill this in from known UI structures; the generic adapter falls back to
 * bubble geometry (right-aligned = outgoing) which works for most chat apps.
 */
data class DirectionHints(
    /** Substrings that mark a view id as an outgoing bubble. */
    val outgoingViewIdContains: List<String> = emptyList(),
    val incomingViewIdContains: List<String> = emptyList(),
    /** Lower-cased substrings searched inside contentDescription. */
    val outgoingDescriptionWords: List<String> = emptyList(),
    val incomingDescriptionWords: List<String> = emptyList(),
    val systemDescriptionWords: List<String> = emptyList(),
    /** System notice text such as "Messages and calls are end-to-end encrypted". */
    val systemTextPatterns: List<String> = emptyList(),
    /** Bubble centre must be right of this fraction of screen width to count as outgoing. */
    val outgoingRightOfFraction: Float = 0.52f,
    /** Bubble centre must be left of this fraction of screen width to count as incoming. */
    val incomingLeftOfFraction: Float = 0.48f,
    /** Confidence awarded to a purely geometric decision. */
    val geometryConfidence: Float = 0.55f,
    /** Confidence awarded to an id/content-description decision. */
    val structuralConfidence: Float = 0.95f
)
