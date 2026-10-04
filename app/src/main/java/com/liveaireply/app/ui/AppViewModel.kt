package com.liveaireply.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liveaireply.app.ai.AiCompletionRequest
import com.liveaireply.app.ai.AiOutcome
import com.liveaireply.app.ai.ChatMessage
import com.liveaireply.app.ai.ModelListOutcome
import com.liveaireply.app.di.AppContainer
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.engine.EngineResult
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single view model for the app.
 *
 * It owns settings reads/writes, the permission model, the AI connection test, model
 * discovery and the built-in test console. Every AI call happens on Dispatchers.IO.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = AssistantRuntime.requireContainer(application)

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.currentSettings)

    val logs = container.eventLog.flow

    val personas = MutableStateFlow(container.personaRepository.all())
    val overlayState = AssistantRuntime.overlayState
    val errors = AssistantRuntime.errors

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.update(transform)
            val latest = container.settingsRepository.settings.let { flow ->
                var value = container.currentSettings
                flow.collect { value = it }
                value
            }
            container.currentSettings = latest
        }
    }

    fun setMode(mode: AssistantMode) = update { it.copy(mode = mode) }

    fun setMonitoring(enabled: Boolean) = update { it.copy(monitoringEnabled = enabled, emergencyStopped = false) }

    fun setAutoReply(enabled: Boolean) = update { it.copy(autoReplyEnabled = enabled) }

    fun emergencyStop() {
        update { it.copy(emergencyStopped = true, monitoringEnabled = false, autoReplyEnabled = false) }
        AssistantRuntime.engine?.stopAll("Stopped from the app")
        stopService()
    }

    fun startService() {
        val context = getApplication<Application>()
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_START)
        )
    }

    fun stopService() {
        val context = getApplication<Application>()
        context.startService(
            android.content.Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_STOP)
        )
    }

    fun saveApiKey(key: String) {
        container.credentialStore.saveApiKey(key)
        container.eventLog.log(if (key.isBlank()) "API key cleared" else "API key saved", "settings")
        _message.value = if (key.isBlank()) "API key cleared" else "API key saved securely"
    }

    fun maskedApiKey(): String = container.credentialStore.maskedApiKey()

    fun hasApiKey(): Boolean = container.credentialStore.hasApiKey()

    /** "Test AI" / "Test Connection": one real round trip with a tiny prompt. */
    fun testConnection() {
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            val result = withContext(Dispatchers.IO) {
                container.aiProvider().complete(
                    AiCompletionRequest(
                        model = container.currentSettings.primaryModel,
                        messages = listOf(
                            ChatMessage.system("Reply with the single word: ready"),
                            ChatMessage.user("ping")
                        ),
                        temperature = 0f,
                        maxTokens = 8,
                        timeoutMs = container.currentSettings.timeoutMs
                    )
                )
            }
            _busy.value = false
            _message.value = when (result) {
                is AiOutcome.Success -> "Connected. ${result.model} replied in ${result.latencyMs} ms."
                is AiOutcome.Failure -> "${result.error.headline()}\n${result.error.message}"
            }
        }
    }

    fun fetchModels() {
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { container.aiProvider().listModels() }
            _busy.value = false
            when (result) {
                is ModelListOutcome.Success ->
                    _message.value = "Found ${result.models.size} models. First: ${result.models.firstOrNull()?.id}"
                is ModelListOutcome.Failure ->
                    _message.value = "${result.error.headline()}\n${result.error.message}"
            }
        }
    }

    /** Built-in test console: full pipeline, no other app involved. */
    fun runTestReply(incoming: String) {
        viewModelScope.launch {
            _busy.value = true
            val outcome = withContext(Dispatchers.IO) {
                val engine = AssistantRuntime.engine
                if (engine != null) {
                    engine.runSimulation(incoming)
                } else {
                    val prompt = com.liveaireply.app.ai.PromptBuilder().build(
                        com.liveaireply.app.ai.PromptInput(
                            context = listOf(
                                com.liveaireply.app.conversation.ChatTurn(
                                    incoming,
                                    com.liveaireply.app.conversation.TurnDirection.INCOMING,
                                    com.liveaireply.app.conversation.TurnSource.SIMULATED,
                                    System.currentTimeMillis()
                                )
                            ),
                            newestIncomingText = incoming,
                            persona = container.personaRepository.selected(),
                            settings = container.currentSettings
                        )
                    )
                    when (val generated = container.replyPipeline().generate(prompt, container.currentSettings)) {
                        is com.liveaireply.app.ai.ReplyGenerationResult.Generated ->
                            EngineResult.Suggested(generated.text, "simulation", incoming, "Test mode")
                        is com.liveaireply.app.ai.ReplyGenerationResult.Invalid ->
                            EngineResult.InvalidReply(generated.validation.text, generated.validation.explanation)
                        is com.liveaireply.app.ai.ReplyGenerationResult.Failed ->
                            EngineResult.AiFailed(generated.error.headline(), generated.error.message)
                    }
                }
            }
            _busy.value = false
            _message.value = when (outcome) {
                is EngineResult.Suggested -> outcome.reply
                is EngineResult.InvalidReply -> "Rejected: ${outcome.explanation}"
                is EngineResult.AiFailed -> "${outcome.headline}\n${outcome.detail}"
                else -> outcome.toString()
            }
        }
    }

    fun clearLogs() = container.eventLog.clear()

    fun setPersona(id: String) {
        container.personaRepository.select(id)
        update { it.copy(personaId = id) }
        personas.value = container.personaRepository.all()
    }

    fun savePersona(persona: com.liveaireply.app.personas.Persona) {
        container.personaRepository.save(persona)
        personas.value = container.personaRepository.all()
    }

    fun deletePersona(id: String) {
        container.personaRepository.delete(id)
        personas.value = container.personaRepository.all()
    }
}
