package com.liveaireply.app.adapters

import com.liveaireply.app.conversation.DirectionHints
import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.NoiseFilter
import com.liveaireply.app.conversation.RectView
import com.liveaireply.app.conversation.findEditableCandidates
import com.liveaireply.app.conversation.findFocusedEditable
import com.liveaireply.app.conversation.flatten

/**
 * Heuristic adapter used for any app that does not have a specialised one.
 *
 * It relies only on things that are true of essentially every chat UI:
 *  - the composer is the editable field nearest the bottom of the screen;
 *  - the send control sits at the same height as the composer, to its right, and is
 *    labelled "Send" in text, hint or content description;
 *  - bubbles on the right half are yours, bubbles on the left half are theirs.
 */
open class GenericChatAdapter(
    override val id: String = "generic",
    override val displayName: String = "Any chat app",
    override val supportedPackages: Set<String> = emptySet()
) : ChatAdapter {

    /**
     * Subclasses inherit this, so it must be driven by [supportedPackages] and not be
     * an unconditional true: the generic adapter has an empty set (it accepts anything)
     * while every specialised adapter names the packages it owns.
     */
    override fun supports(packageName: String): Boolean =
        supportedPackages.isEmpty() ||
            supportedPackages.any { it.equals(packageName, ignoreCase = true) }

    override fun directionHints(overrides: AdapterOverrides): DirectionHints =
        DirectionHints(
            outgoingDescriptionWords = DEFAULT_OUTGOING_WORDS + overrides.extraOutgoingWords,
            incomingDescriptionWords = DEFAULT_INCOMING_WORDS + overrides.extraIncomingWords,
            systemDescriptionWords = DEFAULT_SYSTEM_WORDS,
            systemTextPatterns = DEFAULT_SYSTEM_TEXT
        )

    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        // 1. An explicit user supplied view id always wins.
        overrides.composerViewId?.takeIf { it.isNotBlank() }?.let { wanted ->
            val match = root.flatten().firstOrNull { node ->
                node.isEditable && !node.isPassword &&
                    node.viewIdResourceName?.contains(wanted, ignoreCase = true) == true
            }
            if (match != null) {
                return ComposerCandidate(match, match.viewIdResourceName, match.bounds, 0.98f, "user override view id")
            }
        }

        // 2. Whatever the framework says is focused.
        root.findFocusedEditable()?.let { focused ->
            return ComposerCandidate(focused, focused.viewIdResourceName, focused.bounds, 0.9f, "focused editable field")
        }

        val candidates = root.findEditableCandidates()
        if (candidates.isEmpty()) return null

        // 3. An editable field whose hint mentions messaging.
        val hinted = candidates.maxByOrNull { composerHintScore(it) }
        if (hinted != null && composerHintScore(hinted) > 0) {
            return ComposerCandidate(hinted, hinted.viewIdResourceName, hinted.bounds, 0.85f, "hint mentions messaging")
        }

        // 4. The lowest (and reasonably wide) editable field on screen.
        val widestLow = candidates
            .filter { it.bounds.width >= screenWidth * 0.25f || screenWidth == 0 }
            .maxByOrNull { it.bounds.bottom }
            ?: candidates.maxByOrNull { it.bounds.bottom }
            ?: return null

        return ComposerCandidate(
            node = widestLow,
            signature = widestLow.viewIdResourceName,
            bounds = widestLow.bounds,
            confidence = 0.6f,
            reason = "lowest editable field on screen"
        )
    }

    override fun locateSendTarget(
        root: NodeView,
        composer: ComposerCandidate?,
        overrides: AdapterOverrides
    ): SendTarget {
        val composerBounds = composer?.bounds

        // 1. Explicit view id.
        overrides.sendButtonViewId?.takeIf { it.isNotBlank() }?.let { wanted ->
            root.flatten().firstOrNull { it.isClickable && it.viewIdResourceName?.contains(wanted, true) == true }
                ?.let { return SendTarget(SendTargetKind.NODE_ACTION, it, null, 0.97f, "user override view id") }
        }

        // 2. Explicit content description.
        overrides.sendButtonDescription?.takeIf { it.isNotBlank() }?.let { wanted ->
            root.flatten().firstOrNull {
                it.isClickable &&
                    (it.contentDescription?.contains(wanted, true) == true || it.text?.contains(wanted, true) == true)
            }?.let { return SendTarget(SendTargetKind.NODE_ACTION, it, null, 0.95f, "user override label") }
        }

        val nodes = root.flatten().filter { it.isClickable && it.isVisibleToUser && it.isEnabled }
        val labelled = nodes.filter { matchesSendLabel(it) }
        if (labelled.isEmpty()) {
            // 3. Last resort: an explicit coordinate the user configured for this app.
            overrides.sendPoint?.let { point ->
                return SendTarget(SendTargetKind.GESTURE_POINT, null, point, 0.5f, "user configured coordinate")
            }
            return SendTarget.unavailable("no labelled send control found")
        }

        // Prefer a labelled control that sits on the composer's row, to its right.
        val ranked = if (composerBounds != null && !composerBounds.isEmpty) {
            labelled.sortedWith(
                compareByDescending<NodeView> { sameRow(it.bounds, composerBounds) }
                    .thenByDescending { it.bounds.centerX > composerBounds.centerX }
                    .thenByDescending { it.bounds.centerY }
            )
        } else {
            labelled.sortedByDescending { it.bounds.centerY }
        }

        val best = ranked.first()
        val confidence = when {
            composerBounds != null && sameRow(best.bounds, composerBounds) -> 0.85f
            composerBounds != null -> 0.6f
            else -> 0.5f
        }
        return SendTarget(
            kind = SendTargetKind.NODE_ACTION,
            node = best,
            point = null,
            confidence = confidence,
            reason = describe(best)
        )
    }

    override fun chatTitle(root: NodeView, screenHeight: Int, overrides: AdapterOverrides): String? {
        overrides.titleViewId?.takeIf { it.isNotBlank() }?.let { wanted ->
            root.flatten().firstOrNull { it.viewIdResourceName?.contains(wanted, true) == true }
                ?.displayText?.let { return it.trim() }
        }
        val headerLimit = if (screenHeight > 0) (screenHeight * 0.14f).toInt() else Int.MAX_VALUE
        return root.flatten()
            .filter { it.isTextBearing && !it.isEditable }
            .filter { it.bounds.bottom <= headerLimit || headerLimit == Int.MAX_VALUE }
            .mapNotNull { it.displayText?.trim() }
            .firstOrNull { it.length in 1..60 && !NoiseFilter.isNoise(it) }
    }

    override fun isNonChatActivity(activityName: String?): Boolean {
        val name = activityName?.lowercase() ?: return false
        return NON_CHAT_ACTIVITY_HINTS.any { name.contains(it) }
    }

    // ------------------------------------------------------------- internals

    private fun composerHintScore(node: NodeView): Int {
        val haystack = listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
            .joinToString(" ").lowercase()
        return COMPOSER_HINTS.count { haystack.contains(it) }
    }

    private fun matchesSendLabel(node: NodeView): Boolean {
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.isNotEmpty() && SEND_VIEW_ID_HINTS.any { viewId.contains(it) }) return true
        val description = node.contentDescription?.lowercase()?.trim().orEmpty()
        if (description.isNotEmpty() && SEND_LABELS.any { description == it || description.startsWith("$it ") }) return true
        val text = node.text?.lowercase()?.trim().orEmpty()
        return text.isNotEmpty() && SEND_LABELS.contains(text)
    }

    private fun sameRow(a: RectView, b: RectView): Boolean {
        if (a.isEmpty || b.isEmpty) return false
        val overlap = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        return overlap >= minOf(a.height, b.height) * 0.35f
    }

    private fun describe(node: NodeView): String =
        listOfNotNull(
            node.viewIdResourceName,
            node.contentDescription?.let { "desc='$it'" },
            node.text?.let { "text='$it'" }
        ).joinToString(", ").ifBlank { node.simpleClassName }

    companion object {
        val SEND_LABELS = setOf(
            "send", "send message", "send reply", "enviar", "envoyer", "senden",
            "отправить", "भेजें", "bhejein", " gönder", "kirim", "ส่ง"
        )
        val SEND_VIEW_ID_HINTS = listOf("send", "btn_send", "sendbutton", "send_button", "chat_send")
        val COMPOSER_HINTS = listOf(
            "message", "type", "write", "reply", "chat", "compose", "text", "message_text", "input"
        )
        val NON_CHAT_ACTIVITY_HINTS = listOf(
            "settings", "preference", "profile", "aboutactivity", "login", "signin", "signup",
            "password", "payment", "billing", "permissions", "onboarding", "wallpaper", "mediaview",
            "gallery", "camera", "contactinfo", "groupinfo"
        )
        val DEFAULT_OUTGOING_WORDS = listOf(
            "you:", "you :", "sent by you", "your message", "sent", "delivered", "read at",
            "outgoing", "mine"
        )
        val DEFAULT_INCOMING_WORDS = listOf(
            "received", "incoming", "unread message", "new message from", "from "
        )
        val DEFAULT_SYSTEM_WORDS = listOf("system message", "notice", "encrypted")
        val DEFAULT_SYSTEM_TEXT = listOf(
            "messages and calls are end-to-end encrypted",
            "end-to-end encrypted",
            "you blocked this contact",
            "this contact is no longer"
        )
    }
}
