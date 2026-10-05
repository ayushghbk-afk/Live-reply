package com.liveaireply.app.conversation

import com.liveaireply.app.conversation.TestFixtures.incoming
import com.liveaireply.app.conversation.TestFixtures.snapshot
import com.liveaireply.app.conversation.TestFixtures.turn
import com.liveaireply.app.util.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationDetectorTest {

    private lateinit var clock: ManualClock
    private lateinit var detector: ConversationDetector

    @Before
    fun setUp() {
        clock = ManualClock(0L)
        detector = ConversationDetector(DuplicateGuard(clock), clock)
    }

    @Test
    fun seedsExistingMessagesOnFirstSightInsteadOfReplying() {
        val first = detector.process(
            snapshot(turn("Old message", TurnDirection.INCOMING), turn("Old reply", TurnDirection.OUTGOING))
        )
        assertTrue("first look must not generate", first is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.CONVERSATION_CHANGED, (first as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun detectsANewIncomingMessage() {
        openChat()
        val result = detector.process(
            snapshot(
                turn("Hey, are you coming tomorrow?", TurnDirection.INCOMING),
                turn("Maybe. Why?", TurnDirection.OUTGOING),
                turn("I wanted to ask you something.", TurnDirection.INCOMING)
            )
        )
        assertTrue("expected NewIncoming but got $result", result is DetectionOutcome.NewIncoming)
        val newIncoming = result as DetectionOutcome.NewIncoming
        assertEquals("I wanted to ask you something.", newIncoming.turn.text)
        assertEquals(3, newIncoming.context.size)
        assertTrue(newIncoming.autoSendAllowed)
    }

    @Test
    fun ignoresTheSameMessageWhenItIsRedrawn() {
        openChat()
        val withMessage = snapshot(turn("Are you coming tomorrow?", TurnDirection.INCOMING))
        assertTrue(detector.process(withMessage) is DetectionOutcome.NewIncoming)

        // Same content, new timestamp: a redraw, not a new message.
        val redraw = snapshot(turn("Are you coming tomorrow?", TurnDirection.INCOMING))
            .copy(capturedAtMs = 5_000L)
        val second = detector.process(redraw)
        assertTrue("redraw must be ignored, got $second", second is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.SCROLL_OR_REPEAT, (second as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun ignoresScrollingThatRevealsOlderMessages() {
        openChat()
        detector.process(
            snapshot(
                turn("A", TurnDirection.INCOMING),
                turn("B", TurnDirection.OUTGOING),
                turn("C", TurnDirection.INCOMING)
            )
        )
        // Scrolled up: an older message is now newest on screen.
        val scrolled = detector.process(
            snapshot(turn("A", TurnDirection.INCOMING), turn("B", TurnDirection.OUTGOING))
        )
        assertTrue(scrolled is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.SCROLL_OR_REPEAT, (scrolled as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun recognisesTheUsersOwnMessageInsteadOfReplyingToIt() {
        openChat()
        val result = detector.process(
            snapshot(turn("I will send it later", TurnDirection.OUTGOING))
        )
        assertTrue("own message must not be answered, got $result", result is DetectionOutcome.OwnMessageSent)
    }

    @Test
    fun blocksAutoReplyForAWhileAfterTheUserTypesByHand() {
        openChat()
        clock.setTo(10_000L)
        detector.process(snapshot(turn("Sent by hand", TurnDirection.OUTGOING)))

        clock.setTo(20_000L)
        val result = detector.process(
            snapshot(turn("Sent by hand", TurnDirection.OUTGOING), turn("Thanks!", TurnDirection.INCOMING))
        )
        assertTrue(result is DetectionOutcome.NewIncoming)
        val newIncoming = result as DetectionOutcome.NewIncoming
        assertFalse("manual reply must suppress auto send", newIncoming.autoSendAllowed)
        assertEquals("You replied by hand recently", newIncoming.autoSendBlockedReason)

        // After the cooldown, automatic sending is allowed again.
        clock.setTo(10_000L + detector.config.manualReplyCooldownMs + 1)
        val later = detector.process(
            snapshot(
                turn("Sent by hand", TurnDirection.OUTGOING),
                turn("Thanks!", TurnDirection.INCOMING),
                turn("Any time", TurnDirection.OUTGOING),
                turn("See you then", TurnDirection.INCOMING)
            )
        )
        assertTrue(later is DetectionOutcome.NewIncoming)
        assertTrue((later as DetectionOutcome.NewIncoming).autoSendAllowed)
    }

    @Test
    fun neverRepliesToItsOwnReplyTheLoopPreventionRule() {
        openChat()
        val incomingSnapshot = snapshot(turn("Are you coming tomorrow?", TurnDirection.INCOMING))
        assertTrue(detector.process(incomingSnapshot) is DetectionOutcome.NewIncoming)

        val reply = "Yeah, I'll be there around 7."
        detector.registerSelfReply(reply)

        // The reply now appears in the tree as an outgoing bubble.
        val withEcho = detector.process(
            snapshot(
                turn("Are you coming tomorrow?", TurnDirection.INCOMING),
                turn(reply, TurnDirection.OUTGOING)
            )
        )
        assertTrue("self echo must be ignored, got $withEcho", withEcho is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.SELF_REPLY_ECHO, (withEcho as DetectionOutcome.Ignored).reason)

        // And even if it is somehow classified as incoming, it is still refused.
        val misclassified = detector.process(
            snapshot(
                turn("Are you coming tomorrow?", TurnDirection.INCOMING),
                turn(reply, TurnDirection.INCOMING)
            )
        )
        assertTrue(misclassified is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.SELF_REPLY_ECHO, (misclassified as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun treatsASwitchedChatAsNewContextAndDoesNotAnswerTheBacklog() {
        openChat()
        val switched = detector.process(
            snapshot(turn("Old unread message", TurnDirection.INCOMING), packageName = "org.telegram.messenger")
        )
        assertTrue(switched is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.CONVERSATION_CHANGED, (switched as DetectionOutcome.Ignored).reason)

        // A genuinely new message in the new chat is answered.
        val fresh = detector.process(
            snapshot(
                turn("Old unread message", TurnDirection.INCOMING),
                turn("New question here", TurnDirection.INCOMING),
                packageName = "org.telegram.messenger"
            )
        )
        assertTrue(fresh is DetectionOutcome.NewIncoming)
        assertEquals("New question here", (fresh as DetectionOutcome.NewIncoming).turn.text)
    }

    @Test
    fun honoursThePolicySwitches() {
        openChat()
        val snap = snapshot(turn("Hello there", TurnDirection.INCOMING))

        val paused = detector.process(snap, DetectionPolicy(monitoringActive = false))
        assertEquals(
            IgnoreReason.MONITORING_PAUSED,
            (paused as DetectionOutcome.Ignored).reason
        )

        val disabledApp = detector.process(snap, DetectionPolicy(appEnabled = false))
        assertEquals(IgnoreReason.APP_NOT_ENABLED, (disabledApp as DetectionOutcome.Ignored).reason)

        val pausedChat = detector.process(snap, DetectionPolicy(conversationPaused = true))
        assertEquals(IgnoreReason.CONVERSATION_PAUSED, (pausedChat as DetectionOutcome.Ignored).reason)

        val sensitive = detector.process(snap, DetectionPolicy(sensitiveScreen = true))
        assertEquals(IgnoreReason.SENSITIVE_SCREEN, (sensitive as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun reportsLowConfidenceTurnsSoTheGuardCanRefuseThem() {
        openChat()
        val result = detector.process(
            snapshot(turn("hmm", TurnDirection.INCOMING, confidence = 0.1f))
        )
        assertTrue(result is DetectionOutcome.NewIncoming)
        // Confidence gating for AUTO happens in the engine/guard, but the detector
        // still reports the weak turn so the UI can explain itself.
        assertEquals(0.1f, (result as DetectionOutcome.NewIncoming).turn.confidence, 0.0001f)
    }

    @Test
    fun ignoresSystemNoticesAndUnknownDirections() {
        openChat()
        val system = detector.process(snapshot(turn("Messages are end-to-end encrypted", TurnDirection.SYSTEM)))
        assertEquals(IgnoreReason.SYSTEM_NOTICE, (system as DetectionOutcome.Ignored).reason)

        val unknown = detector.process(snapshot(turn("mystery", TurnDirection.UNKNOWN)))
        assertEquals(IgnoreReason.DIRECTION_UNKNOWN, (unknown as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun dropsSensitiveAndEmptyBubbles() {
        openChat()
        val sensitiveTurn = turn("My OTP is 483920", TurnDirection.INCOMING).copy(sensitive = true)
        val result = detector.process(snapshot(sensitiveTurn))
        assertEquals(IgnoreReason.NO_CANDIDATE_TURNS, (result as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun capsContextToTheConfiguredNumberOfMessages() {
        detector.config = DetectorConfig(contextMessageCount = 5)
        openChat()
        val turns = (1..21).map { index ->
            turn("message $index", if (index % 2 == 0) TurnDirection.OUTGOING else TurnDirection.INCOMING)
        }
        val result = detector.process(snapshot(*turns.toTypedArray()))
        assertTrue(result is DetectionOutcome.NewIncoming)
        assertEquals(5, (result as DetectionOutcome.NewIncoming).context.size)
        assertEquals("message 21", result.turn.text)
    }

    private fun openChat() {
        // First observation seeds the memory; the empty snapshot means "nothing visible yet".
        detector.process(snapshot(packageName = "com.example.chat"))
    }

    @Test
    fun keepsStableConversationIdsAcrossSnapshots() {
        val first = snapshot(turn("hi", TurnDirection.INCOMING)).conversationId
        val second = snapshot(turn("hi", TurnDirection.INCOMING), turn("there", TurnDirection.OUTGOING)).conversationId
        assertEquals(first, second)
        assertTrue(first.contains("Hellen"))
    }

    @Test
    fun titleFlickerDoesNotDropTheMessageThatArrivedDuringTheFlip() {
        openChat()
        // The chat title is momentarily unreadable, which flips the title-based
        // conversation id even though it is the same chat. The new message must still
        // be delivered instead of being swallowed by a conversation re-seed.
        val result = detector.process(
            snapshot(turn("Are you coming tomorrow?", TurnDirection.INCOMING), screenLabel = null)
        )
        assertTrue("title flicker must not drop the message, got $result", result is DetectionOutcome.NewIncoming)
    }

    @Test
    fun titleReturningAfterFlickerKeepsDuplicateMemory() {
        openChat()
        val message = turn("Are you coming tomorrow?", TurnDirection.INCOMING)
        assertTrue(detector.process(snapshot(message, screenLabel = null)) is DetectionOutcome.NewIncoming)

        val titleRestored = detector.process(snapshot(message, screenLabel = "Hellen"))
        assertTrue(titleRestored is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.SCROLL_OR_REPEAT, (titleRestored as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun aDifferentActivityIsNotMistakenForTitleFlicker() {
        openChat()
        val switched = detector.process(
            snapshot(turn("Hey", TurnDirection.INCOMING), screenLabel = null)
                .copy(activityName = "ContactListActivity")
        )
        assertTrue(switched is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.CONVERSATION_CHANGED, (switched as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun switchingToADifferentChatStillReseeds() {
        openChat()
        val switched = detector.process(
            snapshot(turn("Hey", TurnDirection.INCOMING), screenLabel = "Someone Else")
        )
        assertTrue(switched is DetectionOutcome.Ignored)
        assertEquals(IgnoreReason.CONVERSATION_CHANGED, (switched as DetectionOutcome.Ignored).reason)
    }

    @Test
    fun incomingBubblesAreRecognisedFromRealNodeTrees() {
        val extractor = BubbleExtractor()
        val root = TestFixtures.screen(
            listOf(
                incoming("Are you coming tomorrow?", top = 400),
                TestFixtures.outgoing("Maybe. Why?", top = 520),
                TestFixtures.composer(),
                TestFixtures.sendButton()
            )
        )
        val turns = extractor.extractTurns(
            root = root,
            screenWidth = TestFixtures.SCREEN_W,
            screenHeight = TestFixtures.SCREEN_H,
            hints = DirectionHints(),
            composerBounds = TestFixtures.composer().bounds
        )
        assertEquals(2, turns.size)
        assertEquals(TurnDirection.INCOMING, turns[0].direction)
        assertEquals(TurnDirection.OUTGOING, turns[1].direction)
    }
}
