package com.liveaireply.app.engine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.liveaireply.app.automation.AutomationController
import com.liveaireply.app.di.AppContainer
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.util.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide glue between the accessibility service, the foreground service, the
 * overlay and the UI.
 *
 * Android gives services no shared constructor, so a small runtime object is the
 * standard way to hand the same engine instance to all of them. It holds no Context of
 * its own beyond the application context.
 */
object AssistantRuntime {

    /** Immediate process-wide latch checked before reading, capture, generation or automation. */
    @Volatile
    var emergencyStopRequested: Boolean = false
        private set

    @Volatile
    var container: AppContainer? = null

    @Volatile
    var engine: ReplyEngine? = null

    @Volatile
    var automation: AutomationController? = null

    @Volatile
    var overlayPositionUpdater: ((Int, Int) -> Unit)? = null

    private val _overlayState = MutableStateFlow(OverlayState())
    val overlayState: StateFlow<OverlayState> = _overlayState

    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors

    fun requireContainer(context: Context): AppContainer =
        container ?: AppContainer(context).also { container = it }

    fun eventLog(context: Context): EventLog = requireContainer(context).eventLog

    fun publishOverlay(state: OverlayState) {
        _overlayState.value = state
    }

    fun publishError(headline: String, detail: String) {
        _errors.value = "$headline\n$detail"
    }

    fun clearError() {
        _errors.value = null
    }

    fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(ClipData.newPlainText("Live AI Reply", text))
    }

    fun updateSettings(settings: AppSettings) {
        container?.currentSettings = settings
        if (settings.emergencyStopped) emergencyStopRequested = true
    }

    fun requestEmergencyStop() {
        emergencyStopRequested = true
    }

    /** Only an explicit user start action may clear the process-wide stop latch. */
    fun clearEmergencyStopForUserStart() {
        emergencyStopRequested = false
    }

    /** Drops every active engine reference; the emergency-stop latch remains sticky. */
    fun shutdown() {
        engine = null
        // The accessibility service may still be connected. It owns and clears
        // [automation] in its own lifecycle; dropping it here would make a later explicit
        // restart unable to type until Android reconnects the accessibility service.
        _overlayState.value = OverlayState(status = AssistantStatus.STOPPED, statusDetail = "Stopped")
    }
}
