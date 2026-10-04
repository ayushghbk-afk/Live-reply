package com.liveaireply.app.engine

import android.content.Context
import android.content.Intent
import com.liveaireply.app.ocr.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One implementation of the global STOP action used by the app, overlay and notification.
 *
 * The in-memory latch is set synchronously before any service teardown or DataStore work.
 * Accessibility event handling, OCR and automation all consult that latch, so no further
 * reply can slip through while the persistent setting is being written.
 */
object EmergencyStopController {

    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun stopNow(context: Context, reason: String = "Stopped by user") {
        val appContext = context.applicationContext
        AssistantRuntime.requestEmergencyStop()

        // stopAll clears pending suggestions and cancels automation at the engine boundary.
        // Its host callback reaches finishStop; if there is no engine, finish directly.
        val engine = AssistantRuntime.engine
        if (engine != null) engine.stopAll(reason) else finishStop(appContext)
    }

    /** Called by [EngineHostAndroid] after the engine has atomically changed to STOPPED. */
    fun finishStop(context: Context) {
        val appContext = context.applicationContext
        AssistantRuntime.requestEmergencyStop()

        // Explicit stopService calls are immediate and do not instantiate a stopped service.
        appContext.stopService(Intent(appContext, ScreenCaptureService::class.java))
        appContext.stopService(Intent(appContext, AssistantService::class.java))

        // Update the process cache synchronously as another defence against a stale
        // settings collector. The durable DataStore write follows on the IO scope.
        val container = AssistantRuntime.requireContainer(appContext)
        container.currentSettings = stoppedSettings(container.currentSettings)
        persistenceScope.launch {
            val stopped = container.settingsRepository.update(::stoppedSettings)
            container.currentSettings = stopped
        }
    }

    private fun stoppedSettings(settings: com.liveaireply.app.settings.AppSettings) =
        settings.copy(
            mode = com.liveaireply.app.settings.AssistantMode.SUGGEST,
            emergencyStopped = true,
            monitoringEnabled = false,
            autoReplyEnabled = false,
            acknowledgedAutomationRisk = false,
            overlayEnabled = false,
            ocrEnabled = false,
            captureScope = com.liveaireply.app.settings.CaptureScope.OFF
        )
}
