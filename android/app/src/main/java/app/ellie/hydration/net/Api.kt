package app.ellie.hydration.net

import app.ellie.hydration.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Phase 1 runs standalone (no server). The backend is enabled only when a URL is configured. */
object BackendConfig {
    val enabled: Boolean get() = BuildConfig.BACKEND_URL.isNotBlank()
}

class ApiException(val code: Int, msg: String) : IOException(msg)

/**
 * Tiny HTTPS client (no third-party HTTP library = smaller attack surface).
 * - HTTPS enforced (dev flavor may use the emulator loopback only).
 * - Response bodies capped to avoid memory abuse.
 * - Device token sent only to our own backend origin.
 */
class Api(private val deviceToken: String?) {
    private val base = BuildConfig.BACKEND_URL.trimEnd('/')

    init {
        val ok = base.startsWith("https://") ||
            (BuildConfig.ALLOW_CLEARTEXT_DEV && base.startsWith("http://10.0.2.2"))
        require(ok) { "Backend URL must be HTTPS" }
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(base + path).openConnection() as HttpURLConnection
            try {
                if (conn !is HttpsURLConnection && !BuildConfig.ALLOW_CLEARTEXT_DEV) {
                    throw IOException("refusing non-TLS connection")
                }
                conn.instanceFollowRedirects = false // never follow redirects with our token
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Accept", "application/json")
                deviceToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { s ->
                    val bytes = s.readNBytesCompat(MAX_BODY)
                    String(bytes, Charsets.UTF_8)
                }.orEmpty()
                if (code !in 200..299) throw ApiException(code, "HTTP $code")
                text
            } finally {
                conn.disconnect()
            }
        }

    suspend fun pair(code: String): String =
        JSONObject(call("POST", "/v1/pair", JSONObject().put("code", code.trim()))).getString("device_token")

    data class Today(val targetMl: Int, val workoutMinutes: Int, val source: String)

    suspend fun today(tz: String, manualWeightKg: Float?): Today {
        var q = "/v1/today?tz=" + URLEncoder.encode(tz, "UTF-8")
        if (manualWeightKg != null && manualWeightKg > 0) q += "&manual_weight_kg=$manualWeightKg"
        val j = JSONObject(call("GET", q))
        return Today(j.getInt("target_ml"), j.optInt("workout_minutes"), j.optString("source", "fallback"))
    }

    suspend fun messagesJson(): String = call("GET", "/v1/messages")

    suspend fun whoopConnectUrl(): String = JSONObject(call("GET", "/v1/whoop/connect")).getString("authorize_url")

    suspend fun whoopConnected(): Boolean = JSONObject(call("GET", "/v1/whoop/status")).getBoolean("connected")

    suspend fun putFcmToken(token: String) { call("PUT", "/v1/fcm-token", JSONObject().put("token", token)) }

    suspend fun deleteEverything() { call("DELETE", "/v1/me") }

    companion object {
        private const val MAX_BODY = 256 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw IOException("response too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
