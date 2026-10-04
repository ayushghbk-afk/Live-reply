package com.liveaireply.app.conversation

/**
 * Decides whether a bubble was sent by the other person or by the device owner.
 *
 * Order of evidence (strongest first):
 *  1. View id resource names known to belong to the active app's bubbles.
 *  2. Content-description wording ("You:", "Sent", "Delivered", "Received", ...).
 *  3. Explicit system-notice text patterns.
 *  4. Bubble geometry: right-aligned bubbles are outgoing in virtually every chat app.
 *
 * Geometry alone is treated as a weak signal (default 0.55) so the automation layer
 * can refuse AUTO send when direction was only guessed.
 */
class DirectionClassifier {

    fun classify(bubble: RawBubble, screenWidth: Int, hints: DirectionHints): DirectionDecision {
        val viewId = bubble.nodeSignature?.lowercase().orEmpty()
        if (viewId.isNotEmpty()) {
            val outgoingId = hints.outgoingViewIdContains.firstOrNull { viewId.contains(it.lowercase()) }
            if (outgoingId != null) {
                return DirectionDecision(
                    TurnDirection.OUTGOING, hints.structuralConfidence, "view id contains '$outgoingId'"
                )
            }
            val incomingId = hints.incomingViewIdContains.firstOrNull { viewId.contains(it.lowercase()) }
            if (incomingId != null) {
                return DirectionDecision(
                    TurnDirection.INCOMING, hints.structuralConfidence, "view id contains '$incomingId'"
                )
            }
        }

        val description = bubble.contentDescription?.lowercase().orEmpty()
        val text = bubble.text.lowercase()

        if (description.isNotEmpty() || text.isNotEmpty()) {
            val systemHit = hints.systemDescriptionWords.firstOrNull { description.contains(it.lowercase()) }
                ?: hints.systemTextPatterns.firstOrNull { text.contains(it.lowercase()) }
            if (systemHit != null) {
                return DirectionDecision(
                    TurnDirection.SYSTEM, hints.structuralConfidence, "system notice '$systemHit'"
                )
            }
            val outgoingWord = hints.outgoingDescriptionWords.firstOrNull { description.contains(it.lowercase()) }
            if (outgoingWord != null) {
                return DirectionDecision(
                    TurnDirection.OUTGOING, hints.structuralConfidence, "description contains '$outgoingWord'"
                )
            }
            val incomingWord = hints.incomingDescriptionWords.firstOrNull { description.contains(it.lowercase()) }
            if (incomingWord != null) {
                return DirectionDecision(
                    TurnDirection.INCOMING, hints.structuralConfidence, "description contains '$incomingWord'"
                )
            }
        }

        if (screenWidth > 0 && !bubble.bounds.isEmpty) {
            val centreFraction = bubble.bounds.centerX.toFloat() / screenWidth.toFloat()
            if (centreFraction >= hints.outgoingRightOfFraction) {
                val strength = ((centreFraction - hints.outgoingRightOfFraction) /
                    (1f - hints.outgoingRightOfFraction)).coerceIn(0f, 1f)
                return DirectionDecision(
                    TurnDirection.OUTGOING,
                    (hints.geometryConfidence + 0.25f * strength).coerceAtMost(0.9f),
                    "bubble centre at ${(centreFraction * 100).toInt()}% of width"
                )
            }
            if (centreFraction <= hints.incomingLeftOfFraction && hints.incomingLeftOfFraction > 0f) {
                val strength = ((hints.incomingLeftOfFraction - centreFraction) /
                    hints.incomingLeftOfFraction).coerceIn(0f, 1f)
                return DirectionDecision(
                    TurnDirection.INCOMING,
                    (hints.geometryConfidence + 0.25f * strength).coerceAtMost(0.9f),
                    "bubble centre at ${(centreFraction * 100).toInt()}% of width"
                )
            }
        }

        return DirectionDecision(TurnDirection.UNKNOWN, 0f, "no structural or geometric evidence")
    }

    /** Convenience wrapper that returns a finished [ChatTurn]. */
    fun toTurn(
        bubble: RawBubble,
        screenWidth: Int,
        hints: DirectionHints,
        capturedAtMs: Long
    ): ChatTurn {
        val decision = classify(bubble, screenWidth, hints)
        return ChatTurn(
            text = bubble.text,
            direction = decision.direction,
            source = bubble.source,
            capturedAtMs = capturedAtMs,
            confidence = (decision.confidence * bubble.textConfidence).coerceIn(0f, 1f),
            bounds = bubble.bounds,
            nodeSignature = bubble.nodeSignature,
            sensitive = bubble.sensitive
        )
    }
}
