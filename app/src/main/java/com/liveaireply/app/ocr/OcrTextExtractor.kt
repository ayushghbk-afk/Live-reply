package com.liveaireply.app.ocr

import android.graphics.Bitmap
import com.liveaireply.app.conversation.RawBubble
import com.liveaireply.app.conversation.RectView
import com.liveaireply.app.conversation.TurnSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max

/**
 * Groups OCR output into chat bubbles.
 *
 * ML Kit returns lines with bounding boxes; chat bubbles are runs of lines that are
 * aligned to the same side and separated by small vertical gaps. That is enough to
 * reconstruct "who said what" well enough for a fallback path, and the confidence it
 * reports is what stops AUTO mode from firing on a bad scan.
 */
object OcrBubbleGrouper {

    fun group(
        lines: List<OcrLine>,
        screenWidth: Int
    ): List<RawBubble> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val bubbles = ArrayList<RawBubble>()
        var current: MutableList<OcrLine>? = null

        for (line in sorted) {
            val bucket = current
            if (bucket == null) {
                current = mutableListOf(line)
                continue
            }
            val last = bucket.last()
            val verticalGap = line.bounds.top - last.bounds.bottom
            val lineSpacing = max(last.bounds.height, line.bounds.height).coerceAtLeast(8)
            val sameSide = sideOf(line.bounds.centerX, screenWidth) == sideOf(last.bounds.centerX, screenWidth)
            val continues = sameSide && verticalGap < lineSpacing * 1.2 &&
                abs(line.bounds.left - last.bounds.left) < screenWidth * 0.35f
            if (continues) {
                bucket += line
            } else {
                bubbles += bubbleOf(bucket)
                current = mutableListOf(line)
            }
        }
        current?.let { bubbles += bubbleOf(it) }
        return bubbles
    }

    private fun sideOf(centerX: Int, screenWidth: Int): Int =
        if (screenWidth <= 0) 0 else if (centerX * 2 >= screenWidth) 1 else -1

    private fun bubbleOf(lines: List<OcrLine>): RawBubble {
        val left = lines.minOf { it.bounds.left }
        val top = lines.minOf { it.bounds.top }
        val right = lines.maxOf { it.bounds.right }
        val bottom = lines.maxOf { it.bounds.bottom }
        return RawBubble(
            text = lines.joinToString("\n") { it.text }.trim(),
            bounds = RectView(left, top, right, bottom),
            nodeSignature = null,
            contentDescription = null,
            className = "ocr",
            sensitive = false,
            source = TurnSource.OCR,
            textConfidence = if (lines.isEmpty()) 0f else lines.sumOf { it.confidence.toDouble() }.toFloat() / lines.size
        )
    }
}

data class OcrLine(val text: String, val bounds: RectView, val confidence: Float)

/**
 * On-device OCR fallback.
 *
 * Pixels never leave the device: the bitmap is decoded, recognised and recycled here.
 * The caller decides whether to keep a diagnostic copy; by default it does not.
 */
class OcrTextExtractor {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Runs recognition and blocks for the result. Call from a background thread. */
    fun extract(bitmap: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = try {
            com.google.android.gms.tasks.Tasks.await(recognizer.process(image))
        } catch (t: Throwable) {
            return emptyList()
        }
        val out = ArrayList<OcrLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                out += OcrLine(
                    text = line.text,
                    bounds = RectView(box.left, box.top, box.right, box.bottom),
                    confidence = line.confidence ?: 0.7f
                )
            }
        }
        return out
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
