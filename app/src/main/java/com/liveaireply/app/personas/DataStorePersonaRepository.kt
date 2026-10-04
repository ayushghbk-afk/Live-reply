package com.liveaireply.app.personas

import com.liveaireply.app.storage.AppDataStore
import com.liveaireply.app.storage.Json
import com.liveaireply.app.storage.JsonValue
import com.liveaireply.app.storage.arr
import com.liveaireply.app.storage.bool
import com.liveaireply.app.storage.jsonArr
import com.liveaireply.app.storage.jsonObj
import com.liveaireply.app.storage.str
import com.liveaireply.app.storage.toJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Persists personas in DataStore as a single JSON blob.
 *
 * The repository interface is synchronous because the engine and the prompt builder read
 * personas from a background thread mid-pipeline; the DataStore round trip happens on
 * [save]/[select] and on first access only.
 */
class DataStorePersonaRepository(
    private val dataStore: AppDataStore
) : PersonaRepository {

    private val items = LinkedHashMap<String, Persona>(PersonaPresets.ALL.associateBy { it.id })
    private var selectedId: String = PersonaPresets.default().id
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            runBlocking {
                val raw = dataStore.preferences.first()[AppDataStore.Keys.PERSONAS_JSON]
                selectedId = dataStore.preferences.first()[AppDataStore.Keys.SELECTED_PERSONA_ID]
                    ?: PersonaPresets.default().id
                if (!raw.isNullOrBlank()) {
                    val parsed = Json.parseOrNull(raw) as? JsonValue.JObj
                    parsed?.entries?.forEach { (id, value) ->
                        val persona = Persona(
                            id = id,
                            label = value.str("label", id),
                            personaName = value.str("name", ""),
                            personality = value.str("personality", ""),
                            background = value.str("background", ""),
                            relationship = value.str("relationship", ""),
                            speakingStyle = value.str("style", ""),
                            rules = (Json.parseOrNull(value.str("rules", "[]")) as? JsonValue.JArr)
                                ?.items?.mapNotNull { (it as? JsonValue.JStr)?.value }
                                ?: emptyList(),
                            extraInstructions = value.str("extra", ""),
                            isRoleplay = value.bool("roleplay", false),
                            builtIn = value.bool("builtIn", false)
                        )
                        items[id] = persona
                    }
                }
            }
        }
    }

    @Synchronized
    override fun all(): List<Persona> {
        ensureLoaded()
        return items.values.toList()
    }

    @Synchronized
    override fun byId(id: String?): Persona? {
        ensureLoaded()
        return id?.let { items[it] }
    }

    @Synchronized
    override fun selected(): Persona {
        ensureLoaded()
        return items[selectedId] ?: PersonaPresets.default()
    }

    @Synchronized
    override fun save(persona: Persona) {
        ensureLoaded()
        items[persona.id] = persona
        persist()
    }

    @Synchronized
    override fun delete(id: String) {
        ensureLoaded()
        if (items[id]?.builtIn == true) return
        items.remove(id)
        if (selectedId == id) selectedId = PersonaPresets.default().id
        persist()
    }

    @Synchronized
    override fun selectedId(): String {
        ensureLoaded()
        return selectedId
    }

    @Synchronized
    override fun select(id: String) {
        ensureLoaded()
        if (!items.containsKey(id)) return
        selectedId = id
        runCatching {
            runBlocking {
                dataStore.update { it[AppDataStore.Keys.SELECTED_PERSONA_ID] = id }
            }
        }
    }

    private fun persist() {
        val payload = jsonObj(
            *items.map { (id, persona) ->
                id to jsonObj(
                    "label" to persona.label.toJson(),
                    "name" to persona.personaName.toJson(),
                    "personality" to persona.personality.toJson(),
                    "background" to persona.background.toJson(),
                    "relationship" to persona.relationship.toJson(),
                    "style" to persona.speakingStyle.toJson(),
                    "rules" to Json.stringify(jsonArr(persona.rules.map { it.toJson() })).toJson(),
                    "extra" to persona.extraInstructions.toJson(),
                    "roleplay" to persona.isRoleplay.toJson(),
                    "builtIn" to persona.builtIn.toJson()
                )
            }.toTypedArray()
        )
        runCatching {
            runBlocking {
                dataStore.update { prefs ->
                    prefs[AppDataStore.Keys.PERSONAS_JSON] = Json.stringify(payload)
                    prefs[AppDataStore.Keys.SELECTED_PERSONA_ID] = selectedId
                }
            }
        }
    }
}
