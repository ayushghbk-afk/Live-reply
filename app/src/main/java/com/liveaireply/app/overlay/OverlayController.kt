package com.liveaireply.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.liveaireply.app.engine.AssistantRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Lifecycle owner required by ComposeView when it is not inside an Activity. */
private class OverlayLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/**
 * The floating AI control.
 *
 * A small draggable dot that expands into the suggestion card. It is a system overlay
 * window (TYPE_APPLICATION_OVERLAY) so it can sit on top of the chat app, and it is
 * torn down completely when the assistant stops.
 */
class OverlayController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: ComposeView? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var params: WindowManager.LayoutParams? = null
    private var stateJob: Job? = null
    private var attached = false

    fun attach() {
        if (attached) return
        val settings = AssistantRuntime.container?.currentSettings ?: return
        if (!settings.acknowledgedCapabilities || !settings.overlayEnabled ||
            settings.emergencyStopped || AssistantRuntime.emergencyStopRequested ||
            !Settings.canDrawOverlays(context)
        ) return

        val owner = OverlayLifecycleOwner().also { lifecycleOwner = it }
        val view = ComposeView(context)
        // Since Lifecycle 2.6 ViewTreeLifecycleOwner is Kotlin-only and is set through
        // the View extension below (the old ViewTreeLifecycleOwner.set() is Java-only).
        view.setViewTreeLifecycleOwner(owner)
        view.setContent { OverlayContent() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val initialState = AssistantRuntime.overlayState.value
        val layoutParams = WindowManager.LayoutParams(
            widthFor(initialState.expanded),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            flagsFor(initialState.expanded),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayPosition.x
            y = settings.overlayPosition.y
        }
        params = layoutParams
        val added = runCatching { windowManager.addView(view, layoutParams) }
            .onFailure { AssistantRuntime.publishError("Could not show the overlay", it.message.orEmpty()) }
            .isSuccess
        if (!added) {
            owner.destroy()
            lifecycleOwner = null
            params = null
            return
        }
        rootView = view
        attached = true
        AssistantRuntime.overlayPositionUpdater = ::updatePosition
        owner.resume()

        // A collapsed WRAP_CONTENT window is only dot-sized. Explicitly resize and make
        // the window focusable when state expands so the card is not clipped and its edit
        // field can receive keyboard input. Collapse restores the non-focusable dot.
        stateJob?.cancel()
        stateJob = scope.launch {
            AssistantRuntime.overlayState.collectLatest { state ->
                applyExpandedState(state.expanded)
            }
        }
    }

    private fun applyExpandedState(expanded: Boolean) {
        val view = rootView ?: return
        val layoutParams = params ?: return
        layoutParams.width = widthFor(expanded)
        layoutParams.flags = flagsFor(expanded)

        val metrics = context.resources.displayMetrics
        val settings = AssistantRuntime.container?.currentSettings
        val desiredX = settings?.overlayPosition?.x ?: layoutParams.x
        val desiredY = settings?.overlayPosition?.y ?: layoutParams.y
        val windowWidth = if (expanded) layoutParams.width else (52 * metrics.density).roundToInt()
        layoutParams.x = desiredX.coerceIn(0, (metrics.widthPixels - windowWidth).coerceAtLeast(0))
        layoutParams.y = desiredY.coerceIn(0, (metrics.heightPixels - 52 * metrics.density).roundToInt().coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        view.requestLayout()
    }

    private fun widthFor(expanded: Boolean): Int {
        if (!expanded) return WindowManager.LayoutParams.WRAP_CONTENT
        val metrics = context.resources.displayMetrics
        return (340 * metrics.density).roundToInt().coerceAtMost(
            (metrics.widthPixels - 16 * metrics.density).roundToInt().coerceAtLeast(1)
        )
    }

    private fun flagsFor(expanded: Boolean): Int {
        val secureLayoutFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Suggestions and visible conversation excerpts in this app's own overlay
            // must not appear in screenshots or another MediaProjection session.
            WindowManager.LayoutParams.FLAG_SECURE
        return if (expanded) secureLayoutFlags
        else secureLayoutFlags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

    fun updatePosition(x: Int, y: Int) {
        val view = rootView ?: return
        val layoutParams = params ?: return
        layoutParams.x = x
        layoutParams.y = y
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        val container = AssistantRuntime.container ?: return
        val point = com.liveaireply.app.adapters.PointView(x, y)
        container.currentSettings = container.currentSettings.copy(overlayPosition = point)
        scope.launch {
            val saved = container.settingsRepository.update { it.copy(overlayPosition = point) }
            container.currentSettings = saved
        }
    }

    fun detach() {
        stateJob?.cancel()
        stateJob = null
        val view = rootView
        if (view != null) runCatching { windowManager.removeView(view) }
        lifecycleOwner?.destroy()
        AssistantRuntime.overlayPositionUpdater = null
        rootView = null
        lifecycleOwner = null
        params = null
        attached = false
    }
}
