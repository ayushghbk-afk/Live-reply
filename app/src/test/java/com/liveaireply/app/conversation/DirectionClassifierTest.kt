package com.liveaireply.app.conversation

import com.liveaireply.app.conversation.TestFixtures.SCREEN_W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionClassifierTest {

    private val classifier = DirectionClassifier()

    @Test
    fun usesViewIdEvidenceFirst() {
        val bubble = RawBubble(
            text = "hi",
            bounds = RectView(600, 100, 1000, 180),
            nodeSignature = "com.whatsapp:id/message_text_left"
        )
        val decision = classifier.classify(
            bubble,
            SCREEN_W,
            DirectionHints(incomingViewIdContains = listOf("message_text_left"))
        )
        assertEquals(TurnDirection.INCOMING, decision.direction)
        assertTrue(decision.confidence >= 0.9f)
    }

    @Test
    fun usesContentDescriptionWording() {
        val bubble = RawBubble(
            text = "hi",
            bounds = RectView(600, 100, 1000, 180),
            contentDescription = "You: hi, sent"
        )
        val decision = classifier.classify(bubble, SCREEN_W, DirectionHints(outgoingDescriptionWords = listOf("you:")))
        assertEquals(TurnDirection.OUTGOING, decision.direction)
    }

    @Test
    fun fallsBackToGeometry() {
        val rightAligned = RawBubble(text = "hello", bounds = RectView(600, 100, 1050, 180))
        val leftAligned = RawBubble(text = "hello", bounds = RectView(20, 100, 480, 180))
        assertEquals(
            TurnDirection.OUTGOING,
            classifier.classify(rightAligned, SCREEN_W, DirectionHints()).direction
        )
        assertEquals(
            TurnDirection.INCOMING,
            classifier.classify(leftAligned, SCREEN_W, DirectionHints()).direction
        )
    }

    @Test
    fun geometryOnlyDecisionsAreLowConfidence() {
        val decision = classifier.classify(
            RawBubble(text = "hello", bounds = RectView(600, 100, 1050, 180)),
            SCREEN_W,
            DirectionHints()
        )
        assertTrue(
            "geometry-only confidence was ${decision.confidence}",
            decision.confidence < 0.9f
        )
    }

    @Test
    fun reportsUnknownWhenThereIsNoEvidence() {
        val decision = classifier.classify(
            RawBubble(text = "hello", bounds = RectView.EMPTY),
            screenWidth = 0,
            hints = DirectionHints()
        )
        assertEquals(TurnDirection.UNKNOWN, decision.direction)
        assertEquals(0f, decision.confidence, 0.0001f)
    }

    @Test
    fun recognisesSystemNotices() {
        val decision = classifier.classify(
            RawBubble(text = "Messages and calls are end-to-end encrypted", bounds = RectView(100, 100, 900, 160)),
            SCREEN_W,
            DirectionHints(systemTextPatterns = listOf("end-to-end encrypted"))
        )
        assertEquals(TurnDirection.SYSTEM, decision.direction)
    }
}
