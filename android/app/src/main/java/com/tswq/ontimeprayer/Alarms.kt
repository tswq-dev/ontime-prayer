package com.tswq.ontimeprayer

import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
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
    const val CH_ALARM = "alarm"
    const val CH_SOON = "alarm_soon"
    private const val CODE = 3000
    private const val SOON = 30 * 60_000L

    fun list(ctx: Context): List<JSONObject> = runCatching {
        val a = JSONArray(Scheduler.prefs(ctx).getString("alarms", "[]") ?: "[]"); (0 until a.length()).map { a.getJSONObject(it) }
    }.getOrDefault(emptyList())
    fun find(ctx: Context, id: Int) = list(ctx).firstOrNull { it.optInt("id") == id }

    fun save(ctx: Context, json: String) {
        JSONArray(json) // validate
        val p = Scheduler.prefs(ctx); val e = p.edit().putString("alarms", json)
        // a changed alarm forgets an old snooze or skip
        val ids = list(ctx).map { it.optInt("id") }.toSet()
        p.all.keys.filter { it.startsWith("alSnz_") || it.startsWith("alSkip_") || it.startsWith("alSnzN_") }.forEach { k -> val n = k.substringAfterLast('_').toIntOrNull(); if (n == null || n !in ids) e.remove(k) }
        e.apply(); schedule(ctx)
    }

    /** The anchor's time on day [i] of the page's days; the last third runs from that evening's maghrib to the next fajr. */
    private fun anchor(days: JSONArray, i: Int, k: String): Long {
        val d = days.optJSONObject(i) ?: return 0
        if (k == "third") { val n = days.optJSONObject(i + 1) ?: return 0; val m = d.optLong("maghrib"); val f = n.optLong("fajr"); return if (m > 0 && f > m) m + (f - m) * 2 / 3 else 0 }
        return d.optLong(k)
    }
    /** 0 = Sunday … 6 = Saturday, from the day's "YYYY-MM-DD". */
    private fun weekday(iso: String): Int { val p = iso.split("-"); if (p.size < 3) return -1
        return runCatching { val c = Calendar.getInstance(); c.clear(); c.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt()); c.get(Calendar.DAY_OF_WEEK) - 1 }.getOrDefault(-1) }

    /** The next ring after [after], and the prayer time it hangs on. */
    fun next(ctx: Context, a: JSONObject, after: Long): Pair<Long, Long>? {
        val days = WData.load(Scheduler.prefs(ctx))?.o?.optJSONArray("days") ?: return null
        val on = a.optJSONArray("days"); val set = if (on == null || on.length() == 0) (0..6).toSet() else (0 until on.length()).map { on.optInt(it) }.toSet()
        val off = a.optLong("off") * 60_000L; val k = a.optString("k", "fajr")
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i); if (weekday(d.optString("d")) !in set) continue
            val t0 = anchor(days, i, k); if (t0 <= 0) continue
            val t = t0 + off; if (t > after) return t to t0
        }
        return null
    }

    /** When each alarm rings next, as the app shows it: its time, and whether that time is being skipped. */
    fun state(ctx: Context): String {
        val p = Scheduler.prefs(ctx); val now = System.currentTimeMillis(); val o = JSONObject()
        for (a in list(ctx)) { val id = a.optInt("id"); val skip = p.getLong("alSkip_$id", 0)
            val snz = p.getLong("alSnz_$id", 0)
            val n = next(ctx, a, now + 1_000) ?: continue
            val j = JSONObject().put("at", n.first).put("skip", skip == n.first)
            if (skip == n.first) next(ctx, a, n.first)?.let { j.put("then", it.first) }
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
                pi?.let { am.cancel(it); it.cancel() } }
        val ids = ArrayList<Int>()
        for (a in list(ctx)) {
            if (!a.optBoolean("on", true)) continue
            val id = a.optInt("id"); val snz = p.getLong("alSnz_$id", 0); val skip = p.getLong("alSkip_$id", 0)
            var n = next(ctx, a, now + 1_000) ?: continue
            if (skip == n.first) n = next(ctx, a, n.first) ?: continue
            val t = if (snz > now) snz else n.first
            val pi = PendingIntent.getBroadcast(ctx, CODE + id, ringIntent(ctx, id).putExtra("at", t), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val show = PendingIntent.getActivity(ctx, CODE + 500 + id, Intent(ctx, MainActivity::class.java).putExtra("open", "alarms").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // like the clock app's alarms: the most exact the phone has, shown as the next alarm in the status bar
            runCatching { if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
                else am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi) }
            // half an hour before an alarm that repeats: a quiet notice with "skip this time"
            if (snz <= now && t - SOON > now + 5_000) runCatching {
                Scheduler.setExact(am, t - SOON, PendingIntent.getBroadcast(ctx, CODE + 1000 + id, soonIntent(ctx, id).putExtra("at", t), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            ids += id
        }
        p.edit().putString("alIds", ids.joinToString(",")).apply()
    }

    private fun ringIntent(ctx: Context, id: Int) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_RING).putExtra("id", id)
    private fun soonIntent(ctx: Context, id: Int) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_SOON).putExtra("id", id)

    /** Its time came: ring (unless the phone was off long past it), then set the next. */
    fun fire(ctx: Context, id: Int, at: Long) {
        val p = Scheduler.prefs(ctx); val a = find(ctx, id)
        p.edit().remove("alSnz_$id").apply()
        Alerts.cancel(ctx, noteId(id, true))
        if (a != null && a.optBoolean("on", true) && System.currentTimeMillis() - at < 10 * 60_000L) {
            val i = Intent(ctx, RingService::class.java).putExtra("al", a.toString()).putExtra("at", at)
            runCatching { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) }
        }
        schedule(ctx)
    }

    /** "Skip this time" (from the notice, or from the app): this ring is left out, the next one stays. */
    fun skip(ctx: Context, id: Int, at: Long, on: Boolean = true) {
        val e = Scheduler.prefs(ctx).edit(); if (on) e.putLong("alSkip_$id", at) else e.remove("alSkip_$id"); e.apply()
        Alerts.cancel(ctx, noteId(id, true)); schedule(ctx)
    }

    fun snooze(ctx: Context, id: Int, mins: Int) {
        val p = Scheduler.prefs(ctx)
        p.edit().putLong("alSnz_$id", System.currentTimeMillis() + mins * 60_000L).putInt("alSnzN_$id", p.getInt("alSnzN_$id", 0) + 1).apply()
        schedule(ctx)
    }
    fun stopped(ctx: Context, id: Int) { Scheduler.prefs(ctx).edit().remove("alSnz_$id").remove("alSnzN_$id").apply(); schedule(ctx) }
    fun snoozesLeft(ctx: Context, a: JSONObject): Boolean {
        val m = a.optInt("snz", 10); if (m <= 0) return false
        val n = a.optInt("snN", 3); return n <= 0 || Scheduler.prefs(ctx).getInt("alSnzN_${a.optInt("id")}", 0) < n
    }

    fun noteId(id: Int, soon: Boolean = false) = (if (soon) 9300 else 9200) + id

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, if (ar) "المنبّهات" else "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
        nm.createNotificationChannel(NotificationChannel(CH_SOON, if (ar) "منبّه قريب" else "Upcoming alarm", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false) })
    }

    /** The words the phone and the alarm screen use, in the app's language and digits. */
    fun fmt(ctx: Context): JSONObject { val d = WData.load(Scheduler.prefs(ctx))
        return JSONObject().put("lang", if (d?.ar != false) "ar" else "en").put("dg", if (d?.arabDigits == true) 1 else 0).put("f12", if (d?.h12 != false) 1 else 0) }
    fun anchorName(ctx: Context, k: String): String { val d = WData.load(Scheduler.prefs(ctx)); val ar = d?.ar != false
        return if (k == "third") (if (ar) "الثلث الأخير" else "Last third") else d?.name(k) ?: k }
    fun title(ctx: Context, a: JSONObject): String { val l = a.optString("label").trim(); if (l.isNotEmpty()) return l
        val ar = fmt(ctx).optString("lang") != "en"; val off = a.optInt("off"); val r = fmt(ctx); val nm = anchorName(ctx, a.optString("k", "fajr"))
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
    private var al: JSONObject? = null
    private var at = 0L
    private var awake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cur = al
        when (intent?.action) {
            ACTION_STOP -> { cur?.let { PrayerAlarms.stopped(this, it.optInt("id")) }; stopSelf(); return START_NOT_STICKY }
            ACTION_SNOOZE -> { cur?.let { PrayerAlarms.snooze(this, it.optInt("id"), it.optInt("snz", 10)) }; stopSelf(); return START_NOT_STICKY }
        }
        val a = intent?.getStringExtra("al")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (a == null) { stopSelf(); return START_NOT_STICKY }
        al = a; at = intent.getLongExtra("at", System.currentTimeMillis()); instance = this
        PrayerAlarms.channels(this)
        val n = note()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(NOTE_ID, n)
        runCatching { awake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ontime:alarm")?.apply { acquire(31 * 60_000L) } }
        main.removeCallbacksAndMessages(null)
        play(a)
        if (a.optBoolean("vib", true)) runCatching { getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()) }
        // rings until stopped, or for the minutes picked (then it counts as missed, and the next day's stays)
        val mins = a.optInt("ring", 0)
        main.postDelayed({ PrayerAlarms.stopped(this, a.optInt("id")); stopSelf() }, if (mins > 0) mins * 60_000L else 30 * 60_000L)
        return START_NOT_STICKY
    }

    private fun soundUri(a: JSONObject): Uri? {
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
        val a = al!!; val r = PrayerAlarms.fmt(this); val ar = r.optString("lang") != "en"
        val screen = PendingIntent.getActivity(this, 9101, Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, PrayerAlarms.CH_ALARM).setSmallIcon(R.drawable.ic_stat).setCategory(Notification.CATEGORY_ALARM)
            .setContentTitle(PrayerAlarms.title(this, a)).setContentText(Exits.clock(at, r)).setOngoing(true).setShowWhen(false)
            .setContentIntent(screen).setFullScreenIntent(screen, true)
        if (PrayerAlarms.snoozesLeft(this, a)) b.addAction(Notification.Action.Builder(null, (if (ar) "غفوة " else "Snooze ") + Exits.short(a.optInt("snz", 10).toLong(), r), pi(ACTION_SNOOZE, 9102)).build())
        b.addAction(Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", pi(ACTION_STOP, 9103)).build())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
        fun ringing(): Pair<JSONObject, Long>? = instance?.let { s -> s.al?.let { it to s.at } }
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_STOP)) }
        fun snooze(ctx: Context) { ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_SNOOZE)) }
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
        col.addView(tv(PrayerAlarms.title(this, a), 18f, f.plex, Color.argb(210, 243, 230, 196)))
        val now = System.currentTimeMillis()
        col.addView(tv(Exits.clock(now, r).replace(" ص", "").replace(" م", "").replace(" AM", "").replace(" PM", ""), 76f, f.name("kufi"), gold))
        // where the prayer stands, from the alarm's own anchor
        val k = a.optString("k", "fajr"); val anchor = at - a.optLong("off") * 60_000L; val nm = PrayerAlarms.anchorName(this, k)
        val gap = (anchor - now) / 60_000L
        val where = when {
            k == "third" -> if (ar) "الثلث الأخير من الليل" else "The last third of the night"
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
        col.addView(btn(if (ar) "إيقاف" else "Stop", true) { RingService.stop(this); finish() }, lp(66f))
        if (PrayerAlarms.snoozesLeft(this, a)) col.addView(btn((if (ar) "غفوة " else "Snooze ") + Exits.mins(a.optInt("snz", 10).toLong(), r), false) { RingService.snooze(this); finish() }, lp(58f))
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(36f).toInt()))
        @Suppress("DEPRECATION") col.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val a = RingService.ringing()?.first
            if (a != null && PrayerAlarms.snoozesLeft(this, a)) RingService.snooze(this) else RingService.stop(this)
            finish(); return true }
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
    }
}
