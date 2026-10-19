package com.rutg3r.installwatchdog

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** One-time setup screen. Built in code so the project needs no layout XML / AndroidX. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var name: EditText
    private lateinit var allow: EditText
    private lateinit var enabled: Switch
    private lateinit var relaunch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt())
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, (14 * dp).toInt(), 0, 0) }
        fun button(t: String, onClick: () -> Unit) =
            Button(this).apply { text = t; setOnClickListener { onClick() } }

        status = TextView(this).apply { textSize = 15f }
        col.addView(status)

        col.addView(button("Enable accessibility service") {
            if (prefs.disclosureAccepted) openAccessibilitySettings()
            else showDisclosure { openAccessibilitySettings() }
        })
        col.addView(button("App info (Android 13+: ⋮ → Allow restricted settings)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        col.addView(label("Device name"))
        name = EditText(this).apply { setText(prefs.deviceName) }
        col.addView(name)

        col.addView(label("Allowlist — app names exactly as shown in the installer, comma separated"))
        allow = EditText(this).apply { setText(prefs.allowlistRaw) }
        col.addView(allow)

        enabled = Switch(this).apply { text = "Tap Install/Update automatically"; isChecked = prefs.enabled }
        relaunch = Switch(this).apply { text = "Re-open the app after it has been updated"; isChecked = prefs.relaunch }
        col.addView(enabled); col.addView(relaunch)

        col.addView(button("Save") { save() })
        col.addView(button("What does this app do?") { showDisclosure(null) })

        setContentView(ScrollView(this).apply { addView(col, MATCH_PARENT, WRAP_CONTENT) })

        if (!prefs.disclosureAccepted) showDisclosure(null)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Prominent explanation of what the accessibility service does, with explicit consent. */
    private fun showDisclosure(onAccept: (() -> Unit)?) {
        AlertDialog.Builder(this)
            .setTitle("How Install Watchdog works")
            .setMessage(
                "Install Watchdog uses an Android accessibility service.\n\n" +
                "• It only looks at the Android package installer screen.\n" +
                "• When that screen shows an app from your allowlist, it taps Install/Update for you " +
                "and re-opens the app afterwards.\n" +
                "• Apps that are not on the allowlist are never touched.\n" +
                "• It does not read other apps or screens, does not collect any data, " +
                "and has no internet access.\n\n" +
                "Only use it on devices you manage yourself, and only allowlist apps you trust."
            )
            .setPositiveButton("I understand") { _, _ ->
                prefs.disclosureAccepted = true
                onAccept?.invoke()
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun save() {
        prefs.deviceName = name.text.toString()
        prefs.allowlistRaw = allow.text.toString()
        prefs.enabled = enabled.isChecked
        prefs.relaunch = relaunch.isChecked
        val ignored = prefs.ignoredEntries
        val msg = if (ignored.isEmpty()) "Saved"
                  else "Saved. Ignored: " + ignored.joinToString(", ") +
                       " (no wildcards; names need at least ${Prefs.MIN_NAME_LENGTH} characters)"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = WatchdogService.isEnabledInSettings(this) && WatchdogService.instance != null
        val apps = prefs.allowlist
        status.text = buildString {
            appendLine("Device: ${prefs.deviceName}")
            appendLine(if (running) "✅ Watchdog running" else "❌ Accessibility service NOT running")
            appendLine(if (apps.isEmpty()) "⚠️ Allowlist is empty: nothing will be installed"
                       else "Allowed apps: " + apps.joinToString(", "))
            appendLine("Version ${BuildConfig.VERSION_NAME}")
            appendLine("Last action: ${prefs.lastAction}")
        }
    }
}
