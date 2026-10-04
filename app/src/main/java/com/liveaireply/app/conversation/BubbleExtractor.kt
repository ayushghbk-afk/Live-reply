package com.liveaireply.app.conversation

/** Tuning for [BubbleExtractor]. */
data class BubbleExtractorConfig(
    /** Fraction of screen height reserved at the top for the app/toolbar header. */
    val headerFraction: Float = 0.09f,
    /** Never read bubbles from below this fraction of the screen (input bar area). */
    val footerFraction: Float = 0.86f,
    /** Ignore bubbles smaller than this in either dimension (icons, badges, dots). */
    val minBubbleSidePx: Int = 8,
    /** Drop bubbles that cover almost the whole screen (dialogs, full-screen media). */
    val maxScreenCoverage: Float = 0.72f
)

/**
 * Turns a [NodeView] tree into the list of candidate chat bubbles on screen.
 *
 * The important detail is de-duplication: chat apps routinely nest the same string in
 * several containers (bubble -> content -> text view -> accessibility clone). We walk
 * the tree and only keep a node's text when no descendant carries text of its own,
 * which yields exactly one bubble per visible bubble.
 */
class BubbleExtractor(
    private val classifier: DirectionClassifier = DirectionClassifier(),
    private val config: BubbleExtractorConfig = BubbleExtractorConfig()
) {

    /** Extract bubbles and immediately classify their direction. */
    fun extractTurns(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        hints: DirectionHints,
        composerBounds: RectView? = null,
        capturedAtMs: Long = 0L
    ): List<ChatTurn> =
        extractBubbles(root, screenWidth, screenHeight, composerBounds)
            .map { classifier.toTurn(it, screenWidth, hints, capturedAtMs) }

    fun extractBubbles(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        composerBounds: RectView? = null
    ): List<RawBubble> {
        val out = ArrayList<RawBubble>()
        val headerBottom = (screenHeight * config.headerFraction).toInt()
        val footerTop = if (composerBounds != null && composerBounds.height > 0) {
            composerBounds.top
        } else {
            (screenHeight * config.footerFraction).toInt()
        }
        val screenArea = screenWidth.toLong() * screenHeight.toLong()
        walk(root, out, headerBottom, footerTop, screenArea)
        return out.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun walk(
        node: NodeView,
        out: MutableList<RawBubble>,
        headerBottom: Int,
        footerTop: Int,
        screenArea: Long
    ) {
        if (node.isEditable || node.isPassword) return          // composer & secret fields
        if (!node.isVisibleToUser) return
        if (NoiseFilter.isSensitiveClassName(node.className)) return

        val hasTextBelow = node.children.any { subtreeHasText(it) }
        if (node.isTextBearing && !hasTextBelow) {
            toBubble(node, headerBottom, footerTop, screenArea)?.let { out += it }
            return
        }
        for (child in node.children) walk(child, out, headerBottom, footerTop, screenArea)
    }

    private fun subtreeHasText(node: NodeView): Boolean {
        if (node.isTextBearing) return true
        return node.children.any { subtreeHasText(it) }
    }

    private fun toBubble(
        node: NodeView,
        headerBottom: Int,
        footerTop: Int,
        screenArea: Long
    ): RawBubble? {
        val text = node.displayText?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (NoiseFilter.isNoise(text)) return null

        val bounds = node.bounds
        if (!bounds.isEmpty) {
            if (bounds.width < config.minBubbleSidePx || bounds.height < config.minBubbleSidePx) return null
            // Vertically centred content is accepted; only clear header/footer areas are cut.
            if (bounds.bottom <= headerBottom) return null
            if (bounds.top >= footerTop) return null
            if (screenArea > 0 && bounds.area.toFloat() / screenArea > config.maxScreenCoverage) return null
        }

        val sensitive = NoiseFilter.hasSensitiveHint(
            node.text, node.contentDescription, node.viewIdResourceName, node.className
        )

        return RawBubble(
            text = text,
            bounds = bounds,
            nodeSignature = node.viewIdResourceName,
            contentDescription = node.contentDescription,
            className = node.className,
            sensitive = sensitive,
            source = TurnSource.ACCESSIBILITY,
            textConfidence = 1f
        )
    }
}
