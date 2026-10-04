package com.liveaireply.app.security

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.liveaireply.app.accessibility.LiveReplyAccessibilityService

/** Reads the system grant, rather than confusing it with this process's connection state. */
object CapabilityStatus {
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, LiveReplyAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun overlayGranted(context: Context): Boolean = Settings.canDrawOverlays(context)
}
