package com.liveaireply.app.ai.openai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.liveaireply.app.ai.HttpResult
import com.liveaireply.app.ai.HttpTransport
import com.liveaireply.app.ai.TransportFailure
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OkHttp implementation of [HttpTransport].
 *
 * A single client instance is reused (connection pool + DNS cache) and per-request
 * timeouts come from the user's settings, so a slow model cannot pin a socket open
 * indefinitely.
 */
class OkHttpTransport(context: Context) : HttpTransport {

    private val appContext = context.applicationContext

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun isOnline(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutMs: Long
    ): HttpResult {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()
        return execute(request, timeoutMs)
    }

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Long): HttpResult {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { headers.forEach { (key, value) -> addHeader(key, value) } }
            .build()
        return execute(request, timeoutMs)
    }

    private fun execute(request: Request, timeoutMs: Long): HttpResult {
        if (!isOnline()) return HttpResult(0, "", TransportFailure.NO_NETWORK)
        val scoped = client.newBuilder()
            .callTimeout(timeoutMs.coerceIn(2_000L, 300_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.coerceIn(2_000L, 300_000L), TimeUnit.MILLISECONDS)
            .build()
        return try {
            scoped.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                HttpResult(response.code, text)
            }
        } catch (e: SocketTimeoutException) {
            HttpResult(0, "", TransportFailure.TIMEOUT)
        } catch (e: UnknownHostException) {
            HttpResult(0, "", TransportFailure.DNS)
        } catch (e: SSLException) {
            HttpResult(0, "", TransportFailure.TLS)
        } catch (e: IOException) {
            HttpResult(0, "", TransportFailure.NO_NETWORK)
        } catch (e: Exception) {
            HttpResult(0, "", TransportFailure.UNKNOWN)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
