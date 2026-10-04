package com.liveaireply.app.ai.openai

import com.liveaireply.app.ai.AiCompletionRequest
import com.liveaireply.app.ai.AiError
import com.liveaireply.app.ai.AiErrorKind
import com.liveaireply.app.ai.AiOutcome
import com.liveaireply.app.ai.AiProvider
import com.liveaireply.app.ai.DiscoveredModel
import com.liveaireply.app.ai.HttpResult
import com.liveaireply.app.ai.HttpTransport
import com.liveaireply.app.ai.ModelListOutcome
import com.liveaireply.app.ai.TransportFailure
import com.liveaireply.app.storage.Json
import com.liveaireply.app.storage.JsonValue
import com.liveaireply.app.storage.arr
import com.liveaireply.app.storage.asStringOrNull
import com.liveaireply.app.storage.get
import com.liveaireply.app.storage.jsonArr
import com.liveaireply.app.storage.jsonObj
import com.liveaireply.app.storage.toJson

/** Connection details for any OpenAI-compatible endpoint. Supplied per call. */
data class OpenAiEndpointConfig(
    val baseUrl: String = OPENAI_BASE_URL,
    val apiKey: String = "",
    /** Extra headers, e.g. OpenRouter's HTTP-Referer / X-Title. */
    val extraHeaders: Map<String, String> = emptyMap(),
    val chatPath: String = "/chat/completions",
    val modelsPath: String = "/models",
    /** Extra body fields injected verbatim (some gateways require them). */
    val extraBodyFields: Map<String, JsonValue> = emptyMap()
) {
    fun chatUrl(): String = joinUrl(baseUrl, chatPath)
    fun modelsUrl(): String = joinUrl(baseUrl, modelsPath)

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

        fun joinUrl(base: String, path: String): String {
            val trimmedBase = base.trim().trimEnd('/')
            val trimmedPath = if (path.startsWith("/")) path else "/$path"
            return trimmedBase + trimmedPath
        }
    }
}

/**
 * Talks to any OpenAI-compatible `/chat/completions` endpoint: OpenAI itself,
 * OpenRouter, Groq, Together, and OpenAI-compatible local servers (llama.cpp, vLLM,
 * LM Studio).
 *
 * No API key is held here. [configProvider] is invoked on every request and reads the
 * key from the encrypted credential store, so a key never lives in a field, a log line
 * or a process dump for longer than one call.
 */
open class OpenAiCompatibleProvider(
    override val id: String = "openai-compatible",
    override val displayName: String = "OpenAI compatible",
    private val transport: HttpTransport,
    private val configProvider: () -> OpenAiEndpointConfig
) : AiProvider {

    override val defaultBaseUrl: String get() = configProvider().baseUrl

    override fun complete(request: AiCompletionRequest): AiOutcome {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return AiOutcome.Failure(
                AiError(AiErrorKind.NOT_CONFIGURED, "No API key is configured for ${config.baseUrl}")
            )
        }
        if (!transport.isOnline()) {
            return AiOutcome.Failure(AiError(AiErrorKind.NO_NETWORK, "Device is offline"))
        }

        val startedAt = System.currentTimeMillis()
        val result = transport.post(
            url = config.chatUrl(),
            headers = headers(config),
            body = buildPayload(request, config),
            timeoutMs = request.timeoutMs
        )
        val latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

        result.failedWith?.let { failure ->
            return AiOutcome.Failure(
                AiError(
                    kind = when (failure) {
                        TransportFailure.TIMEOUT -> AiErrorKind.TIMEOUT
                        TransportFailure.NO_NETWORK, TransportFailure.DNS -> AiErrorKind.NO_NETWORK
                        TransportFailure.TLS -> AiErrorKind.SERVER
                        TransportFailure.UNKNOWN -> AiErrorKind.UNKNOWN
                    },
                    message = "Transport failure: $failure"
                )
            )
        }

        val parsed = Json.parseOrNull(result.body)

        if (!result.isSuccess) {
            return AiOutcome.Failure(classifyHttpError(result, parsed))
        }
        if (parsed == null) {
            return AiOutcome.Failure(
                AiError(
                    AiErrorKind.BAD_RESPONSE,
                    "Response was not valid JSON",
                    result.statusCode,
                    preview(result.body)
                )
            )
        }

        val text = extractText(parsed)
        if (text != null) {
            return AiOutcome.Success(
                text = text,
                model = parsed["model"]?.asStringOrNull() ?: request.model,
                latencyMs = latencyMs,
                promptChars = request.payloadChars
            )
        }

        // A 200 that carries an error body happens with some gateways.
        return AiOutcome.Failure(
            errorFromBody(parsed) ?: AiError(
                AiErrorKind.BAD_RESPONSE,
                "Response contained no message content",
                result.statusCode,
                preview(result.body)
            )
        )
    }

    override fun listModels(): ModelListOutcome {
        val config = configProvider()
        if (config.apiKey.isBlank()) {
            return ModelListOutcome.Failure(AiError(AiErrorKind.NOT_CONFIGURED, "No API key is configured"))
        }
        if (!transport.isOnline()) {
            return ModelListOutcome.Failure(AiError(AiErrorKind.NO_NETWORK, "Device is offline"))
        }

        val result = transport.get(config.modelsUrl(), headers(config), 20_000L)
        if (result.failedWith != null) {
            return ModelListOutcome.Failure(
                AiError(AiErrorKind.NO_NETWORK, "Could not reach ${config.baseUrl}")
            )
        }
        val parsed = Json.parseOrNull(result.body)
        if (!result.isSuccess) {
            return ModelListOutcome.Failure(classifyHttpError(result, parsed))
        }
        if (parsed == null) {
            return ModelListOutcome.Failure(
                AiError(AiErrorKind.BAD_RESPONSE, "Model list was not valid JSON")
            )
        }

        val items = parsed.arr("data")
        val models = items.mapNotNull { item ->
            val modelId = item["id"]?.asStringOrNull() ?: return@mapNotNull null
            DiscoveredModel(
                id = modelId,
                name = item["name"]?.asStringOrNull(),
                contextLength = (item["context_length"] as? JsonValue.JNum)?.value?.toInt()
            )
        }.sortedBy { it.id }

        if (models.isEmpty()) {
            return ModelListOutcome.Failure(
                AiError(AiErrorKind.BAD_RESPONSE, "Endpoint returned no models", result.statusCode)
            )
        }
        return ModelListOutcome.Success(models)
    }

    // -------------------------------------------------------------------- payload

    open fun buildPayload(request: AiCompletionRequest, config: OpenAiEndpointConfig): String {
        val messages = jsonArr(
            request.messages.map { message ->
                jsonObj("role" to message.role.wire.toJson(), "content" to message.content.toJson())
            }
        )
        val entries = LinkedHashMap<String, JsonValue>()
        entries["model"] = request.model.toJson()
        entries["messages"] = messages
        entries["temperature"] = request.temperature.coerceIn(0f, 2f).toDouble().toJson()
        entries["max_tokens"] = request.maxTokens.coerceIn(1, 32_000).toJson()
        entries["stream"] = false.toJson()
        config.extraBodyFields.forEach { (key, value) -> entries[key] = value }
        return Json.stringify(JsonValue.JObj(entries))
    }

    open fun headers(config: OpenAiEndpointConfig): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers["Authorization"] = "Bearer ${config.apiKey}"
        headers.putAll(config.extraHeaders)
        return headers
    }

    // -------------------------------------------------------------------- parsing

    private fun extractText(parsed: JsonValue): String? {
        for (choice in parsed.arr("choices")) {
            choice["message"]?.let { it["content"] }?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            choice["delta"]?.let { it["content"] }?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
            choice["text"]?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        // Some gateways wrap the OpenAI payload one level deeper.
        for (wrapper in listOf("data", "result")) {
            val nested = parsed[wrapper]
            if (nested != null && nested != parsed) {
                extractText(nested)?.let { return it }
            }
        }
        return null
    }

    private fun errorFromBody(parsed: JsonValue): AiError? {
        val error = parsed["error"] as? JsonValue.JObj ?: return null
        val message = error["message"]?.asStringOrNull() ?: "Provider returned an error"
        val code = error["code"]?.asStringOrNull()
        val type = error["type"]?.asStringOrNull()
        val kind = when {
            code?.contains("rate", true) == true || type?.contains("rate", true) == true -> AiErrorKind.RATE_LIMIT
            code?.contains("auth", true) == true || type?.contains("auth", true) == true -> AiErrorKind.AUTH
            code?.contains("model", true) == true -> AiErrorKind.MODEL_UNAVAILABLE
            code?.contains("quota", true) == true || code?.contains("billing", true) == true -> AiErrorKind.QUOTA
            code?.contains("content", true) == true -> AiErrorKind.CONTENT_FILTERED
            else -> AiErrorKind.SERVER
        }
        return AiError(kind, message, null, "code=$code type=$type")
    }

    private fun classifyHttpError(result: HttpResult, parsed: JsonValue?): AiError {
        val fromBody = parsed?.let { errorFromBody(it) }
        val kind = when (result.statusCode) {
            401, 403 -> AiErrorKind.AUTH
            402 -> AiErrorKind.QUOTA
            404 -> AiErrorKind.MODEL_UNAVAILABLE
            408, 425 -> AiErrorKind.TIMEOUT
            429 -> AiErrorKind.RATE_LIMIT
            in 500..599 -> AiErrorKind.SERVER
            else -> fromBody?.kind ?: AiErrorKind.UNKNOWN
        }
        return AiError(
            kind = kind,
            message = fromBody?.message ?: "HTTP ${result.statusCode}",
            httpStatus = result.statusCode,
            providerDetail = fromBody?.providerDetail ?: preview(result.body)
        )
    }

    private fun preview(body: String): String = body.replace('\n', ' ').trim().take(240)
}

/**
 * OpenRouter (https://openrouter.ai) with the attribution headers it asks for.
 * The model id is always user supplied - nothing is hard coded.
 */
class OpenRouterProvider(
    transport: HttpTransport,
    configProvider: () -> OpenAiEndpointConfig,
    private val appUrl: String = "https://github.com/ayushghbk-afk/Live-reply",
    private val appTitle: String = "Live AI Reply"
) : OpenAiCompatibleProvider(
    id = "openrouter",
    displayName = "OpenRouter",
    transport = transport,
    configProvider = configProvider
) {
    override fun headers(config: OpenAiEndpointConfig): Map<String, String> =
        super.headers(config) + mapOf(
            "HTTP-Referer" to appUrl,
            "X-Title" to appTitle
        )

    companion object {
        const val BASE_URL = OpenAiEndpointConfig.OPENROUTER_BASE_URL
    }
}
