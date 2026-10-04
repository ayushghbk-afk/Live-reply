package com.liveaireply.app.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.engine.OverlayAction

/**
 * Handles notification actions.
 *
 * Registered in the manifest so the actions keep working even if the app process was
 * recreated, and each one forwards to the same engine entry point the overlay uses, so
 * there is exactly one implementation of "stop".
 */
class AssistantActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val engine = AssistantRuntime.engine
        when (intent.action) {
            AssistantService.ACTION_PAUSE -> engine?.pauseAll("Paused from the notification")
            AssistantService.ACTION_RESUME -> engine?.resume()
            AssistantService.ACTION_STOP -> {
                engine?.stopAll("Stopped from the notification")
                context.startService(
                    Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)
                )
            }
            ACTION_COPY -> engine?.handleAction(OverlayAction.COPY)
            ACTION_SEND -> engine?.handleAction(OverlayAction.SEND)
        }
    }

    companion object {
        const val ACTION_COPY = "com.liveaireply.app.action.COPY"
        const val ACTION_SEND = "com.liveaireply.app.action.SEND_REPLY"
    }
}
