package com.liveaireply.app.adapters

import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.RectView

/** A screen coordinate, used only for the explicitly configured gesture fallback. */
data class PointView(val x: Int, val y: Int)

/** Where the chat composer (the "Type a message" box) was found. */
data class ComposerCandidate(
    val node: NodeView?,
    val signature: String?,
    val bounds: RectView,
    val confidence: Float,
    val reason: String
) {
    val isUsable: Boolean get() = node != null && confidence > 0f && !bounds.isEmpty
}

enum class SendTargetKind {
    /** Perform an accessibility action on a located node. Preferred. */
    NODE_ACTION,

    /** Tap an explicit, user-configured coordinate. Only when the user set one. */
    GESTURE_POINT,

    /** Nothing reliable was found - the caller must not send. */
    UNAVAILABLE
}

data class SendTarget(
    val kind: SendTargetKind,
    val node: NodeView? = null,
    val point: PointView? = null,
    val confidence: Float = 0f,
    val reason: String = ""
) {
    companion object {
        fun unavailable(reason: String) =
            SendTarget(SendTargetKind.UNAVAILABLE, null, null, 0f, reason)
    }
}

/**
 * Per-app overrides entered by the user in Settings -> Supported apps.
 *
 * Chat apps change their internal view ids often; overrides let a user fix a broken
 * adapter without a new build, and are the only sanctioned way to use a coordinate tap.
 */
data class AdapterOverrides(
    val composerViewId: String? = null,
    val sendButtonViewId: String? = null,
    val sendButtonDescription: String? = null,
    val titleViewId: String? = null,
    val sendPoint: PointView? = null,
    /** Extra content-description keywords that mark a bubble as outgoing. */
    val extraOutgoingWords: List<String> = emptyList(),
    val extraIncomingWords: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = composerViewId.isNullOrBlank() && sendButtonViewId.isNullOrBlank() &&
            sendButtonDescription.isNullOrBlank() && titleViewId.isNullOrBlank() &&
            sendPoint == null && extraOutgoingWords.isEmpty() && extraIncomingWords.isEmpty()

    companion object {
        val NONE = AdapterOverrides()
    }
}
