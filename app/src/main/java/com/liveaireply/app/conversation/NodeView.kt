package com.liveaireply.app.conversation

/**
 * Platform neutral mirror of an accessibility node.
 *
 * The Android layer ([com.liveaireply.app.accessibility.NodeMapper]) converts
 * `AccessibilityNodeInfo` trees into this shape, and the OCR layer produces a
 * simplified version of it from recognised text blocks. Everything downstream
 * (adapters, direction classification, input/send-button location) only ever sees
 * [NodeView], which is what makes the detection logic unit-testable on a plain JVM
 * without Robolectric or an emulator.
 */
data class RectView(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val area: Long get() = width.toLong() * height.toLong()

    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersects(other: RectView): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun contains(x: Int, y: Int): Boolean =
        x in left until right && y in top until bottom

    /** Fraction of this rect's area that is covered by [other]. */
    fun overlapRatioWith(other: RectView): Float {
        val overlapW = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val overlapH = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        val overlap = overlapW.toLong() * overlapH.toLong()
        val mine = area
        return if (mine <= 0L) 0f else (overlap.toFloat() / mine.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        val EMPTY = RectView(0, 0, 0, 0)
    }
}

data class NodeView(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val packageName: String?,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false,
    val isLongClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isVisibleToUser: Boolean = true,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    val isPassword: Boolean = false,
    val bounds: RectView = RectView.EMPTY,
    val depth: Int = 0,
    val index: Int = 0,
    val children: List<NodeView> = emptyList()
) {
    /** Text a human would read from this node. */
    val displayText: String?
        get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

    val simpleClassName: String
        get() = className?.substringAfterLast('.') ?: ""

    val isTextBearing: Boolean
        get() = !displayText.isNullOrBlank()
}

/** Depth-first traversal including the receiver. */
fun NodeView.flatten(): List<NodeView> {
    val out = ArrayList<NodeView>()
    val stack = ArrayDeque<NodeView>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        out += node
        // Reverse so children come out in natural left-to-right / top-to-bottom order.
        for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
    }
    return out
}

fun NodeView.findFirst(predicate: (NodeView) -> Boolean): NodeView? =
    flatten().firstOrNull(predicate)

fun NodeView.findAll(predicate: (NodeView) -> Boolean): List<NodeView> =
    flatten().filter(predicate)

/**
 * Editable nodes that could plausibly be a chat composer. Password fields are
 * excluded by definition - the app must never type into them.
 */
fun NodeView.findEditableCandidates(): List<NodeView> = flatten()
    .filter { it.isEditable && !it.isPassword && it.isVisibleToUser && it.isEnabled }

/** The node the framework reports as focused, if any. */
fun NodeView.findFocusedEditable(): NodeView? =
    findFirst { it.isEditable && it.isFocused && !it.isPassword }

fun NodeView.findScrollableContainers(): List<NodeView> =
    flatten().filter { it.isScrollable }
