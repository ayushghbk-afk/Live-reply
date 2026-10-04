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
import kotlinx.coroutines.withContext

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
    private var lastPackage: String? = null
    private var lastActivity: String? = null
    private var lastEventAtMs = 0L

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
                    ContextCompat.startForegroundService(
                        this@LiveReplyAccessibilityService,
                        Intent(this@LiveReplyAccessibilityService, AssistantService::class.java)
                            .setAction(AssistantService.ACTION_START)
                    )
                }
            }
        }

        val settings = container.currentSettings
        updatePackageScope(settings.enabledPackages)
        if (canMonitor(settings)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AssistantService::class.java).setAction(AssistantService.ACTION_START)
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (lastPackage != packageName) lastPackage = packageName
            }
            else -> return
        }

        lastEventAtMs = System.currentTimeMillis()
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
        return super.onUnbind(intent)
    }

    /** True while the user is actively typing in the composer. */
    private fun userIsTyping(): Boolean =
        System.currentTimeMillis() - lastEventAtMs < 1_500L &&
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

        scope.launch {
            val built = withContext(Dispatchers.Default) {
                buildSnapshot(packageName, lastActivity, settings)
            } ?: return@launch
            if (AssistantRuntime.emergencyStopRequested) return@launch

            automation.refresh()
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
                verdict
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
                SensitiveScreenVerdict.CLEAR
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
                SensitiveScreenVerdict.CLEAR
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
                SensitiveScreenVerdict(true, "OCR detected a credential or payment screen")
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
            SensitiveScreenVerdict.CLEAR
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
        val verdict: SensitiveScreenVerdict
    )

    companion object {
        @Volatile
        var connected: Boolean = false
            private set

        fun isRunning(): Boolean = connected
    }
}
