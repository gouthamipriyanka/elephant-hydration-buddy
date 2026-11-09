package app.ellie.hydration.data

import android.content.Context
import org.json.JSONObject

/**
 * Message pools. Source order: server copy (cached) -> bundled assets/messages.json.
 * Every message is re-validated on the phone (defence in depth): plain text,
 * <= 200 chars, no links, no markup. Anything suspicious is dropped.
 */
class MessageRepo(private val context: Context) {
    data class Fact(val text: String, val source: String, val sourceUrl: String)

    private val linkLike = Regex("""(https?://|www\.|\b[a-z0-9-]+\.(com|net|org|io|ly|me|app|link)\b)""", RegexOption.IGNORE_CASE)
    private val badChars = Regex("""[\u0000-\u0008\u000B-\u001F\u007F<>]""")

    private fun safe(s: String): Boolean =
        s.isNotBlank() && s.length <= 200 && !linkLike.containsMatchIn(s) && !badChars.containsMatchIn(s)

    private fun root(): JSONObject {
        Prefs(context).cachedMessagesJson?.let { cached ->
            runCatching { JSONObject(cached) }.getOrNull()?.let { if (it.has("reminder")) return it }
        }
        return JSONObject(context.assets.open("messages.json").bufferedReader().use { it.readText() })
    }

    fun pool(name: String): List<String> {
        val arr = root().optJSONArray(name) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(::safe) }
    }

    fun pick(name: String, nickname: String = ""): String {
        val fallback = "Time for some water, love 💧🐘"
        val text = pool(name).randomOrNull() ?: fallback
        val nick = nickname.takeIf(::safe).orEmpty()
        return text.replace("{name}", nick).trim()
    }

    fun facts(): List<Fact> {
        val arr = root().optJSONArray("facts") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val f = Fact(o.optString("text"), o.optString("source"), o.optString("source_url"))
            f.takeIf { safe(it.text) && safe(it.source) && it.sourceUrl.startsWith("https://") }
        }
    }

    /** Validates and caches a server-provided pool; rejects it wholesale if malformed. */
    fun cacheFromServer(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val pools = listOf("reminder", "behind", "kudos", "goal_met", "bottle")
        val valid = pools.all { p ->
            val a = o.optJSONArray(p)
            a != null && a.length() > 0 && (0 until a.length()).all { safe(a.optString(it)) }
        }
        if (valid) Prefs(context).cachedMessagesJson = json
    }
}
