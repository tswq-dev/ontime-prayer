package com.tswq.ontimeprayer

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Calendar

/**
 * Alarms tied to a prayer (0.24): "5 minutes after Fajr", "20 minutes before", "the last third of the night". Each day's time follows
 * the prayer, on the days picked; it rings like the clock app's alarm (the phone's alarm sounds, through silent mode, until stopped
 * or for the minutes picked), with snooze. Half an hour before, a quiet notice offers to skip this one time.
 *
 * The page keeps the list (`S.alarms`) and sends it whole; times come from the days the page sends for the widgets (35 ahead),
 * so alarms keep working for weeks without the app being opened.
 */
object PrayerAlarms {
    const val ACTION_RING = "com.tswq.ontimeprayer.ALARM_RING"
    const val ACTION_SOON = "com.tswq.ontimeprayer.ALARM_SOON"
    const val ACTION_SKIP = "com.tswq.ontimeprayer.ALARM_SKIP"
    const val ACTION_UNSNOOZE = "com.tswq.ontimeprayer.ALARM_UNSNOOZE"
    const val ACTION_CHECK = "com.tswq.ontimeprayer.ALARM_CHECK"
    const val ACTION_AWAKE = "com.tswq.ontimeprayer.ALARM_AWAKE"
    private val KEYS = listOf("alSnz_", "alSkip_", "alSnzN_", "alSnzB_", "alSnzI_", "alCut_", "alChk_", "alChkB_", "alLastB_")
    const val CH_ALARM = "alarm"
    const val CH_SOON = "alarm_soon"
    const val CH_CHECK = "alarm_check"
    private const val CODE = 3000
    private const val SOON = 30 * 60_000L

    fun list(ctx: Context): List<JSONObject> = runCatching {
        val a = JSONArray(Scheduler.prefs(ctx).getString("alarms", "[]") ?: "[]"); (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun find(ctx: Context, id: Int) = list(ctx).firstOrNull { it.optInt("id") == id }

    fun save(ctx: Context, json: String) {
        val arr = JSONArray(json)
        val old = list(ctx).associateBy { it.optInt("id") }
        val p = Scheduler.prefs(ctx); val e = p.edit().putString("alarms", json)
        val ids = HashSet<Int>()
        for (i in 0 until arr.length()) { val a = arr.optJSONObject(i) ?: continue; val id = a.optInt("id"); ids += id
            // a new time, or turned off, is a new alarm: its snooze, the snooze count and a skip go. A new sound or name keeps the snooze.
            val o = old[id] ?: continue
            if (timeOf(o) != timeOf(a) || !a.optBoolean("on", true)) { KEYS.forEach { e.remove(it + id) }; Alerts.cancel(ctx, noteId(id)); Alerts.cancel(ctx, checkNote(id)) } }
        // a deleted one leaves nothing behind
        p.all.keys.filter { k -> KEYS.any { k.startsWith(it) } }.forEach { k -> val n = k.substringAfterLast('_').toIntOrNull(); if (n == null || n !in ids) e.remove(k) }
        old.keys.filter { it !in ids }.forEach { Alerts.cancel(ctx, noteId(it)); Alerts.cancel(ctx, noteId(it, true)); Alerts.cancel(ctx, checkNote(it)) }
        e.apply(); schedule(ctx)
    }
    private fun timeOf(a: JSONObject) = a.optString("k", "fajr") + "|" + a.optBoolean("end") + "|" + a.optInt("off") + "|" + a.optInt("rep", 1) + "|" + a.optInt("gap", 5)

    /** The anchor's time on day [i] of the page's days; the last third runs from that evening's maghrib to the next fajr. */
    private fun anchor(days: JSONArray, i: Int, k: String, end: Boolean = false): Long {
        val d = days.optJSONObject(i) ?: return 0
        // "before the time ends": the first end the app shows for that prayer (its chosen time where there is one), sent with the days
        if (end) return d.optJSONObject("ex")?.optJSONArray(k)?.optJSONObject(0)?.optLong("at") ?: 0
        if (k == "third") { val n = days.optJSONObject(i + 1) ?: return 0; val m = d.optLong("maghrib"); val f = n.optLong("fajr"); return if (m > 0 && f > m) m + (f - m) * 2 / 3 else 0 }
        return d.optLong(k)
    }
    /** 0 = Sunday … 6 = Saturday, from the day's "YYYY-MM-DD". */
    private fun weekday(iso: String): Int { val p = iso.split("-"); if (p.size < 3) return -1
        return runCatching { val c = Calendar.getInstance(); c.clear(); c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt()); c.get(Calendar.DAY_OF_WEEK) - 1 }.getOrDefault(-1) }

    /** One time an alarm goes off: when, the series it belongs to (the time of its first ring), its place in it, and the prayer time. */
    data class Ring(val at: Long, val base: Long, val idx: Int, val anchor: Long)

    /** Its rings after [after], nearest first (0.27): each day's time and, in a series, the ones after it every few minutes.
     *  A skipped day (`alSkip_`, its first ring) goes whole, and so does the rest of a series ended with «استيقظت» (`alCut_`). */
    fun rings(ctx: Context, a: JSONObject, after: Long, max: Int = 1, skipped: Boolean = true): List<Ring> {
        val days = WData.load(Scheduler.prefs(ctx))?.o?.optJSONArray("days") ?: return emptyList()
        val on = a.optJSONArray("days"); val set = if (on == null || on.length() == 0) (0..6).toSet() else (0 until on.length()).map { on.optInt(it) }.toSet()
        val off = a.optLong("off") * 60_000L; val k = a.optString("k", "fajr"); val end = a.optBoolean("end")
        val rep = a.optInt("rep", 1).coerceIn(1, 10); val gap = a.optInt("gap", 5).coerceIn(1, 60) * 60_000L
        val p = Scheduler.prefs(ctx); val id = a.optInt("id")
        val skip = if (skipped) p.getLong("alSkip_$id", 0) else 0L; val cut = p.getLong("alCut_$id", 0)
        val out = ArrayList<Ring>()
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i); if (weekday(d.optString("d")) !in set) continue
            val t0 = anchor(days, i, k, end); if (t0 <= 0) continue
            val base = t0 + off; if (base == skip) continue
            for (j in 0 until rep) { if (j > 0 && base == cut) break
                val t = base + j * gap; if (t > after) { out += Ring(t, base, j, t0); if (out.size >= max) return out } }
        }
        return out
    }
    /** The next ring after [after], and the prayer time it hangs on. */
    fun next(ctx: Context, a: JSONObject, after: Long): Pair<Long, Long>? = rings(ctx, a, after, 1, false).firstOrNull()?.let { it.at to it.anchor }

    /** When each alarm rings next, as the app shows it: its time, and whether that time is being skipped. */
    fun state(ctx: Context): String {
        val p = Scheduler.prefs(ctx); val now = System.currentTimeMillis(); val o = JSONObject()
        for (a in list(ctx)) { val id = a.optInt("id"); val skip = p.getLong("alSkip_$id", 0)
            val snz = p.getLong("alSnz_$id", 0)
            val n = rings(ctx, a, now + 1_000, 1, false).firstOrNull() ?: continue
            val j = JSONObject().put("at", n.at).put("skip", skip == n.base)
            if (skip == n.base) rings(ctx, a, now + 1_000).firstOrNull()?.let { j.put("then", it.at) }
            if (snz > now) j.put("snooze", snz)
            o.put(id.toString(), j) }
        return o.toString()
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = Scheduler.prefs(ctx); val now = System.currentTimeMillis()
        // what was set before goes (an alarm turned off or deleted leaves nothing behind)
        (p.getString("alIds", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.forEach { id ->
            for (pi in listOf(PendingIntent.getBroadcast(ctx, CODE + id, ringIntent(ctx, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE),
                PendingIntent.getBroadcast(ctx, CODE + 1000 + id, soonIntent(ctx, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)))
                pi?.let { am.cancel(it); it.cancel() }
            PendingIntent.getBroadcast(ctx, CODE + 4000 + id, checkIntent(ctx, id), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { am.cancel(it); it.cancel() } }
        val ids = ArrayList<Int>()
        for (a in list(ctx)) {
            if (!a.optBoolean("on", true)) continue
            val id = a.optInt("id"); val snz = p.getLong("alSnz_$id", 0)
            val n = rings(ctx, a, now + 1_000).firstOrNull()
            // «هل استيقظت؟» not answered: the alarm again
            val chk = p.getLong("alChk_$id", 0)
            if (chk > now) runCatching { val ci = PendingIntent.getBroadcast(ctx, CODE + 4000 + id, checkIntent(ctx, id).putExtra("at", chk), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, chk, ci)
                else am.setAlarmClock(AlarmManager.AlarmClockInfo(chk, PendingIntent.getActivity(ctx, CODE + 500 + id, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)), ci) }
            // the next of: its own time (or the next in its series) and a snooze
            val t = listOfNotNull(n?.at, snz.takeIf { it > now }).minOrNull() ?: continue
            val pi = PendingIntent.getBroadcast(ctx, CODE + id, ringIntent(ctx, id).putExtra("at", t), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val show = PendingIntent.getActivity(ctx, CODE + 500 + id, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // like the clock app's alarms: the most exact the phone has, shown as the next alarm in the status bar
            runCatching { if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi) }
            // half an hour before an alarm that repeats: a quiet notice with "skip this time"
            if (snz <= now && n != null && n.idx == 0 && t == n.at && t - SOON > now + 5_000) runCatching {
                Scheduler.setExact(am, t - SOON, PendingIntent.getBroadcast(ctx, CODE + 1000 + id, soonIntent(ctx, id).putExtra("at", t), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            ids += id
        }
        // an alarm with a check pending keeps its id here so the check can be cancelled next time
        list(ctx).forEach { val id = it.optInt("id"); if (id !in ids && p.getLong("alChk_$id", 0) > now) ids += id }
        p.edit().putString("alIds", ids.joinToString(",")).apply()
        runCatching { AlarmWidget.update(ctx) }
    }

    private fun ringIntent(ctx: Context, id: Int) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RING).putExtra("id", id)
    private fun soonIntent(ctx: Context, id: Int) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SOON).putExtra("id", id)
    private fun checkIntent(ctx: Context, id: Int) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_CHECK).putExtra("id", id)

    /** Its time came: ring (unless the phone was off long past it), then set the next. */
    fun fire(ctx: Context, id: Int, at: Long) {
        val p = Scheduler.prefs(ctx); val a = find(ctx, id)
        // a snooze coming back keeps its count; the first ring of a day starts it again, so no count carries over to another day
        val back = p.getLong("alSnz_$id", 0) == at
        val r = if (back || a == null) null else rings(ctx, a, at - 1, 1, false).firstOrNull()?.takeIf { it.at == at }
        val base = r?.base ?: p.getLong("alSnzB_$id", at); val idx = r?.idx ?: p.getInt("alSnzI_$id", 0)
        p.edit().apply { if (back) remove("alSnz_$id"); if (r != null && r.idx == 0) remove("alSnzN_$id"); putLong("alLastB_$id", base) }.apply()
        Alerts.cancel(ctx, noteId(id, true)); if (back) Alerts.cancel(ctx, noteId(id))
        if (a != null && a.optBoolean("on", true) && System.currentTimeMillis() - at < 10 * 60_000L) {
            val j = JSONObject(a.toString()).put("_at", at).put("_base", base).put("_idx", idx).put("_anchor", r?.anchor ?: (base - a.optLong("off") * 60_000L))
            val i = Intent(ctx, RingService::class.java).putExtra("al", j.toString()).putExtra("at", at)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
        schedule(ctx)
    }

    /** "Skip this time" (from the notice, or from the app): this ring is left out, the next one stays. */
    fun skip(ctx: Context, id: Int, at: Long, on: Boolean = true) {
        val e = Scheduler.prefs(ctx).edit(); if (on) e.putLong("alSkip_$id", at) else e.remove("alSkip_$id"); e.apply()
        Alerts.cancel(ctx, noteId(id, true)); schedule(ctx)
    }

    fun snooze(ctx: Context, id: Int, mins: Int, base: Long = 0, idx: Int = 0) {
        val p = Scheduler.prefs(ctx); val until = System.currentTimeMillis() + mins * 60_000L
        p.edit().putLong("alSnz_$id", until).putInt("alSnzN_$id", p.getInt("alSnzN_$id", 0) + 1).putLong("alSnzB_$id", base).putInt("alSnzI_$id", idx).apply()
        schedule(ctx); snoozed(ctx, id, until)
    }
    /** "Skip the next one only" from the app: the next day (all of its series). */
    fun skipNext(ctx: Context, id: Int, on: Boolean) {
        val a = find(ctx, id) ?: return
        if (!on) { skip(ctx, id, 0, false); return }
        rings(ctx, a, System.currentTimeMillis() + 1_000, 1, false).firstOrNull()?.let { skip(ctx, id, it.base, true) }
    }

    // ---------- the wake-up check and the rest of a series (0.27) ----------
    fun checkNote(id: Int) = 9400 + id

    /** An alarm was stopped (or went unanswered). With the wake-up check on, after the last ring of its series: «هل استيقظت؟»
     *  with «نعم، استيقظت», and the alarm again in a few minutes unless it is pressed. */
    fun ended(ctx: Context, j: JSONObject) {
        if (!j.optBoolean("wake") || j.optBoolean("_check")) return
        val id = j.optInt("id"); val p = Scheduler.prefs(ctx); val base = j.optLong("_base")
        if (j.optInt("_idx") < j.optInt("rep", 1) - 1 && p.getLong("alCut_$id", 0) != base) return   // more of the series to come
        val at = System.currentTimeMillis() + j.optInt("wakeIn", 3).coerceIn(1, 15) * 60_000L
        p.edit().putLong("alChk_$id", at).putLong("alChkB_$id", base).apply()
        channels(ctx); val r = fmt(ctx); val ar = r.optString("lang") != "en"
        val yes = PendingIntent.getBroadcast(ctx, CODE + 5000 + id, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_AWAKE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_CHECK).setSmallIcon(R.drawable.ic_stat).setContentTitle(if (ar) "هل استيقظت؟" else "Are you up?")
            .setContentText((if (ar) "يعمل المنبّه مرة أخرى " else "The alarm goes off again at ") + Exits.clock(at, r) + (if (ar) " إن لم تؤكد." else " unless you confirm."))
            .setShowWhen(false).setOngoing(true).setTimeoutAfter(at - System.currentTimeMillis() + 60_000L).setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(yes)
            .addAction(Notification.Action.Builder(null, if (ar) "نعم، استيقظت" else "Yes, I'm up", yes).build())
        Alerts.post(ctx, n, checkNote(id))
        schedule(ctx)
    }
    /** «هل استيقظت؟» went unanswered: the alarm goes off again, as a check (its button: «نعم، استيقظت»). */
    fun check(ctx: Context, id: Int, at: Long) {
        val p = Scheduler.prefs(ctx); if (p.getLong("alChk_$id", 0) != at) return
        val a = find(ctx, id); p.edit().remove("alChk_$id").apply(); Alerts.cancel(ctx, checkNote(id))
        if (a != null && a.optBoolean("on", true) && System.currentTimeMillis() - at < 10 * 60_000L) {
            val base = p.getLong("alChkB_$id", at)
            val j = JSONObject(a.toString()).put("_at", at).put("_base", base).put("_idx", a.optInt("rep", 1) - 1).put("_check", true)
                .put("_anchor", base - a.optLong("off") * 60_000L)
            runCatching { val i = Intent(ctx, RingService::class.java).putExtra("al", j.toString()).putExtra("at", at)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
        schedule(ctx)
    }
    /** «نعم، استيقظت»: no check, and what is left of the series goes. */
    fun awake(ctx: Context, id: Int) {
        val p = Scheduler.prefs(ctx); val base = p.getLong("alChkB_$id", p.getLong("alLastB_$id", 0))
        p.edit().remove("alChk_$id").remove("alSnz_$id").remove("alSnzN_$id").apply { if (base > 0) putLong("alCut_$id", base) }.apply()
        Alerts.cancel(ctx, checkNote(id)); Alerts.cancel(ctx, noteId(id)); schedule(ctx)
    }
    /** «ألغِ الباقي» on the alarm screen: the rest of this series goes. */
    fun cut(ctx: Context, id: Int, base: Long) { Scheduler.prefs(ctx).edit().putLong("alCut_$id", base).apply(); schedule(ctx) }

    /** The next alarm of all (for the widget): when, which, and whether it is a snooze. */
    fun upcoming(ctx: Context): Triple<Long, JSONObject, Boolean>? {
        val p = Scheduler.prefs(ctx); val now = System.currentTimeMillis()
        var best: Triple<Long, JSONObject, Boolean>? = null
        for (a in list(ctx)) { if (!a.optBoolean("on", true)) continue
            val id = a.optInt("id"); val snz = p.getLong("alSnz_$id", 0)
            val t = rings(ctx, a, now + 1_000).firstOrNull()?.at
            val c = listOfNotNull(t?.let { Triple(it, a, false) }, snz.takeIf { it > now }?.let { Triple(it, a, true) }).minByOrNull { it.first } ?: continue
            if (best == null || c.first < best.first) best = c }
        return best
    }
    fun stopped(ctx: Context, id: Int) { Scheduler.prefs(ctx).edit().remove("alSnz_$id").remove("alSnzN_$id").apply(); Alerts.cancel(ctx, noteId(id)); schedule(ctx) }
    /** "Dismiss" on the snooze notice, or "cancel the snooze" in the app. */
    fun unsnooze(ctx: Context, id: Int) = stopped(ctx, id)

    /** While snoozed: «غفوة حتى ٥:٠٥» with "Stop the alarm", as the clock app does. */
    private fun snoozed(ctx: Context, id: Int, until: Long) {
        val a = find(ctx, id) ?: return; channels(ctx); val r = fmt(ctx); val ar = r.optString("lang") != "en"
        val stop = PendingIntent.getBroadcast(ctx, CODE + 3000 + id, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_UNSNOOZE).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_SOON).setSmallIcon(R.drawable.ic_stat).setContentTitle((if (ar) "غفوة حتى " else "Snoozed until ") + Exits.clock(until, r))
            .setContentText(title(ctx, a)).setShowWhen(false).setTimeoutAfter(until - System.currentTimeMillis() + 60_000L)
            .setContentIntent(PendingIntent.getActivity(ctx, CODE + 2500 + id, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, if (ar) "إيقاف المنبّه" else "Dismiss", stop).build())
        Alerts.post(ctx, n, noteId(id))
    }
    fun snoozesLeft(ctx: Context, a: JSONObject): Boolean {
        if (a.optBoolean("_check")) return false
        val m = a.optInt("snz", 10); if (m <= 0) return false
        val n = a.optInt("snN", 3); return n <= 0 || Scheduler.prefs(ctx).getInt("alSnzN_${a.optInt("id")}", 0) < n
    }

    fun noteId(id: Int, soon: Boolean = false) = (if (soon) 9300 else 9200) + id

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, if (ar) "المنبّهات" else "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        nm.deleteNotificationChannel("alarm_screen")   // the helper notification of 0.25.1 is gone
        nm.createNotificationChannel(NotificationChannel(CH_CHECK, if (ar) "هل استيقظت؟" else "Are you up?", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        nm.createNotificationChannel(NotificationChannel(CH_SOON, if (ar) "منبّه قريب" else "Upcoming alarm", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false) })
    }

    /** The words the phone and the alarm screen use, in the app's language and digits. */
    fun fmt(ctx: Context): JSONObject { val d = WData.load(Scheduler.prefs(ctx))
        return JSONObject().put("lang", if (d?.ar != false) "ar" else "en").put("dg", if (d?.arabDigits == true) 1 else 0).put("f12", if (d?.h12 != false) 1 else 0) }
    fun anchorName(ctx: Context, k: String): String { val d = WData.load(Scheduler.prefs(ctx)); val ar = d?.ar != false
        return if (k == "third") (if (ar) "الثلث الأخير" else "Last third") else d?.name(k) ?: k }
    fun title(ctx: Context, a: JSONObject): String { if (a.optBoolean("_check")) return if (fmt(ctx).optString("lang") != "en") "هل استيقظت؟" else "Are you up?"
        return name(ctx, a) }
    fun name(ctx: Context, a: JSONObject): String { val l = a.optString("label").trim(); if (l.isNotEmpty()) return l
        val ar = fmt(ctx).optString("lang") != "en"; val off = a.optInt("off"); val r = fmt(ctx); val nm = anchorName(ctx, a.optString("k", "fajr"))
        if (a.optBoolean("end")) { val m = Exits.mins(maxOf(1, -off).toLong(), r); return if (ar) "قبل خروج وقت $nm بـ$m" else "$m before $nm time ends" }
        return when { off == 0 -> (if (ar) "عند " else "At ") + nm; off < 0 -> (if (ar) "قبل $nm بـ" else Exits.mins(-off.toLong(), r) + " before ") + (if (ar) Exits.mins(-off.toLong(), r) else nm)
            else -> (if (ar) "بعد $nm بـ" else Exits.mins(off.toLong(), r) + " after ") + (if (ar) Exits.mins(off.toLong(), r) else nm) } }

    /** Half an hour before: «المنبّه بعد نصف ساعة · 4:55 ص» with "skip this time". */
    fun soon(ctx: Context, id: Int, at: Long) {
        val a = find(ctx, id) ?: return; if (!a.optBoolean("on", true)) return
        val p = Scheduler.prefs(ctx); if (p.getLong("alSkip_$id", 0) == at) return
        channels(ctx); val r = fmt(ctx); val ar = r.optString("lang") != "en"
        val skip = PendingIntent.getBroadcast(ctx, CODE + 2000 + id, Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SKIP).putExtra("id", id).putExtra("at", at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CH_SOON).setSmallIcon(R.drawable.ic_stat).setContentTitle((if (ar) "منبّه " else "Alarm at ") + Exits.clock(at, r))
            .setContentText(title(ctx, a)).setShowWhen(false).setAutoCancel(true).setTimeoutAfter(SOON + 60_000L)
            .setContentIntent(PendingIntent.getActivity(ctx, CODE + 2500 + id, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, if (ar) "تخطَّ هذه المرة" else "Skip this time", skip).build())
        Alerts.post(ctx, n, noteId(id, true))
    }
}

/**
 * Rings an alarm: the phone's alarm sound (or the one picked) on the alarm stream, looping, the volume rising gently if wished,
 * with vibration; until stopped, or for the minutes picked. Its notification opens the alarm screen over the lock screen.
 */
class RingService : Service() {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    /** The alarms going off now: the first one's sound and length; another that comes while it is on joins it on the same screen. */
    private val als = ArrayList<JSONObject>()
    private var at = 0L
    private var awake: PowerManager.WakeLock? = null
    private var screenOn: PowerManager.WakeLock? = null
    private var startedAt = 0L
    private var done = false
    private var screenRx: BroadcastReceiver? = null
    private var volRx: BroadcastReceiver? = null
    private var session: MediaSession? = null
    private var flip: SensorEventListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun fg() { val n = note()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTE_ID, n) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            ACTION_SNOOZE -> { hush(); return START_NOT_STICKY }
            ACTION_CUT -> { if (!done) { done = true; als.forEach { PrayerAlarms.cut(this, it.optInt("id"), it.optLong("_base")); PrayerAlarms.stopped(this, it.optInt("id")) } }; stopSelf(); return START_NOT_STICKY }
        }
        val a = intent?.getStringExtra("al")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (a == null) { if (als.isEmpty()) stopSelf(); return START_NOT_STICKY }
        PrayerAlarms.channels(this)
        if (als.isNotEmpty() && !done) {
            // two alarms at the same time (or one while another is on): one sound, one screen with both names; Stop and Snooze take both
            if (als.none { it.optInt("id") == a.optInt("id") }) als += a
            fg(); RingActivity.refresh(); return START_NOT_STICKY
        }
        als.clear(); als += a; done = false; at = intent.getLongExtra("at", System.currentTimeMillis()); instance = this
        fg()
        awake?.let { runCatching { if (it.isHeld) it.release() } }
        runCatching { awake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ontime:alarm")?.apply { acquire(31 * 60_000L) } }
        main.removeCallbacksAndMessages(null)
        play(a)
        if (a.optBoolean("vib", true)) runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()) }
        // rings until stopped, or for the minutes picked (then it counts as missed, and the next day's stays)
        val mins = a.optInt("ring", 0)
        main.postDelayed({ stopAll(); stopSelf() }, if (mins > 0) mins * 60_000L else 30 * 60_000L)
        listen()
        return START_NOT_STICKY
    }

    private fun screenPi() = PendingIntent.getActivity(this, 9101, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Stopped, or unanswered: the snooze goes; a check ring stopped means awake; otherwise the wake-up check may follow. */
    private fun stopAll() { if (done) return; done = true
        als.forEach { PrayerAlarms.stopped(this, it.optInt("id")); if (it.optBoolean("_check")) PrayerAlarms.awake(this, it.optInt("id")) else PrayerAlarms.ended(this, it) } }
    /** Snooze (each with its own minutes, while it has snoozes left), or stop when the first one has none left. */
    private fun hush() {
        if (done || als.isEmpty()) { stopSelf(); return }
        val first = als[0]
        if (!PrayerAlarms.snoozesLeft(this, first)) { stopAll(); stopSelf(); return }
        done = true
        als.forEach { x -> val id = x.optInt("id")
            if (PrayerAlarms.snoozesLeft(this, x)) PrayerAlarms.snooze(this, id, x.optInt("snz", 10), x.optLong("_base"), x.optInt("_idx"))
            else { PrayerAlarms.stopped(this, id); PrayerAlarms.ended(this, x) } }
        stopSelf()
    }

    /** The power button, the volume buttons and turning the phone face down snooze it, with nothing to set (as in the clock apps). */
    private fun listen() {
        unlisten(); startedAt = System.currentTimeMillis()
        val pm = getSystemService(PowerManager::class.java); val wasOn = pm?.isInteractive == true
        // in use: keep the screen on while it goes off, so it is the button that turns it off, not the screen timeout
        if (wasOn) runCatching { @Suppress("DEPRECATION") screenOn = pm?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ontime:alarmscreen")?.apply { acquire(31 * 60_000L) } }
        val fsOk = Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        val viaScreen = !wasOn && !fsOk   // screen off and no alarm screen: pressing power turns it on
        screenRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) {
            if (System.currentTimeMillis() - startedAt < 2_500) return
            if (i.action == Intent.ACTION_SCREEN_OFF || (viaScreen && i.action == Intent.ACTION_SCREEN_ON)) main.post { hush() } } }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        runCatching { if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenRx, f) }
        session = runCatching { MediaSession(this, "ontime-alarm").apply {
            setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) { main.post { hush() } } })
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            isActive = true } }.getOrNull()
        volRx = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { if (System.currentTimeMillis() - startedAt > 1_500) main.post { hush() } } }
        runCatching { val vf = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volRx, vf, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(volRx, vf) }
        // turned face down (after being face up at some point while it goes off), held for half a second
        val sm = getSystemService(SensorManager::class.java); val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm != null && acc != null) { var up = false; var downSince = 0L
            flip = object : SensorEventListener {
                override fun onSensorChanged(se: SensorEvent) { val z = se.values[2]
                    if (z > -3f) { up = true; downSince = 0L }
                    else if (up && z < -8f) { if (downSince == 0L) downSince = System.currentTimeMillis() else if (System.currentTimeMillis() - downSince > 500) main.post { hush() } } }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {} }
            sm.registerListener(flip, acc, SensorManager.SENSOR_DELAY_UI) }
    }
    private fun unlisten() {
        screenRx?.let { runCatching { unregisterReceiver(it) } }; screenRx = null
        volRx?.let { runCatching { unregisterReceiver(it) } }; volRx = null
        session?.let { runCatching { it.isActive = false; it.release() } }; session = null
        flip?.let { l -> runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(l) } }; flip = null
        screenOn?.let { runCatching { if (it.isHeld) it.release() } }; screenOn = null
    }

    private fun soundUri(a: JSONObject): Uri? {
        // an adhan from the app's voices: Fajr alarms take the voice's own Fajr adhan where it has one
        val s = a.optString("snd")
        if (s.isNotEmpty() && s != "tone") { val f = if (a.optString("k") == "fajr" && Alerts.rawId(this, s + "_fajr") != 0) s + "_fajr" else s
            Alerts.rawId(this, f).takeIf { it != 0 }?.let { return Uri.parse("android.resource://$packageName/$it") } }
        val key = "tone_al" + a.optInt("id")
        return Scheduler.prefs(this).getString("soundUri_$key", null)?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    private fun play(a: JSONObject) {
        player?.release(); player = null
        val rise = a.optBoolean("rise", true)
        fun start(uri: Uri): Boolean = try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@RingService, uri); isLooping = true
                if (rise) setVolume(.12f, .12f)
                prepare(); start()
            }; true
        } catch (e: Exception) { player?.release(); player = null; false }
        val ok = soundUri(a)?.let { start(it) } == true || RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { start(it) } == true
        // the volume climbs to full over about half a minute, as a gentle alarm does
        if (ok && rise) { var v = .12f; val step = object : Runnable { override fun run() { v = minOf(1f, v + .03f); runCatching { player?.setVolume(v, v) }; if (v < 1f) main.postDelayed(this, 1_000) } }; main.postDelayed(step, 1_000) }
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, RingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun note(): Notification {
        val a = als[0]; val r = PrayerAlarms.fmt(this); val ar = r.optString("lang") != "en"
        val screen = screenPi()
        val b = Notification.Builder(this, PrayerAlarms.CH_ALARM).setSmallIcon(R.drawable.ic_stat).setCategory(Notification.CATEGORY_ALARM)
            .setContentTitle(titles(this)).setContentText(Exits.clock(at, r)).setOngoing(true).setShowWhen(false)
            .setContentIntent(screen).setFullScreenIntent(screen, true)
        if (PrayerAlarms.snoozesLeft(this, a)) b.addAction(Notification.Action.Builder(null, (if (ar) "غفوة " else "Snooze ") + Exits.short(a.optInt("snz", 10).toLong(), r), pi(ACTION_SNOOZE, 9102)).build())
        b.addAction(Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", pi(ACTION_STOP, 9103)).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null); unlisten()
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        runCatching { getSystemService(Vibrator::class.java)?.cancel() }
        awake?.let { runCatching { if (it.isHeld) it.release() } }; awake = null
        RingActivity.close()
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.tswq.ontimeprayer.ALARM_STOP"
        const val ACTION_SNOOZE = "com.tswq.ontimeprayer.ALARM_SNOOZE"
        const val NOTE_ID = 9100
        private var instance: RingService? = null
        fun ringing(): Pair<JSONObject, Long>? = instance?.let { s -> s.als.firstOrNull()?.let { it to s.at } }
        /** The names of all the alarms on now, «عند أذان الفجر · قيام الليل». */
        fun titles(ctx: Context): String = (instance?.als ?: emptyList<JSONObject>()).map { PrayerAlarms.title(ctx, it) }.distinct().joinToString(" · ")
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_STOP)) }
        fun snooze(ctx: Context) { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_SNOOZE)) }
        fun cutRest(ctx: Context) { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_CUT)) }
        const val ACTION_CUT = "com.tswq.ontimeprayer.ALARM_CUT"
    }
}

/**
 * The alarm screen, over the lock screen: the night sky in navy with a few stars, the time large, what the alarm is for, and
 * where the prayer stands («بقي ٢٠ دقيقة على أذان الفجر», «أذّن الفجر قبل ٥ دقائق»); "Stop" and "Snooze". The volume buttons snooze.
 */
class RingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#1B2C52"); window.navigationBarColor = Color.parseColor("#0B1630")
        current = WeakReference(this)
        show()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); show() }
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun show() {
        val (a, at) = RingService.ringing() ?: run { finish(); return }
        val r = PrayerAlarms.fmt(this); val ar = r.optString("lang") != "en"; val f = WFonts.get(assets)
        val gold = Color.parseColor("#E9CD8A"); val cream = Color.parseColor("#F3E6C4")
        val root = FrameLayout(this); root.addView(Night(this))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = if (ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        fun tv(s: String, size: Float, tf: Typeface, color: Int) = TextView(this).apply { text = s; textSize = size; typeface = tf; setTextColor(color); gravity = Gravity.CENTER }
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(tv(RingService.titles(this), 18f, f.plex, Color.argb(210, 243, 230, 196)))
        val now = System.currentTimeMillis()
        col.addView(tv(Exits.clock(now, r).replace(" ص", "").replace(" م", "").replace(" AM", "").replace(" PM", ""), 76f, f.name("kufi"), gold))
        // where the prayer stands, from the alarm's own anchor
        val k = a.optString("k", "fajr"); val anchor = a.optLong("_anchor", at - a.optLong("off") * 60_000L); val nm = PrayerAlarms.anchorName(this, k)
        val gap = (anchor - now) / 60_000L
        val where = when {
            a.optBoolean("_check") -> PrayerAlarms.name(this, a)
            k == "third" -> if (ar) "الثلث الأخير من الليل" else "The last third of the night"
            a.optBoolean("end") -> if (gap >= 1) (if (ar) "يخرج وقت $nm بعد ${Exits.mins(gap, r)}" else "$nm time ends in ${Exits.mins(gap, r)}") else (if (ar) "يخرج وقت $nm الآن" else "$nm time is ending")
            gap >= 1 -> if (ar) "بقي ${Exits.mins(gap, r)} على أذان $nm" else "${Exits.mins(gap, r)} to the $nm adhan"
            gap <= -1 -> if (ar) "أذّن $nm قبل ${Exits.mins(-gap, r)}" else "The $nm adhan was ${Exits.mins(-gap, r)} ago"
            else -> if (ar) "حان وقت $nm" else "It is time for $nm" }
        col.addView(tv(where, 19f, f.plexB, cream), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(24f).toInt(), dp(6f).toInt(), dp(24f).toInt(), 0) })
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1.2f))
        fun btn(s: String, primary: Boolean, click: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; textSize = if (primary) 18f else 15f; typeface = f.plexB; stateListAnimator = null
            setTextColor(if (primary) Color.parseColor("#0B1630") else cream)
            background = GradientDrawable().apply { cornerRadius = dp(24f); if (primary) setColor(gold) else { setColor(Color.argb(28, 255, 255, 255)); setStroke(dp(1f).toInt(), Color.argb(80, 233, 205, 138)) } }
            setOnClickListener { click() } }
        val lp = { h: Float -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h).toInt()).apply { setMargins(dp(28f).toInt(), dp(7f).toInt(), dp(28f).toInt(), dp(7f).toInt()) } }
        val check = a.optBoolean("_check")
        col.addView(btn(if (check) (if (ar) "نعم، استيقظت" else "Yes, I'm up") else (if (ar) "إيقاف" else "Stop"), true) { RingService.stop(this); finish() }, lp(66f))
        if (PrayerAlarms.snoozesLeft(this, a)) col.addView(btn((if (ar) "غفوة " else "Snooze ") + Exits.mins(a.optInt("snz", 10).toLong(), r), false) { RingService.snooze(this); finish() }, lp(58f))
        // a series: the ones still to come can go at once
        val left = a.optInt("rep", 1) - 1 - a.optInt("_idx")
        if (!check && left > 0) col.addView(TextView(this).apply {
            text = if (ar) "ألغِ الباقي ($left)" else "Cancel the rest ($left)"; textSize = 15f; typeface = f.plex; setTextColor(cream); gravity = Gravity.CENTER
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG; setPadding(0, dp(10f).toInt(), 0, dp(10f).toInt())
            setOnClickListener { RingService.cutRest(this@RingActivity); finish() } })
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(36f).toInt()))
        @Suppress("DEPRECATION") col.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            RingService.snooze(this); finish(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    /** Navy like the sky half an hour after Isha, a soft glow, and a few stars. */
    private class Night(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stars = FloatArray(60) { (Math.random()).toFloat() }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, w * .4f, h, Color.parseColor("#1B2C52"), Color.parseColor("#0B1630"), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
            p.shader = RadialGradient(w / 2, h * .38f, w * .7f, intArrayOf(Color.argb(46, 233, 205, 138), 0), null, Shader.TileMode.CLAMP); c.drawCircle(w / 2, h * .38f, w * .7f, p)
            p.shader = null
            for (i in stars.indices step 2) { p.color = Color.argb(if (i % 6 == 0) 150 else 80, 243, 230, 196); c.drawCircle(w * stars[i], h * .7f * stars[i + 1], if (i % 4 == 0) w * .004f else w * .0028f, p) }
        }
    }

    companion object {
        private var current: WeakReference<RingActivity>? = null
        fun close() { current?.get()?.let { a -> a.runOnUiThread { a.finish() } } }
        fun refresh() { current?.get()?.let { a -> a.runOnUiThread { a.show() } } }
    }
}
