package com.tswq.ontimeprayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Thin shell: shows the bundled page, which computes the times; the shell sets alarms and finds the location. */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) locateNow() else jsError("denied")
    }
    private var pendingVoice = ""
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) keepSound(pendingVoice, uri) }
    private var stoppedAt = 0L

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

        web.setBackgroundColor(getColor(R.color.bg))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(), "Android")
        if (savedInstanceState == null) web.loadUrl("file:///android_asset/www/index.html")
        else web.restoreState(savedInstanceState)

        Alerts.channels(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Scheduler.reschedule(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onStop() {
        super.onStop()
        Alerts.stopPreview()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    // Since Android 12, Back only sends the app to the background, so "opening" it again resumes this page.
    // Replay the opening when the app comes back after a few seconds away.
    override fun onRestart() {
        super.onRestart()
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > 5_000) {
            web.evaluateJavascript("window.__replayIntro&&__replayIntro()", null)
        }
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
