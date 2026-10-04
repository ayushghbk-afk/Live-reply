package com.liveaireply.app.adapters

import com.liveaireply.app.conversation.DirectionHints
import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.RectView
import com.liveaireply.app.conversation.flatten

/**
 * App specific adapters.
 *
 * Every one of them extends [GenericChatAdapter] and only adds *hints*: known resource
 * ids and content-description keywords. Nothing here is a hard requirement, because
 * chat apps rename their internal views between releases - if a hint stops matching,
 * the generic heuristics (and the user's overrides in Settings) take over.
 *
 * The view id strings below are the ones these apps have historically used; treat them
 * as accelerators, not as facts that must hold on every build.
 */

class WhatsAppAdapter : GenericChatAdapter(
    id = "whatsapp",
    displayName = "WhatsApp",
    supportedPackages = setOf("com.whatsapp", "com.whatsapp.w4b")
) {
    override fun directionHints(overrides: AdapterOverrides): DirectionHints =
        super.directionHints(overrides).copy(
            outgoingViewIdContains = listOf("message_text_right", "right_container", "outgoing"),
            incomingViewIdContains = listOf("message_text_left", "left_container", "incoming"),
            outgoingDescriptionWords = super.directionHints(overrides).outgoingDescriptionWords + "you:",
            systemTextPatterns = super.directionHints(overrides).systemTextPatterns +
                listOf("messages and calls are end-to-end encrypted", "you created group", "added you")
        )

    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull {
                it.isEditable && !it.isPassword &&
                    (it.viewIdResourceName?.contains("entry", true) == true ||
                        it.viewIdResourceName?.contains("message_edit_text", true) == true)
            }?.let {
                return ComposerCandidate(it, it.viewIdResourceName, it.bounds, 0.92f, "WhatsApp composer id")
            }
        }
        return super.locateComposer(root, screenWidth, screenHeight, overrides)
    }

    override fun locateSendTarget(
        root: NodeView,
        composer: ComposerCandidate?,
        overrides: AdapterOverrides
    ): SendTarget {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull { node ->
                node.isClickable && node.viewIdResourceName?.let {
                    it.endsWith(":id/send") || it.contains("send_message_button") || it.contains("sendbtn")
                } == true
            }?.let {
                return SendTarget(SendTargetKind.NODE_ACTION, it, null, 0.9f, "WhatsApp send button id")
            }
        }
        return super.locateSendTarget(root, composer, overrides)
    }

    override fun promptAddendum(): String =
        "The conversation is taking place on WhatsApp, a mobile instant messenger. " +
            "Keep replies short enough to read in one bubble."
}

class TelegramAdapter : GenericChatAdapter(
    id = "telegram",
    displayName = "Telegram",
    supportedPackages = setOf(
        "org.telegram.messenger", "org.telegram.messenger.web",
        "org.telegram.plus", "org.telegram.messenger.beta"
    )
) {
    override fun supports(packageName: String): Boolean = packageName.startsWith("org.telegram.")

    override fun directionHints(overrides: AdapterOverrides): DirectionHints =
        super.directionHints(overrides).copy(
            outgoingViewIdContains = listOf("bubble_out", "right"),
            incomingViewIdContains = listOf("bubble_in", "left"),
            outgoingDescriptionWords = super.directionHints(overrides).outgoingDescriptionWords + "your message"
        )

    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull {
                it.isEditable && !it.isPassword &&
                    it.viewIdResourceName?.contains("chat_message_input", true) == true
            }?.let {
                return ComposerCandidate(it, it.viewIdResourceName, it.bounds, 0.92f, "Telegram composer id")
            }
        }
        return super.locateComposer(root, screenWidth, screenHeight, overrides)
    }

    override fun locateSendTarget(
        root: NodeView,
        composer: ComposerCandidate?,
        overrides: AdapterOverrides
    ): SendTarget {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull {
                it.isClickable && it.viewIdResourceName?.contains("chat_send_button", true) == true
            }?.let {
                return SendTarget(SendTargetKind.NODE_ACTION, it, null, 0.9f, "Telegram send button id")
            }
        }
        return super.locateSendTarget(root, composer, overrides)
    }

    override fun promptAddendum(): String =
        "The conversation is taking place on Telegram. Short, direct messages fit the medium best."
}

class InstagramAdapter : GenericChatAdapter(
    id = "instagram",
    displayName = "Instagram",
    supportedPackages = setOf("com.instagram.android")
) {
    override fun directionHints(overrides: AdapterOverrides): DirectionHints =
        super.directionHints(overrides).copy(
            outgoingViewIdContains = listOf("outgoing", "self", "row_thread_self"),
            incomingViewIdContains = listOf("incoming", "other", "row_thread_other"),
            outgoingDescriptionWords = super.directionHints(overrides).outgoingDescriptionWords +
                listOf("your message", "sent by you")
        )

    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull {
                it.isEditable && !it.isPassword &&
                    it.viewIdResourceName?.let { id -> id.contains("composer", true) || id.contains("rich_input", true) } == true
            }?.let {
                return ComposerCandidate(it, it.viewIdResourceName, it.bounds, 0.88f, "Instagram composer id")
            }
        }
        return super.locateComposer(root, screenWidth, screenHeight, overrides)
    }

    override fun promptAddendum(): String =
        "The conversation is taking place in Instagram Direct. Tone is casual; avoid long paragraphs."
}

class DiscordAdapter : GenericChatAdapter(
    id = "discord",
    displayName = "Discord",
    supportedPackages = setOf("com.discord")
) {
    override fun directionHints(overrides: AdapterOverrides): DirectionHints =
        super.directionHints(overrides).copy(
            outgoingViewIdContains = listOf("message_own", "outgoing"),
            incomingViewIdContains = listOf("message_other", "incoming")
        )

    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        if (overrides.isEmpty) {
            root.flatten().firstOrNull {
                it.isEditable && !it.isPassword &&
                    it.viewIdResourceName?.let { id -> id.contains("message_input", true) || id.contains("chat_input", true) } == true
            }?.let {
                return ComposerCandidate(it, it.viewIdResourceName, it.bounds, 0.88f, "Discord composer id")
            }
        }
        return super.locateComposer(root, screenWidth, screenHeight, overrides)
    }

    override fun promptAddendum(): String =
        "The conversation is taking place on Discord. Replies can be a little more playful, " +
            "but stay under a couple of sentences unless the other person writes long messages."
}

/**
 * Browser based chat (web WhatsApp, Character.AI, ChatGPT web, ...).
 *
 * Browser accessibility trees are deep and rarely expose stable ids, so this adapter is
 * deliberately conservative: it never claims a coordinate, and it tells the engine to
 * prefer Suggest mode when confidence is low.
 */
class BrowserChatAdapter : GenericChatAdapter(
    id = "browser",
    displayName = "Browser chat",
    supportedPackages = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.brave.browser",
        "com.microsoft.emmx", "com.opera.browser", "com.sec.android.app.sbrowser",
        "org.chromium.webview_shell", "com.vivaldi.browser"
    )
) {
    override fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides
    ): ComposerCandidate? {
        val candidate = super.locateComposer(root, screenWidth, screenHeight, overrides)
        // Browser trees often expose a hidden or off-screen editable node; demand a
        // real, visible, reasonably wide field before we trust it.
        return when {
            candidate == null -> null
            candidate.bounds.isEmpty -> null
            screenWidth > 0 && candidate.bounds.width < screenWidth * 0.3f ->
                candidate.copy(confidence = minOf(candidate.confidence, 0.45f), reason = "browser field looks too narrow")
            else -> candidate
        }
    }

    override fun promptAddendum(): String =
        "The conversation is taking place in a web chat running inside a browser."
}

