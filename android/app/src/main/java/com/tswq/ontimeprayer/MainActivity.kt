package com.tswq.ontimeprayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.app.NotificationManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Thin shell: shows the bundled page, which computes the times; the shell sets alarms and finds the location. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private val notifyPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { tellNotify() }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locateNow() else jsError("denied")
    }
    private var pendingVoice = ""
    // While a settings sheet is open, Back closes it instead of leaving the app.
    private val closeSheet = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { web.evaluateJavascript("window.__closeSheet&&__closeSheet()", null) }
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) keepSound(pendingVoice, uri) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // From target SDK 35 the app draws under the system bars, so pad the page by their size.
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        web = WebView(this)
        root.addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, closeSheet)

        web.setBackgroundColor(getColor(R.color.bg))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/www/index.html")
        else web.restoreState(savedInstanceState)

        takeWidget(intent)
        Alerts.channels(this)
        // Notification permission is asked from the page, after a card that explains it (first run, or when an alert is turned on).
        Scheduler.reschedule(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // The opening plays when the page loads, so only after the app was fully closed (not when it comes back from the background).
    private fun tellNotify() { web.evaluateJavascript("window.__onNotify&&__onNotify()", null) }

    private fun notifyOn(): Boolean {
        val perm = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return perm && getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false
    }

    // Back from the phone's settings: the page re-checks whether notifications are allowed (and which widgets were added).
    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) { tellNotify(); web.evaluateJavascript("window.__onWidgets&&__onWidgets()", null) }
    }

    // "Customize" on a home screen widget: the page opens that widget's look.
    private var pendingWidget = ""
    private fun takeWidget(i: Intent?): Boolean {
        if (i == null) return false
        val id = i.getIntExtra("widgetId", 0); val type = i.getStringExtra("widgetType")
        if (id == 0 || type == null || type !in WidgetUpdater.CLASSES) return false
        i.removeExtra("widgetId"); pendingWidget = "$id,$type"; return true
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (takeWidget(intent) && ::web.isInitialized)
            web.evaluateJavascript("if(window.__openWidget){var p=Android.pendingWidget().split(',');__openWidget(p[0],p[1])}", null)
    }

    override fun onStop() {
        super.onStop()
        Alerts.stopPreview()
    }

    private fun keepSound(voice: String, uri: Uri) {
        if (voice.isEmpty()) return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = uri.lastPathSegment ?: ""
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        }
        Scheduler.prefs(this).edit().putString("soundUri_$voice", uri.toString()).putString("soundName_$voice", name).apply()
        web.evaluateJavascript("window.__onSound&&__onSound()", null)
        Alerts.preview(this, voice)
    }

    inner class Bridge {
        @JavascriptInterface fun schedule(json: String) {
            runCatching { Scheduler.save(applicationContext, json) }
        }
        @JavascriptInterface fun notifyState(): String = if (notifyOn()) "on" else "off"
        @JavascriptInterface fun askNotify() {
            main.post {
                val p = Scheduler.prefs(this@MainActivity)
                val asked = p.getBoolean("askedNotify", false)
                val canAsk = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    (!asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                if (canAsk) {
                    p.edit().putBoolean("askedNotify", true).apply()
                    notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!notifyOn()) {
                    // refused before, or switched off in the phone's settings: open this app's notification settings
                    runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                } else tellNotify()
            }
        }
        @JavascriptInterface fun sheet(open: Boolean) { main.post { closeSheet.isEnabled = open } }
        // home screen widgets
        @JavascriptInterface fun widgetData(json: String) {
            runCatching { org.json.JSONObject(json); Scheduler.prefs(applicationContext).edit().putString("widgetData", json).apply(); WidgetUpdater.updateAll(applicationContext) }
        }
        @JavascriptInterface fun widgets(): String = runCatching { WidgetUpdater.info(applicationContext) }.getOrDefault("{\"placed\":[],\"styles\":{},\"pin\":false}")
        @JavascriptInterface fun widgetPreview(type: String, json: String): String = runCatching { WidgetUpdater.preview(applicationContext, type, json) }.getOrDefault("")
        @JavascriptInterface fun widgetStyle(type: String, id: Int, json: String) { runCatching { WidgetUpdater.setStyle(applicationContext, type, id, json) } }
        @JavascriptInterface fun pinWidget(type: String) { main.post { WidgetUpdater.pin(applicationContext, type) } }
        @JavascriptInterface fun pendingWidget(): String { val p = pendingWidget; pendingWidget = ""; return p }
        @JavascriptInterface fun reminder(pk: String): String = Reminders.find(applicationContext, pk)?.optLong("at")?.toString() ?: ""
        @JavascriptInterface fun cancelRemind(pk: String) { main.post { runCatching { Alerts.unremind(applicationContext, pk) } } }
        @JavascriptInterface fun remind(json: String) {
            main.post { runCatching { Alerts.channels(applicationContext); Alerts.remind(applicationContext, org.json.JSONObject(json)) } }
        }
        @JavascriptInterface fun preview(voice: String) {
            main.post { Alerts.preview(applicationContext, voice) }
        }
        @JavascriptInterface fun pickSound(voice: String) {
            main.post { pendingVoice = voice; runCatching { pickAudio.launch(arrayOf("audio/*")) } }
        }
        @JavascriptInterface fun soundName(voice: String): String =
            Scheduler.prefs(applicationContext).getString("soundName_$voice", "") ?: ""
        @JavascriptInterface fun clearSound(voice: String) {
            Scheduler.prefs(applicationContext).edit().remove("soundUri_$voice").remove("soundName_$voice").apply()
        }
        @JavascriptInterface fun locate() {
            main.post {
                val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) locateNow()
                else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }

    private var locating = false

    /** Like the website: fresh fixes only, listen up to 8 s, keep the most accurate one, stop early at 100 m. */
    @SuppressLint("MissingPermission")
    private fun locateNow() {
        if (locating) return
        val lm = getSystemService(LocationManager::class.java) ?: return jsError("none")
        val fineOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { (it != LocationManager.GPS_PROVIDER || fineOk) && runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return jsError("off")
        locating = true
        val started = System.currentTimeMillis()
        var best: Location? = null
        lateinit var listener: LocationListener
        val finish = Runnable {
            if (!locating) return@Runnable
            locating = false
            runCatching { lm.removeUpdates(listener) }
            val b = best
            if (b != null) send(b) else jsError("none")
        }
        listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (l.time < started - 5_000) return
                if (best == null || l.accuracy < best!!.accuracy) best = l
                if (l.accuracy <= 100f) { main.removeCallbacks(finish); finish.run() }
            }
            @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        for (p in providers) runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
        // Some phones are slow to get a first fix indoors: wait up to 15 s if nothing came within 8 s.
        main.postDelayed({ if (best != null) { main.removeCallbacks(finish); finish.run() } }, 8_000)
        main.postDelayed(finish, 15_000)
    }

    private fun send(l: Location) {
        web.evaluateJavascript("window.__onLocation&&__onLocation(${l.latitude},${l.longitude},${l.accuracy})", null)
    }

    private fun jsError(code: String) {
        web.evaluateJavascript("window.__onLocationError&&__onLocationError('$code')", null)
    }
}
