package com.liveaireply.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.liveaireply.app.MainActivity
import com.liveaireply.app.R
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.ocr.ScreenCaptureService

/**
 * The persistent notification.
 *
 * It always shows what the assistant is doing and always carries a Stop action, so the
 * user can kill automation without hunting for the app.
 */
class MonitoringNotifier(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(title: String, status: String, paused: Boolean = false): Notification =
        baseBuilder(title, status)
            .addAction(
                0,
                context.getString(if (paused) R.string.action_resume else R.string.action_pause),
                assistantIntent(if (paused) AssistantService.ACTION_RESUME else AssistantService.ACTION_PAUSE)
            )
            .addAction(0, context.getString(R.string.action_stop), assistantIntent(AssistantService.ACTION_STOP))
            .build()

    /** The OCR notification stops only the explicitly authorized capture session. */
    fun buildCapture(title: String, status: String): Notification =
        baseBuilder(title, status)
            .addAction(0, context.getString(R.string.action_stop), captureIntent())
            .build()

    fun update(title: String, status: String, paused: Boolean = false) {
        manager.notify(NOTIFICATION_ID, build(title, status, paused))
    }

    private fun baseBuilder(title: String, status: String) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai_dot)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("$status  |  $title")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$title"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), flags(PendingIntent.FLAG_UPDATE_CURRENT)
    )

    private fun assistantIntent(action: String): PendingIntent = PendingIntent.getService(
        context, action.hashCode(),
        Intent(context, AssistantService::class.java).setAction(action),
        flags(PendingIntent.FLAG_UPDATE_CURRENT)
    )

    private fun captureIntent(): PendingIntent = PendingIntent.getService(
        context, ScreenCaptureService.ACTION_RELEASE.hashCode(),
        Intent(context, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_RELEASE),
        flags(PendingIntent.FLAG_UPDATE_CURRENT)
    )

    private fun flags(base: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) base or PendingIntent.FLAG_IMMUTABLE else base

    companion object {
        const val CHANNEL_ID = "live_ai_reply_monitoring"
        const val NOTIFICATION_ID = 1001
    }
}
