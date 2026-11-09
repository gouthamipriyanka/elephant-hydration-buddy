package app.ellie.hydration.data

import android.content.Context
import app.ellie.hydration.logic.Hydration
import app.ellie.hydration.security.KeystoreCipher
import java.time.LocalTime

/**
 * Settings. Sensitive values (device token, home location, DB passphrase) are
 * stored encrypted with a Keystore key. Backups are disabled app-wide.
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("ellie_prefs", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(v) = sp.edit().putString("nickname", v.take(30)).apply()

    var wakeMinutes: Int
        get() = sp.getInt("wake_min", 7 * 60)
        set(v) = sp.edit().putInt("wake_min", v.coerceIn(0, 1439)).apply()

    var sleepMinutes: Int
        get() = sp.getInt("sleep_min", 22 * 60 + 30)
        set(v) = sp.edit().putInt("sleep_min", v.coerceIn(0, 1439)).apply()

    val wake: LocalTime get() = LocalTime.of(wakeMinutes / 60, wakeMinutes % 60)
    val sleep: LocalTime get() = LocalTime.of(sleepMinutes / 60, sleepMinutes % 60)

    var manualWeightKg: Float
        get() = sp.getFloat("manual_weight", 0f)
        set(v) = sp.edit().putFloat("manual_weight", v).apply()

    var targetMl: Int
        get() = sp.getInt("target_ml", Hydration.FALLBACK_TARGET_ML)
        set(v) = sp.edit().putInt("target_ml", v).apply()

    var targetDay: String
        get() = sp.getString("target_day", "") ?: ""
        set(v) = sp.edit().putString("target_day", v).apply()

    var targetSource: String
        get() = sp.getString("target_source", "fallback") ?: "fallback"
        set(v) = sp.edit().putString("target_source", v).apply()

    var targetFetchedAt: Long
        get() = sp.getLong("target_fetched_at", 0)
        set(v) = sp.edit().putLong("target_fetched_at", v).apply()

    var lastReminderAt: Long
        get() = sp.getLong("last_reminder_at", 0)
        set(v) = sp.edit().putLong("last_reminder_at", v).apply()

    /** Tracks rapid dismissals so re-posting never becomes an annoying loop. */
    var dismissCount: Int
        get() = sp.getInt("dismiss_count", 0)
        set(v) = sp.edit().putInt("dismiss_count", v).apply()

    var lastDismissAt: Long
        get() = sp.getLong("last_dismiss_at", 0)
        set(v) = sp.edit().putLong("last_dismiss_at", v).apply()

    var lastBottleAlertAt: Long
        get() = sp.getLong("last_bottle_at", 0)
        set(v) = sp.edit().putLong("last_bottle_at", v).apply()

    var bottleRemindersEnabled: Boolean
        get() = sp.getBoolean("bottle_enabled", false)
        set(v) = sp.edit().putBoolean("bottle_enabled", v).apply()

    var whoopConnected: Boolean
        get() = sp.getBoolean("whoop_connected", false)
        set(v) = sp.edit().putBoolean("whoop_connected", v).apply()

    var cachedMessagesJson: String?
        get() = sp.getString("messages_json", null)
        set(v) = sp.edit().putString("messages_json", v).apply()

    var pendingFcmToken: String?
        get() = sp.getString("fcm_pending", null)?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
        set(v) = sp.edit().putString("fcm_pending", v?.let { KeystoreCipher.encryptString(it) }).apply()

    var deviceToken: String?
        get() = sp.getString("device_token", null)?.let { runCatching { KeystoreCipher.decryptString(it) }.getOrNull() }
        set(v) = sp.edit().putString("device_token", v?.let { KeystoreCipher.encryptString(it) }).apply()

    /** Home location never leaves the device. Stored encrypted. */
    var home: Pair<Double, Double>?
        get() = sp.getString("home", null)?.let { enc ->
            runCatching {
                val (lat, lng) = KeystoreCipher.decryptString(enc).split(",").map { it.toDouble() }
                lat to lng
            }.getOrNull()
        }
        set(v) = sp.edit().putString("home", v?.let { KeystoreCipher.encryptString("${it.first},${it.second}") }).apply()

    /** 32 random bytes, generated once, encrypted with the Keystore key. */
    fun databasePassphrase(): ByteArray {
        sp.getString("db_pass", null)?.let { return KeystoreCipher.decrypt(it) }
        val fresh = KeystoreCipher.randomBytes(32)
        sp.edit().putString("db_pass", KeystoreCipher.encrypt(fresh)).commit()
        return fresh
    }

    fun wipe() {
        val pass = sp.getString("db_pass", null)
        // Editor applies clear() before puts in the same commit, so this keeps only the
        // DB passphrase (needed to open the now-empty encrypted DB).
        val e = sp.edit().clear()
        if (pass != null) e.putString("db_pass", pass)
        e.commit()
    }
}
