package ma.goldmaroc18k.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPrefs(context: Context) : SyncState {
    private val sp: SharedPreferences = context.getSharedPreferences("gold_prefs", Context.MODE_PRIVATE)

    private fun SharedPreferences.optDouble(key: String): Double? =
        if (contains(key)) Double.fromBits(getLong(key, 0)) else null

    private fun SharedPreferences.optBool(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null

    var notificationsEnabled: Boolean
        get() = sp.getBoolean("notif", true)
        set(v) = sp.edit { putBoolean("notif", v) }

    /** "en" (défaut), "fr" ou "ar" */
    var language: String
        get() = sp.getString("lang", "en") ?: "en"
        set(v) = sp.edit { putString("lang", v) }

    var alertAbove: Double?
        get() = sp.optDouble("above")
        set(v) = sp.edit { if (v == null) remove("above") else putLong("above", v.toBits()) }

    var alertBelow: Double?
        get() = sp.optDouble("below")
        set(v) = sp.edit { if (v == null) remove("below") else putLong("below", v.toBits()) }

    var aboveFired: Boolean
        get() = sp.getBoolean("aboveFired", false)
        set(v) = sp.edit { putBoolean("aboveFired", v) }

    var belowFired: Boolean
        get() = sp.getBoolean("belowFired", false)
        set(v) = sp.edit { putBoolean("belowFired", v) }

    override var lastGoldAttemptMs: Long
        get() = sp.getLong("gAttempt", 0)
        set(v) = sp.edit { putLong("gAttempt", v) }

    override var lastFxAttemptMs: Long
        get() = sp.getLong("fAttempt", 0)
        set(v) = sp.edit { putLong("fAttempt", v) }

    /** Résultat de la dernière tentative par source (null = jamais tentée). */
    override var lastGoldOk: Boolean?
        get() = sp.optBool("gOk")
        set(v) = sp.edit { if (v == null) remove("gOk") else putBoolean("gOk", v) }

    override var lastFxOk: Boolean?
        get() = sp.optBool("fOk")
        set(v) = sp.edit { if (v == null) remove("fOk") else putBoolean("fOk", v) }

    override var goldBlockedUntilMs: Long
        get() = sp.getLong("gBlock", 0)
        set(v) = sp.edit { putLong("gBlock", v) }

    override var fxBlockedUntilMs: Long
        get() = sp.getLong("fBlock", 0)
        set(v) = sp.edit { putLong("fBlock", v) }

    override var lastError: String?
        get() = sp.getString("err", null)
        set(v) = sp.edit { putString("err", v) }
}
