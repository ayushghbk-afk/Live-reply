package com.liveaireply.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.liveaireply.app.adapters.ChatAdapterRegistry
import com.liveaireply.app.conversation.BubbleExtractor
import com.liveaireply.app.conversation.ConversationSnapshot
import com.liveaireply.app.conversation.LanguageDetector
import com.liveaireply.app.conversation.TurnSource
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.engine.LogSeverity
import com.liveaireply.app.engine.SnapshotInput
import com.liveaireply.app.util.Debouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's eyes and hands.
 *
 * It only *observes* window changes; the decision making lives in the (unit tested)
 * engine. Work is debounced, done off the accessibility thread, and skipped entirely
 * when the assistant is stopped, paused, or looking at an excluded/sensitive screen.
 */
class LiveReplyAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val debouncer = Debouncer()
    private val extractor = BubbleExtractor()

    private var pendingJob: Job? = null
    private var pollJob: Job? = null
    private var controller: AccessibilityAutomationController? = null
    private var lastPackage: String? = null
    private var lastEventAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        val automation = AccessibilityAutomationController(
            service = this,
            rootProvider = { rootInActiveWindow },
            adapterProvider = {
                val container = AssistantRuntime.container
                val pkg = lastPackage.orEmpty()
                val registry = container?.adapterRegistry() ?: ChatAdapterRegistry()
                registry.forPackage(pkg) to registry.overridesFor(pkg)
            },
            screenSize = { screenSize() }
        )
        controller = automation
        AssistantRuntime.automation = automation
        AssistantRuntime.requireContainer(applicationContext).eventLog
            .log("Accessibility service connected", "accessibility")
        startService(Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_START))
        startPollingIfConfigured()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val container = AssistantRuntime.container ?: return
        val settings = container.currentSettings
        if (settings.emergencyStopped || !settings.monitoringEnabled) return
        if (!AssistantRuntime.engine?.isRunning().orFalse()) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return          // ignore our own UI
        if (settings.isExcluded(packageName)) return
        if (!settings.isPackageEnabled(packageName) && !settings.enabledPackages.isEmpty()) {
            // Still allowed to look, but the engine's policy gate will refuse to reply.
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> lastPackage = packageName
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Unit
            else -> return
        }

        lastEventAtMs = System.currentTimeMillis()
        debouncer.onChanged("$packageName/${event.windowId}")
        scheduleProcessing(settings.debounceMs.coerceIn(150L, 5_000L))
    }

    override fun onInterrupt() {
        AssistantRuntime.container?.eventLog?.log("Accessibility interrupted", "accessibility", LogSeverity.WARN)
    }

    override fun onDestroy() {
        pendingJob?.cancel()
        pollJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (AssistantRuntime.automation === controller) AssistantRuntime.automation = null
        scope.cancel()
        super.onDestroy()
    }

    /** True while the user is actively typing in the composer. */
    private fun userIsTyping(): Boolean =
        System.currentTimeMillis() - lastEventAtMs < 1_500L &&
            AssistantRuntime.automation?.composerContent()?.isNotBlank() == true

    private fun scheduleProcessing(delayMs: Long) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!debouncer.isQuiet(delayMs)) return@launch
            processNow()
        }
    }

    private fun processNow() {
        val container = AssistantRuntime.container ?: return
        val engine = AssistantRuntime.engine ?: return
        val automation = controller ?: return
        val packageName = lastPackage ?: rootInActiveWindow?.packageName?.toString() ?: return
        val settings = container.currentSettings

        scope.launch {
            val snapshot = withContext(Dispatchers.Default) { buildSnapshot(packageName, settings) } ?: return@launch
            automation.refresh()
            val verdict = container.sensitiveScreenPolicy.evaluate(
                packageName = packageName,
                activityName = snapshot.activityName,
                root = null,
                excludedPackages = settings.excludedPackages
            )
            val detectedLanguage = snapshot.newestTurn?.let { LanguageDetector.detect(it.text) }
            engine.handleSnapshot(
                SnapshotInput(
                    snapshot = snapshot,
                    userIsTyping = userIsTyping(),
                    sensitive = verdict,
                    otherPartyName = snapshot.screenLabel,
                    detectedLanguage = detectedLanguage
                )
            )
        }
    }

    private fun buildSnapshot(
        packageName: String,
        settings: com.liveaireply.app.settings.AppSettings
    ): ConversationSnapshot? {
        val rootInfo: AccessibilityNodeInfo = rootInActiveWindow ?: return null
        val (width, height) = screenSize()
        val container = AssistantRuntime.container ?: return null
        val registry = container.adapterRegistry()
        val adapter = registry.forPackage(packageName)
        val overrides = registry.overridesFor(packageName)

        val mapped = NodeMapper.map(rootInfo, packageName) ?: return null
        val composer = adapter.locateComposer(mapped, width, height, overrides)
        val turns = extractor.extractTurns(
            root = mapped,
            screenWidth = width,
            screenHeight = height,
            hints = adapter.directionHints(overrides),
            composerBounds = composer?.bounds,
            capturedAtMs = System.currentTimeMillis()
        )
        return ConversationSnapshot(
            packageName = packageName,
            activityName = null,
            screenLabel = adapter.chatTitle(mapped, height, overrides),
            turns = turns,
            capturedAtMs = System.currentTimeMillis(),
            hasEditableInput = composer?.isUsable == true,
            editableFieldSignature = composer?.signature,
            screenWidth = width,
            screenHeight = height,
            source = TurnSource.ACCESSIBILITY,
            extractionConfidence = 1f
        )
    }

    private fun startPollingIfConfigured() {
        scope.launch {
            val container = AssistantRuntime.container ?: return@launch
            container.settingsRepository.settings.collect { settings ->
                pollJob?.cancel()
                val interval = settings.pollingIntervalMs
                if (interval <= 0L || !settings.monitoringEnabled) return@collect
                pollJob = scope.launch {
                    while (true) {
                        kotlinx.coroutines.delay(interval)
                        if (AssistantRuntime.engine?.isRunning() == true) scheduleProcessing(0L)
                    }
                }
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun Boolean?.orFalse(): Boolean = this == true

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        fun isRunning(): Boolean = connected
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }
}
