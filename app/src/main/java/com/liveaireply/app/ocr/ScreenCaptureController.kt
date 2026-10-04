package com.liveaireply.app.ocr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.liveaireply.app.settings.OcrRegion

/**
 * Single-frame screen capture for the OCR fallback.
 *
 * This is deliberately not a continuous capture loop: one frame per OCR request, then
 * the virtual display is released. The screenshot is processed locally and discarded;
 * it is only kept if the user turned on diagnostic logging.
 */
class ScreenCaptureController(private val context: Context) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    val isReady: Boolean get() = projection != null

    fun attach(resultCode: Int, data: Intent) {
        release()
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
    }

    fun release() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    private fun metrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    /**
     * Captures one frame, optionally cropped to the app's saved region, and returns the
     * bitmap. Returns null when capture is unavailable (permission revoked, DRM screen).
     */
    @SuppressLint("WrongConstant")
    fun captureFrame(region: OcrRegion?): Bitmap? {
        val activeProjection = projection ?: return null
        val metrics = metrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) return null

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = activeProjection.createVirtualDisplay(
            "live-ai-reply-ocr",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )

        // Give the mirror a moment to produce its first frame.
        var image: Image? = null
        val deadline = System.currentTimeMillis() + 1_500L
        while (System.currentTimeMillis() < deadline && image == null) {
            image = reader.acquireLatestImage()
            if (image == null) Thread.sleep(40L)
        }
        val captured = image ?: run {
            releaseDisplay()
            return null
        }

        return try {
            toBitmap(captured, width, height)?.let { crop(it, region, width, height) }
        } finally {
            runCatching { captured.close() }
            releaseDisplay()
        }
    }

    private fun releaseDisplay() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
    }

    private fun toBitmap(image: Image, width: Int, height: Int): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        return if (bitmapWidth == width) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, width, height).also { if (it !== bitmap) bitmap.recycle() }
    }

    private fun crop(source: Bitmap, region: OcrRegion?, screenWidth: Int, screenHeight: Int): Bitmap {
        if (region == null) return source
        val pixels = region.toPixels(screenWidth, screenHeight)
        val left = pixels.left.coerceIn(0, source.width - 1)
        val top = pixels.top.coerceIn(0, source.height - 1)
        val width = pixels.width.coerceAtMost(source.width - left)
        val height = pixels.height.coerceAtMost(source.height - top)
        if (width <= 1 || height <= 1) return source
        return Bitmap.createBitmap(source, left, top, width, height)
    }

    fun projectionIntent(): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }
}
