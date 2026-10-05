package com.liveaireply.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.liveaireply.app.adapters.ChatAdapterRegistry
import com.liveaireply.app.conversation.BubbleExtractor
import com.liveaireply.app.conversation.ConversationSnapshot
import com.liveaireply.app.conversation.DirectionClassifier
import com.liveaireply.app.conversation.LanguageDetector
import com.liveaireply.app.conversation.NoiseFilter
import com.liveaireply.app.conversation.TurnSource
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.engine.LogSeverity
import com.liveaireply.app.engine.SnapshotInput
import com.liveaireply.app.ocr.OcrBubbleGrouper
import com.liveaireply.app.ocr.ScreenCaptureService
import com.liveaireply.app.security.SensitiveScreenVerdict
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.CaptureScope
import com.liveaireply.app.util.Debouncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads visible conversation nodes in apps the user explicitly enabled and exposes the
 * composer/send controls to the guarded reply engine.
 *
 * The service does no work until the in-app disclosure has been accepted and monitoring
 * has been deliberately enabled. Its packageNames scope is updated to the user's enabled
 * package list, so Android does not deliver events from unrelated apps. Password fields,
 * sensitive screens and excluded packages are rejected before extraction or optional OCR.
 */
class LiveReplyAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val debouncer = Debouncer()
    private val extractor = BubbleExtractor()
    private val directionClassifier = DirectionClassifier()

    private var pendingJob: Job? = null
    private var pollJob: Job? = null
    private var settingsJob: Job? = null
    private var controller: AccessibilityAutomationController? = null
    @Volatile
    private var lastPackage: String? = null
    @Volatile
    private var lastActivity: String? = null
    /** Updated only by text edits in an editable field, so "user is typing" means real typing. */
    @Volatile
    private var lastTypingAtMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        val container = AssistantRuntime.requireContainer(applicationContext)
        val automation = AccessibilityAutomationController(
            service = this,
            rootProvider = { rootInActiveWindow },
            adapterProvider = {
                val pkg = lastPackage.orEmpty()
                val registry = container.adapterRegistry()
                registry.forPackage(pkg) to registry.overridesFor(pkg)
            },
            screenSize = { screenSize() }
        )
        controller = automation
        AssistantRuntime.automation = automation
        container.eventLog.log("Accessibility service connected", "accessibility")

        settingsJob?.cancel()
        settingsJob = scope.launch {
            container.settingsRepository.settings.collect { settings ->
                container.currentSettings = settings
                updatePackageScope(settings.enabledPackages)
                restartPolling(settings)
                if (canMonitor(settings) && AssistantRuntime.engine == null) {
                    tryStartAssistant("settings changed")
                }
            }
        }

        val settings = container.currentSettings
        updatePackageScope(settings.enabledPackages)
        if (canMonitor(settings)) {
            tryStartAssistant("accessibility service connected")
        }
    }

    /**
     * Starts the foreground assistant safely. Android 12+ throws
     * ForegroundServiceStartNotAllowedException when the process was woken in the
     * background - which is exactly what happens when the system re-binds this
     * accessibility service after a reboot or after the app was swiped away while
     * monitoring was on. That must degrade to a log entry, never crash this service,
     * or Android enters a re-bind/crash loop.
     */
    private fun tryStartAssistant(reason: String) {
        val started = runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_START)
            )
        }.isSuccess
        if (!started) {
            AssistantRuntime.container?.eventLog?.log(
                "Could not start the foreground assistant ($reason); open the app to resume monitoring",
                "accessibility",
                LogSeverity.WARN
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || AssistantRuntime.emergencyStopRequested) return
        val container = AssistantRuntime.container ?: return
        val settings = container.currentSettings
        if (!canMonitor(settings)) return
        if (AssistantRuntime.engine?.isRunning() != true) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        // This check happens before rootInActiveWindow is touched. Even if Android briefly
        // delivers a stale event while packageNames is being updated, disabled apps are not read.
        if (!settings.isPackageEnabled(packageName) || settings.isExcluded(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastPackage = packageName
                lastActivity = event.className?.toString()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (lastPackage != packageName) lastPackage = packageName
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (lastPackage != packageName) lastPackage = packageName
                // Only an edit inside an editable node counts as typing. New incoming
                // bubbles also fire text-changed events and must not mark the user as
                // typing (which would suppress AUTO mode at random).
                val editInProgress = runCatching {
                    val source = event.source
                    val editable = source?.isEditable == true
                    source?.recycle()
                    editable
                }.getOrDefault(false)
                if (editInProgress) lastTypingAtMs = System.currentTimeMillis()
            }
            else -> return
        }

        debouncer.onChanged("$packageName/${event.windowId}")
        scheduleProcessing(settings.debounceMs.coerceIn(150L, 5_000L))
    }

    override fun onInterrupt() {
        AssistantRuntime.container?.eventLog?.log(
            "Accessibility interrupted",
            "accessibility",
            LogSeverity.WARN
        )
    }

    override fun onDestroy() {
        connected = false
        pendingJob?.cancel()
        pollJob?.cancel()
        settingsJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (AssistantRuntime.automation === controller) AssistantRuntime.automation = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        if (AssistantRuntime.automation === controller) AssistantRuntime.automation = null
        // Accessibility delivers no events after this, so the foreground assistant would
        // keep displaying "Monitoring" while being able to read nothing. Pause the engine
        // and tear the foreground service down; stopService is allowed from the
        // background, unlike startForegroundService. Re-enabling the service re-enters
        // onServiceConnected, which starts the assistant again when monitoring is on.
        AssistantRuntime.engine?.pauseAll("Accessibility service was turned off")
        runCatching {
            stopService(Intent(this, AssistantService::class.java))
        }
        return super.onUnbind(intent)
    }

    /** True while the user is actively typing in the composer. */
    private fun userIsTyping(): Boolean =
        System.currentTimeMillis() - lastTypingAtMs < 1_500L &&
            AssistantRuntime.automation?.composerContent()?.isNotBlank() == true

    private fun canMonitor(settings: AppSettings): Boolean =
        settings.acknowledgedCapabilities && settings.monitoringEnabled &&
            !settings.emergencyStopped && !AssistantRuntime.emergencyStopRequested

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
        val packageName = lastPackage ?: return
        val settings = container.currentSettings
        if (!canMonitor(settings) || !settings.isPackageEnabled(packageName)) return

        // Everything below blocks: the node-tree walk, the optional OCR fallback, then
        // the engine call with its AI network round trip, retry/backoff waits, AUTO-mode
        // reply delay and simulated typing. Running that on the main thread froze the
        // whole app (and stalled accessibility callbacks) for every generated reply, so
        // the complete pipeline runs on a background dispatcher.
        scope.launch(Dispatchers.Default) {
            val built = buildSnapshot(packageName, lastActivity, settings) ?: return@launch
            if (AssistantRuntime.emergencyStopRequested) return@launch

            // Refresh the automation cache from the tree buildSnapshot already mapped;
            // walking the window a second time on the main thread was pure double work.
            if (built.mapped != null) automation.refreshWith(built.mapped) else automation.refresh()
            val detectedLanguage = built.snapshot.newestTurn?.let { LanguageDetector.detect(it.text) }
            engine.handleSnapshot(
                SnapshotInput(
                    snapshot = built.snapshot,
                    userIsTyping = userIsTyping(),
                    sensitive = built.verdict,
                    otherPartyName = built.snapshot.screenLabel,
                    detectedLanguage = detectedLanguage
                )
            )
        }
    }

    /** Maps and evaluates the accessibility tree before any text extraction or OCR. */
    private fun buildSnapshot(
        packageName: String,
        activityName: String?,
        settings: AppSettings
    ): BuiltSnapshot? {
        if (AssistantRuntime.emergencyStopRequested) return null
        val (width, height) = screenSize()
        val container = AssistantRuntime.container ?: return null
        val registry = container.adapterRegistry()
        val adapter = registry.forPackage(packageName)
        val overrides = registry.overridesFor(packageName)

        val rootInfo: AccessibilityNodeInfo? = rootInActiveWindow
        val mapped = NodeMapper.map(rootInfo, packageName)
        val verdict = container.sensitiveScreenPolicy.evaluate(
            packageName = packageName,
            activityName = activityName,
            root = mapped,
            excludedPackages = settings.excludedPackages
        )
        if (verdict.sensitive) {
            return BuiltSnapshot(
                emptySnapshot(packageName, activityName, width, height, sensitive = true),
                verdict,
                mapped
            )
        }

        var title: String? = null
        var composerSignature: String? = null
        var hasComposer = false
        var turns = emptyList<com.liveaireply.app.conversation.ChatTurn>()
        if (mapped != null) {
            val composer = adapter.locateComposer(mapped, width, height, overrides)
            title = adapter.chatTitle(mapped, height, overrides)
            composerSignature = composer?.signature
            hasComposer = composer?.isUsable == true
            turns = extractor.extractTurns(
                root = mapped,
                screenWidth = width,
                screenHeight = height,
                hints = adapter.directionHints(overrides),
                composerBounds = composer?.bounds,
                capturedAtMs = System.currentTimeMillis()
            )
        }

        if (turns.isNotEmpty()) {
            return BuiltSnapshot(
                ConversationSnapshot(
                    packageName = packageName,
                    activityName = activityName,
                    screenLabel = title,
                    turns = turns,
                    capturedAtMs = System.currentTimeMillis(),
                    hasEditableInput = hasComposer,
                    editableFieldSignature = composerSignature,
                    screenWidth = width,
                    screenHeight = height,
                    source = TurnSource.ACCESSIBILITY,
                    extractionConfidence = 1f
                ),
                SensitiveScreenVerdict.CLEAR,
                mapped
            )
        }

        // OCR is a one-shot fallback only. It cannot create or authorize a projection;
        // MainActivity must already have received Android's explicit user confirmation.
        val capture = ScreenCaptureService.instance
        val canUseOcr = settings.ocrEnabled && settings.captureScope != CaptureScope.OFF &&
            ScreenCaptureService.isArmed && capture != null &&
            !AssistantRuntime.emergencyStopRequested
        if (!canUseOcr) {
            return BuiltSnapshot(
                emptySnapshot(packageName, activityName, width, height, sensitive = false, title = title),
                SensitiveScreenVerdict.CLEAR,
                mapped
            )
        }

        val region = when (settings.captureScope) {
            CaptureScope.OFF -> null
            CaptureScope.FULL_SCREEN -> null
            CaptureScope.CONVERSATION_AREA -> settings.ocrRegionFor(packageName)
        }
        val lines = capture.captureAndRecognise(region)
            .filter { it.text.isNotBlank() && !NoiseFilter.isNoise(it.text) }

        // OCR happens locally. If its text indicates a credential/payment screen, stop
        // here and do not construct an AI prompt from any of it.
        if (lines.any { NoiseFilter.hasSensitiveHint(it.text) }) {
            return BuiltSnapshot(
                emptySnapshot(packageName, activityName, width, height, sensitive = true),
                SensitiveScreenVerdict(true, "OCR detected a credential or payment screen"),
                mapped
            )
        }

        val bubbles = OcrBubbleGrouper.group(lines, width)
        val capturedAt = System.currentTimeMillis()
        val ocrTurns = bubbles.map {
            directionClassifier.toTurn(it, width, adapter.directionHints(overrides), capturedAt)
        }
        val confidence = if (ocrTurns.isEmpty()) 0f else
            ocrTurns.map { it.confidence }.average().toFloat()
        return BuiltSnapshot(
            ConversationSnapshot(
                packageName = packageName,
                activityName = activityName,
                screenLabel = title,
                turns = ocrTurns,
                capturedAtMs = capturedAt,
                hasEditableInput = hasComposer,
                editableFieldSignature = composerSignature,
                screenWidth = width,
                screenHeight = height,
                source = TurnSource.OCR,
                extractionConfidence = confidence
            ),
            SensitiveScreenVerdict.CLEAR,
            mapped
        )
    }

    private fun emptySnapshot(
        packageName: String,
        activityName: String?,
        width: Int,
        height: Int,
        sensitive: Boolean,
        title: String? = null
    ) = ConversationSnapshot(
        packageName = packageName,
        activityName = activityName,
        screenLabel = title,
        capturedAtMs = System.currentTimeMillis(),
        screenWidth = width,
        screenHeight = height,
        sensitiveScreen = sensitive
    )

    private fun restartPolling(settings: AppSettings) {
        pollJob?.cancel()
        val interval = settings.pollingIntervalMs
        if (interval <= 0L || !canMonitor(settings)) return
        pollJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(interval)
                if (AssistantRuntime.engine?.isRunning() == true) scheduleProcessing(0L)
            }
        }
    }

    /** Restricts Android event delivery to exactly the package names selected in-app. */
    private fun updatePackageScope(packages: List<String>) {
        val selected = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val info = serviceInfo ?: return
        // null means "all packages", so an empty selection intentionally scopes the service
        // to this app instead (its events are ignored above) rather than broadening access.
        info.packageNames = (selected.ifEmpty { listOf(applicationContext.packageName) }).toTypedArray()
        runCatching { serviceInfo = info }
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private data class BuiltSnapshot(
        val snapshot: ConversationSnapshot,
        val verdict: SensitiveScreenVerdict,
        /** The window tree walk that produced the snapshot; reused for the automation cache. */
        val mapped: com.liveaireply.app.conversation.NodeView? = null
    )

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        fun isRunning(): Boolean = connected
    }
}
