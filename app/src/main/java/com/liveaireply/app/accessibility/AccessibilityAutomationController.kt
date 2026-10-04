package com.liveaireply.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.liveaireply.app.adapters.AdapterOverrides
import com.liveaireply.app.adapters.ChatAdapter
import com.liveaireply.app.adapters.ComposerCandidate
import com.liveaireply.app.adapters.PointView
import com.liveaireply.app.adapters.SendTarget
import com.liveaireply.app.adapters.SendTargetKind
import com.liveaireply.app.automation.AutomationController
import com.liveaireply.app.automation.InsertResult
import com.liveaireply.app.automation.SendResult
import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.flatten

/**
 * Types into and sends from the chat that is currently on screen.
 *
 * Every action prefers an accessibility node action over a coordinate. A gesture tap is
 * only ever performed when the user configured an explicit point for that app, and the
 * engine refuses AUTO mode unless that point came with high confidence.
 */
class AccessibilityAutomationController(
    private val service: AccessibilityService,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val adapterProvider: () -> Pair<ChatAdapter, AdapterOverrides>,
    private val screenSize: () -> Pair<Int, Int>
) : AutomationController {

    @Volatile
    private var lastComposer: ComposerCandidate? = null

    @Volatile
    private var lastSendTarget: SendTarget = SendTarget.unavailable("not scanned yet")

    /** Re-scans the current window and caches composer + send target. */
    fun refresh(): Boolean {
        val rootInfo = rootProvider() ?: run {
            lastComposer = null
            lastSendTarget = SendTarget.unavailable("no window content")
            return false
        }
        val (width, height) = screenSize()
        val mapped = NodeMapper.map(rootInfo, null) ?: run {
            lastComposer = null
            lastSendTarget = SendTarget.unavailable("empty node tree")
            return false
        }
        val (adapter, overrides) = adapterProvider()
        lastComposer = adapter.locateComposer(mapped, width, height, overrides)
        lastSendTarget = adapter.locateSendTarget(mapped, lastComposer, overrides)
        return lastComposer != null
    }

    override fun hasComposer(): Boolean = lastComposer?.isUsable == true

    override fun composerConfidence(): Float = lastComposer?.confidence ?: 0f

    override fun composerContent(): String? = lastComposer?.node?.text

    override fun sendConfidence(): Float = lastSendTarget.confidence

    override fun usesGestureFallback(): Boolean = lastSendTarget.kind == SendTargetKind.GESTURE_POINT

    override fun foregroundPackage(): String? = lastComposer?.node?.packageName

    override fun chatTitle(): String? {
        val rootInfo = rootProvider() ?: return null
        val mapped = NodeMapper.map(rootInfo, null) ?: return null
        val (_, height) = screenSize()
        val (adapter, overrides) = adapterProvider()
        return adapter.chatTitle(mapped, height, overrides)
    }

    override fun clearComposer(): Boolean {
        val node = liveComposerNode() ?: return false
        return performAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundleOfText(""))
    }

    override fun insertText(text: String, simulateTyping: Boolean, charsPerSecond: Int): InsertResult {
        val node = liveComposerNode()
            ?: return InsertResult.failed(
                "Could not find the chat input field. The current app may not expose it " +
                    "through Accessibility - try Suggest mode or enable OCR."
            )

        val ok = if (simulateTyping && charsPerSecond > 0) {
            typeProgressively(node, text, charsPerSecond)
        } else {
            performAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundleOfText(text))
        }
        if (!ok) {
            // Some composers reject SET_TEXT; fall back to clipboard + paste, which uses
            // the app's own paste action rather than a synthetic key event.
            val pasted = pasteViaClipboard(node, text)
            if (!pasted) return InsertResult.failed("The chat box refused the text (SET_TEXT and paste both failed).")
        }

        val verified = readComposerText()
        return InsertResult.ok(verified)
    }

    override fun sendComposer(): SendResult {
        when (lastSendTarget.kind) {
            SendTargetKind.UNAVAILABLE ->
                return SendResult.failed(
                    "Could not find a Send button. The reply is on the overlay - send it manually."
                )

            SendTargetKind.NODE_ACTION -> {
                val target = lastSendTarget.node ?: return SendResult.failed("Send control disappeared")
                val live = findLiveNode(target)
                    ?: return SendResult.failed("Send control is no longer on screen")
                val clicked = performAction(live, AccessibilityNodeInfo.ACTION_CLICK, null)
                return if (clicked) SendResult.ok(false)
                else SendResult.failed("The Send button did not accept the click action")
            }

            SendTargetKind.GESTURE_POINT -> {
                val point = lastSendTarget.point ?: return SendResult.failed("No configured tap point")
                return if (tap(point)) SendResult.ok(true)
                else SendResult.failed("Gesture tap was rejected by the system")
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private fun liveComposerNode(): AccessibilityNodeInfo? {
        val signature = lastComposer?.takeIf { it.isUsable } ?: return null
        val rootInfo = rootProvider() ?: return null
        val mapped = NodeMapper.map(rootInfo, null) ?: return null
        val wanted = mapped.flatten().firstOrNull { node ->
            node.isEditable && !node.isPassword &&
                (node.viewIdResourceName == signature.signature || node.bounds == signature.bounds)
        } ?: return null
        return findLiveNode(wanted)
    }

    /** Finds the live framework node matching a mapped node, by id then by bounds. */
    private fun findLiveNode(target: NodeView): AccessibilityNodeInfo? {
        val rootInfo = rootProvider() ?: return null
        target.viewIdResourceName?.let { id ->
            val found = runCatching { rootInfo.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
            found?.firstOrNull()?.let { return it }
        }
        val rect = android.graphics.Rect(
            target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.bottom
        )
        val byBounds = ArrayList<AccessibilityNodeInfo>()
        collectAtBounds(rootInfo, rect, byBounds, 0)
        return byBounds.firstOrNull()
    }

    private fun collectAtBounds(
        node: AccessibilityNodeInfo,
        rect: android.graphics.Rect,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 40 || out.isNotEmpty()) return
        val bounds = android.graphics.Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds == rect) {
            out += node
            return
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectAtBounds(child, rect, out, depth + 1)
        }
    }

    private fun readComposerText(): String? {
        val rootInfo = rootProvider() ?: return null
        val mapped = NodeMapper.map(rootInfo, null) ?: return null
        return mapped.flatten().firstOrNull { it.isEditable && !it.isPassword }?.text
    }

    private fun bundleOfText(text: String): Bundle =
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }

    private fun performAction(node: AccessibilityNodeInfo, action: Int, args: Bundle?): Boolean =
        runCatching { node.performAction(action, args) }.getOrDefault(false)

    /**
     * Types progressively by setting ever longer prefixes.
     *
     * This is the only "typing simulation" Android actually permits without an input
     * method: there is no public API to inject key events into another app. It is
     * deliberately fast (the user configures the speed) and stops if a set fails.
     */
    private fun typeProgressively(node: AccessibilityNodeInfo, text: String, charsPerSecond: Int): Boolean {
        val stepSize = (charsPerSecond / 4).coerceIn(1, 12)
        val stepDelayMs = (250L / (charsPerSecond / stepSize).coerceAtLeast(1)).coerceIn(8L, 120L)
        var index = stepSize
        while (index < text.length) {
            val prefix = text.substring(0, index)
            if (!performAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundleOfText(prefix))) return false
            SystemClock.sleep(stepDelayMs)
            index += stepSize
        }
        return performAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundleOfText(text))
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager ?: return false
        val previous = runCatching { clipboard.primaryClip }.getOrNull()
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", text))
        val pasted = performAction(node, AccessibilityNodeInfo.ACTION_PASTE, null)
        runCatching {
            if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip()
        }
        return pasted
    }

    private fun tap(point: PointView): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 40L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { service.dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }
}
