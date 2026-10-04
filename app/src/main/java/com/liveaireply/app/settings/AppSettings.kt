package com.liveaireply.app.settings

import com.liveaireply.app.adapters.AdapterOverrides
import com.liveaireply.app.adapters.PointView
import com.liveaireply.app.ai.openai.OpenAiEndpointConfig
import com.liveaireply.app.personas.PersonaPresets
import com.liveaireply.app.personas.ReplyLength

/** The three operating modes from the specification. */
enum class AssistantMode(val label: String, val description: String) {
    SUGGEST(
        "Suggest",
        "Generates a reply and shows it. Nothing is typed or sent until you act."
    ),
    APPROVE(
        "Approve",
        "Generates a reply and shows it with Send. Sends only after you tap Send."
    ),
    AUTO(
        "Auto",
        "Generates, types and sends automatically. STOP AI is always available."
    );

    val isAutomatic: Boolean get() = this == AUTO
}

enum class ReplyDelay(val label: String, val millis: Long) {
    INSTANT("Instant", 0L),
    ONE_SECOND("1 second", 1_000L),
    THREE_SECONDS("3 seconds", 3_000L),
    FIVE_SECONDS("5 seconds", 5_000L),
    TEN_SECONDS("10 seconds", 10_000L),
    CUSTOM("Custom", -1L)
}

enum class LanguagePolicy(val label: String) {
    AUTO("Auto - match the other person"),
    ENGLISH("English"),
    HINDI("Hindi"),
    HINGLISH("Hinglish"),
    SPANISH("Spanish"),
    FRENCH("French"),
    GERMAN("German"),
    CUSTOM("Custom instruction")
}

enum class ReplyLanguagePolicy(val label: String) {
    SAME_AS_INCOMING("Same language as the incoming message"),
    ENGLISH("English"),
    HINDI("Hindi"),
    HINGLISH("Hinglish"),
    SPANISH("Spanish"),
    FRENCH("French"),
    GERMAN("German"),
    CUSTOM("Custom instruction")
}

enum class CaptureScope(val label: String) {
    OFF("Off - accessibility text only"),
    FULL_SCREEN("Full screen"),
    CONVERSATION_AREA("Conversation area (cropped)")
}

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/**
 * Cropped OCR region, stored as fractions of the screen so it survives rotation and
 * different resolutions. One region per app package.
 */
data class OcrRegion(
    val packageName: String,
    val leftFraction: Float = 0f,
    val topFraction: Float = 0.18f,
    val rightFraction: Float = 1f,
    val bottomFraction: Float = 0.84f
) {
    init {
        require(leftFraction in 0f..1f && rightFraction in 0f..1f) { "fractions must be 0..1" }
        require(topFraction in 0f..1f && bottomFraction in 0f..1f) { "fractions must be 0..1" }
        require(rightFraction > leftFraction) { "right must be greater than left" }
        require(bottomFraction > topFraction) { "bottom must be greater than top" }
    }

    fun toPixels(screenWidth: Int, screenHeight: Int): OcrRegionPixels = OcrRegionPixels(
        left = (leftFraction * screenWidth).toInt().coerceIn(0, screenWidth),
        top = (topFraction * screenHeight).toInt().coerceIn(0, screenHeight),
        right = (rightFraction * screenWidth).toInt().coerceIn(0, screenWidth),
        bottom = (bottomFraction * screenHeight).toInt().coerceIn(0, screenHeight)
    )

    companion object {
        val DEFAULT = OcrRegion("")
    }
}

data class OcrRegionPixels(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Every user preference in one immutable value object.
 *
 * Pure Kotlin with plain defaults, which means the same defaults are used by the app,
 * by the unit tests and by the offline verifier. The DataStore repository serialises
 * this to/from preferences; nothing here touches Android.
 */
data class AppSettings(
    // ------------------------------------------------------------- operating state
    val mode: AssistantMode = AssistantMode.SUGGEST,
    val monitoringEnabled: Boolean = false,
    val autoReplyEnabled: Boolean = false,
    /** Emergency stop. Sticky until the user starts the assistant again. */
    val emergencyStopped: Boolean = false,

    // ------------------------------------------------------------------- detection
    val debounceMs: Long = 900L,
    /** 0 = pure event driven (recommended). >0 adds a safety poll at this interval. */
    val pollingIntervalMs: Long = 0L,
    val contextMessageCount: Int = 10,
    val minIncomingConfidence: Float = 0.35f,
    val manualReplyCooldownMs: Long = 120_000L,

    // --------------------------------------------------------------- delay/typing
    val replyDelay: ReplyDelay = ReplyDelay.THREE_SECONDS,
    val customReplyDelayMs: Long = 3_000L,
    val simulateTyping: Boolean = false,
    val typingSpeedCharsPerSecond: Int = 22,

    // ------------------------------------------------------------------------- AI
    val providerId: String = "openrouter",
    val baseUrl: String = OpenAiEndpointConfig.OPENROUTER_BASE_URL,
    val primaryModel: String = "",
    val fallbackModels: List<String> = emptyList(),
    val temperature: Float = 0.8f,
    val maxTokens: Int = 220,
    val timeoutMs: Long = 20_000L,
    val retryCount: Int = 2,
    /** When set, this replaces the built-in system prompt entirely. */
    val customSystemPrompt: String = "",
    val personaId: String = PersonaPresets.default().id,
    val replyLength: ReplyLength = ReplyLength.SHORT,
    val maxReplyChars: Int = 500,
    val languagePolicy: LanguagePolicy = LanguagePolicy.AUTO,
    val customLanguageInstruction: String = "",
    val translateIncoming: Boolean = false,
    val replyLanguage: ReplyLanguagePolicy = ReplyLanguagePolicy.SAME_AS_INCOMING,
    val maxEmojisPerReply: Int = 2,

    // --------------------------------------------------------------- capture / OCR
    val captureScope: CaptureScope = CaptureScope.OFF,
    val ocrEnabled: Boolean = false,
    val ocrMinConfidence: Float = 0.55f,
    val ocrRegions: Map<String, OcrRegion> = emptyMap(),

    // --------------------------------------------------------------------- overlay
    val overlayEnabled: Boolean = true,
    val overlayAutoShow: Boolean = true,
    val overlayScale: Float = 1f,
    val overlayOpacity: Float = 0.95f,
    val overlayPosition: PointView = PointView(48, 260),
    val overlayExpandedByDefault: Boolean = false,

    // --------------------------------------------------------------------- privacy
    val debugLogging: Boolean = false,
    val storeConversationHistory: Boolean = false,
    val excludedPackages: List<String> = emptyList(),
    val pausedConversations: List<String> = emptyList(),
    val enabledPackages: List<String> = DEFAULT_ENABLED_PACKAGES,
    val adapterOverrides: Map<String, AdapterOverrides> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val setupCompleted: Boolean = false,
    val acknowledgedAutomationRisk: Boolean = false
) {

    fun effectiveReplyDelayMs(): Long = when (replyDelay) {
        ReplyDelay.CUSTOM -> customReplyDelayMs.coerceAtLeast(0L)
        else -> replyDelay.millis
    }

    /** Characters allowed in a reply, honouring "Custom". */
    fun effectiveMaxReplyChars(): Int = when (replyLength) {
        ReplyLength.CUSTOM -> maxReplyChars.coerceIn(20, 4_000)
        else -> replyLength.maxChars.coerceAtMost(4_000)
    }

    fun effectiveMaxTokens(): Int = maxTokens.coerceIn(16, 8_000)

    fun isPackageEnabled(packageName: String): Boolean =
        enabledPackages.any { it.equals(packageName, ignoreCase = true) }

    fun isExcluded(packageName: String): Boolean =
        excludedPackages.any { it.equals(packageName, ignoreCase = true) }

    fun isConversationPaused(conversationId: String): Boolean =
        pausedConversations.contains(conversationId)

    fun overridesFor(packageName: String): AdapterOverrides =
        adapterOverrides[packageName] ?: AdapterOverrides.NONE

    /** The list of models to try, in order, with duplicates removed. */
    fun modelChain(): List<String> =
        (listOf(primaryModel) + fallbackModels)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun ocrRegionFor(packageName: String): OcrRegion =
        ocrRegions[packageName] ?: OcrRegion(packageName)

    /**
     * AUTO is only ever honoured when the user has separately switched automatic
     * replying on and acknowledged the risk. This single predicate is what the engine
     * consults, so the rule cannot drift between screens.
     */
    fun autoSendPermitted(): Boolean =
        autoReplyEnabled && acknowledgedAutomationRisk && !emergencyStopped && mode == AssistantMode.AUTO

    companion object {
        // NOTE: these two must be declared before DEFAULT. A companion object is
        // initialised in declaration order, so referencing them from DEFAULT's default
        // arguments only works if they already exist. (Caught by AppSettingsTest.)
        val DEFAULT_ENABLED_PACKAGES = listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.instagram.android"
        )

        val CONTEXT_CHOICES = listOf(5, 10, 20, 30, 50)

        val DEFAULT = AppSettings()
    }
}
