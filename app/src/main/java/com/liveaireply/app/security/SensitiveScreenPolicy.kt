package com.liveaireply.app.security

import com.liveaireply.app.conversation.NodeView
import com.liveaireply.app.conversation.NoiseFilter
import com.liveaireply.app.conversation.findEditableCandidates
import com.liveaireply.app.conversation.flatten

data class SensitiveScreenVerdict(
    val sensitive: Boolean,
    val reason: String? = null
) {
    companion object {
        val CLEAR = SensitiveScreenVerdict(false, null)
    }
}

/**
 * Decides whether the app must look away.
 *
 * Three independent checks, any of which is enough:
 *  1. The package itself is a bank / wallet / password manager / authenticator.
 *  2. The visible tree contains a field that Android reports as a password or PIN, or
 *     whose hint/description advertises secret input (OTP, card number, CVV, ...).
 *  3. The user explicitly excluded the package or the activity.
 *
 * When the verdict is sensitive the engine stops detection, stops generation and stops
 * automation for as long as that screen is in front. Nothing is captured, hashed or
 * sent, and no screenshot is taken.
 */
class SensitiveScreenPolicy {

    fun evaluate(
        packageName: String?,
        activityName: String?,
        root: NodeView?,
        excludedPackages: List<String>
    ): SensitiveScreenVerdict {
        if (!packageName.isNullOrBlank()) {
            if (excludedPackages.any { it.equals(packageName, ignoreCase = true) }) {
                return SensitiveScreenVerdict(true, "You excluded $packageName")
            }
            if (NoiseFilter.isSensitivePackage(packageName)) {
                return SensitiveScreenVerdict(true, "$packageName looks like a banking or credential app")
            }
        }

        if (!activityName.isNullOrBlank()) {
            val lowered = activityName.lowercase()
            if (SENSITIVE_ACTIVITY_HINTS.any { lowered.contains(it) }) {
                return SensitiveScreenVerdict(true, "Screen name suggests credential entry")
            }
        }

        if (root != null) {
            for (node in root.flatten()) {
                if (node.isPassword || NoiseFilter.isSensitiveClassName(node.className)) {
                    return SensitiveScreenVerdict(true, "A password or PIN field is on screen")
                }
                if (node.isEditable && NoiseFilter.hasSensitiveHint(node.text, node.contentDescription)) {
                    return SensitiveScreenVerdict(true, "An input field asks for sensitive information")
                }
            }
            // A screen full of secret-looking labels but no editable field is still off limits.
            val labelHit = root.flatten().firstOrNull {
                NoiseFilter.hasSensitiveHint(it.text, it.contentDescription)
            }
            if (labelHit != null) {
                return SensitiveScreenVerdict(true, "Screen mentions sensitive information")
            }
        }

        return SensitiveScreenVerdict.CLEAR
    }

    /** True when the visible tree exposes no safe composer at all. */
    fun hasNoSafeComposer(root: NodeView?): Boolean {
        if (root == null) return true
        return root.findEditableCandidates().isEmpty()
    }

    companion object {
        val SENSITIVE_ACTIVITY_HINTS = listOf(
            "password", "passcode", "pin", "otp", "verify", "authentication", "login",
            "signin", "signup", "payment", "billing", "checkout", "card", "bank", "wallet",
            "biometric", "fingerprint", "keyguard", "credentials"
        )
    }
}
