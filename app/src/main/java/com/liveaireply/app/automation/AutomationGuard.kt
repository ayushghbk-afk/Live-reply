package com.liveaireply.app.automation

import com.liveaireply.app.adapters.SendTargetKind
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode

/** Everything the guard needs to decide whether automatic sending is safe. */
data class AutomationContext(
    val settings: AppSettings,
    val composerFound: Boolean,
    val composerConfidence: Float,
    val sendTargetKind: SendTargetKind,
    val sendConfidence: Float,
    val replyPassedValidation: Boolean,
    val replyNonEmpty: Boolean,
    val sensitiveScreen: Boolean,
    val userIsTyping: Boolean,
    val activeAppChanged: Boolean,
    val recipientClear: Boolean,
    val detectionConfidence: Float,
    val ocrConfidence: Float,
    val usedOcr: Boolean,
    val conversationPaused: Boolean,
    val emergencyStopped: Boolean
)

sealed interface AutomationDecision {
    data object Allow : AutomationDecision
    data class Refuse(val reason: String, val showSuggestionInstead: Boolean = true) : AutomationDecision
}

/**
 * The single place that decides whether the app may type and send by itself.
 *
 * Every condition from the "important automation rule" is checked here, and refusing
 * always means "show the suggested reply instead".
 */
class AutomationGuard {

    fun decide(context: AutomationContext): AutomationDecision {
        if (context.emergencyStopped) return AutomationDecision.Refuse("AI is stopped")
        if (context.settings.emergencyStopped) return AutomationDecision.Refuse("AI is stopped")
        if (!context.settings.autoSendPermitted()) {
            return AutomationDecision.Refuse("Automatic sending is not enabled", showSuggestionInstead = true)
        }
        if (context.settings.mode != AssistantMode.AUTO) {
            return AutomationDecision.Refuse("Mode is ${context.settings.mode.label}")
        }
        if (context.conversationPaused) return AutomationDecision.Refuse("This chat is paused")
        if (context.sensitiveScreen) return AutomationDecision.Refuse("Sensitive or excluded screen")
        if (context.activeAppChanged) return AutomationDecision.Refuse("The active app changed unexpectedly")
        if (context.userIsTyping) return AutomationDecision.Refuse("You are typing")
        if (!context.recipientClear) return AutomationDecision.Refuse("The recipient is unclear")
        if (!context.composerFound || context.composerConfidence < MIN_COMPOSER_CONFIDENCE) {
            return AutomationDecision.Refuse("Could not confidently identify the chat input field")
        }
        if (context.sendTargetKind == SendTargetKind.UNAVAILABLE) {
            return AutomationDecision.Refuse("Could not find a reliable send control")
        }
        if (context.sendTargetKind == SendTargetKind.GESTURE_POINT &&
            context.sendConfidence < MIN_GESTURE_CONFIDENCE
        ) {
            return AutomationDecision.Refuse("Send control would need an unverified tap")
        }
        if (context.sendConfidence < MIN_SEND_CONFIDENCE) {
            return AutomationDecision.Refuse("Send control confidence too low")
        }
        if (!context.replyNonEmpty) return AutomationDecision.Refuse("The reply is empty")
        if (!context.replyPassedValidation) return AutomationDecision.Refuse("The reply failed validation")
        if (context.detectionConfidence < context.settings.minIncomingConfidence) {
            return AutomationDecision.Refuse("Message direction confidence too low")
        }
        if (context.usedOcr && context.ocrConfidence < context.settings.ocrMinConfidence) {
            return AutomationDecision.Refuse("OCR confidence too low")
        }
        return AutomationDecision.Allow
    }

    companion object {
        const val MIN_COMPOSER_CONFIDENCE = 0.6f
        const val MIN_SEND_CONFIDENCE = 0.6f
        const val MIN_GESTURE_CONFIDENCE = 0.9f
    }
}
