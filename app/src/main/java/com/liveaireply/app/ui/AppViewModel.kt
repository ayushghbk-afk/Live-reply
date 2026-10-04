package com.liveaireply.app.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.liveaireply.app.ai.AiCompletionRequest
import com.liveaireply.app.ai.AiOutcome
import com.liveaireply.app.ai.ChatMessage
import com.liveaireply.app.ai.ModelListOutcome
import com.liveaireply.app.di.AppContainer
import com.liveaireply.app.engine.AssistantRuntime
import com.liveaireply.app.engine.AssistantService
import com.liveaireply.app.engine.EmergencyStopController
import com.liveaireply.app.engine.EngineResult
import com.liveaireply.app.ocr.ScreenCaptureService
import com.liveaireply.app.security.CapabilityStatus
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import com.liveaireply.app.settings.CaptureScope
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
    val ocrCaptureState = ScreenCaptureService.captureState

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { persist(transform) }
    }

    private suspend fun persist(transform: (AppSettings) -> AppSettings): AppSettings {
        val latest = container.settingsRepository.update(transform)
        container.currentSettings = latest
        return latest
    }

    fun acknowledgeCapabilities(accepted: Boolean) {
        if (!accepted) {
            EmergencyStopController.stopNow(getApplication(), "Disclosure acceptance withdrawn")
        }
        update { it.copy(acknowledgedCapabilities = accepted) }
    }

    fun finishSetup() = update { it.copy(setupCompleted = it.acknowledgedCapabilities) }

    fun setMode(mode: AssistantMode) {
        if (mode == AssistantMode.AUTO) {
            _message.value = "Review and confirm the Auto-mode disclosure before enabling Auto."
            return
        }
        val disableAutomation: (AppSettings) -> AppSettings = {
            it.copy(
                mode = mode,
                autoReplyEnabled = false,
                acknowledgedAutomationRisk = false
            )
        }
        // The engine reads this process cache immediately before send. Update it
        // synchronously so leaving Auto takes effect before the DataStore write completes.
        container.currentSettings = disableAutomation(container.currentSettings)
            .enforceSafetyInvariants()
        update(disableAutomation)
    }

    /** Called only from the explicit Auto-mode confirmation dialog. */
    fun enableAutoModeConfirmed() {
        if (!settings.value.acknowledgedCapabilities) {
            _message.value = "Accept the security and privacy disclosure before enabling Auto mode."
            return
        }
        update {
            it.copy(
                mode = AssistantMode.AUTO,
                autoReplyEnabled = true,
                acknowledgedAutomationRisk = true
            )
        }
    }

    fun disableAutoReply() {
        val disable: (AppSettings) -> AppSettings = {
            it.copy(autoReplyEnabled = false, acknowledgedAutomationRisk = false)
        }
        container.currentSettings = disable(container.currentSettings).enforceSafetyInvariants()
        update(disable)
    }

    fun setMonitoring(enabled: Boolean) {
        val context = getApplication<Application>()
        if (!enabled) {
            val stopMonitoring: (AppSettings) -> AppSettings = {
                it.copy(
                    monitoringEnabled = false,
                    autoReplyEnabled = false,
                    acknowledgedAutomationRisk = false
                )
            }
            // Pause the engine and tear services down synchronously. If an AI request is
            // already in flight, ReplyEngine's post-request `running` check prevents send.
            container.currentSettings = stopMonitoring(container.currentSettings)
                .enforceSafetyInvariants()
            AssistantRuntime.engine?.pauseAll("Monitoring turned off by user")
            context.stopService(Intent(context, AssistantService::class.java))
            context.stopService(Intent(context, ScreenCaptureService::class.java))
            update(stopMonitoring)
            return
        }

        viewModelScope.launch {
            val current = settings.value
            if (!current.acknowledgedCapabilities) {
                _message.value = "Accept the security and privacy disclosure first."
                return@launch
            }
            if (!CapabilityStatus.accessibilityEnabled(context)) {
                _message.value = "Enable Live AI Reply in Android Accessibility settings first."
                return@launch
            }

            AssistantRuntime.clearEmergencyStopForUserStart()
            persist { it.copy(monitoringEnabled = true, emergencyStopped = false) }
            startService()
        }
    }

    fun emergencyStop() {
        EmergencyStopController.stopNow(getApplication(), "Stopped from the app")
        _message.value = "STOP is active. Monitoring, overlay, OCR and automatic replies are off."
    }

    fun startService() {
        val context = getApplication<Application>()
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_START)
        )
    }

    fun setOverlayEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (enabled && !settings.value.acknowledgedCapabilities) {
            _message.value = "Accept the security and privacy disclosure first."
            return
        }
        if (enabled && !CapabilityStatus.overlayGranted(context)) {
            _message.value = "Grant Display over other apps first, then enable the floating assistant."
            return
        }
        update { it.copy(overlayEnabled = enabled) }
    }

    fun setOcrEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        if (enabled && !settings.value.acknowledgedCapabilities) {
            _message.value = "Accept the security and privacy disclosure first."
            return
        }
        if (!enabled) context.stopService(Intent(context, ScreenCaptureService::class.java))
        update {
            it.copy(
                ocrEnabled = enabled,
                captureScope = if (enabled) CaptureScope.CONVERSATION_AREA else CaptureScope.OFF
            )
        }
    }

    /** Called only with the result of Android's MediaProjection confirmation activity. */
    fun armScreenCapture(resultCode: Int, data: Intent?) {
        val context = getApplication<Application>()
        val current = settings.value
        if (resultCode != Activity.RESULT_OK || data == null) {
            _message.value = "Screen capture was not authorized. OCR remains idle."
            return
        }
        if (!current.acknowledgedCapabilities || !current.ocrEnabled ||
            current.emergencyStopped || AssistantRuntime.emergencyStopRequested
        ) {
            _message.value = "OCR is off or STOP is active. No screen capture was started."
            return
        }
        val serviceIntent = Intent(context, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_ARM)
            .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(ScreenCaptureService.EXTRA_DATA, data)
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        _message.value = "Android authorized one OCR fallback capture."
    }

    fun stopScreenCapture() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, ScreenCaptureService::class.java))
        _message.value = "Screen capture stopped."
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
