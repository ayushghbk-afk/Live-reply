package com.liveaireply.app.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "live_ai_reply")

/**
 * Single place where preference keys live.
 *
 * The API key is NOT here: it is stored encrypted by
 * [com.liveaireply.app.security.SecureCredentialStore] and only its presence is recorded
 * in preferences.
 */
class AppDataStore(private val context: Context) {

    object Keys {
        val MODE = stringPreferencesKey("mode")
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val AUTO_REPLY_ENABLED = booleanPreferencesKey("auto_reply_enabled")
        val EMERGENCY_STOPPED = booleanPreferencesKey("emergency_stopped")
        val ACKNOWLEDGED_AUTOMATION_RISK = booleanPreferencesKey("acknowledged_automation_risk")
        val ACKNOWLEDGED_CAPABILITIES = booleanPreferencesKey("acknowledged_capabilities")

        val DEBOUNCE_MS = longPreferencesKey("debounce_ms")
        val POLLING_INTERVAL_MS = longPreferencesKey("polling_interval_ms")
        val CONTEXT_MESSAGE_COUNT = intPreferencesKey("context_message_count")
        val MIN_INCOMING_CONFIDENCE = floatPreferencesKey("min_incoming_confidence")
        val MANUAL_REPLY_COOLDOWN_MS = longPreferencesKey("manual_reply_cooldown_ms")

        val REPLY_DELAY = stringPreferencesKey("reply_delay")
        val CUSTOM_REPLY_DELAY_MS = longPreferencesKey("custom_reply_delay_ms")
        val SIMULATE_TYPING = booleanPreferencesKey("simulate_typing")
        val TYPING_SPEED = intPreferencesKey("typing_speed_cps")

        val PROVIDER_ID = stringPreferencesKey("provider_id")
        val BASE_URL = stringPreferencesKey("base_url")
        val PRIMARY_MODEL = stringPreferencesKey("primary_model")
        val FALLBACK_MODELS = stringPreferencesKey("fallback_models")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val TIMEOUT_MS = longPreferencesKey("timeout_ms")
        val RETRY_COUNT = intPreferencesKey("retry_count")
        val CUSTOM_SYSTEM_PROMPT = stringPreferencesKey("custom_system_prompt")
        val PERSONA_ID = stringPreferencesKey("persona_id")
        val REPLY_LENGTH = stringPreferencesKey("reply_length")
        val MAX_REPLY_CHARS = intPreferencesKey("max_reply_chars")
        val LANGUAGE_POLICY = stringPreferencesKey("language_policy")
        val CUSTOM_LANGUAGE_INSTRUCTION = stringPreferencesKey("custom_language_instruction")
        val TRANSLATE_INCOMING = booleanPreferencesKey("translate_incoming")
        val REPLY_LANGUAGE = stringPreferencesKey("reply_language")
        val MAX_EMOJIS = intPreferencesKey("max_emojis")

        val CAPTURE_SCOPE = stringPreferencesKey("capture_scope")
        val OCR_ENABLED = booleanPreferencesKey("ocr_enabled")
        val OCR_MIN_CONFIDENCE = floatPreferencesKey("ocr_min_confidence")
        val OCR_REGIONS_JSON = stringPreferencesKey("ocr_regions_json")

        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_SCALE = floatPreferencesKey("overlay_scale")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_EXPANDED = booleanPreferencesKey("overlay_expanded")

        val DEBUG_LOGGING = booleanPreferencesKey("debug_logging")
        val EXCLUDED_PACKAGES = stringSetPreferencesKey("excluded_packages")
        val PAUSED_CONVERSATIONS = stringSetPreferencesKey("paused_conversations")
        val ENABLED_PACKAGES = stringSetPreferencesKey("enabled_packages")
        val ADAPTER_OVERRIDES_JSON = stringPreferencesKey("adapter_overrides_json")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val SETUP_COMPLETED = booleanPreferencesKey("setup_completed")

        val PERSONAS_JSON = stringPreferencesKey("personas_json")
        val SELECTED_PERSONA_ID = stringPreferencesKey("selected_persona_id")
        val API_KEY_PRESENT = booleanPreferencesKey("api_key_present")
        val LAST_MODELS_JSON = stringPreferencesKey("last_models_json")
    }

    val preferences: Flow<Preferences> = context.dataStore.data

    suspend fun <T> read(key: Preferences.Key<T>, default: T): T =
        context.dataStore.data.map { it[key] ?: default }.first()

    suspend fun update(transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { transform(it) }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}
