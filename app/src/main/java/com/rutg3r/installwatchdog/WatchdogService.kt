package com.rutg3r.installwatchdog

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The watchdog. Runs as an AccessibilityService, which means:
 *  - no ADB, no Shizuku, no root
 *  - Android starts it again by itself after every reboot (as long as it stays enabled)
 *  - it can read the installer dialog and press its Install/Update button
 */
class WatchdogService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var clickInProgress = false
    private var lastClickAt = 0L
    private var lastIgnoredSig = ""

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        registerPackageReceiver()
        Log.i(TAG, "service started")
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(packageReceiver) } catch (_: Exception) {}
    }

    // ------------------------------------------------------- installer watching

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // Never let an exception escape: a crashing accessibility service gets disabled by Android.
        try {
            val pkg = e?.packageName?.toString() ?: return
            if (!pkg.contains("packageinstaller")) return
            if (!prefs.enabled) return
            evaluateInstallerWindow()
        } catch (ex: Exception) {
            Log.e(TAG, "onAccessibilityEvent", ex)
        }
    }

    private fun evaluateInstallerWindow() {
        val root = installerRoot() ?: return
        val button = findInstallButton(root)

        if (button == null) {
            maybeClickDone(root)
            return
        }

        val texts = collectTexts(root)
        val matched = prefs.matchesAllowlist(texts)
        if (matched == null) {
            val sig = texts.joinToString("|").take(300)
            if (sig != lastIgnoredSig) {
                lastIgnoredSig = sig
                record("Ignored (not on allowlist): " + texts.take(3).joinToString(" / "))
            }
            return
        }
        scheduleClick(matched)
    }

    /**
     * The Install button is disabled for a short moment after the dialog appears
     * (tap-jacking protection), so retry a few times until it is enabled.
     */
    private fun scheduleClick(app: String) {
        if (clickInProgress) return
        clickInProgress = true
        wakeScreen()
        attemptClick(app, 0)
    }

    private fun attemptClick(app: String, attempt: Int) {
        val btn = installerRoot()?.let { findInstallButton(it) }
        if (btn == null) { clickInProgress = false; return }
        if (btn.isEnabled && clickNode(btn)) {
            clickInProgress = false
            lastClickAt = SystemClock.elapsedRealtime()
            record("Tapped \"${btn.text}\" for $app")
            return
        }
        if (attempt >= 20) {
            clickInProgress = false
            record("Could not tap Install for $app")
            return
        }
        main.postDelayed({ attemptClick(app, attempt + 1) }, 350)
    }

    /** After a successful install the old-style installer shows "Done / Open". Close it. */
    private fun maybeClickDone(root: AccessibilityNodeInfo) {
        if (SystemClock.elapsedRealtime() - lastClickAt > 120_000) return
        val done = findByIds(root, DONE_IDS) ?: findByTexts(root, DONE_WORDS) ?: return
        if (done.isEnabled && clickNode(done)) lastClickAt = 0
    }

    private fun installerRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.contains("packageinstaller") == true) return it }
        return try {
            windows.mapNotNull { it.root }.firstOrNull { it.packageName?.contains("packageinstaller") == true }
        } catch (_: Exception) { null }
    }

    private fun findInstallButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) Known ids, but only if the label really is Install/Update
        //    (android:id/button1 is also "Settings" on the unknown-sources dialog!)
        findByIds(root, INSTALL_IDS)?.let { if (isInstallText(it)) return it }
        // 2) Fallback: any node whose text is exactly one of the install words
        return findByTexts(root, INSTALL_WORDS)
    }

    private fun findByIds(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        return null
    }

    private fun findByTexts(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        for (w in words) {
            root.findAccessibilityNodeInfosByText(w)
                ?.firstOrNull { n -> n.isVisibleToUser && n.text?.toString()?.trim()?.equals(w, true) == true }
                ?.let { return it }
        }
        return null
    }

    private fun isInstallText(n: AccessibilityNodeInfo): Boolean {
        val t = n.text?.toString()?.trim() ?: return false
        return INSTALL_WORDS.any { it.equals(t, ignoreCase = true) }
    }

    private fun collectTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 25 || out.size > 80) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        if (n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        // Fallback: real tap on the centre of the button
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null
        )
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "InstallWatchdog:wake"
                ).acquire(10_000)
            }
        } catch (_: Exception) {}
    }

    private fun record(text: String) {
        Log.i(TAG, text)
        prefs.logAction(text)
    }

    // ----------------------------------------------- relaunch after an update

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg == packageName) return
                val pm = packageManager
                val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                val version = try { pm.getPackageInfo(pkg, 0).versionName } catch (_: Exception) { null }
                if (prefs.matchesAllowlist(listOf(label, pkg)) == null) return
                record("$label updated to ${version ?: "?"}")
                if (prefs.relaunch) {
                    main.postDelayed({
                        pm.getLaunchIntentForPackage(pkg)?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(it)
                        }
                    }, 2500)
                }
            } catch (e: Exception) {
                Log.w(TAG, "packageReceiver", e)
            }
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)
    }

    companion object {
        const val TAG = "InstallWatchdog"
        @Volatile var instance: WatchdogService? = null

        fun isEnabledInSettings(context: Context): Boolean {
            val me = ComponentName(context, WatchdogService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }

        private val INSTALL_IDS = listOf(
            "com.android.packageinstaller:id/ok_button",          // Android 7–11 / LineageOS
            "com.google.android.packageinstaller:id/ok_button",
            "android:id/button1"                                  // Android 12+ alert dialog
        )
        private val DONE_IDS = listOf(
            "com.android.packageinstaller:id/done_button",
            "com.google.android.packageinstaller:id/done_button"
        )
        // English + Dutch + German labels. Add your own if your tablets use another language.
        private val INSTALL_WORDS = listOf(
            "Install", "Update", "Installeren", "Bijwerken", "Updaten",
            "Installieren", "Aktualisieren"
        )
        private val DONE_WORDS = listOf("Done", "Gereed", "Klaar", "Fertig")
    }
}
