package com.liveaireply.app.adapters

import com.liveaireply.app.conversation.DirectionHints
import com.liveaireply.app.conversation.NodeView

/**
 * Everything the engine needs to know about one chat application.
 *
 * Implementations only ever see [NodeView] trees, never `AccessibilityNodeInfo`, which
 * is what lets each adapter be unit tested with hand-built trees.
 */
interface ChatAdapter {

    /** Stable id used for persisted per-app settings. */
    val id: String

    val displayName: String

    /** Package names this adapter claims. Empty means "generic fallback". */
    val supportedPackages: Set<String>

    fun supports(packageName: String): Boolean =
        supportedPackages.any { it.equals(packageName, ignoreCase = true) }

    /** Bubble direction knowledge for this app. */
    fun directionHints(overrides: AdapterOverrides = AdapterOverrides.NONE): DirectionHints

    /**
     * Find the message composer. Returns null (never a guess) when no editable field
     * can be identified with confidence - the engine then refuses to auto-send.
     */
    fun locateComposer(
        root: NodeView,
        screenWidth: Int,
        screenHeight: Int,
        overrides: AdapterOverrides = AdapterOverrides.NONE
    ): ComposerCandidate?

    /**
     * Find how to send. Must never invent a coordinate: a GESTURE_POINT target is only
     * produced when the user explicitly configured one in [AdapterOverrides.sendPoint].
     */
    fun locateSendTarget(
        root: NodeView,
        composer: ComposerCandidate?,
        overrides: AdapterOverrides = AdapterOverrides.NONE
    ): SendTarget

    /** Contact / group name of the open chat, used to build the conversation id. */
    fun chatTitle(
        root: NodeView,
        screenHeight: Int,
        overrides: AdapterOverrides = AdapterOverrides.NONE
    ): String?

    /** True when this activity is known NOT to be a chat (settings, profile, calls...). */
    fun isNonChatActivity(activityName: String?): Boolean

    /** Extra instructions appended to the system prompt for this app, if any. */
    fun promptAddendum(): String? = null
}
