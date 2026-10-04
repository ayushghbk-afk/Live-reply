package com.liveaireply.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import com.liveaireply.app.engine.AssistantRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    private var attached = false

    fun attach() {
        if (attached) return
        val settings = AssistantRuntime.container?.currentSettings ?: return
        if (!settings.overlayEnabled) return

        val owner = OverlayLifecycleOwner().also { lifecycleOwner = it }
        val view = ComposeView(context)
        ViewTreeLifecycleOwner.set(view, owner)
        view.setContent { OverlayContent() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayPosition.x
            y = settings.overlayPosition.y
        }
        params = layoutParams
        runCatching { windowManager.addView(view, layoutParams) }
            .onFailure { AssistantRuntime.publishError("Could not show the overlay", it.message.orEmpty()) }
        rootView = view
        attached = true
        owner.resume()

        scope.launch {
            AssistantRuntime.overlayState.collectLatest { state ->
                view.setContent { OverlayContent() }
            }
        }
    }

    fun updatePosition(x: Int, y: Int) {
        val view = rootView ?: return
        val layoutParams = params ?: return
        layoutParams.x = x
        layoutParams.y = y
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
        kotlinx.coroutines.runBlocking {
            AssistantRuntime.container?.settingsRepository?.update {
                it.copy(overlayPosition = com.liveaireply.app.adapters.PointView(x, y))
            }
        }
    }

    fun detach() {
        val view = rootView ?: return
        runCatching { windowManager.removeView(view) }
        lifecycleOwner?.destroy()
        rootView = null
        lifecycleOwner = null
        attached = false
    }
}
