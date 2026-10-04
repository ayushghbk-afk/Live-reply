package com.liveaireply.app.automation

/** Result of writing text into the composer. */
data class InsertResult(
    val success: Boolean,
    /** What the field actually contains afterwards, when it could be read back. */
    val verifiedText: String?,
    val reason: String
) {
    val verified: Boolean
        get() = success && verifiedText != null

    companion object {
        fun ok(verifiedText: String?) = InsertResult(true, verifiedText, "Text inserted")
        fun failed(reason: String) = InsertResult(false, null, reason)
    }
}

/** Result of pressing send. */
data class SendResult(
    val success: Boolean,
    val reason: String,
    val usedGesture: Boolean = false
) {
    companion object {
        fun ok(viaGesture: Boolean = false) = SendResult(true, "Sent", viaGesture)
        fun failed(reason: String) = SendResult(false, reason, false)
    }
}

/**
 * The platform side of automation. Implemented by the accessibility service; faked in
 * tests. Every method is best-effort and reports *why* it failed, because the engine
 * has to explain that to the user.
 */
interface AutomationController {
    /** True when a composer was located in the current window. */
    fun hasComposer(): Boolean

    fun composerConfidence(): Float

    fun composerContent(): String?

    fun insertText(text: String, simulateTyping: Boolean, charsPerSecond: Int): InsertResult

    fun clearComposer(): Boolean

    fun sendComposer(): SendResult

    fun sendConfidence(): Float

    fun usesGestureFallback(): Boolean

    /** Package currently in the foreground, used to detect unexpected app switches. */
    fun foregroundPackage(): String?

    /** Chat title / contact name, when it could be read. */
    fun chatTitle(): String?
}
