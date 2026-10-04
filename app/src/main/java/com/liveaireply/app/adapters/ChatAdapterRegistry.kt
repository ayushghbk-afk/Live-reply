package com.liveaireply.app.adapters

/** One row in Settings -> Supported apps. */
data class SupportedApp(
    val id: String,
    val displayName: String,
    val packageName: String,
    val enabledByDefault: Boolean
)

/**
 * Picks the right [ChatAdapter] for a package and hands it the user's overrides.
 *
 * Adapters are consulted in registration order and the first that claims the package
 * wins; [GenericChatAdapter] is always last so something always answers.
 */
class ChatAdapterRegistry(
    adapters: List<ChatAdapter> = defaultAdapters(),
    private val overridesProvider: (String) -> AdapterOverrides = { AdapterOverrides.NONE }
) {
    /** Always the last thing consulted, so some adapter always answers. */
    private val generic: ChatAdapter =
        adapters.filterIsInstance<GenericChatAdapter>().firstOrNull { it.id == "generic" }
            ?: GenericChatAdapter()

    private val specialised: List<ChatAdapter> = adapters.filter { it !== generic }

    val all: List<ChatAdapter> get() = specialised + generic

    fun forPackage(packageName: String): ChatAdapter =
        specialised.firstOrNull { it.supports(packageName) } ?: generic

    fun overridesFor(packageName: String): AdapterOverrides = overridesProvider(packageName)

    fun adapterForId(id: String): ChatAdapter? = all.firstOrNull { it.id == id }

    companion object {
        /** Every app shown in Settings -> Supported apps, in display order. */
        val KNOWN_APPS: List<SupportedApp> = listOf(
            SupportedApp("whatsapp", "WhatsApp", "com.whatsapp", enabledByDefault = true),
            SupportedApp("whatsapp-business", "WhatsApp Business", "com.whatsapp.w4b", enabledByDefault = false),
            SupportedApp("telegram", "Telegram", "org.telegram.messenger", enabledByDefault = true),
            SupportedApp("instagram", "Instagram", "com.instagram.android", enabledByDefault = true),
            SupportedApp("discord", "Discord", "com.discord", enabledByDefault = false),
            SupportedApp("chrome", "Chrome (web chat)", "com.android.chrome", enabledByDefault = false),
            SupportedApp("firefox", "Firefox (web chat)", "org.mozilla.firefox", enabledByDefault = false),
            SupportedApp("generic", "Any other app", "", enabledByDefault = false)
        )

        fun defaultAdapters(): List<ChatAdapter> = listOf(
            WhatsAppAdapter(),
            TelegramAdapter(),
            InstagramAdapter(),
            DiscordAdapter(),
            BrowserChatAdapter(),
            GenericChatAdapter()
        )
    }
}

/** Convenience used by the accessibility service: adapter + its overrides in one pair. */
data class ResolvedAdapter(val adapter: ChatAdapter, val overrides: AdapterOverrides)
