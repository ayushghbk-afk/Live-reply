package com.liveaireply.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.liveaireply.app.adapters.ChatAdapterRegistry
import com.liveaireply.app.accessibility.LiveReplyAccessibilityService
import com.liveaireply.app.ocr.OcrCaptureState
import com.liveaireply.app.security.CapabilityStatus
import com.liveaireply.app.personas.Persona
import com.liveaireply.app.personas.PersonaPresets
import com.liveaireply.app.settings.AppSettings
import com.liveaireply.app.settings.AssistantMode
import com.liveaireply.app.settings.LanguagePolicy
import com.liveaireply.app.settings.ReplyDelay
import com.liveaireply.app.settings.ReplyLanguagePolicy
import com.liveaireply.app.settings.ThemeMode
import com.liveaireply.app.ui.AppViewModel
import com.liveaireply.app.ui.theme.LiveReplyTheme
import com.liveaireply.app.personas.ReplyLength
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private val systemStatusRefresh = MutableStateFlow(0)

    override fun onResume() {
        super.onResume()
        systemStatusRefresh.value = systemStatusRefresh.value + 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by viewModel.settings.collectAsState()
            val statusRefresh by systemStatusRefresh.collectAsState()
            LiveReplyTheme(themeMode = settings.themeMode) {
                AppRoot(viewModel, statusRefresh)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: AppViewModel, systemStatusRefresh: Int) {
    // Reading this value refreshes system-grant statuses after returning from Settings.
    systemStatusRefresh.hashCode()
    val settings by viewModel.settings.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var screen by remember { mutableStateOf(if (settings.setupCompleted) "home" else "setup") }
    LaunchedEffect(settings.setupCompleted) {
        if (settings.setupCompleted && screen == "setup") screen = "home"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.app_name)) },
                actions = {
                    Text(
                        text = context.getString(R.string.stop_ai),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { viewModel.emergencyStop() }
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (screen) {
                "home" -> HomeScreen(viewModel, settings, onNavigate = { screen = it })
                "setup" -> SetupScreen(viewModel, settings, onDone = { screen = "home" })
                "ai" -> AiSettingsScreen(viewModel, settings)
                "personas" -> PersonasScreen(viewModel)
                "apps" -> AppsScreen(viewModel, settings)
                "privacy" -> PrivacyScreen(viewModel, settings)
                "settings" -> SecuritySettingsScreen(viewModel, settings, onNavigate = { screen = it })
                "permissions" -> SecuritySettingsScreen(viewModel, settings, onNavigate = { screen = it })
                "logs" -> LogsScreen(viewModel)
                "test" -> TestModeScreen(viewModel)
            }
            message?.let {
                Card { Text(it, modifier = Modifier.padding(12.dp), fontSize = 13.sp) }
            }
        }
    }
}

// --------------------------------------------------------------------- screens

@Composable
private fun HomeScreen(viewModel: AppViewModel, settings: AppSettings, onNavigate: (String) -> Unit) {
    val overlay by viewModel.overlayState.collectAsState()
    val context = LocalContext.current
    val running = LiveReplyAccessibilityService.isRunning()
    var showAutoDisclosure by remember { mutableStateOf(false) }

    SectionCard(title = "Status") {
        StatusLine("Service", if (running) "Running" else "Not connected")
        StatusLine("Mode", settings.mode.label)
        StatusLine("AI model", settings.primaryModel.ifBlank { "not set" })
        StatusLine("Persona", settings.personaId)
        StatusLine("Current app", overlay.currentAppLabel ?: "-")
        StatusLine("Status", "${overlay.status.label} ${overlay.statusDetail}")
    }

    SectionCard(title = "Monitoring") {
        SwitchRow(
            label = "Monitoring",
            checked = settings.monitoringEnabled,
            onChecked = { viewModel.setMonitoring(it) }
        )
        SwitchRow(
            label = "Auto reply",
            checked = settings.autoReplyEnabled && settings.mode == AssistantMode.AUTO,
            onChecked = { enabled ->
                if (enabled) showAutoDisclosure = true else viewModel.disableAutoReply()
            }
        )
        Text(
            text = "Automatic sending types into another app and presses Send. " +
                "Only enable it for chats where that is acceptable.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    SectionCard(title = "Mode") {
        AssistantMode.entries.forEach { mode ->
            ChoiceRow(
                label = mode.label,
                description = mode.description,
                selected = settings.mode == mode,
                onClick = {
                    if (mode == AssistantMode.AUTO) showAutoDisclosure = true
                    else viewModel.setMode(mode)
                }
            )
        }
    }

    if (showAutoDisclosure) {
        AutoModeOptInDialog(
            onDismiss = { showAutoDisclosure = false },
            onConfirm = {
                viewModel.enableAutoModeConfirmed()
                showAutoDisclosure = false
            }
        )
    }

    Button(onClick = { viewModel.testConnection() }, modifier = Modifier.fillMaxWidth()) {
        Text("Test AI")
    }
    Button(
        onClick = { viewModel.emergencyStop() },
        modifier = Modifier.fillMaxWidth()
    ) { Text(context.getString(R.string.stop_ai), fontWeight = FontWeight.Bold) }

    SectionCard(title = "Settings") {
        NavRow("Manage personas") { onNavigate("personas") }
        NavRow("AI settings") { onNavigate("ai") }
        NavRow("Supported apps") { onNavigate("apps") }
        NavRow("Security & Settings") { onNavigate("settings") }
        NavRow("Privacy details") { onNavigate("privacy") }
        NavRow("Logs") { onNavigate("logs") }
        NavRow("Test mode") { onNavigate("test") }
        NavRow("Setup wizard") { onNavigate("setup") }
    }
}

@Composable
private fun SetupScreen(viewModel: AppViewModel, settings: AppSettings, onDone: () -> Unit) {
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    var showAutoDisclosure by remember { mutableStateOf(false) }

    // The disclosure comes first and cannot be skipped: the sensitive capabilities are
    // stated in plain language before any permission is requested, and "Finish setup" stays
    // disabled until the user has acknowledged them (tracked by
    // AppSettings.acknowledgedCapabilities, which is also recorded in preferences).
    SectionCard(title = "1. ${context.getString(R.string.disclosure_title)}") {
        Text(
            text = context.getString(R.string.disclosure_accessibility),
            fontWeight = FontWeight.SemiBold
        )
        Text(context.getString(R.string.disclosure_accessibility_scope), fontSize = 12.sp)
        Text(context.getString(R.string.disclosure_overlay), fontSize = 12.sp)
        Text(context.getString(R.string.disclosure_foreground), fontSize = 12.sp)
        Text(context.getString(R.string.disclosure_screen_capture), fontSize = 12.sp)
        Text(context.getString(R.string.disclosure_network), fontSize = 12.sp)
        ConsentCheckbox(
            label = context.getString(R.string.disclosure_acknowledge),
            checked = settings.acknowledgedCapabilities,
            onChecked = viewModel::acknowledgeCapabilities
        )
    }
    SectionCard(title = "2. Welcome") {
        Text("Live AI Reply reads a chat you selected, drafts a reply with your AI, and " +
            "shows it in the app or optional floating assistant. Suggest mode is the default; " +
            "nothing is typed or sent until you act.")
    }
    SectionCard(title = "3. Accessibility permission") {
        Text("Needed to read the conversation and to type the reply. Without it the app cannot see any chat.")
        OutlinedButton(
            onClick = { openAccessibilitySettings(context) },
            enabled = settings.acknowledgedCapabilities
        ) { Text("Open Accessibility settings") }
    }
    SectionCard(title = "4. Optional floating assistant") {
        Text("Off by default. Android's Display over other apps grant and the in-app switch " +
            "are both required. You can remove the floating UI at any time.", fontSize = 12.sp)
        OutlinedButton(
            onClick = { openOverlaySettings(context) },
            enabled = settings.acknowledgedCapabilities
        ) { Text("Open overlay permission") }
        SwitchRow(
            label = "Show floating assistant",
            checked = settings.overlayEnabled,
            onChecked = viewModel::setOverlayEnabled
        )
    }
    SectionCard(title = "5. Screen capture / OCR") {
        OcrControls(viewModel, settings)
    }
    SectionCard(title = "6. AI provider") {
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { viewModel.update { s -> s.copy(baseUrl = it) } },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key (never shown again after saving)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.saveApiKey(key); key = "" }) { Text("Save key") }
        Button(onClick = { viewModel.testConnection() }) { Text("Test connection") }
    }
    SectionCard(title = "7. Model") {
        OutlinedTextField(
            value = settings.primaryModel,
            onValueChange = { viewModel.update { s -> s.copy(primaryModel = it) } },
            label = { Text("Model id") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { viewModel.fetchModels() }) { Text("Fetch models") }
    }
    SectionCard(title = "8. Persona") {
        PersonaPresets.ALL.forEach { persona ->
            ChoiceRow(
                label = persona.label,
                description = persona.personality,
                selected = settings.personaId == persona.id,
                onClick = { viewModel.setPersona(persona.id) }
            )
        }
    }
    SectionCard(title = "9. Mode") {
        AssistantMode.entries.forEach { mode ->
            ChoiceRow(
                label = mode.label,
                description = mode.description,
                selected = settings.mode == mode,
                onClick = {
                    if (mode == AssistantMode.AUTO) showAutoDisclosure = true
                    else viewModel.setMode(mode)
                }
            )
        }
    }
    if (showAutoDisclosure) {
        AutoModeOptInDialog(
            onDismiss = { showAutoDisclosure = false },
            onConfirm = {
                viewModel.enableAutoModeConfirmed()
                showAutoDisclosure = false
            }
        )
    }
    SectionCard(title = "10. Finish") {
        Button(onClick = { viewModel.testConnection() }) { Text("Test connection") }
        Button(
            onClick = {
                viewModel.finishSetup()
                onDone()
            },
            enabled = settings.acknowledgedCapabilities,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Finish setup") }
        if (!settings.acknowledgedCapabilities) {
            Text(
                text = context.getString(R.string.disclosure_required),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun AiSettingsScreen(viewModel: AppViewModel, settings: AppSettings) {
    var key by remember { mutableStateOf("") }
    SectionCard(title = "Provider") {
        OutlinedTextField(
            value = settings.providerId,
            onValueChange = { viewModel.update { s -> s.copy(providerId = it) } },
            label = { Text("Provider id (openrouter or custom)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.baseUrl,
            onValueChange = { viewModel.update { s -> s.copy(baseUrl = it) } },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Saved key: ${viewModel.maskedApiKey().ifBlank { "none" }}",
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("API key") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.saveApiKey(key); key = "" }) { Text("Save key") }
        OutlinedButton(onClick = { viewModel.saveApiKey("") }) { Text("Clear key") }
    }

    SectionCard(title = "Models") {
        OutlinedTextField(
            value = settings.primaryModel,
            onValueChange = { viewModel.update { s -> s.copy(primaryModel = it) } },
            label = { Text("Primary model") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.fallbackModels.joinToString(", "),
            onValueChange = { value ->
                viewModel.update { s ->
                    s.copy(fallbackModels = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            },
            label = { Text("Fallback models (comma separated)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { viewModel.fetchModels() }) { Text("Fetch models") }
        Text("Tried in order. A rate limit, timeout or unavailable model moves to the next one; " +
            "an invalid API key does not.", fontSize = 11.sp)
    }

    SectionCard(title = "Generation") {
        SliderRow("Temperature", settings.temperature, 0f, 2f) {
            viewModel.update { s -> s.copy(temperature = it) }
        }
        OutlinedTextField(
            value = settings.maxTokens.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { viewModel.update { s -> s.copy(maxTokens = it) } }
            },
            label = { Text("Max response tokens") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.timeoutMs.toString(),
            onValueChange = { value ->
                value.toLongOrNull()?.let { viewModel.update { s -> s.copy(timeoutMs = it) } }
            },
            label = { Text("Timeout (ms)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.retryCount.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { viewModel.update { s -> s.copy(retryCount = it) } }
            },
            label = { Text("Retry count") },
            modifier = Modifier.fillMaxWidth()
        )
    }

    SectionCard(title = "Reply style") {
        ReplyLength.entries.forEach { length ->
            ChoiceRow(
                label = length.label,
                description = if (length == ReplyLength.CUSTOM) "uses max characters below" else "${length.maxChars} chars",
                selected = settings.replyLength == length,
                onClick = { viewModel.update { s -> s.copy(replyLength = length) } }
            )
        }
        OutlinedTextField(
            value = settings.maxReplyChars.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { viewModel.update { s -> s.copy(maxReplyChars = it) } }
            },
            label = { Text("Maximum reply length (characters)") },
            modifier = Modifier.fillMaxWidth()
        )
        LanguagePolicy.entries.forEach { policy ->
            ChoiceRow(
                label = policy.label,
                description = "Reply language",
                selected = settings.languagePolicy == policy,
                onClick = { viewModel.update { s -> s.copy(languagePolicy = policy) } }
            )
        }
        SwitchRow(
            label = "Translation mode",
            checked = settings.translateIncoming,
            onChecked = { viewModel.update { s -> s.copy(translateIncoming = it) } }
        )
        ReplyLanguagePolicy.entries.forEach { policy ->
            ChoiceRow(
                label = policy.label,
                description = "Reply in",
                selected = settings.replyLanguage == policy,
                onClick = { viewModel.update { s -> s.copy(replyLanguage = policy) } }
            )
        }
    }

    SectionCard(title = "Timing") {
        ReplyDelay.entries.forEach { delay ->
            ChoiceRow(
                label = delay.label,
                description = "Wait before sending in Auto mode",
                selected = settings.replyDelay == delay,
                onClick = { viewModel.update { s -> s.copy(replyDelay = delay) } }
            )
        }
        SwitchRow(
            label = "Simulate typing",
            checked = settings.simulateTyping,
            onChecked = { viewModel.update { s -> s.copy(simulateTyping = it) } }
        )
        OutlinedTextField(
            value = settings.typingSpeedCharsPerSecond.toString(),
            onValueChange = { value ->
                value.toIntOrNull()?.let { viewModel.update { s -> s.copy(typingSpeedCharsPerSecond = it) } }
            },
            label = { Text("Typing speed (chars/second)") },
            modifier = Modifier.fillMaxWidth()
        )
        SliderRow("Debounce (ms)", settings.debounceMs.toFloat(), 200f, 3000f) {
            viewModel.update { s -> s.copy(debounceMs = it.toLong()) }
        }
        AppSettings.CONTEXT_CHOICES.forEach { count ->
            ChoiceRow(
                label = "$count messages",
                description = "Conversation context sent to the AI",
                selected = settings.contextMessageCount == count,
                onClick = { viewModel.update { s -> s.copy(contextMessageCount = count) } }
            )
        }
    }

    SectionCard(title = "System prompt") {
        Text("Leave blank to use the built-in prompt. Anything you write here replaces it " +
            "completely; persona, length and language rules are still added.", fontSize = 11.sp)
        OutlinedTextField(
            value = settings.customSystemPrompt,
            onValueChange = { viewModel.update { s -> s.copy(customSystemPrompt = it) } },
            label = { Text("Custom system prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4
        )
    }

    Button(onClick = { viewModel.testConnection() }, modifier = Modifier.fillMaxWidth()) {
        Text("Test connection")
    }
}

@Composable
private fun PersonasScreen(viewModel: AppViewModel) {
    val personas by viewModel.personas.collectAsState()
    var editing by remember { mutableStateOf<Persona?>(null) }
    val draft = editing ?: Persona(id = "", label = "")

    personas.forEach { persona ->
        SectionCard(title = persona.label) {
            if (persona.personaName.isNotBlank()) StatusLine("Character", persona.personaName)
            if (persona.personality.isNotBlank()) StatusLine("Personality", persona.personality)
            if (persona.background.isNotBlank()) StatusLine("Background", persona.background)
            if (persona.relationship.isNotBlank()) StatusLine("Relationship", persona.relationship)
            if (persona.speakingStyle.isNotBlank()) StatusLine("Style", persona.speakingStyle)
            persona.rules.forEach { Text("\u2022 $it", fontSize = 12.sp) }
            OutlinedButton(onClick = { editing = persona }) { Text("Edit") }
            if (!persona.builtIn) {
                OutlinedButton(onClick = { viewModel.deletePersona(persona.id) }) { Text("Delete") }
            }
        }
    }

    SectionCard(title = if (draft.id.isEmpty()) "New persona" else "Edit ${draft.label}") {
        PersonaField("Label", draft.label) { editing = draft.copy(id = draft.id.ifEmpty { Persona.newId(it) }, label = it) }
        PersonaField("Character name", draft.personaName) { editing = draft.copy(personaName = it) }
        PersonaField("Personality", draft.personality) { editing = draft.copy(personality = it) }
        PersonaField("Background", draft.background) { editing = draft.copy(background = it) }
        PersonaField("Relationship", draft.relationship) { editing = draft.copy(relationship = it) }
        PersonaField("Speaking style", draft.speakingStyle) { editing = draft.copy(speakingStyle = it) }
        PersonaField("Rules (one per line)", draft.rules.joinToString("\n")) {
            editing = draft.copy(rules = it.split("\n").map { line -> line.trim() }.filter { line -> line.isNotEmpty() })
        }
        PersonaField("Extra instructions", draft.extraInstructions) { editing = draft.copy(extraInstructions = it) }
        SwitchRow(
            label = "Roleplay persona",
            checked = draft.isRoleplay,
            onChecked = { editing = draft.copy(isRoleplay = it) }
        )
        Button(onClick = {
            editing?.let { viewModel.savePersona(it); editing = null }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save persona") }
    }
}

@Composable
private fun AppsScreen(viewModel: AppViewModel, settings: AppSettings) {
    SectionCard(title = "Enabled apps") {
        ChatAdapterRegistry.KNOWN_APPS.filter { it.packageName.isNotBlank() }.forEach { app ->
            val enabled = settings.isPackageEnabled(app.packageName)
            SwitchRow(
                label = app.displayName,
                checked = enabled,
                onChecked = { checked ->
                    viewModel.update { current ->
                        val others = current.enabledPackages.filter { it != app.packageName }
                        current.copy(enabledPackages = if (checked) others + app.packageName else others)
                    }
                }
            )
        }
    }
    SectionCard(title = "Other chat app package names") {
        val knownPackages = ChatAdapterRegistry.KNOWN_APPS.map { it.packageName }.filter { it.isNotBlank() }.toSet()
        val additional = settings.enabledPackages.filterNot { it in knownPackages }
        var additionalText by remember(additional) { mutableStateOf(additional.joinToString(", ")) }
        Text(
            "Optional. Add exact package names only for chat apps you want the service to read. " +
                "Android event delivery is restricted to this enabled list.",
            fontSize = 12.sp
        )
        OutlinedTextField(
            value = additionalText,
            onValueChange = { additionalText = it },
            label = { Text("Additional package names (comma separated)") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            val requested = additionalText.split(",")
                .map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) }
            viewModel.update { current ->
                current.copy(enabledPackages = current.enabledPackages.filter { it in knownPackages } + requested)
            }
        }) { Text("Save additional apps") }
    }
    SectionCard(title = "Excluded apps") {
        OutlinedTextField(
            value = settings.excludedPackages.joinToString(", "),
            onValueChange = { value ->
                viewModel.update { s ->
                    s.copy(excludedPackages = value.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            },
            label = { Text("Package names the assistant must never read") },
            modifier = Modifier.fillMaxWidth()
        )
    }
    SectionCard(title = "Paused conversations") {
        if (settings.pausedConversations.isEmpty()) {
            Text("None. Use Pause chat on the overlay.", fontSize = 12.sp)
        } else {
            settings.pausedConversations.forEach { conversationId ->
                Column {
                    Text(conversationId, fontSize = 12.sp)
                    OutlinedButton(onClick = {
                        viewModel.update { s ->
                            s.copy(pausedConversations = s.pausedConversations.filter { it != conversationId })
                        }
                    }) { Text("Resume") }
                }
            }
        }
    }
}

@Composable
private fun PrivacyScreen(viewModel: AppViewModel, settings: AppSettings) {
    SectionCard(title = "What leaves the device") {
        Text("The configured number of recent visible chat lines, persona/prompt instructions, " +
            "model settings and the API request are sent to the AI endpoint you selected. " +
            "Sensitive values are redacted first. Screenshots are processed only on-device, " +
            "discarded immediately and never uploaded.", fontSize = 12.sp)
    }
    SectionCard(title = "Sensitive screens") {
        Text("Banking, wallet, authenticator and password screens are skipped automatically. " +
            "Add any package you want excluded above.", fontSize = 12.sp)
    }
    SectionCard(title = "Logging") {
        SwitchRow(
            label = "Additional in-memory diagnostic detail",
            checked = settings.debugLogging,
            onChecked = { viewModel.update { s -> s.copy(debugLogging = it) } }
        )
        Text("Diagnostics stay in a bounded in-memory buffer, are not uploaded or persisted, " +
            "and always pass through secret redaction. API keys are never logged.", fontSize = 11.sp)
    }
    SectionCard(title = "Overlay") {
        SwitchRow(
            label = "Show floating control while monitoring",
            checked = settings.overlayEnabled,
            onChecked = viewModel::setOverlayEnabled
        )
        SliderRow("Size", settings.overlayScale, 0.7f, 1.6f) {
            viewModel.update { s -> s.copy(overlayScale = it) }
        }
        SliderRow("Opacity", settings.overlayOpacity, 0.4f, 1f) {
            viewModel.update { s -> s.copy(overlayOpacity = it) }
        }
    }
    SectionCard(title = "Theme") {
        ThemeMode.entries.forEach { mode ->
            ChoiceRow(
                label = mode.label, description = "",
                selected = settings.themeMode == mode,
                onClick = { viewModel.update { s -> s.copy(themeMode = mode) } }
            )
        }
    }
}

@Composable
private fun SecuritySettingsScreen(
    viewModel: AppViewModel,
    settings: AppSettings,
    onNavigate: (String) -> Unit
) {
    val context = LocalContext.current
    val ocrState by viewModel.ocrCaptureState.collectAsState()
    val accessibilityGranted = CapabilityStatus.accessibilityEnabled(context)
    val overlayGranted = CapabilityStatus.overlayGranted(context)

    SectionCard(title = "Security status") {
        StatusLine(
            "Accessibility status",
            when {
                !accessibilityGranted -> "Not enabled"
                LiveReplyAccessibilityService.isRunning() -> "Enabled and connected"
                else -> "Enabled (service not connected)"
            }
        )
        StatusLine(
            "Overlay status",
            when {
                !overlayGranted -> "System permission not granted"
                settings.overlayEnabled -> "Granted and enabled by you"
                else -> "Granted, floating UI off"
            }
        )
        StatusLine(
            "Screen capture / OCR status",
            if (!settings.ocrEnabled) "Feature off" else ocrState.label
        )
        StatusLine("AI provider", settings.providerId.ifBlank { "Not configured" })
        Text("API endpoint", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(settings.baseUrl.ifBlank { "Not configured" }, fontSize = 12.sp)
    }

    SectionCard(title = "Emergency STOP") {
        Text(
            "Stops the foreground assistant, removes the overlay, releases screen capture, " +
                "clears pending suggestions and prevents further automated replies.",
            fontSize = 12.sp
        )
        SwitchRow(
            label = if (settings.emergencyStopped) "STOP is active" else "Activate global STOP",
            checked = settings.emergencyStopped,
            enabled = !settings.emergencyStopped,
            onChecked = { enabled -> if (enabled) viewModel.emergencyStop() }
        )
        Button(
            onClick = viewModel::emergencyStop,
            modifier = Modifier.fillMaxWidth()
        ) { Text("STOP EVERYTHING NOW", fontWeight = FontWeight.Bold) }
        if (settings.emergencyStopped) {
            Text(
                "STOP remains active. To resume, deliberately turn Monitoring on from Home; " +
                    "Auto reply remains off and needs a new opt-in.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    SectionCard(title = "Accessibility — core feature") {
        Text(
            "Reads visible conversation text only from package names you enable under Supported " +
                "apps. It identifies the composer and can use ACTION_SET_TEXT / a labelled Send " +
                "control only in Approve or explicitly opted-in Auto mode. Coordinate gestures " +
                "and key-event filtering are disabled.",
            fontSize = 12.sp
        )
        OutlinedButton(
            onClick = { openAccessibilitySettings(context) },
            enabled = settings.acknowledgedCapabilities
        ) { Text("Open Accessibility settings") }
        OutlinedButton(onClick = { onNavigate("apps") }) { Text("Choose supported apps") }
    }

    SectionCard(title = "Floating assistant — optional") {
        Text(
            "The system grant alone does not show anything. The in-app switch below controls " +
                "whether the overlay exists while monitoring. It is removed immediately by STOP.",
            fontSize = 12.sp
        )
        OutlinedButton(
            onClick = { openOverlaySettings(context) },
            enabled = settings.acknowledgedCapabilities
        ) { Text("Open Display over other apps") }
        SwitchRow(
            label = "Show floating assistant",
            checked = settings.overlayEnabled,
            onChecked = viewModel::setOverlayEnabled
        )
    }

    SectionCard(title = "Screen capture / OCR — optional") {
        OcrControls(viewModel, settings)
    }

    SectionCard(title = "AI and data processing") {
        Text("Provider: ${settings.providerId.ifBlank { "Not configured" }}", fontSize = 12.sp)
        Text("Endpoint: ${settings.baseUrl.ifBlank { "Not configured" }}", fontSize = 12.sp)
        Text(
            "When monitoring detects a new message, recent visible chat text and your reply " +
                "instructions may be sent to this endpoint. Before transmission, Live AI Reply " +
                "redacts OTPs, passwords, PINs, card numbers, CVVs, bank-account values and " +
                "authentication codes. The selected provider may process the remaining text " +
                "under its own terms. OCR images never leave the device.",
            fontSize = 12.sp
        )
        OutlinedButton(onClick = { onNavigate("ai") }) { Text("Edit AI provider and endpoint") }
        OutlinedButton(onClick = { onNavigate("privacy") }) { Text("Open privacy details") }
    }

    SectionCard(title = "Other system controls") {
        StatusLine(
            "Notifications",
            if (Build.VERSION.SDK_INT < 33 ||
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
            ) "Enabled" else "Not enabled"
        )
        if (Build.VERSION.SDK_INT >= 33) {
            OutlinedButton(
                onClick = {
                    ActivityCompat.requestPermissions(
                        context as Activity,
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        1001
                    )
                },
                enabled = settings.acknowledgedCapabilities
            ) { Text("Allow monitoring notifications") }
        }
        StatusLine(
            "Battery optimisation",
            if (isIgnoringBatteryOptimisations(context)) "Exempt" else "Optimised"
        )
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }) { Text("Battery optimisation settings") }
        Text(text = context.getString(R.string.disclosure_battery), fontSize = 11.sp)
    }
}

@Composable
private fun LogsScreen(viewModel: AppViewModel) {
    val logs by viewModel.logs.collectAsState()
    SectionCard(title = "Diagnostics") {
        OutlinedButton(onClick = { viewModel.clearLogs() }) { Text("Clear logs") }
        logs.takeLast(120).reversed().forEach { entry ->
            Text(
                text = "${com.liveaireply.app.util.EventLog.formatLine(entry)}",
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun TestModeScreen(viewModel: AppViewModel) {
    var incoming by remember { mutableStateOf("Are you coming tomorrow?") }
    SectionCard(title = "Test mode") {
        Text("Runs the full pipeline (prompt, model, validation) without touching another " +
            "app.", fontSize = 12.sp)
        OutlinedTextField(
            value = incoming,
            onValueChange = { incoming = it },
            label = { Text("Incoming message") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { viewModel.runTestReply(incoming) }, modifier = Modifier.fillMaxWidth()) {
            Text("Generate test reply")
        }
    }
}

// ---------------------------------------------------------- consent / safety controls

@Composable
private fun OcrControls(viewModel: AppViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val captureState by viewModel.ocrCaptureState.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.armScreenCapture(result.resultCode, result.data)
    }

    Text(
        "Optional and off by default. Accessibility text is always tried first. If it is " +
            "unavailable, one frame may be captured only after you tap the button below and " +
            "approve Android's screen-capture dialog. OCR runs on-device; the bitmap is " +
            "discarded immediately and never uploaded.",
        fontSize = 12.sp
    )
    Text(
        "Android excludes FLAG_SECURE-protected content. Live AI Reply does not try to bypass " +
            "that protection. A fresh fallback requires fresh Android confirmation.",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SwitchRow(
        label = "Enable optional OCR fallback",
        checked = settings.ocrEnabled,
        enabled = settings.acknowledgedCapabilities && !settings.emergencyStopped,
        onChecked = viewModel::setOcrEnabled
    )
    StatusLine("One-shot capture", captureState.label)
    Button(
        onClick = {
            val manager = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            launcher.launch(manager.createScreenCaptureIntent())
        },
        enabled = settings.acknowledgedCapabilities && settings.ocrEnabled &&
            !settings.emergencyStopped && captureState == OcrCaptureState.OFF,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Authorize one OCR fallback") }
    if (captureState != OcrCaptureState.OFF) {
        OutlinedButton(
            onClick = viewModel::stopScreenCapture,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop screen capture now") }
    }
}

@Composable
private fun AutoModeOptInDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var understood by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto mode can send without another tap") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "When Monitoring is on, Auto mode may read visible chat text in the apps " +
                        "you selected, send a redacted conversation excerpt to your AI endpoint, " +
                        "insert the generated reply into the identified composer, and activate " +
                        "that chat's labelled Send control after your configured delay."
                )
                Text(
                    "It does not approve permissions, payments, security prompts or credential " +
                        "fields. Safety checks can fall back to a suggestion, but generated text " +
                        "can still be wrong. STOP AI remains available in the app, overlay and " +
                        "foreground notification."
                )
                ConsentCheckbox(
                    label = "I understand Auto can type and send messages without another tap",
                    checked = understood,
                    onChecked = { understood = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = understood) { Text("Enable Auto mode") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConsentCheckbox(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
    }
}

// --------------------------------------------------------------------- widgets

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Divider()
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChecked: (Boolean) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (description.isNotBlank()) {
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = selected, onCheckedChange = { onClick() })
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", fontSize = 12.sp)
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onChange,
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PersonaField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private fun openOverlaySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            )
        )
    }
}

private fun isIgnoringBatteryOptimisations(context: android.content.Context): Boolean {
    val manager = context.getSystemService(android.content.Context.POWER_SERVICE)
        as? android.os.PowerManager ?: return false
    return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
}
