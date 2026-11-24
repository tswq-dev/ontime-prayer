package com.tswq.ontimeprayer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.widget.RemoteViews
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.min

/** «المنبّه القادم» on the home screen (0.27). */
class WAlarm : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = AlarmWidget.update(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle?) = AlarmWidget.update(ctx)
}

/**
 * The next alarm, in the alarm screen's night navy: its time large in Kufi and gold, what it is for, and the day; a snooze says so.
 * Redrawn whenever the alarms are set again (`PrayerAlarms.schedule`: after each one goes off, an edit, a skip, the app opening),
 * and every half hour by the phone. A tap opens the alarms page.
 */
object AlarmWidget {
    fun update(ctx: Context) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, WAlarm::class.java)) }.getOrNull() ?: return
        ids.forEach { id -> runCatching { draw(ctx, mgr, id) } }
    }

    private fun draw(ctx: Context, mgr: AppWidgetManager, id: Int) {
        val o = mgr.getAppWidgetOptions(id)
        var w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); var h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (w < 40 || h < 40) { w = 180; h = 90 }
        val s = min(ctx.resources.displayMetrics.density, 2.5f)
        val bmp = render(ctx, (w * s).toInt(), (h * s).toInt(), s)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_image)
        rv.setImageViewBitmap(R.id.img, bmp)
        rv.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(ctx, 3900, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        mgr.updateAppWidget(id, rv)
    }

    private val DAYS_AR = arrayOf("الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val DAYS_EN = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

    /** «اليوم» / «غداً» / the weekday, in Saudi time like the rest of the app. */
    private fun dayWord(at: Long, ar: Boolean): String {
        val tz = TimeZone.getTimeZone("Asia/Riyadh")
        val a = Calendar.getInstance(tz).apply { timeInMillis = at }; val n = Calendar.getInstance(tz)
        val d = ((a.get(Calendar.YEAR) - n.get(Calendar.YEAR)) * 400 + a.get(Calendar.DAY_OF_YEAR) - n.get(Calendar.DAY_OF_YEAR))
        return when (d) { 0 -> if (ar) "اليوم" else "Today"; 1 -> if (ar) "غداً" else "Tomorrow"; else -> (if (ar) DAYS_AR else DAYS_EN)[a.get(Calendar.DAY_OF_WEEK) - 1] }
    }

    private fun num(n: Int, fm: org.json.JSONObject): String = if (fm.optInt("dg") == 1) n.toString().map { if (it.isDigit()) "٠١٢٣٤٥٦٧٨٩"[it - '0'] else it }.joinToString("") else n.toString()

    private fun fit(p: Paint, t: String, max: Float): String {
        if (p.measureText(t) <= max) return t
        var e = t; while (e.length > 1 && p.measureText("$e…") > max) e = e.dropLast(1)
        return "$e…"
    }

    fun render(ctx: Context, W: Int, H: Int, s: Float): Bitmap {
        val bmp = Bitmap.createBitmap(W.coerceAtLeast(1), H.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val gold = Color.parseColor("#E9CD8A"); val cream = Color.parseColor("#F3E6C4")
        val w = W.toFloat(); val h = H.toFloat(); val r = 22f * s
        p.shader = LinearGradient(0f, 0f, w * .4f, h, Color.parseColor("#1B2C52"), Color.parseColor("#0B1630"), Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        p.shader = RadialGradient(w * .5f, h * .2f, w * .7f, intArrayOf(Color.argb(36, 233, 205, 138), 0), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, w, h), r, r, p); p.shader = null
        // a few stars
        val st = floatArrayOf(.12f, .2f, .3f, .1f, .52f, .16f, .7f, .28f, .86f, .14f, .62f, .08f)
        for (i in st.indices step 2) { p.color = Color.argb(if (i % 4 == 0) 120 else 70, 243, 230, 196); c.drawCircle(w * st[i], h * st[i + 1], 1.1f * s, p) }

        val f = WFonts.get(ctx.assets); val fm = PrayerAlarms.fmt(ctx); val ar = fm.optString("lang") != "en"
        val up = PrayerAlarms.upcoming(ctx)
        val n = PrayerAlarms.list(ctx).count { it.optBoolean("on", true) }
        val pad = 14f * s
        // the alarm mark: a gold ring with a bell-clock, on the side away from the text
        val ic = min(h * .34f, 30f * s); val icx = if (ar) pad + ic / 2 else w - pad - ic / 2; val icy = if (h / s < 70) h / 2 else pad + ic / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * s; p.color = gold
        c.drawCircle(icx, icy + ic * .06f, ic * .36f, p)
        c.drawLine(icx, icy + ic * .06f, icx, icy - ic * .16f, p); c.drawLine(icx, icy + ic * .06f, icx + ic * .14f, icy + ic * .14f, p)
        c.drawLine(icx - ic * .42f, icy - ic * .3f, icx - ic * .24f, icy - ic * .44f, p); c.drawLine(icx + ic * .42f, icy - ic * .3f, icx + ic * .24f, icy - ic * .44f, p)
        p.style = Paint.Style.FILL

        val textW = w - pad * 3 - ic
        val x = if (ar) w - pad else pad
        p.textAlign = if (ar) Paint.Align.RIGHT else Paint.Align.LEFT
        if (up == null) {
            p.typeface = f.plexB; p.color = cream; p.textSize = min(15f * s, h * .2f)
            c.drawText(fit(p, if (n == 0 && PrayerAlarms.list(ctx).isEmpty()) (if (ar) "لا منبّهات بعد" else "No alarms yet") else (if (ar) "لا منبّه مفعّل" else "No alarm on"), textW), x, h / 2 - 2f * s, p)
            p.typeface = f.plex; p.color = Color.argb(170, 243, 230, 196); p.textSize = min(11.5f * s, h * .15f)
            c.drawText(fit(p, if (ar) "اضغط لإضافة منبّه" else "Tap to add one", textW), x, h / 2 + 16f * s, p)
            return bmp
        }
        val (at, a, snoozed) = up
        val clock = Exits.clock(at, fm); val mer = listOf(" ص", " م", " AM", " PM").firstOrNull { clock.endsWith(it) } ?: ""
        val time = clock.removeSuffix(mer); val title = PrayerAlarms.name(ctx, a)
        val sub = (if (snoozed) (if (ar) "غفوة · " else "Snoozed · ") else "") + dayWord(at, ar) +
            when { n < 2 -> ""; !ar -> " · $n alarms on"; n == 2 -> " · منبّهان مفعّلان"; else -> " · " + num(n, fm) + (if (n <= 10) " منبّهات مفعّلة" else " منبّهاً مفعّلاً") }
        val tf: Typeface = f.name("kufi")
        if (h / s < 70) {
            // one line: the time, then the name
            p.typeface = tf; p.color = gold; p.textSize = min(h * .5f, 30f * s)
            val tw = p.measureText(time); c.drawText(time, x, h / 2 + p.textSize * .34f, p)
            p.typeface = f.plexB; p.color = cream; p.textSize = min(12.5f * s, h * .26f)
            val x2 = if (ar) x - tw - 10f * s else x + tw + 10f * s
            c.drawText(fit(p, title, textW - tw - 10f * s), x2, h / 2 + p.textSize * .36f, p)
        } else {
            p.typeface = tf; p.color = gold; p.textSize = minOf(h * .36f, w * .22f, 46f * s)
            val ty = pad + p.textSize * .86f
            val tw = p.measureText(time); c.drawText(time, x, ty, p)
            if (mer.isNotEmpty()) { p.typeface = f.plexB; p.textSize = 12f * s; c.drawText(mer.trim(), if (ar) x - tw - 5f * s else x + tw + 5f * s, ty, p) }
            p.typeface = f.plexB; p.color = cream; p.textSize = min(13f * s, h * .14f)
            val y2 = ty + p.textSize * 1.55f
            c.drawText(fit(p, title, w - pad * 2), x, y2, p)
            p.typeface = f.plex; p.color = Color.argb(175, 243, 230, 196); p.textSize = min(11.5f * s, h * .12f)
            c.drawText(fit(p, sub, w - pad * 2), x, y2 + p.textSize * 1.55f, p)
        }
        return bmp
    }
}
