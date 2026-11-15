package com.wukong.phone

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Explicit, bounded discovery against the configured origin. Never follows credential redirects. */
class ModelCatalog(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    enum class Failure { AUTH, UNAVAILABLE, RATE_LIMIT, NETWORK, INVALID_RESPONSE, UNKNOWN_ROUTE, HTTP }
    class CatalogException(val reason: Failure, val status: Int? = null) : Exception(reason.name)
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    fun cancel() {
        cancelled = true
        connection?.let { active -> closer.execute { active.disconnect() } }
    }
    fun fetch(settings: ModelSettings): List<String> {
        // Model discovery must work before a model has been selected.
        settings.copy(model = "discovery").validate()
        val address = settings.modelsEndpoint() ?: throw CatalogException(Failure.UNKNOWN_ROUTE)
        check(!cancelled && !Thread.currentThread().isInterrupted)
        val conn = connect(URL(address)); connection = conn
        try {
            check(!cancelled && !Thread.currentThread().isInterrupted)
            conn.requestMethod = "GET"; conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status !in 200..299) throw CatalogException(when (status) {
                401, 403 -> Failure.AUTH
                404, 405, 501 -> Failure.UNAVAILABLE
                429 -> Failure.RATE_LIMIT
                else -> Failure.HTTP
            }, status)
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (true) {
                    check(!cancelled && !Thread.currentThread().isInterrupted)
                    val count = input.read(chunk); if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024) throw CatalogException(Failure.INVALID_RESPONSE)
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            return parse(String(bytes, Charsets.UTF_8))
        } catch (e: java.io.IOException) { throw CatalogException(Failure.NETWORK) }
        finally { connection = null; conn.disconnect() }
    }
    companion object {
        private val closer = Executors.newSingleThreadExecutor()
        internal fun parse(body: String): List<String> {
            try {
                val parser = org.json.JSONTokener(body)
                val root = parser.nextValue() as? JSONObject ?: throw CatalogException(Failure.INVALID_RESPONSE)
                if (parser.nextClean() != '\u0000') throw CatalogException(Failure.INVALID_RESPONSE)
                val data = root.optJSONArray("data") ?: throw CatalogException(Failure.INVALID_RESPONSE)
                if (data.length() > 2000) throw CatalogException(Failure.INVALID_RESPONSE)
                return (0 until data.length()).mapNotNull { index ->
                    (data.optJSONObject(index)?.opt("id") as? String)?.trim()?.takeIf {
                        it.isNotEmpty() && it.length <= 200 && it.none(Char::isISOControl)
                    }
                }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            } catch (e: org.json.JSONException) { throw CatalogException(Failure.INVALID_RESPONSE) }
        }
    }
}
