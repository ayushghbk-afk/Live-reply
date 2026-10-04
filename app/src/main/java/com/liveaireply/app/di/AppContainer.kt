package com.liveaireply.app.di

import android.content.Context
import com.liveaireply.app.adapters.AdapterOverrides
import com.liveaireply.app.adapters.ChatAdapterRegistry
import com.liveaireply.app.ai.AiProvider
import com.liveaireply.app.ai.PrivacyFilteringAiProvider
import com.liveaireply.app.ai.ReplyPipeline
import com.liveaireply.app.ai.ReplyValidator
import com.liveaireply.app.ai.openai.OkHttpTransport
import com.liveaireply.app.ai.openai.OpenAiCompatibleProvider
import com.liveaireply.app.ai.openai.OpenAiEndpointConfig
import com.liveaireply.app.ai.openai.OpenRouterProvider
import com.liveaireply.app.conversation.ConversationDetector
import com.liveaireply.app.conversation.DetectorConfig
import com.liveaireply.app.conversation.DuplicateGuard
import com.liveaireply.app.personas.DataStorePersonaRepository
import com.liveaireply.app.personas.PersonaRepository
import com.liveaireply.app.security.SecureCredentialStore
import com.liveaireply.app.security.SensitiveScreenPolicy
import com.liveaireply.app.settings.SettingsRepository
import com.liveaireply.app.storage.AppDataStore
import com.liveaireply.app.util.EventLog

/**
 * Hand written dependency container.
 *
 * Deliberately not Hilt/Dagger: the app has one process, a handful of singletons and no
 * need for generated factories, and avoiding an annotation processor keeps the build
 * reproducible on any machine.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val dataStore = AppDataStore(appContext)
    val eventLog = EventLog()
    val settingsRepository = SettingsRepository(dataStore, eventLog)
    val credentialStore = SecureCredentialStore(appContext)
    val personaRepository: PersonaRepository = DataStorePersonaRepository(dataStore)
    val sensitiveScreenPolicy = SensitiveScreenPolicy()

    val transport = OkHttpTransport(appContext)

    /** Current settings snapshot, refreshed by [SettingsRepository]. */
    @Volatile
    var currentSettings = com.liveaireply.app.settings.AppSettings.DEFAULT

    fun adapterRegistry(): ChatAdapterRegistry = ChatAdapterRegistry(
        overridesProvider = { packageName: String -> currentSettings.overridesFor(packageName) }
    )

    /**
     * Builds the provider for the currently configured endpoint. Called per session so
     * that a changed base URL or key takes effect without a restart, and so the key is
     * read from the encrypted store only when a request is actually made.
     */
    fun aiProvider(): AiProvider {
        val settings = currentSettings
        val configProvider: () -> OpenAiEndpointConfig = {
            OpenAiEndpointConfig(
                baseUrl = settings.baseUrl.ifBlank { OpenAiEndpointConfig.OPENROUTER_BASE_URL },
                apiKey = credentialStore.apiKey(),
                extraHeaders = if (settings.providerId == "openrouter") {
                    mapOf("HTTP-Referer" to APP_URL, "X-Title" to APP_TITLE)
                } else {
                    emptyMap()
                }
            )
        }
        val provider = if (settings.providerId == "openrouter") {
            OpenRouterProvider(transport, configProvider, APP_URL, APP_TITLE)
        } else {
            OpenAiCompatibleProvider(
                id = settings.providerId,
                displayName = "Custom endpoint",
                transport = transport,
                configProvider = configProvider
            )
        }
        // This decorator is the final boundary before provider serialization. It redacts
        // passwords, OTPs, PINs, payment details, bank-account values and authentication
        // codes from every completion request, including test mode and regeneration.
        return PrivacyFilteringAiProvider(provider) { categories ->
            eventLog.log(
                "Sensitive values redacted before the AI request (${categories.joinToString { it.name }})",
                "privacy"
            )
        }
    }

    fun replyPipeline(): ReplyPipeline = ReplyPipeline(
        provider = aiProvider(),
        validator = ReplyValidator(),
        retryPolicy = com.liveaireply.app.ai.RetryPolicy.fromSettings(currentSettings)
    )

    fun conversationDetector(): ConversationDetector {
        val settings = currentSettings
        return ConversationDetector(
            duplicateGuard = DuplicateGuard(),
            config = DetectorConfig(
                contextMessageCount = settings.contextMessageCount,
                minIncomingConfidence = settings.minIncomingConfidence,
                manualReplyCooldownMs = settings.manualReplyCooldownMs
            )
        )
    }

    fun overridesFor(packageName: String): AdapterOverrides = currentSettings.overridesFor(packageName)

    companion object {
        const val APP_URL = "https://github.com/ayushghbk-afk/Live-reply"
        const val APP_TITLE = "Live AI Reply"
    }
}
