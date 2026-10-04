package com.liveaireply.app.ai

/**
 * Scriptable [HttpTransport]. Every AI test runs against this, so the request shape,
 * header handling and error classification are all exercised without a network.
 */
class FakeTransport(
    private var online: Boolean = true
) : HttpTransport {

    data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?, val timeoutMs: Long)

    val calls = ArrayList<Call>()

    /** Queued responses, consumed in order. The last one repeats forever. */
    private val responses = ArrayDeque<HttpResult>()

    fun enqueue(result: HttpResult) {
        responses += result
    }

    fun enqueueJson(status: Int, body: String) = enqueue(HttpResult(status, body))

    fun enqueueFailure(failure: TransportFailure) = enqueue(HttpResult(0, "", failure))

    fun setOnline(online: Boolean) {
        this.online = online
    }

    override fun isOnline(): Boolean = online

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Long): HttpResult {
        calls += Call("POST", url, headers, body, timeoutMs)
        return next()
    }

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        calls += Call("GET", url, headers, null, timeoutMs)
        return next()
    }

    private fun next(): HttpResult =
        if (responses.isEmpty()) HttpResult(500, """{"error":{"message":"no response queued"}}""")
        else if (responses.size == 1) responses.first()
        else responses.removeFirst()

    fun lastCall(): Call = calls.last()
}

/** AI provider that replays canned outcomes, used by engine and pipeline tests. */
class FakeProvider(
    override val id: String = "fake",
    override val displayName: String = "Fake",
    override val defaultBaseUrl: String = "https://fake.test/v1"
) : AiProvider {

    val outcomes = ArrayDeque<AiOutcome>()
    val requests = ArrayList<AiCompletionRequest>()

    fun replyWith(text: String) {
        outcomes += AiOutcome.Success(text, "fake-model", 42L, 100)
    }

    fun failWith(kind: AiErrorKind, message: String = "failed", retryAfterMs: Long? = null) {
        outcomes += AiOutcome.Failure(AiError(kind, message, retryAfterMs = retryAfterMs))
    }

    override fun complete(request: AiCompletionRequest): AiOutcome {
        requests += request
        return when {
            outcomes.size > 1 -> outcomes.removeFirst()
            outcomes.size == 1 -> outcomes.first()
            else -> AiOutcome.Failure(AiError(AiErrorKind.UNKNOWN, "no outcome queued"))
        }
    }
}
