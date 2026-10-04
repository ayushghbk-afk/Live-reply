package com.liveaireply.app.engine

import android.content.Context
import com.liveaireply.app.util.EventLog

/** Android [EngineHost] that forwards to the runtime, the log buffer and a stop hook. */
class EngineHostAndroid(
    private val context: Context,
    private val onStop: () -> Unit
) : EngineHost {

    override fun onStatusChanged(status: AssistantStatus, detail: String) {
        AssistantRuntime.publishOverlay(AssistantRuntime.overlayState.value.copy(status = status, statusDetail = detail))
    }

    override fun onOverlayStateChanged(state: OverlayState) {
        AssistantRuntime.publishOverlay(state)
    }

    override fun onLog(entry: EngineLogEntry) {
        AssistantRuntime.requireContainer(context).eventLog.record(entry)
    }

    override fun onCopyToClipboard(text: String) {
        AssistantRuntime.copyToClipboard(context, text)
    }

    override fun onError(headline: String, detail: String) {
        AssistantRuntime.publishError(headline, detail)
        AssistantRuntime.requireContainer(context).eventLog.log("$headline: $detail", "error", LogSeverity.ERROR)
    }

    override fun onEngineStopped() {
        EmergencyStopController.finishStop(context)
        onStop()
    }

    companion object {
        fun logOf(context: Context): EventLog = AssistantRuntime.requireContainer(context).eventLog
    }
}
