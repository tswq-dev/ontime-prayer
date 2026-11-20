package com.tswq.ontimeprayer

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

/** One provider per widget; they all draw through [WidgetUpdater]. */
abstract class BaseWidget(private val type: String) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUpdater.update(ctx, mgr, it, type) }
        WidgetUpdater.tick(ctx)
    }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle?) = WidgetUpdater.update(ctx, mgr, id, type)
    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = Scheduler.prefs(ctx).edit(); ids.forEach { e.remove("w_$it") }; e.apply()
    }
    override fun onDisabled(ctx: Context) = WidgetUpdater.tick(ctx)
}
class WPano : BaseWidget("pano")
class WWindow : BaseWidget("window")
class WRing : BaseWidget("ring")
class WOrbit : BaseWidget("orbit")
class WScript : BaseWidget("script")
class WTable : BaseWidget("table")
class WClock : BaseWidget("clock")
class WMoon : BaseWidget("moon")
class WRibbon : BaseWidget("ribbon")
class WExit : BaseWidget("exit")
class WActions : BaseWidget("actions")
class WLine : BaseWidget("line")
class WPrayed : BaseWidget("prayed")
class WNight : BaseWidget("night")
class WRamadan : BaseWidget("ramadan")
class WWhite : BaseWidget("white")

object WidgetUpdater {
    const val ACTION_TICK = "com.tswq.ontimeprayer.WTICK"
    const val ACTION_BTN = "com.tswq.ontimeprayer.WBTN"

    val CLASSES: Map<String, Class<out BaseWidget>> = linkedMapOf(
        "pano" to WPano::class.java, "window" to WWindow::class.java, "ring" to WRing::class.java, "orbit" to WOrbit::class.java,
        "script" to WScript::class.java, "table" to WTable::class.java, "clock" to WClock::class.java, "moon" to WMoon::class.java,
        "ribbon" to WRibbon::class.java, "exit" to WExit::class.java, "actions" to WActions::class.java, "line" to WLine::class.java,
        "prayed" to WPrayed::class.java, "night" to WNight::class.java, "ramadan" to WRamadan::class.java, "white" to WWhite::class.java)

    /** The size each widget starts at (dp): 4×2, 2×2 or 4×1 cells. */
    fun defaultDp(type: String): Pair<Int, Int> = when (type) {
        "window", "ring", "clock", "moon", "night" -> 170 to 170
        "script", "ribbon", "exit", "line", "prayed" -> 340 to 90
        else -> 340 to 170
    }

    fun typeOf(ctx: Context, id: Int): String? {
        val cls = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id)?.provider?.className ?: return null
        return CLASSES.entries.firstOrNull { it.value.name == cls }?.key
    }

    fun placed(ctx: Context): List<Pair<Int, String>> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return CLASSES.flatMap { (t, c) -> runCatching { mgr.getAppWidgetIds(ComponentName(ctx, c)).map { it to t } }.getOrDefault(emptyList()) }
    }

    /** The widget's own look if it was set from the home screen, else the look chosen for its kind in the app. */
    fun style(ctx: Context, id: Int, type: String, d: WData? = WData.load(Scheduler.prefs(ctx))): WStyle {
        val p = Scheduler.prefs(ctx)
        val j = (if (id > 0) p.getString("w_$id", null) else null) ?: p.getString("wt_$type", null)
        return WStyle.from(type, j?.let { runCatching { JSONObject(it) }.getOrNull() }, d?.o?.optString("font"))
    }

    fun setStyle(ctx: Context, type: String, id: Int, json: String) {
        val e = Scheduler.prefs(ctx).edit()
        if (id > 0) e.putString("w_$id", json)
        else { e.putString("wt_$type", json); placed(ctx).filter { it.second == type }.forEach { e.remove("w_${it.first}") } }
        e.apply()
        refresh(ctx, type)
    }

    fun updateAll(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        placed(ctx).forEach { (id, t) -> update(ctx, mgr, id, t) }
        tick(ctx)
    }

    fun refresh(ctx: Context, type: String) {
        val mgr = AppWidgetManager.getInstance(ctx)
        placed(ctx).filter { it.second == type }.forEach { (id, t) -> update(ctx, mgr, id, t) }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int, type: String) {
        runCatching {
            val o = mgr.getAppWidgetOptions(id)
            var w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); var h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (w < 40 || h < 40) defaultDp(type).let { w = it.first; h = it.second }
            val now = System.currentTimeMillis()
            val d = WData.load(Scheduler.prefs(ctx))
            val bmp = draw(ctx, type, style(ctx, id, type, d), d, now, w, h, ctx.resources.displayMetrics.density)
            val rv: RemoteViews; val st = style(ctx, id, type, d)
            if (type == "prayed" && st.tap == "pray" && w.toFloat() / h > 1.8f) {
                // «صلّيت» by tapping a circle: five areas over the five circles, the title side opens the app
                rv = RemoteViews(ctx.packageName, R.layout.widget_prayed)
                val ar = d?.ar != false
                rv.setViewVisibility(R.id.sL1, if (ar) android.view.View.GONE else android.view.View.VISIBLE)
                rv.setViewVisibility(R.id.sR2, if (ar) android.view.View.GONE else android.view.View.VISIBLE)
                rv.setViewVisibility(R.id.sL2, if (ar) android.view.View.VISIBLE else android.view.View.GONE)
                rv.setViewVisibility(R.id.sR1, if (ar) android.view.View.VISIBLE else android.view.View.GONE)
                listOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4).forEachIndexed { i, v -> rv.setOnClickPendingIntent(v, button(ctx, "p" + (if (ar) 4 - i else i))) }
            } else if (type == "actions") {
                rv = RemoteViews(ctx.packageName, R.layout.widget_actions)
                // the buttons are drawn right to left in Arabic: b0 is the left third
                val order = if (d?.ar != false) listOf("remind", "mute", "pray") else listOf("pray", "mute", "remind")
                listOf(R.id.b0, R.id.b1, R.id.b2).forEachIndexed { i, v -> rv.setOnClickPendingIntent(v, button(ctx, order[i])) }
            } else rv = RemoteViews(ctx.packageName, R.layout.widget_image)
            rv.setImageViewBitmap(R.id.img, bmp)
            rv.setOnClickPendingIntent(R.id.root, if (type == "prayed" && st.tap == "pray" && w.toFloat() / h <= 1.8f) button(ctx, "pray") else tapIntent(ctx, st.tap))
            mgr.updateAppWidget(id, rv)
        }
    }

    /** What a tap opens: the app, the month table, or the next prayer's settings. */
    private fun tapIntent(ctx: Context, tap: String): PendingIntent =
        if (tap != "month" && tap != "prayer") Alerts.openApp(ctx)
        else PendingIntent.getActivity(ctx, if (tap == "month") 11 else 12,
            Intent(ctx, MainActivity::class.java).putExtra("open", tap).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The phone's own colours (Android 12 and later), for the "phone's colours" background. */
    private fun palette(ctx: Context): IntArray? = if (Build.VERSION.SDK_INT < 31) null else runCatching {
        intArrayOf(ctx.getColor(android.R.color.system_accent1_100), ctx.getColor(android.R.color.system_accent2_200),
            ctx.getColor(android.R.color.system_accent1_900), ctx.getColor(android.R.color.system_accent2_700), ctx.getColor(android.R.color.system_accent1_600))
    }.getOrNull()

    /** Draws at most ~0.9 MP so the picture stays light to send to the home screen. */
    fun draw(ctx: Context, type: String, st: WStyle, d: WData?, now: Long, wDp: Int, hDp: Int, density: Float): Bitmap {
        if (st.bg == "dyn") WidgetArt.dyn = palette(ctx)
        if (type == "prayed") WidgetArt.marks = marks(ctx, d, now)
        var s = min(density, 2.5f); val px = wDp * hDp * s * s
        if (px > 900_000f) s *= sqrt(900_000f / px)
        val fonts = WFonts.get(ctx.assets)
        val wp = (wDp * s).toInt(); val hp = (hDp * s).toInt()
        return if (type == "actions") WidgetArt.renderActions(st, d, now, wp, hp, s, fonts, btns(ctx, d, now))
        else WidgetArt.render(type, st, d, now, wp, hp, s, fonts)
    }

    /** A picture of the widget for the app's widget page. */
    fun preview(ctx: Context, type: String, json: String): String {
        val d = WData.load(Scheduler.prefs(ctx))
        val st = WStyle.from(type, runCatching { JSONObject(json) }.getOrNull(), d?.o?.optString("font"))
        val (w, h) = defaultDp(type)
        val bmp = draw(ctx, type, st, d, System.currentTimeMillis(), w, h, 2f)
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** «صلواتي اليوم»: what is marked for the day whose prayers count now, and the days in a row. */
    fun marks(ctx: Context, d: WData?, now: Long): WidgetArt.Marks {
        val pd = d?.prayerDay(now) ?: return WidgetArt.Marks()
        return WidgetArt.Marks(Prayed.mask(ctx, pd.optString("d")), Prayed.streak(ctx, pd.optLong("fajr")))
    }

    // ---------- the buttons widget ----------
    private fun pkOf(k: String, start: Long) = k + ":" + WData.dateKey(start)

    /** "Remind me before it ends": the time we are in, else the next prayer. */
    private fun remindTarget(d: WData, now: Long): JSONObject? {
        val cur = d.current(now)
        val (k, start) = if (cur != null) cur.k to cur.start else (d.next(now) ?: return null).let { it.k to it.at }
        val ex = d.day(start)?.optJSONObject("ex")?.optJSONArray(k) ?: return null
        return JSONObject().put("pk", pkOf(k, start)).put("key", k).put("title", d.name(k)).put("ex", ex)
            .put("lang", if (d.ar) "ar" else "en").put("dg", if (d.arabDigits) 1 else 0).put("f12", if (d.h12) 1 else 0)
            .put("mins", d.o.optInt("mins", 20)).put("rs", d.o.optString("rs", "speak")).put("rring", d.o.optInt("rring", 0)).put("xspk", "spk_exit_$k")
            .takeIf { Exits.next(it, now) != null }
    }

    private fun btns(ctx: Context, d: WData?, now: Long): WidgetArt.Btns {
        if (d == null) return WidgetArt.Btns()
        val cur = d.current(now); val pk = cur?.let { pkOf(it.k, it.start) }
        val nx = d.next(now); val rt = remindTarget(d, now)
        return WidgetArt.Btns(
            prayed = pk != null && Prayed.has(ctx, pk),
            muted = nx != null && Scheduler.prefs(ctx).getLong("muteNext", 0) == nx.at,
            reminded = rt != null && Reminders.find(ctx, rt.optString("pk")) != null,
            canPray = pk != null, canRemind = rt != null)
    }

    private fun button(ctx: Context, which: String): PendingIntent = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, WidgetReceiver::class.java).setAction(ACTION_BTN).setData(Uri.parse("ontime://wbtn/$which")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun press(ctx: Context, which: String) {
        val d = WData.load(Scheduler.prefs(ctx)) ?: return
        val now = System.currentTimeMillis()
        when (which) {
            "pray" -> d.current(now)?.let { if (it.k in WData.PRAY) Alerts.prayed(ctx, pkOf(it.k, it.start)) }
            // a circle of «صلواتي اليوم»: mark that prayer (once its time has come), or undo the mark
            "p0", "p1", "p2", "p3", "p4" -> d.prayerDay(now)?.let { pd -> val k = WData.PRAY[which[1] - '0']; val t = pd.optLong(k)
                if (t in 1..now) { val pk = pkOf(k, t); if (Prayed.has(ctx, pk) || Prayed.mask(ctx, pd.optString("d")) and (1 shl (which[1] - '0')) != 0) Prayed.remove(ctx, pk) else Alerts.prayed(ctx, pk) } }
            "mute" -> d.next(now)?.let { nx ->
                val p = Scheduler.prefs(ctx)
                if (p.getLong("muteNext", 0) == nx.at) p.edit().remove("muteNext").apply() else p.edit().putLong("muteNext", nx.at).apply()
            }
            "remind" -> remindTarget(d, now)?.let { ev ->
                Alerts.channels(ctx)
                if (Reminders.find(ctx, ev.optString("pk")) != null) Alerts.unremind(ctx, ev.optString("pk")) else Alerts.remind(ctx, ev)
            }
        }
        refresh(ctx, "actions"); refresh(ctx, "prayed")
    }

    /** "Mute the next adhan" from the widget: this adhan comes as a silent notification. */
    fun takeMute(ctx: Context, ev: JSONObject): Boolean {
        val p = Scheduler.prefs(ctx); val m = p.getLong("muteNext", 0)
        if (m == 0L || ev.optString("kind") != "adhan" || ev.optString("key") !in WData.PRAY) return false
        if (kotlin.math.abs(m - ev.optLong("at")) > 60_000) { if (m < System.currentTimeMillis() - 120_000) p.edit().remove("muteNext").apply(); return false }
        p.edit().remove("muteNext").apply()
        return true
    }

    // ---------- the minute tick (only while widgets are placed; it does not wake the phone) ----------
    fun tick(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, WidgetReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (placed(ctx).isEmpty()) { am.cancel(pi); return }
        val at = (System.currentTimeMillis() / 60_000 + 1) * 60_000 + 300
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.set(AlarmManager.RTC, at, pi)
        else am.setExact(AlarmManager.RTC, at, pi)
    }

    /** What the app's widget page needs: the widgets on the home screen, each kind's look, and whether "Add" works. */
    fun info(ctx: Context): String {
        val d = WData.load(Scheduler.prefs(ctx))
        val pl = JSONArray(); placed(ctx).forEach { (id, t) -> pl.put(JSONObject().put("id", id).put("type", t).put("style", style(ctx, id, t, d).toJson())) }
        val st = JSONObject(); CLASSES.keys.forEach { st.put(it, style(ctx, 0, it, d).toJson()) }
        val pin = Build.VERSION.SDK_INT >= 26 && AppWidgetManager.getInstance(ctx).isRequestPinAppWidgetSupported
        val show = JSONObject(); WStyle.SHOW.forEach { (t, v) -> show.put(t, JSONArray(v.first)) }
        return JSONObject().put("placed", pl).put("styles", st).put("pin", pin).put("show", show).put("dyn", Build.VERSION.SDK_INT >= 31).toString()
    }

    fun pin(ctx: Context, type: String): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx); val cls = CLASSES[type] ?: return false
        if (Build.VERSION.SDK_INT < 26 || !mgr.isRequestPinAppWidgetSupported) return false
        // its look was just chosen in the app: the launcher's customise step need not open the app again
        Scheduler.prefs(ctx).edit().putLong("pinAt", System.currentTimeMillis()).apply()
        return runCatching { mgr.requestPinAppWidget(ComponentName(ctx, cls), null, null) }.getOrDefault(false)
    }
}

class WidgetReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pr = goAsync()
        Thread {
            try {
                when (intent.action) {
                    WidgetUpdater.ACTION_BTN -> intent.data?.lastPathSegment?.let { WidgetUpdater.press(ctx, it) }
                    else -> WidgetUpdater.updateAll(ctx)
                }
            } finally { pr.finish() }
        }.start()
    }
}

/** "Customize" from the home screen (and, before Android 12, right after adding): opens this widget's look in the app. */
class WidgetConfigActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val type = if (id != AppWidgetManager.INVALID_APPWIDGET_ID) WidgetUpdater.typeOf(this, id) else null
        if (type != null) {
            WidgetUpdater.update(this, AppWidgetManager.getInstance(this), id, type)
            WidgetUpdater.tick(this)
            val p = Scheduler.prefs(this); val justPinned = System.currentTimeMillis() - p.getLong("pinAt", 0) < 120_000
            if (justPinned) { p.edit().remove("pinAt").apply(); finish(); return }
            startActivity(Intent(this, MainActivity::class.java).putExtra("widgetId", id).putExtra("widgetType", type)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        finish()
    }
}
