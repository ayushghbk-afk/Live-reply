package com.liveaireply.app.ocr

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.liveaireply.app.R
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.notifications.MonitoringNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that holds the MediaProjection session.
 *
 * Android 10+ requires a foreground service of type mediaProjection to be running while
 * a projection is in use, and Android 14 requires the permission request to happen
 * before the projection is created. The service therefore exists only while OCR is
 * armed, and is stopped as soon as the frame has been processed.
 */
class ScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: ScreenCaptureController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val notifier = MonitoringNotifier(this).apply { createChannel() }
        val notification = notifier.build("Screen capture", "Reading the screen for OCR")
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
        when (intent?.action) {
            ACTION_ARM -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val fresh = ScreenCaptureController(applicationContext)
                fresh.attach(resultCode, data)
                controller = fresh
                AssistantRuntime.container?.eventLog?.log("Screen capture armed", "ocr")
            }

            ACTION_RELEASE -> {
                controller?.release()
                controller = null
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Captures one frame and runs OCR. Pixels are processed here and discarded unless
     * debug logging is enabled by the user.
     */
    fun captureAndRecognise(region: com.liveaireply.app.settings.OcrRegion?): List<OcrLine> {
        val active = controller ?: return emptyList()
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
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        controller?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ARM = "com.liveaireply.app.action.ARM_CAPTURE"
        const val ACTION_RELEASE = "com.liveaireply.app.action.RELEASE_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val CAPTURE_NOTIFICATION_ID = 1002

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }
}
