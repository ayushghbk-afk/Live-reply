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

    @Volatile
    var container: AppContainer? = null

    @Volatile
    var engine: ReplyEngine? = null

    @Volatile
    var automation: AutomationController? = null

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
    }

    /** Drops every reference; called when the foreground service is destroyed. */
    fun shutdown() {
        engine = null
        automation = null
        _overlayState.value = OverlayState()
    }
}
