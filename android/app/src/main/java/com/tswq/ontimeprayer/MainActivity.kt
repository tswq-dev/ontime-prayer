package com.tswq.ontimeprayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
    }

    inner class Bridge {
        @JavascriptInterface fun schedule(json: String) {
            runCatching { Scheduler.save(applicationContext, json) }
        }
        @JavascriptInterface fun preview(voice: String) {
            main.post { Alerts.preview(applicationContext, voice) }
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

    private var located = false

    @SuppressLint("MissingPermission")
    private fun locateNow() {
        val lm = getSystemService(LocationManager::class.java) ?: return jsError("none")
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return jsError("off")
        var last: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            if (l != null && (last == null || l.time > last.time)) last = l
        }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) return send(last)
        located = false
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { send(l) }
            @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        for (p in providers) runCatching {
            @Suppress("DEPRECATION") lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
        }
        val fallback = last
        main.postDelayed({
            runCatching { lm.removeUpdates(listener) }
            if (!located) { if (fallback != null) send(fallback) else jsError("none") }
        }, 15_000)
    }

    private fun send(l: Location) {
        if (located) return
        located = true
        web.evaluateJavascript("window.__onLocation&&__onLocation(${l.latitude},${l.longitude})", null)
    }

    private fun jsError(code: String) {
        web.evaluateJavascript("window.__onLocationError&&__onLocationError('$code')", null)
    }
}
