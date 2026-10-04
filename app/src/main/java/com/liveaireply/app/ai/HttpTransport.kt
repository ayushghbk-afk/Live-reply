package com.liveaireply.app.ai

/**
 * Minimal HTTP surface used by the AI providers.
 *
 * Providers depend on this instead of OkHttp directly, so the whole request/response
 * pipeline (headers, payload shape, error classification, retries, fallbacks) can be
 * unit tested with a fake transport and no network.
 */
interface HttpTransport {
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult
    fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult

    /** True when the device has no usable network at all. */
    fun isOnline(): Boolean = true
}

data class HttpResult(
    val statusCode: Int,
    val body: String,
    val failedWith: TransportFailure? = null
) {
    val isSuccess: Boolean get() = statusCode in 200..299 && failedWith == null
}

enum class TransportFailure { TIMEOUT, NO_NETWORK, DNS, TLS, UNKNOWN }
