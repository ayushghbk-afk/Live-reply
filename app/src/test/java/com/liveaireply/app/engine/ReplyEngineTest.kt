package com.liveaireply.app.engine

import com.liveaireply.app.ai.AiErrorKind
import com.liveaireply.app.ai.ChatMessage
import com.liveaireply.app.ai.FakeProvider
import com.liveaireply.app.ai.ReplyPipeline
import com.liveaireply.app.ai.RetryPolicy
import com.liveaireply.app.ai.Sleeper
import com.liveaireply.app.automation.AutomationController
import com.liveaireply.app.automation.InsertResult
import com.liveaireply.app.automation.SendResult
import com.liveaireply.app.conversation.ChatTurn
import com.liveaireply.app.conversation.ConversationDetector
import com.liveaireply.app.conversation.ConversationSnapshot
import com.liveaireply.app.conversation.DuplicateGuard
import com.liveaireply.app.conversation.TurnDirection
import com.liveaireply.app.conversation.TurnSource
import com.liveaireply.app.personas.PersonaPresets
import com.liveaireply.app.security.SensitiveScreenVerdict
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import com.liveaireply.app.settings.ReplyDelay
import com.liveaireply.app.util.ManualClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplyEngineTest {

    private class FakeAutomation : AutomationController {
        var composerAvailable = true
        var composerConfidenceValue = 0.9f
        var sendConfidenceValue = 0.85f
        var gestureFallback = false
        var sendSucceeds = true
        var insertSucceeds = true
        var verifyText = true
        var title: String? = "Hellen"
        var foreground: String? = "com.example.chat"
        var composerText: String? = ""

        val inserted = ArrayList<String>()
        var sendCount = 0

        override fun hasComposer(): Boolean = composerAvailable
        override fun composerConfidence(): Float = composerConfidenceValue
        override fun composerContent(): String? = composerText
        override fun insertText(text: String, simulateTyping: Boolean, charsPerSecond: Int): InsertResult {
            if (!insertSucceeds) return InsertResult.failed("No editable field")
            inserted += text
            composerText = text
            return InsertResult.ok(if (verifyText) text else "something else")
        }
        override fun clearComposer(): Boolean {
            composerText = ""
            return true
        }
        override fun sendComposer(): SendResult {
            if (!sendSucceeds) return SendResult.failed("Send control not found")
            sendCount++
            return SendResult.ok(gestureFallback)
        }
        override fun sendConfidence(): Float = sendConfidenceValue
        override fun usesGestureFallback(): Boolean = gestureFallback
        override fun foregroundPackage(): String? = foreground
        override fun chatTitle(): String? = title
    }

    private class FakeHost : EngineHost {
        val statuses = ArrayList<AssistantStatus>()
        val states = ArrayList<OverlayState>()
        val logs = ArrayList<EngineLogEntry>()
        val errors = ArrayList<Pair<String, String>>()
        val copied = ArrayList<String>()
        var stopRequests = 0

        override fun onStatusChanged(status: AssistantStatus, detail: String) {
            statuses += status
        }
        override fun onOverlayStateChanged(state: OverlayState) {
            states += state
        }
        override fun onLog(entry: EngineLogEntry) {
            logs += entry
        }
        override fun onCopyToClipboard(text: String) {
            copied += text
        }
        override fun onError(headline: String, detail: String) {
            errors += headline to detail
        }
        override fun onEngineStopped() {
            stopRequests++
        }
    }

    private class TestPauses : ConversationPauseController {
        val paused = mutableSetOf<String>()
        override fun isPaused(conversationId: String) = paused.contains(conversationId)
        override fun pause(conversationId: String) {
            paused += conversationId
        }
        override fun resume(conversationId: String) {
            paused -= conversationId
        }
        override fun pausedConversations(): List<String> = paused.toList()
    }

    private lateinit var clock: ManualClock
    private lateinit var automation: FakeAutomation
    private lateinit var host: FakeHost
    private lateinit var provider: FakeProvider
    private lateinit var pauses: TestPauses
    private lateinit var sleeper: com.liveaireply.app.ai.RecordingSleeper
    private lateinit var engine: ReplyEngine

    private var settings = AppSettings(
        mode = AssistantMode.SUGGEST,
        monitoringEnabled = true,
        primaryModel = "test-model",
        replyDelay = ReplyDelay.INSTANT,
        // The fixtures use a fake chat package; it has to be in the enabled list.
        enabledPackages = listOf("com.example.chat")
    )

    @Before
    fun setUp() {
        clock = ManualClock(0L)
        automation = FakeAutomation()
        host = FakeHost()
        provider = FakeProvider()
        pauses = TestPauses()
        sleeper = Sleeper.recording()
        val detector = ConversationDetector(DuplicateGuard(clock), clock)
        val pipeline = ReplyPipeline(provider, sleeper = sleeper, retryPolicy = RetryPolicy(maxAttemptsPerModel = 1))
        engine = ReplyEngine(
            detector = detector,
            pipeline = pipeline,
            automation = automation,
            host = host,
            settingsProvider = { settings },
            personaProvider = { PersonaPresets.FRIENDLY },
            conversationPauses = pauses,
            clock = clock,
            sleeper = sleeper
        )
        engine.start()
        // Opening the chat: seed the detector's memory without answering the backlog.
        engine.handleSnapshot(SnapshotInput(emptySnapshot()))
        provider.requests.clear()
    }

    private fun emptySnapshot() = ConversationSnapshot(
        packageName = "com.example.chat",
        activityName = "ChatActivity",
        screenLabel = "Hellen",
        capturedAtMs = clock.now(),
        hasEditableInput = true,
        screenWidth = 1080,
        screenHeight = 2400
    )

    private fun snapshotWith(vararg turns: ChatTurn) = emptySnapshot().copy(turns = turns.toList())

    private fun incoming(vararg texts: String): SnapshotInput {
        val turns = texts.map { ChatTurn(it, TurnDirection.INCOMING, TurnSource.ACCESSIBILITY, clock.now()) }
        return SnapshotInput(snapshotWith(*turns.toTypedArray()))
    }

    // ------------------------------------------------------------------- modes

    @Test
    fun suggestModeShowsTheReplyAndNeverTouchesTheOtherApp() {
        provider.replyWith("Sure, see you at 7!")
        val result = engine.handleSnapshot(incoming("Are you coming tomorrow?"))

        assertTrue("expected Suggested, got $result", result is EngineResult.Suggested)
        assertEquals("Sure, see you at 7!", engine.state().replyText)
        assertEquals(AssistantStatus.REPLY_READY, engine.state().status)
        assertTrue("suggest mode must not type", automation.inserted.isEmpty())
        assertEquals(0, automation.sendCount)
        assertTrue(engine.state().canSend)
    }

    @Test
    fun approveModeStagesTheReplyInComposerButNeverSends() {
        settings = settings.copy(mode = AssistantMode.APPROVE)
        provider.replyWith("Sure, see you at 7!")
        engine.handleSnapshot(incoming("Are you coming tomorrow?"))

        assertEquals(listOf("Sure, see you at 7!"), automation.inserted)
        assertEquals("approve mode must not press send", 0, automation.sendCount)
        assertTrue(engine.state().canSend)
    }

    @Test
    fun autoModeTypesAndSends() {
        settings = autoSettings()
        provider.replyWith("Yep, I'll be there.")
        val result = engine.handleSnapshot(incoming("Are you coming tomorrow?"))

        assertTrue("expected AutoSent, got $result", result is EngineResult.AutoSent)
        assertEquals(listOf("Yep, I'll be there."), automation.inserted)
        assertEquals(1, automation.sendCount)
        assertNull("overlay clears after sending", engine.state().replyText)
    }

    @Test
    fun autoModeWaitsForTheConfiguredDelay() {
        settings = autoSettings().copy(replyDelay = ReplyDelay.THREE_SECONDS)
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals(listOf(3_000L), sleeper.delays)
    }

    @Test
    fun autoModeFallsBackToASuggestionWhenThereIsNoComposer() {
        settings = autoSettings()
        automation.composerAvailable = false
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(incoming("Coming?"))

        assertTrue("expected SuggestedInsteadOfAuto, got $result", result is EngineResult.SuggestedInsteadOfAuto)
        assertEquals(0, automation.sendCount)
        assertEquals("Yep.", engine.state().replyText)
        assertTrue((result as EngineResult.SuggestedInsteadOfAuto).reason.contains("input field"))
    }

    @Test
    fun autoModeFallsBackWhenTheSendControlCannotBeFound() {
        settings = autoSettings()
        automation.sendConfidenceValue = 0.1f
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(incoming("Coming?"))
        assertTrue(result is EngineResult.SuggestedInsteadOfAuto)
        assertEquals(0, automation.sendCount)
    }

    @Test
    fun autoModeDoesNotSendWhileTheUserIsTyping() {
        settings = autoSettings()
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(incoming("Coming?").copy(userIsTyping = true))
        assertTrue("expected Suggested, got $result", result is EngineResult.Suggested)
        assertEquals(0, automation.sendCount)
        assertEquals("You are typing", engine.state().sendBlockedReason)
    }

    @Test
    fun autoModeStopsIfTheActiveAppChangedDuringTheDelay() {
        settings = autoSettings().copy(replyDelay = ReplyDelay.FIVE_SECONDS)
        provider.replyWith("Yep.")
        automation.foreground = "com.whatsapp"   // not the chat we are watching
        val result = engine.handleSnapshot(incoming("Coming?"))
        assertTrue(result is EngineResult.SuggestedInsteadOfAuto)
        assertEquals(0, automation.sendCount)
    }

    // ------------------------------------------------------------ loop safety

    @Test
    fun theAssistantNeverAnswersItsOwnReply() {
        settings = autoSettings()
        provider.replyWith("Yeah, I'll be there around 7.")
        engine.handleSnapshot(incoming("Are you coming tomorrow?"))
        assertEquals(1, provider.requests.size)
        assertEquals(1, automation.sendCount)

        // The reply now appears in the accessibility tree as an outgoing bubble.
        val withEcho = SnapshotInput(
            snapshotWith(
                ChatTurn("Are you coming tomorrow?", TurnDirection.INCOMING, TurnSource.ACCESSIBILITY, clock.now()),
                ChatTurn("Yeah, I'll be there around 7.", TurnDirection.OUTGOING, TurnSource.ACCESSIBILITY, clock.now())
            )
        )
        val result = engine.handleSnapshot(withEcho)
        assertTrue("echo produced $result", result is EngineResult.Nothing)
        assertEquals("a second AI call would be an infinite loop", 1, provider.requests.size)
        assertEquals(1, automation.sendCount)
    }

    @Test
    fun theAssistantNeverAnswersItselfEvenWhenTheEchoLooksIncoming() {
        settings = autoSettings()
        provider.replyWith("On my way now.")
        engine.handleSnapshot(incoming("Where are you?"))
        val misclassified = SnapshotInput(
            snapshotWith(
                ChatTurn("Where are you?", TurnDirection.INCOMING, TurnSource.ACCESSIBILITY, clock.now()),
                ChatTurn("On my way now.", TurnDirection.INCOMING, TurnSource.ACCESSIBILITY, clock.now())
            )
        )
        engine.handleSnapshot(misclassified)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun nothingIsSentTwiceForTheSameMessage() {
        settings = autoSettings()
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        // Same message redrawn several times, as chat apps do.
        repeat(4) { engine.handleSnapshot(incoming("Coming?")) }
        assertEquals(1, provider.requests.size)
        assertEquals(1, automation.sendCount)
    }

    // ----------------------------------------------------------------- safety

    @Test
    fun emergencyStopHaltsDetectionGenerationAndSending() {
        settings = autoSettings()
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals(1, automation.sendCount)

        engine.handleAction(OverlayAction.STOP)

        assertFalse(engine.isRunning())
        assertEquals(AssistantStatus.STOPPED, engine.state().status)
        assertEquals(1, host.stopRequests)

        engine.handleSnapshot(incoming("Another question?"))
        assertEquals("no AI calls after stop", 1, provider.requests.size)
        assertEquals("no sends after stop", 1, automation.sendCount)
    }

    @Test
    fun pauseStopsDetectionButKeepsTheServiceAlive() {
        settings = autoSettings()
        engine.handleAction(OverlayAction.PAUSE_ALL)
        assertEquals(AssistantStatus.PAUSED, engine.state().status)
        assertEquals("pause must not tear the service down", 0, host.stopRequests)

        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals(0, provider.requests.size)

        engine.handleAction(OverlayAction.RESUME)
        assertTrue(engine.isRunning())
    }

    @Test
    fun pausedConversationsAreIgnored() {
        settings = autoSettings()
        engine.handleAction(OverlayAction.PAUSE_CHAT)
        assertTrue(pauses.paused.isNotEmpty())
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals(0, provider.requests.size)
    }

    @Test
    fun sensitiveScreensAreNeverProcessed() {
        settings = autoSettings()
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(
            incoming("My OTP is 482913").copy(
                sensitive = SensitiveScreenVerdict(true, "A password or PIN field is on screen")
            )
        )
        assertTrue(result is EngineResult.Nothing)
        assertEquals(0, provider.requests.size)
        assertEquals(0, automation.sendCount)
        assertTrue(engine.state().statusDetail.contains("sensitive"))
    }

    @Test
    fun monitoringOffMeansNoWork() {
        settings = settings.copy(monitoringEnabled = false)
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals(0, provider.requests.size)
        assertEquals(AssistantStatus.IDLE, engine.state().status)
    }

    // ------------------------------------------------------------------- errors

    @Test
    fun apiFailuresBecomeReadableErrorsAndNothingIsSent() {
        settings = autoSettings()
        provider.failWith(AiErrorKind.RATE_LIMIT, "slow down")
        val result = engine.handleSnapshot(incoming("Coming?"))

        assertTrue("expected AiFailed, got $result", result is EngineResult.AiFailed)
        assertEquals(AssistantStatus.ERROR, engine.state().status)
        assertEquals(0, automation.sendCount)
        assertTrue(host.errors.isNotEmpty())
        val (headline, _) = host.errors.last()
        assertTrue("headline was '$headline'", headline.contains("Rate limit"))
    }

    @Test
    fun offlineIsReportedWithoutEndlessRetries() {
        settings = autoSettings()
        provider.failWith(AiErrorKind.NO_NETWORK, "offline")
        val result = engine.handleSnapshot(incoming("Coming?"))
        assertTrue(result is EngineResult.AiFailed)
        assertEquals(1, provider.requests.size)
        assertEquals(0, sleeper.delays.size)
        assertTrue(engine.state().errorMessage!!.contains("offline") || host.errors.isNotEmpty())
    }

    @Test
    fun invalidRepliesAreNeverSent() {
        settings = autoSettings()
        provider.replyWith("As an AI language model, I cannot answer that.")
        val result = engine.handleSnapshot(incoming("Coming?"))

        assertTrue("expected InvalidReply, got $result", result is EngineResult.InvalidReply)
        assertEquals(0, automation.sendCount)
        assertEquals(AssistantStatus.ERROR, engine.state().status)
        assertNotNull(engine.state().errorMessage)
    }

    @Test
    fun insertFailuresAreExplainedAndNothingIsSent() {
        settings = autoSettings()
        automation.insertSucceeds = false
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(incoming("Coming?"))
        assertTrue(result is EngineResult.AutomationFailed)
        assertEquals(0, automation.sendCount)
        assertTrue(engine.state().statusDetail.isNotBlank())
    }

    @Test
    fun unverifiedTextIsNeverSent() {
        settings = autoSettings()
        automation.verifyText = false
        provider.replyWith("Yep.")
        val result = engine.handleSnapshot(incoming("Coming?"))
        assertTrue(result is EngineResult.AutomationFailed)
        assertEquals(0, automation.sendCount)
    }

    @Test
    fun aFailedSendLeavesTheReplyOnScreenForManualSending() {
        settings = autoSettings()
        automation.sendSucceeds = false
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertEquals("Yep.", engine.state().replyText)
        assertTrue(engine.state().canSend)
    }

    // ------------------------------------------------------------------ actions

    @Test
    fun userCanSendTheSuggestedReply() {
        provider.replyWith("Sure thing.")
        engine.handleSnapshot(incoming("Coming?"))
        val result = engine.handleAction(OverlayAction.SEND)
        assertTrue(result is EngineResult.SentByUser)
        assertEquals(listOf("Sure thing."), automation.inserted)
        assertEquals(1, automation.sendCount)
    }

    @Test
    fun userEditsAreSentVerbatimAndNeverOverwritten() {
        provider.replyWith("Sure thing.")
        engine.handleSnapshot(incoming("Coming?"))
        engine.handleAction(OverlayAction.EDIT)
        assertTrue(engine.state().replyEditable)
        engine.handleAction(OverlayAction.SEND, editedText = "Yep, on my way")
        assertEquals(listOf("Yep, on my way"), automation.inserted)
    }

    @Test
    fun copyAndRejectWork() {
        provider.replyWith("Sure thing.")
        engine.handleSnapshot(incoming("Coming?"))
        engine.handleAction(OverlayAction.COPY)
        assertEquals(listOf("Sure thing."), host.copied)
        engine.handleAction(OverlayAction.REJECT)
        assertNull(engine.state().replyText)
        assertFalse(engine.state().canSend)
    }

    @Test
    fun regenerateAsksForSomethingDifferent() {
        provider.replyWith("First answer.")
        engine.handleSnapshot(incoming("Coming?"))
        provider.outcomes.clear()
        provider.replyWith("Second answer.")
        val result = engine.handleAction(OverlayAction.REGENERATE)
        assertTrue(result is EngineResult.Suggested)
        assertEquals("Second answer.", engine.state().replyText)
        // The previous reply is fed back so the model avoids repeating itself.
        val lastRequest = provider.requests.last()
        assertTrue(lastRequest.messages.any { it.role == ChatMessage.Role.ASSISTANT })
        assertTrue(lastRequest.messages.last().content.contains("different reply"))
    }

    // --------------------------------------------------------------------- misc

    @Test
    fun theTestConsoleRunsThePipelineWithoutTouchingAnotherApp() {
        provider.replyWith("Simulated reply.")
        val result = engine.runSimulation("Are you coming tomorrow?")
        assertTrue(result is EngineResult.Suggested)
        assertEquals(0, automation.sendCount)
        assertTrue(automation.inserted.isEmpty())
        assertEquals("Simulated reply.", engine.state().replyText)
    }

    @Test
    fun statusChangesArePublishedForTheOverlayAndNotification() {
        settings = autoSettings()
        provider.replyWith("Yep.")
        engine.handleSnapshot(incoming("Coming?"))
        assertTrue(host.statuses.contains(AssistantStatus.THINKING))
        assertTrue(host.statuses.contains(AssistantStatus.MONITORING))
        assertTrue(host.logs.any { it.tag == "ai" })
        assertTrue(host.logs.any { it.tag == "automation" })
    }

    @Test
    fun contextSizeIsPassedToThePrompt() {
        settings = settings.copy(contextMessageCount = 5)
        provider.replyWith("Yep.")
        val turns = (1..9).map { index ->
            ChatTurn(
                "message $index",
                if (index % 2 == 0) TurnDirection.OUTGOING else TurnDirection.INCOMING,
                TurnSource.ACCESSIBILITY,
                clock.now()
            )
        }
        engine.handleSnapshot(SnapshotInput(snapshotWith(*turns.toTypedArray())))
        val transcript = provider.requests.last().messages.last().content
        assertTrue(transcript.contains("message 9"))
        assertFalse(transcript.contains("message 1\n"))
    }

    private fun autoSettings() = settings.copy(
        mode = AssistantMode.AUTO,
        autoReplyEnabled = true,
        acknowledgedAutomationRisk = true,
        replyDelay = ReplyDelay.INSTANT
    )
}
