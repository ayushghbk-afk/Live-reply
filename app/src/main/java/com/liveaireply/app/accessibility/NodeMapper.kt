package com.liveaireply.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.RectView

/**
 * Converts `AccessibilityNodeInfo` trees into the platform neutral [NodeView] shape.
 *
 * Every node it obtains is recycled: an accessibility tree for a chat window can hold
 * thousands of nodes, and leaking them leaks the whole window.
 */
object NodeMapper {

    private const val MAX_DEPTH = 40
    private const val MAX_NODES = 4_000

    fun map(root: AccessibilityNodeInfo?, packageName: String?): NodeView? {
        if (root == null) return null
        var visited = 0
        return try {
            mapNode(root, 0, 0, packageName) { visited++ < MAX_NODES }
        } finally {
            recycle(root)
        }
    }

    private fun mapNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int,
        packageName: String?,
        budget: () -> Boolean
    ): NodeView? {
        if (!budget()) return null
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val children = ArrayList<NodeView>()
        if (depth < MAX_DEPTH) {
            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                mapNode(child, depth + 1, i, packageName, budget)?.let { children += it }
                recycle(child)
            }
        }
        return NodeView(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            className = node.className?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            packageName = node.packageName?.toString() ?: packageName,
            isEditable = runCatching { node.isEditable }.getOrDefault(false),
            isClickable = node.isClickable,
            isLongClickable = node.isLongClickable,
            isScrollable = node.isScrollable,
            isVisibleToUser = runCatching { node.isVisibleToUser }.getOrDefault(true),
            isEnabled = node.isEnabled,
            isFocused = node.isFocused,
            isPassword = runCatching { node.isPassword }.getOrDefault(false),
            bounds = RectView(bounds.left, bounds.top, bounds.right, bounds.bottom),
            depth = depth,
            index = index,
            children = children
        )
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        runCatching { @Suppress("DEPRECATION") node?.recycle() }
    }
}
