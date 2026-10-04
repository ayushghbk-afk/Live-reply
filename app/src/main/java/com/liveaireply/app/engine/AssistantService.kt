package com.liveaireply.app.engine

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.lifecycle.LifecycleService
import com.liveaireply.app.notifications.MonitoringNotifier
import com.liveaireply.app.overlay.OverlayController
import com.liveaireply.app.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * User-controlled foreground assistant.
 *
 * The service does not start work in onCreate and is not sticky. ACTION_START is accepted
 * only after disclosure acceptance, explicit monitoring opt-in, and a connected
 * AccessibilityService. Overlay attachment additionally requires both the in-app toggle
 * and Android's draw-over-other-apps grant.
 */
class AssistantService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notifier: MonitoringNotifier? = null
    private var overlay: OverlayController? = null
    private var settingsJob: Job? = null
    private var started = false
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        notifier = MonitoringNotifier(this).apply { createChannel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val settings = AssistantRuntime.requireContainer(applicationContext).currentSettings
                if (!mayStart(settings)) {
                    AssistantRuntime.publishError(
                        "Assistant not started",
                        "Accept the disclosure, enable Accessibility, and turn Monitoring on."
                    )
                    stopSelfSafely()
                } else {
                    startAsForeground()
                    startAssistant()
                }
            }

            ACTION_PAUSE -> AssistantRuntime.engine?.pauseAll("Paused from the notification")
            ACTION_RESUME -> {
                val settings = AssistantRuntime.requireContainer(applicationContext).currentSettings
                if (mayStart(settings)) {
                    startAsForeground()
                    if (!started) startAssistant() else AssistantRuntime.engine?.resume()
                }
            }

            ACTION_STOP -> EmergencyStopController.stopNow(this, "Stopped from the notification")
            else -> stopSelfSafely()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun mayStart(settings: AppSettings): Boolean =
        settings.acknowledgedCapabilities && settings.monitoringEnabled &&
            !settings.emergencyStopped && !AssistantRuntime.emergencyStopRequested &&
            AssistantRuntime.automation != null

    private fun startAsForeground() {
        if (foreground) return
        val notification = notifier?.build(appLabel(null), "Monitoring enabled by you") ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                MonitoringNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(MonitoringNotifier.NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun startAssistant() {
        if (started) return
        started = true
        val context = applicationContext
        val container = AssistantRuntime.requireContainer(context)

        scope.launch {
            val settings = container.settingsRepository.settings.first()
            container.currentSettings = settings
            container.eventLog.debugEnabled = settings.debugLogging
            if (!mayStart(settings)) {
                stopSelfSafely()
                return@launch
            }

            val automationController = AssistantRuntime.automation ?: run {
                stopSelfSafely()
                return@launch
            }
            val pauses = DataStoreConversationPauses(container)
            val host = EngineHostAndroid(context) { stopSelfSafely() }
            val engine = ReplyEngine(
                detector = container.conversationDetector(),
                pipeline = container.replyPipeline(),
                automation = automationController,
                host = host,
                settingsProvider = { container.currentSettings },
                personaProvider = { container.personaRepository.selected() },
                conversationPauses = pauses
            )
            AssistantRuntime.engine = engine
            engine.start(settings)

            updateOverlay(settings)
            settingsJob?.cancel()
            settingsJob = scope.launch {
                container.settingsRepository.settings.collect { latest ->
                    container.currentSettings = latest
                    container.eventLog.debugEnabled = latest.debugLogging
                    if (!mayStart(latest)) {
                        stopSelfSafely()
                        return@collect
                    }
                    notifier?.update(appLabel(latest), statusLine())
                    updateOverlay(latest)
                }
            }
            notifier?.update(appLabel(settings), statusLine())
        }
    }

    private fun updateOverlay(settings: AppSettings) {
        val allowed = settings.acknowledgedCapabilities && settings.overlayEnabled &&
            settings.monitoringEnabled && !settings.emergencyStopped &&
            Settings.canDrawOverlays(this)
        if (allowed) {
            if (overlay == null) overlay = OverlayController(applicationContext, scope)
            overlay?.attach()
        } else {
            overlay?.detach()
        }
    }

    private fun statusLine(): String {
        val state = AssistantRuntime.overlayState.value
        return "${state.status.label} - ${state.statusDetail.ifBlank { "Ready" }}"
    }

    private fun appLabel(settings: AppSettings?): String {
        val container = AssistantRuntime.container ?: return "Live AI Reply"
        val mode = (settings ?: container.currentSettings).mode
        val automation = AssistantRuntime.automation
        val app = automation?.foregroundPackage()
        val adapterName = app?.let { container.adapterRegistry().forPackage(it).displayName }
        return buildString {
            append("Mode: ${mode.label}")
            if (adapterName != null) append(" | Watching $adapterName")
        }
    }

    private fun stopSelfSafely() {
        overlay?.detach()
        settingsJob?.cancel()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    override fun onDestroy() {
        overlay?.detach()
        settingsJob?.cancel()
        scope.cancel()
        AssistantRuntime.shutdown()
        super.onDestroy()
    }

    /** "Pause this chat" list backed by settings storage. */
    private class DataStoreConversationPauses(
        private val container: com.liveaireply.app.di.AppContainer
    ) : ConversationPauseController {
        override fun isPaused(conversationId: String) =
            container.currentSettings.isConversationPaused(conversationId)

        override fun pause(conversationId: String) = mutate { it + conversationId }

        override fun resume(conversationId: String) = mutate { list -> list.filter { it != conversationId } }

        override fun pausedConversations() = container.currentSettings.pausedConversations

        private fun mutate(transform: (List<String>) -> List<String>) {
            val next = transform(container.currentSettings.pausedConversations).distinct()
            container.currentSettings = container.currentSettings.copy(pausedConversations = next)
            kotlinx.coroutines.runBlocking {
                container.settingsRepository.update { it.copy(pausedConversations = next) }
            }
        }
    }

    companion object {
        const val ACTION_START = "com.liveaireply.app.action.START"
        const val ACTION_PAUSE = "com.liveaireply.app.action.PAUSE"
        const val ACTION_RESUME = "com.liveaireply.app.action.RESUME"
        const val ACTION_STOP = "com.liveaireply.app.action.STOP"
    }
}
