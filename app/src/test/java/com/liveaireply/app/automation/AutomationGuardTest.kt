package com.liveaireply.app.automation

import com.liveaireply.app.adapters.SendTargetKind
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationGuardTest {

    private val autoSettings = AppSettings(
        mode = AssistantMode.AUTO,
        monitoringEnabled = true,
        autoReplyEnabled = true,
        acknowledgedAutomationRisk = true,
        acknowledgedCapabilities = true
    )

    private fun context(
        settings: AppSettings = autoSettings,
        composerFound: Boolean = true,
        composerConfidence: Float = 0.9f,
        sendTargetKind: SendTargetKind = SendTargetKind.NODE_ACTION,
        sendConfidence: Float = 0.85f,
        replyPassedValidation: Boolean = true,
        replyNonEmpty: Boolean = true,
        sensitiveScreen: Boolean = false,
        userIsTyping: Boolean = false,
        activeAppChanged: Boolean = false,
        recipientClear: Boolean = true,
        detectionConfidence: Float = 0.9f,
        ocrConfidence: Float = 0.9f,
        usedOcr: Boolean = false,
        conversationPaused: Boolean = false,
        emergencyStopped: Boolean = false
    ) = AutomationContext(
        settings, composerFound, composerConfidence, sendTargetKind, sendConfidence,
        replyPassedValidation, replyNonEmpty, sensitiveScreen, userIsTyping,
        activeAppChanged, recipientClear, detectionConfidence, ocrConfidence, usedOcr,
        conversationPaused, emergencyStopped
    )

    private val guard = AutomationGuard()

    @Test
    fun allowsAutoSendWhenEverythingChecksOut() {
        assertEquals(AutomationDecision.Allow, guard.decide(context()))
    }

    @Test
    fun refusesWhenAutoReplyIsNotSwitchedOn() {
        val decision = guard.decide(context(settings = autoSettings.copy(autoReplyEnabled = false)))
        assertTrue(decision is AutomationDecision.Refuse)
        assertTrue((decision as AutomationDecision.Refuse).showSuggestionInstead)
    }

    @Test
    fun refusesWhenTheRiskWasNeverAcknowledged() {
        val decision = guard.decide(context(settings = autoSettings.copy(acknowledgedAutomationRisk = false)))
        assertTrue(decision is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenEmergencyStopped() {
        assertTrue(guard.decide(context(emergencyStopped = true)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(settings = autoSettings.copy(emergencyStopped = true))) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenTheComposerIsMissingOrUnsure() {
        assertTrue(guard.decide(context(composerFound = false)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(composerConfidence = 0.3f)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenThereIsNoSendControl() {
        assertTrue(guard.decide(context(sendTargetKind = SendTargetKind.UNAVAILABLE)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesCoordinateTapsUnlessTheyWereExplicitlyConfigured() {
        val gesture = context(sendTargetKind = SendTargetKind.GESTURE_POINT, sendConfidence = 0.5f)
        assertTrue(guard.decide(gesture) is AutomationDecision.Refuse)
        val confidentGesture = context(sendTargetKind = SendTargetKind.GESTURE_POINT, sendConfidence = 0.95f)
        assertEquals(AutomationDecision.Allow, guard.decide(confidentGesture))
    }

    @Test
    fun refusesWhenTheReplyIsEmptyOrFailedValidation() {
        assertTrue(guard.decide(context(replyNonEmpty = false)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(replyPassedValidation = false)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesOnSensitiveScreensAndUnexpectedAppSwitches() {
        assertTrue(guard.decide(context(sensitiveScreen = true)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(activeAppChanged = true)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenTheRecipientIsUnclear() {
        assertTrue(guard.decide(context(recipientClear = false)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenTheUserIsTypingOrTheChatIsPaused() {
        assertTrue(guard.decide(context(userIsTyping = true)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(conversationPaused = true)) is AutomationDecision.Refuse)
    }

    @Test
    fun refusesWhenDetectionOrOcrConfidenceIsTooLow() {
        assertTrue(guard.decide(context(detectionConfidence = 0.1f)) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(usedOcr = true, ocrConfidence = 0.2f)) is AutomationDecision.Refuse)
        // OCR that is not being used must not block anything.
        assertEquals(AutomationDecision.Allow, guard.decide(context(usedOcr = false, ocrConfidence = 0.1f)))
    }

    @Test
    fun refusesInSuggestAndApproveModes() {
        assertTrue(guard.decide(context(settings = autoSettings.copy(mode = AssistantMode.SUGGEST))) is AutomationDecision.Refuse)
        assertTrue(guard.decide(context(settings = autoSettings.copy(mode = AssistantMode.APPROVE))) is AutomationDecision.Refuse)
    }

    @Test
    fun everyRefusalExplainsItself() {
        val decision = guard.decide(context(composerFound = false))
        assertTrue(decision is AutomationDecision.Refuse)
        assertTrue((decision as AutomationDecision.Refuse).reason.isNotBlank())
    }
}
