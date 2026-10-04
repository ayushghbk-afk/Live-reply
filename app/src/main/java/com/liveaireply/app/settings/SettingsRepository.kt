package com.liveaireply.app.settings

import com.liveaireply.app.adapters.AdapterOverrides
import com.liveaireply.app.adapters.PointView
import com.liveaireply.app.personas.PersonaPresets
import com.liveaireply.app.personas.ReplyLength
import com.liveaireply.app.storage.AppDataStore
import com.liveaireply.app.storage.Json
import com.liveaireply.app.storage.JsonValue
import com.liveaireply.app.storage.bool
import com.liveaireply.app.storage.dbl
import com.liveaireply.app.storage.int
import com.liveaireply.app.storage.jsonArr
import com.liveaireply.app.storage.jsonObj
import com.liveaireply.app.storage.str
import com.liveaireply.app.storage.toJson
import com.liveaireply.app.util.EventLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Maps DataStore preferences onto the immutable [AppSettings] value object.
 *
 * Enum names are persisted as strings so a renamed enum constant cannot corrupt stored
 * preferences - unknown values fall back to the default.
 */
class SettingsRepository(
    private val dataStore: AppDataStore,
    private val eventLog: EventLog? = null
) {

    val settings: Flow<AppSettings> = dataStore.preferences
        .map { prefs ->
            AppSettings(
                mode = enumOrDefault(prefs[AppDataStore.Keys.MODE], AssistantMode.SUGGEST),
                monitoringEnabled = prefs[AppDataStore.Keys.MONITORING_ENABLED] ?: false,
                autoReplyEnabled = prefs[AppDataStore.Keys.AUTO_REPLY_ENABLED] ?: false,
                emergencyStopped = prefs[AppDataStore.Keys.EMERGENCY_STOPPED] ?: false,
                acknowledgedAutomationRisk = prefs[AppDataStore.Keys.ACKNOWLEDGED_AUTOMATION_RISK] ?: false,

                debounceMs = prefs[AppDataStore.Keys.DEBOUNCE_MS] ?: 900L,
                pollingIntervalMs = prefs[AppDataStore.Keys.POLLING_INTERVAL_MS] ?: 0L,
                contextMessageCount = prefs[AppDataStore.Keys.CONTEXT_MESSAGE_COUNT] ?: 10,
                minIncomingConfidence = prefs[AppDataStore.Keys.MIN_INCOMING_CONFIDENCE] ?: 0.35f,
                manualReplyCooldownMs = prefs[AppDataStore.Keys.MANUAL_REPLY_COOLDOWN_MS] ?: 120_000L,

                replyDelay = enumOrDefault(prefs[AppDataStore.Keys.REPLY_DELAY], ReplyDelay.THREE_SECONDS),
                customReplyDelayMs = prefs[AppDataStore.Keys.CUSTOM_REPLY_DELAY_MS] ?: 3_000L,
                simulateTyping = prefs[AppDataStore.Keys.SIMULATE_TYPING] ?: false,
                typingSpeedCharsPerSecond = prefs[AppDataStore.Keys.TYPING_SPEED] ?: 22,

                providerId = prefs[AppDataStore.Keys.PROVIDER_ID] ?: "openrouter",
                baseUrl = prefs[AppDataStore.Keys.BASE_URL]
                    ?: com.liveaireply.app.ai.openai.OpenAiEndpointConfig.OPENROUTER_BASE_URL,
                primaryModel = prefs[AppDataStore.Keys.PRIMARY_MODEL] ?: "",
                fallbackModels = decodeList(prefs[AppDataStore.Keys.FALLBACK_MODELS]),
                temperature = prefs[AppDataStore.Keys.TEMPERATURE] ?: 0.8f,
                maxTokens = prefs[AppDataStore.Keys.MAX_TOKENS] ?: 220,
                timeoutMs = prefs[AppDataStore.Keys.TIMEOUT_MS] ?: 20_000L,
                retryCount = prefs[AppDataStore.Keys.RETRY_COUNT] ?: 2,
                customSystemPrompt = prefs[AppDataStore.Keys.CUSTOM_SYSTEM_PROMPT] ?: "",
                personaId = prefs[AppDataStore.Keys.SELECTED_PERSONA_ID] ?: PersonaPresets.default().id,
                replyLength = enumOrDefault(prefs[AppDataStore.Keys.REPLY_LENGTH], ReplyLength.SHORT),
                maxReplyChars = prefs[AppDataStore.Keys.MAX_REPLY_CHARS] ?: 500,
                languagePolicy = enumOrDefault(prefs[AppDataStore.Keys.LANGUAGE_POLICY], LanguagePolicy.AUTO),
                customLanguageInstruction = prefs[AppDataStore.Keys.CUSTOM_LANGUAGE_INSTRUCTION] ?: "",
                translateIncoming = prefs[AppDataStore.Keys.TRANSLATE_INCOMING] ?: false,
                replyLanguage = enumOrDefault(
                    prefs[AppDataStore.Keys.REPLY_LANGUAGE], ReplyLanguagePolicy.SAME_AS_INCOMING
                ),
                maxEmojisPerReply = prefs[AppDataStore.Keys.MAX_EMOJIS] ?: 2,

                captureScope = enumOrDefault(prefs[AppDataStore.Keys.CAPTURE_SCOPE], CaptureScope.OFF),
                ocrEnabled = prefs[AppDataStore.Keys.OCR_ENABLED] ?: false,
                ocrMinConfidence = prefs[AppDataStore.Keys.OCR_MIN_CONFIDENCE] ?: 0.55f,
                ocrRegions = decodeRegions(prefs[AppDataStore.Keys.OCR_REGIONS_JSON]),

                overlayEnabled = prefs[AppDataStore.Keys.OVERLAY_ENABLED] ?: true,
                overlayAutoShow = prefs[AppDataStore.Keys.OVERLAY_AUTO_SHOW] ?: true,
                overlayScale = prefs[AppDataStore.Keys.OVERLAY_SCALE] ?: 1f,
                overlayOpacity = prefs[AppDataStore.Keys.OVERLAY_OPACITY] ?: 0.95f,
                overlayPosition = PointView(
                    prefs[AppDataStore.Keys.OVERLAY_X] ?: 48,
                    prefs[AppDataStore.Keys.OVERLAY_Y] ?: 260
                ),
                overlayExpandedByDefault = prefs[AppDataStore.Keys.OVERLAY_EXPANDED] ?: false,

                debugLogging = prefs[AppDataStore.Keys.DEBUG_LOGGING] ?: false,
                storeConversationHistory = prefs[AppDataStore.Keys.STORE_HISTORY] ?: false,
                excludedPackages = prefs[AppDataStore.Keys.EXCLUDED_PACKAGES]?.toList() ?: emptyList(),
                pausedConversations = prefs[AppDataStore.Keys.PAUSED_CONVERSATIONS]?.toList() ?: emptyList(),
                enabledPackages = prefs[AppDataStore.Keys.ENABLED_PACKAGES]?.toList()
                    ?: AppSettings.DEFAULT_ENABLED_PACKAGES,
                adapterOverrides = decodeOverrides(prefs[AppDataStore.Keys.ADAPTER_OVERRIDES_JSON]),
                themeMode = enumOrDefault(prefs[AppDataStore.Keys.THEME_MODE], ThemeMode.SYSTEM),
                setupCompleted = prefs[AppDataStore.Keys.SETUP_COMPLETED] ?: false
            )
        }
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        // Read-modify-write against the latest stored value.
        val current = settings.first()
        val next = transform(current)
        write(current, next)
        eventLog?.log("Settings updated", "settings")
    }

    private suspend fun write(previous: AppSettings, next: AppSettings) {
        dataStore.update { prefs ->
            fun <T : Any> put(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T, old: T) {
                if (value != old) prefs[key] = value
            }
            put(AppDataStore.Keys.MODE, next.mode.name, previous.mode.name)
            put(AppDataStore.Keys.MONITORING_ENABLED, next.monitoringEnabled, previous.monitoringEnabled)
            put(AppDataStore.Keys.AUTO_REPLY_ENABLED, next.autoReplyEnabled, previous.autoReplyEnabled)
            put(AppDataStore.Keys.EMERGENCY_STOPPED, next.emergencyStopped, previous.emergencyStopped)
            put(
                AppDataStore.Keys.ACKNOWLEDGED_AUTOMATION_RISK,
                next.acknowledgedAutomationRisk, previous.acknowledgedAutomationRisk
            )
            put(AppDataStore.Keys.DEBOUNCE_MS, next.debounceMs, previous.debounceMs)
            put(AppDataStore.Keys.POLLING_INTERVAL_MS, next.pollingIntervalMs, previous.pollingIntervalMs)
            put(AppDataStore.Keys.CONTEXT_MESSAGE_COUNT, next.contextMessageCount, previous.contextMessageCount)
            put(AppDataStore.Keys.MIN_INCOMING_CONFIDENCE, next.minIncomingConfidence, previous.minIncomingConfidence)
            put(AppDataStore.Keys.MANUAL_REPLY_COOLDOWN_MS, next.manualReplyCooldownMs, previous.manualReplyCooldownMs)
            put(AppDataStore.Keys.REPLY_DELAY, next.replyDelay.name, previous.replyDelay.name)
            put(AppDataStore.Keys.CUSTOM_REPLY_DELAY_MS, next.customReplyDelayMs, previous.customReplyDelayMs)
            put(AppDataStore.Keys.SIMULATE_TYPING, next.simulateTyping, previous.simulateTyping)
            put(AppDataStore.Keys.TYPING_SPEED, next.typingSpeedCharsPerSecond, previous.typingSpeedCharsPerSecond)
            put(AppDataStore.Keys.PROVIDER_ID, next.providerId, previous.providerId)
            put(AppDataStore.Keys.BASE_URL, next.baseUrl, previous.baseUrl)
            put(AppDataStore.Keys.PRIMARY_MODEL, next.primaryModel, previous.primaryModel)
            put(AppDataStore.Keys.FALLBACK_MODELS, encodeList(next.fallbackModels), encodeList(previous.fallbackModels))
            put(AppDataStore.Keys.TEMPERATURE, next.temperature, previous.temperature)
            put(AppDataStore.Keys.MAX_TOKENS, next.maxTokens, previous.maxTokens)
            put(AppDataStore.Keys.TIMEOUT_MS, next.timeoutMs, previous.timeoutMs)
            put(AppDataStore.Keys.RETRY_COUNT, next.retryCount, previous.retryCount)
            put(AppDataStore.Keys.CUSTOM_SYSTEM_PROMPT, next.customSystemPrompt, previous.customSystemPrompt)
            put(AppDataStore.Keys.SELECTED_PERSONA_ID, next.personaId, previous.personaId)
            put(AppDataStore.Keys.REPLY_LENGTH, next.replyLength.name, previous.replyLength.name)
            put(AppDataStore.Keys.MAX_REPLY_CHARS, next.maxReplyChars, previous.maxReplyChars)
            put(AppDataStore.Keys.LANGUAGE_POLICY, next.languagePolicy.name, previous.languagePolicy.name)
            put(
                AppDataStore.Keys.CUSTOM_LANGUAGE_INSTRUCTION,
                next.customLanguageInstruction, previous.customLanguageInstruction
            )
            put(AppDataStore.Keys.TRANSLATE_INCOMING, next.translateIncoming, previous.translateIncoming)
            put(AppDataStore.Keys.REPLY_LANGUAGE, next.replyLanguage.name, previous.replyLanguage.name)
            put(AppDataStore.Keys.MAX_EMOJIS, next.maxEmojisPerReply, previous.maxEmojisPerReply)
            put(AppDataStore.Keys.CAPTURE_SCOPE, next.captureScope.name, previous.captureScope.name)
            put(AppDataStore.Keys.OCR_ENABLED, next.ocrEnabled, previous.ocrEnabled)
            put(AppDataStore.Keys.OCR_MIN_CONFIDENCE, next.ocrMinConfidence, previous.ocrMinConfidence)
            put(
                AppDataStore.Keys.OCR_REGIONS_JSON,
                encodeRegions(next.ocrRegions), encodeRegions(previous.ocrRegions)
            )
            put(AppDataStore.Keys.OVERLAY_ENABLED, next.overlayEnabled, previous.overlayEnabled)
            put(AppDataStore.Keys.OVERLAY_AUTO_SHOW, next.overlayAutoShow, previous.overlayAutoShow)
            put(AppDataStore.Keys.OVERLAY_SCALE, next.overlayScale, previous.overlayScale)
            put(AppDataStore.Keys.OVERLAY_OPACITY, next.overlayOpacity, previous.overlayOpacity)
            put(AppDataStore.Keys.OVERLAY_X, next.overlayPosition.x, previous.overlayPosition.x)
            put(AppDataStore.Keys.OVERLAY_Y, next.overlayPosition.y, previous.overlayPosition.y)
            put(AppDataStore.Keys.OVERLAY_EXPANDED, next.overlayExpandedByDefault, previous.overlayExpandedByDefault)
            put(AppDataStore.Keys.DEBUG_LOGGING, next.debugLogging, previous.debugLogging)
            put(AppDataStore.Keys.STORE_HISTORY, next.storeConversationHistory, previous.storeConversationHistory)
            put(
                AppDataStore.Keys.EXCLUDED_PACKAGES,
                next.excludedPackages.toSet(), previous.excludedPackages.toSet()
            )
            put(
                AppDataStore.Keys.PAUSED_CONVERSATIONS,
                next.pausedConversations.toSet(), previous.pausedConversations.toSet()
            )
            put(
                AppDataStore.Keys.ENABLED_PACKAGES,
                next.enabledPackages.toSet(), previous.enabledPackages.toSet()
            )
            put(
                AppDataStore.Keys.ADAPTER_OVERRIDES_JSON,
                encodeOverrides(next.adapterOverrides), encodeOverrides(previous.adapterOverrides)
            )
            put(AppDataStore.Keys.THEME_MODE, next.themeMode.name, previous.themeMode.name)
            put(AppDataStore.Keys.SETUP_COMPLETED, next.setupCompleted, previous.setupCompleted)
        }
    }

    private fun <T : Comparable<T>> enumOrDefault(value: String?, default: T): T where T : Enum<T> {
        if (value.isNullOrBlank()) return default
        return default.javaClass.enumConstants?.firstOrNull { it.name == value } ?: default
    }

    // ------------------------------------------------------------- json encodings

    private fun encodeList(values: List<String>): String = Json.stringify(jsonArr(values.map { it.toJson() }))

    private fun decodeList(raw: String?): List<String> =
        (Json.parseOrNull(raw) as? JsonValue.JArr)?.items?.mapNotNull { (it as? JsonValue.JStr)?.value }
            ?: emptyList()

    private fun encodeRegions(regions: Map<String, OcrRegion>): String = Json.stringify(
        jsonObj(
            *regions.map { (key, region) ->
                key to jsonObj(
                    "l" to region.leftFraction.toDouble().toJson(),
                    "t" to region.topFraction.toDouble().toJson(),
                    "r" to region.rightFraction.toDouble().toJson(),
                    "b" to region.bottomFraction.toDouble().toJson()
                )
            }.toTypedArray()
        )
    )

    private fun decodeRegions(raw: String?): Map<String, OcrRegion> {
        val parsed = Json.parseOrNull(raw) as? JsonValue.JObj ?: return emptyMap()
        val out = LinkedHashMap<String, OcrRegion>()
        for ((key, value) in parsed.entries) {
            runCatching {
                out[key] = OcrRegion(
                    packageName = key,
                    leftFraction = value.dbl("l", 0f).toFloat(),
                    topFraction = value.dbl("t", 0.18f).toFloat(),
                    rightFraction = value.dbl("r", 1f).toFloat(),
                    bottomFraction = value.dbl("b", 0.84f).toFloat()
                )
            }
        }
        return out
    }

    private fun encodeOverrides(overrides: Map<String, AdapterOverrides>): String = Json.stringify(
        jsonObj(
            *overrides.map { (key, value) ->
                key to jsonObj(
                    "composer" to (value.composerViewId ?: "").toJson(),
                    "sendId" to (value.sendButtonViewId ?: "").toJson(),
                    "sendLabel" to (value.sendButtonDescription ?: "").toJson(),
                    "titleId" to (value.titleViewId ?: "").toJson(),
                    "x" to (value.sendPoint?.x ?: -1).toJson(),
                    "y" to (value.sendPoint?.y ?: -1).toJson(),
                    "outWords" to encodeList(value.extraOutgoingWords),
                    "inWords" to encodeList(value.extraIncomingWords)
                )
            }.toTypedArray()
        )
    )

    private fun decodeOverrides(raw: String?): Map<String, AdapterOverrides> {
        val parsed = Json.parseOrNull(raw) as? JsonValue.JObj ?: return emptyMap()
        val out = LinkedHashMap<String, AdapterOverrides>()
        for ((key, value) in parsed.entries) {
            val x = value.int("x", -1)
            val y = value.int("y", -1)
            out[key] = AdapterOverrides(
                composerViewId = value.str("composer").takeUnless { it.isNullOrBlank() },
                sendButtonViewId = value.str("sendId").takeUnless { it.isNullOrBlank() },
                sendButtonDescription = value.str("sendLabel").takeUnless { it.isNullOrBlank() },
                titleViewId = value.str("titleId").takeUnless { it.isNullOrBlank() },
                sendPoint = if (x >= 0 && y >= 0) PointView(x, y) else null,
                extraOutgoingWords = decodeList(value.str("outWords")),
                extraIncomingWords = decodeList(value.str("inWords"))
            )
        }
        return out
    }
}
