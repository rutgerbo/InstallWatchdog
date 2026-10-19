package com.rutg3r.installwatchdog

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The few settings InstallWatchdog has. */
class Prefs(context: Context) {
    private val app = context.applicationContext
    private val sp: SharedPreferences = app.getSharedPreferences("watchdog", Context.MODE_PRIVATE)

    /** Name of this tablet. Falls back to the Android device name, then the model. */
    var deviceName: String
        get() = sp.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: systemDeviceName()
        set(v) = sp.edit().putString(KEY_NAME, v.trim()).apply()

    private fun systemDeviceName(): String = try {
        android.provider.Settings.Global.getString(app.contentResolver, "device_name")
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    /**
     * Comma/newline separated app names, exactly as shown in the installer dialog.
     * There is deliberately no wildcard: every app must be named explicitly.
     */
    var allowlistRaw: String
        get() = sp.getString(KEY_ALLOW, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_ALLOWLIST
        set(v) = sp.edit().putString(KEY_ALLOW, v).apply()

    /** Valid entries only: at least [MIN_NAME_LENGTH] characters, no wildcard. */
    val allowlist: List<String>
        get() = allowlistRaw.split(',', '\n')
            .map { it.trim() }
            .filter { it.length >= MIN_NAME_LENGTH && it != "*" }

    /** Entries that were typed but are ignored (too short or "*"), to warn about on screen. */
    val ignoredEntries: List<String>
        get() = allowlistRaw.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && (it.length < MIN_NAME_LENGTH || it == "*") }

    /** Has the user seen and accepted the first-run explanation? */
    var disclosureAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLOSURE, false)
        set(v) = sp.edit().putBoolean(KEY_DISCLOSURE, v).apply()

    /** Master switch: tap Install/Update automatically. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Re-open an allowlisted app after it has been updated (Kiosk Satellite is closed by its own update). */
    var relaunch: Boolean
        get() = sp.getBoolean(KEY_RELAUNCH, true)
        set(v) = sp.edit().putBoolean(KEY_RELAUNCH, v).apply()

    /** Last thing the watchdog did, shown on the setup screen. */
    val lastAction: String
        get() = sp.getString(KEY_LAST, null) ?: "nothing yet"

    fun logAction(text: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        sp.edit().putString(KEY_LAST, "$time  $text").apply()
    }

    /**
     * Returns the allowlist entry if one of the texts IS that app name (whole text,
     * case-insensitive). "Kiosk Satellite Pro" does not match "Kiosk Satellite".
     */
    fun matchesAllowlist(texts: Collection<String>): String? {
        val list = allowlist
        for (entry in list) {
            if (texts.any { it.trim().equals(entry, ignoreCase = true) }) return entry
        }
        return null
    }

    companion object {
        private const val KEY_NAME = "device_name"
        private const val KEY_ALLOW = "allowlist"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RELAUNCH = "relaunch"
        private const val KEY_LAST = "last_action"
        private const val KEY_DISCLOSURE = "disclosure_accepted"
        const val MIN_NAME_LENGTH = 3
    }
}
