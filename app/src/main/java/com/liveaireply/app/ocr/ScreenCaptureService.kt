package com.liveaireply.app.ocr

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.notifications.MonitoringNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** State displayed in Security & Settings. */
enum class OcrCaptureState(val label: String) {
    OFF("Off — no screen-capture session"),
    STARTING("Starting after Android confirmation"),
    ARMED("Authorized for one optional OCR fallback"),
    CAPTURING("Capturing one frame for on-device OCR")
}

/**
 * Foreground service that holds a user-approved, one-shot MediaProjection session.
 *
 * There is no automatic entry point. The only ARM intent is created after MainActivity
 * receives RESULT_OK from Android's standard screen-capture confirmation dialog. One frame
 * may then be used as an accessibility fallback; the projection, virtual display and image
 * are released immediately afterward. A fresh fallback requires fresh Android consent.
 *
 * Android's compositor excludes windows/layers marked FLAG_SECURE from MediaProjection.
 * This app uses the public MediaProjection API as-is and contains no alternate capture,
 * rooting, accessibility-screenshot, or other workaround for that protection. Pixels are
 * never written to disk or sent to an AI provider.
 */
class ScreenCaptureService : Service() {

    private var controller: ScreenCaptureController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        _captureState.value = OcrCaptureState.STARTING
        val notifier = MonitoringNotifier(this).apply { createChannel() }
        val notification = notifier.buildCapture("Optional OCR", "One screen capture authorized by you")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RELEASE) {
            releaseAndStop()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_ARM) {
            releaseAndStop()
            return START_NOT_STICKY
        }

        val settings = AssistantRuntime.requireContainer(applicationContext).currentSettings
        val allowed = settings.acknowledgedCapabilities && settings.ocrEnabled &&
            !settings.emergencyStopped && !AssistantRuntime.emergencyStopRequested
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (!allowed || resultCode != Activity.RESULT_OK || data == null) {
            AssistantRuntime.container?.eventLog?.log("Rejected screen-capture arm request", "ocr")
            releaseAndStop()
            return START_NOT_STICKY
        }

        val fresh = ScreenCaptureController(applicationContext) {
            // Android or the user revoked the MediaProjection. Do not leave an idle
            // foreground capture service or an apparently armed authorization behind.
            releaseAndStop()
        }
        val attached = runCatching { fresh.attach(resultCode, data) }.isSuccess && fresh.isReady
        if (!attached) {
            fresh.release()
            AssistantRuntime.container?.eventLog?.log("Android did not create the capture session", "ocr")
            releaseAndStop()
            return START_NOT_STICKY
        }

        controller?.release()
        controller = fresh
        _captureState.value = OcrCaptureState.ARMED
        AssistantRuntime.container?.eventLog?.log(
            "One-shot OCR capture authorized through Android confirmation",
            "ocr"
        )
        return START_NOT_STICKY
    }

    /**
     * Captures one frame and runs OCR locally. This consumes the one-shot authorization
     * whether recognition succeeds or fails, and always recycles the bitmap.
     */
    @Synchronized
    fun captureAndRecognise(region: com.liveaireply.app.settings.OcrRegion?): List<OcrLine> {
        if (AssistantRuntime.emergencyStopRequested) {
            releaseAndStop()
            return emptyList()
        }
        val active = controller ?: return emptyList()
        _captureState.value = OcrCaptureState.CAPTURING
        val extractor = OcrTextExtractor()
        return try {
            val bitmap = active.captureFrame(region) ?: return emptyList()
            try {
                extractor.extract(bitmap)
            } finally {
                bitmap.recycle()
            }
        } finally {
            extractor.close()
            releaseAndStop()
        }
    }

    @Synchronized
    private fun releaseAndStop() {
        controller?.release()
        controller = null
        _captureState.value = OcrCaptureState.OFF
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        controller?.release()
        controller = null
        if (instance === this) instance = null
        _captureState.value = OcrCaptureState.OFF
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM = "com.liveaireply.app.action.ARM_CAPTURE"
        const val ACTION_RELEASE = "com.liveaireply.app.action.RELEASE_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val CAPTURE_NOTIFICATION_ID = 1002

        private val _captureState = MutableStateFlow(OcrCaptureState.OFF)
        val captureState: StateFlow<OcrCaptureState> = _captureState

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        val isArmed: Boolean
            get() = _captureState.value == OcrCaptureState.ARMED && instance?.controller?.isReady == true
    }
}
