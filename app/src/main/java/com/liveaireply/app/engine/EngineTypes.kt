package com.liveaireply.app.engine

import com.liveaireply.app.settings.AssistantMode

/** What the overlay dot and the notification show. */
enum class AssistantStatus(val glyph: String, val label: String) {
    STOPPED("\u25A0", "Stopped"),
    IDLE("\u25CB", "Idle"),
    MONITORING("\u25CF", "Monitoring"),
    THINKING("\u231B", "Thinking..."),
    REPLY_READY("\u2713", "Reply ready"),
    ERROR("\u26A0", "Error"),
    PAUSED("\u23F8", "Paused")
}

enum class LogSeverity { DEBUG, INFO, WARN, ERROR }

data class EngineLogEntry(
    val atMs: Long,
    val severity: LogSeverity,
    val message: String,
    /** Stable tag so the log screen can group events, e.g. "detect", "ai", "send". */
    val tag: String = "engine"
)

/** Everything the overlay needs to render. Immutable on purpose. */
data class OverlayState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val statusDetail: String = "",
    val mode: AssistantMode = AssistantMode.SUGGEST,
    val monitoring: Boolean = false,
    val currentAppLabel: String? = null,
    val latestIncomingText: String? = null,
    val replyText: String? = null,
    val replyEditable: Boolean = false,
    val errorMessage: String? = null,
    val conversationId: String? = null,
    val canSend: Boolean = false,
    val sendBlockedReason: String? = null,
    val modelUsed: String? = null,
    val latencyMs: Long? = null,
    val expanded: Boolean = false
) {
    val hasSuggestion: Boolean get() = !replyText.isNullOrBlank()
}

enum class OverlayAction {
    SEND, EDIT, REGENERATE, COPY, REJECT,
    PAUSE_CHAT, PAUSE_ALL, RESUME, STOP, START, TOGGLE_EXPAND
}

/** Platform callbacks. Implemented by the service/overlay; faked in tests. */
interface EngineHost {
    fun onStatusChanged(status: AssistantStatus, detail: String)
    fun onOverlayStateChanged(state: OverlayState)
    fun onLog(entry: EngineLogEntry)
    fun onCopyToClipboard(text: String)
    fun onError(headline: String, detail: String)
    /** Called when the user asked for a full stop so the service can shut itself down. */
    fun onEngineStopped()
}

/** Lets a long pipeline notice that the user hit STOP mid-flight. */
interface Cancellation {
    fun isCancelled(): Boolean

    companion object {
        val NEVER: Cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
        }
    }
}

/** Persisted "pause this chat" list. Backed by settings storage on Android. */
interface ConversationPauseController {
    fun isPaused(conversationId: String): Boolean
    fun pause(conversationId: String)
    fun resume(conversationId: String)
    fun pausedConversations(): List<String>
}

/** What the engine returns to its caller after handling one snapshot or action. */
sealed interface EngineResult {
    data class Nothing(val reason: String) : EngineResult
    data class ManualReplyNoted(val conversationId: String) : EngineResult
    data class Suggested(
        val reply: String,
        val conversationId: String,
        val incomingText: String,
        val sendBlockedReason: String?
    ) : EngineResult
    data class AutoSent(val reply: String, val conversationId: String) : EngineResult
    data class SentByUser(val reply: String, val conversationId: String) : EngineResult
    data class SuggestedInsteadOfAuto(val reply: String, val reason: String) : EngineResult
    data class InvalidReply(val text: String, val explanation: String) : EngineResult
    data class AiFailed(val headline: String, val detail: String) : EngineResult
    data class AutomationFailed(val reply: String, val reason: String) : EngineResult
    data object Stopped : EngineResult
}
