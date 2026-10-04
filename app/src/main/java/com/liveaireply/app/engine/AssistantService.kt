package com.liveaireply.app.engine

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.liveaireply.app.notifications.MonitoringNotifier
import com.liveaireply.app.overlay.OverlayController
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the assistant for as long as the user wants it on.
 *
 * Android 14+ requires a declared foreground service type; `specialUse` is the honest
 * classification for "an accessibility-driven assistant with an overlay", and the
 * manifest declares the matching subtype property and permission.
 */
class AssistantService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notifier: MonitoringNotifier? = null
    private var overlay: OverlayController? = null
    private var settingsJob: Job? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        notifier = MonitoringNotifier(this)
        notifier?.createChannel()
        startAsForeground()
        if (AssistantRuntime.overlayState.value.status != AssistantStatus.STOPPED) {
            startAssistant()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startAssistant()
            ACTION_PAUSE -> AssistantRuntime.engine?.pauseAll("Paused from the notification")
            ACTION_RESUME -> AssistantRuntime.engine?.resume()
            ACTION_STOP -> {
                AssistantRuntime.engine?.stopAll("Stopped from the notification")
                stopSelfSafely()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startAsForeground() {
        val notification = notifier?.build(appLabel(null), "Starting") ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MonitoringNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(MonitoringNotifier.NOTIFICATION_ID, notification)
        }
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

            val automationController = AssistantRuntime.automation
            if (automationController == null) {
                container.eventLog.log(
                    "Accessibility service is not connected yet; waiting for events",
                    "lifecycle",
                    com.liveaireply.app.engine.LogSeverity.WARN
                )
            }

            val pauses = DataStoreConversationPauses(container)
            val host = EngineHostAndroid(context) { stopSelfSafely() }
            val engine = ReplyEngine(
                detector = container.conversationDetector(),
                pipeline = container.replyPipeline(),
                automation = automationController ?: UnavailableAutomation,
                host = host,
                settingsProvider = { container.currentSettings },
                personaProvider = { container.personaRepository.selected() },
                conversationPauses = pauses
            )
            AssistantRuntime.engine = engine
            engine.start(settings)

            overlay?.detach()
            if (settings.overlayEnabled) {
                overlay = OverlayController(context, scope).also { it.attach() }
            }

            settingsJob?.cancel()
            settingsJob = scope.launch {
                container.settingsRepository.settings.collect { latest ->
                    container.currentSettings = latest
                    container.eventLog.debugEnabled = latest.debugLogging
                    notifier?.update(appLabel(latest), statusLine())
                    if (!latest.overlayEnabled) overlay?.detach() else overlay?.attach()
                }
            }
            notifier?.update(appLabel(settings), statusLine())
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
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        overlay?.detach()
        settingsJob?.cancel()
        scope.cancel()
        AssistantRuntime.shutdown()
        super.onDestroy()
    }

    /** Used before the accessibility service has connected. */
    private object UnavailableAutomation : com.liveaireply.app.automation.AutomationController {
        override fun hasComposer() = false
        override fun composerConfidence() = 0f
        override fun composerContent(): String? = null
        override fun insertText(text: String, simulateTyping: Boolean, charsPerSecond: Int) =
            com.liveaireply.app.automation.InsertResult.failed("Accessibility service is not enabled")
        override fun clearComposer() = false
        override fun sendComposer() = com.liveaireply.app.automation.SendResult.failed("Accessibility service is not enabled")
        override fun sendConfidence() = 0f
        override fun usesGestureFallback() = false
        override fun foregroundPackage(): String? = null
        override fun chatTitle(): String? = null
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
