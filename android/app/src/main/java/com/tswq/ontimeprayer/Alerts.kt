package com.tswq.ontimeprayer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONObject

object Alerts {
    const val CH_ADHAN = "adhan"
    const val CH_VIBRATE = "vibrate"
    const val CH_REMIND = "remind"
    const val CH_EXIT = "exit"
    const val CH_AFTER = "after"
    const val CH_SILENT = "quiet"
    const val CH_LATER = "later"

    /** The prayer whose notification is showing the "remind me in" choices. */
    @Volatile var menuPk: String? = null
    private const val RESTORE_CODE = 900

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        fun t(a: String, e: String) = if (ar) a else e
        nm.deleteNotificationChannel("tone") // the soft tone option is gone (0.4)
        // The adhan itself is played by AdhanService, so its channel stays silent.
        nm.createNotificationChannel(NotificationChannel(CH_ADHAN, t("الأذان", "Adhan"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_VIBRATE, t("اهتزاز فقط", "Vibrate only"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 400, 250, 400)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, t("التذكير والإقامة", "Reminders and iqama"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_EXIT, t("خروج وقت الصلاة", "End of prayer time"), NotificationManager.IMPORTANCE_HIGH))
        // "Notification only", and reminders whose sound is spoken or off: no sound, no vibration.
        nm.createNotificationChannel(NotificationChannel(CH_SILENT, t("إشعار بدون صوت", "Silent notification"), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_LATER, t("ذكّرني بعد", "Remind me in"), NotificationManager.IMPORTANCE_HIGH))
        // What stays in the shade after the adhan (with "remind me" and "I prayed"): no sound of its own.
        nm.createNotificationChannel(NotificationChannel(CH_AFTER, t("بعد الأذان", "After the adhan"), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
        })
    }

    /** Events saved before 0.4 carry only "voice". */
    fun soundOf(ev: JSONObject): String = ev.optString("sound").ifEmpty {
        when (ev.optString("voice")) {
            "makkah", "madinah", "adhan" -> "afasy"; "takbir" -> "takbir_afasy"
            "vibrate" -> "vibrate"; "none" -> "none"; else -> "notify"
        }
    }

    /** [done] is called when nothing more is playing (the receiver keeps the process alive until then). */
    fun fire(ctx: Context, ev: JSONObject, done: () -> Unit = {}) {
        channels(ctx)
        val title = ev.optString("title"); val text = ev.optString("text"); val id = ev.optString("id").hashCode()
        val pk = ev.optString("pk")
        val spoken = ev.optString("rs", "ding") == "speak"
        val remCh = when { ev.optString("voice") == "vibrate" -> CH_VIBRATE; ev.optString("rs", "ding") == "ding" -> CH_REMIND; else -> CH_SILENT }
        var speaking = false
        fun say(name: String, then: () -> Unit = {}) { speaking = true; Speaker.play(ctx, name) { then(); done() } }
        fun quietNote() = post(ctx, if (Exits.hasEx(ev)) prayerBuilder(ctx, ev, CH_SILENT, null) else builder(ctx, CH_SILENT, title, text), noteId(ev))
        when (ev.optString("kind")) {
            "adhan" -> when (val s = soundOf(ev).let { if (it != "none" && WidgetUpdater.takeMute(ctx, ev)) "notify" else it }) {
                "none" -> {}
                "vibrate" -> post(ctx, prayerBuilder(ctx, ev, CH_VIBRATE, null), noteId(ev))
                "notify" -> quietNote()
                else -> if (s.startsWith("spk_")) { quietNote(); say(s) } else {
                    val i = Intent(ctx, AdhanService::class.java).putExtra("ev", ev.toString())
                    try {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                    } catch (e: Exception) {
                        post(ctx, prayerBuilder(ctx, ev, CH_REMIND, null), noteId(ev))
                    }
                }
            }
            "pre", "iqama" -> {
                post(ctx, builder(ctx, remCh, title, text).also { untilNow(it, ev.optLong("until")) }, id)
                if (spoken && remCh != CH_VIBRATE) say(ev.optString("spk"))
            }
            // the spoken line first, then vibrate mode (vibrate mode would cut it off)
            "silence" -> if (spoken && ringerNormal(ctx)) say(ev.optString("spk")) { silence(ctx, ev.optInt("min", 20), title, text) }
                else silence(ctx, ev.optInt("min", 20), title, text)
            "exit" -> if (!Prayed.has(ctx, pk)) { exitNote(ctx, ev); if (spoken) say(ev.optString("xspk")) }
            // a plain reminder from the page (the last third of the night, fasting tomorrow)
            "note" -> post(ctx, builder(ctx, CH_REMIND, title, text).also { untilNow(it, ev.optLong("until")) }, id)
        }
        if (!speaking) done()
    }

    private fun ringerNormal(ctx: Context) = ctx.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    fun noteId(ev: JSONObject) = ev.optString("pk").ifEmpty { ev.optString("id") }.let { (it.hashCode() and 0x0FFFFFFF) or 0x10000000 }
    fun noteIdOf(pk: String) = (pk.hashCode() and 0x0FFFFFFF) or 0x10000000
    fun exitId(pk: String) = noteIdOf(pk) xor 0x20000000

    fun builder(ctx: Context, channel: String, title: String, text: String): Notification.Builder =
        Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))

    /** A broadcast for a notification button; the data URI keeps each prayer's buttons apart. */
    fun action(ctx: Context, act: String, pk: String, label: String, key: String, value: String): Notification.Action {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(act).setData(Uri.parse("ontime://$act/${Uri.encode(pk)}")).putExtra(key, value)
        val pi = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pi).build()
    }

    /** The adhan notification: the prayer, then the buttons "Stop" (while it plays), "Remind me before it ends", "I prayed". */
    fun prayerBuilder(ctx: Context, ev: JSONObject, channel: String, stop: PendingIntent?): Notification.Builder {
        val pk = ev.optString("pk"); val ar = Exits.ar(ev); val now = System.currentTimeMillis()
        val withEx = pk.isNotEmpty() && Exits.hasEx(ev)
        if (withEx && menuPk == pk) return laterMenu(ctx, ev, channel)
        val rem = if (withEx) Reminders.find(ctx, pk) else null
        val lat = if (withEx) Reminders.find(ctx, pk, "later") else null
        val text = when { lat != null && (rem == null || lat.optLong("at") < rem.optLong("at")) -> laterText(lat); rem != null -> confirmText(rem); else -> ev.optString("text") }
        val b = builder(ctx, channel, ev.optString("title"), text)
        // the buttons the user picked, in their order; Android shows three at most, and "Stop" takes one while the adhan plays
        val acts = ArrayList<Notification.Action>()
        if (stop != null) acts += Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", stop).build()
        for (bt in buttonsOf(ev)) when (bt) {
            "exit" -> if (withEx && rem == null && Exits.next(ev) != null)
                acts += action(ctx, AlarmReceiver.ACTION_REMIND, pk, if (ar) "ذكّرني قبل خروج الوقت" else "Remind me before the time ends", "ev", ev.toString())
            "pray" -> if (withEx) acts += action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk)
            "later" -> if (withEx && laterChoices(ev, now).isNotEmpty())
                acts += action(ctx, AlarmReceiver.ACTION_LATER_MENU, pk, if (ar) "ذكّرني بعد…" else "Remind me in…", "ev", ev.toString())
        }
        acts.take(3).forEach { b.addAction(it) }
        untilNow(b, untilOf(ev))
        return b
    }

    /** The user's buttons, in order: "exit" (before the time ends), "pray", "later" (remind me in…). */
    fun buttonsOf(ev: JSONObject): List<String> = ev.optString("btns", "exit,pray,later").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** The user's "remind me in" durations that still fall before the time ends. */
    fun laterChoices(ev: JSONObject, now: Long): List<Long> {
        val a = ev.optJSONArray("later"); val l = if (a == null) listOf(15L, 25L, 35L) else (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }
        val end = Exits.next(ev, now)?.at ?: Long.MAX_VALUE
        return l.distinct().sorted().filter { now + it * 60_000L <= end }.take(3)
    }

    /** The notification goes away by itself once its time is over. */
    private fun untilNow(b: Notification.Builder, until: Long) { val left = until - System.currentTimeMillis(); if (until > 0 && left > 0) b.setTimeoutAfter(left) }
    private fun untilOf(ev: JSONObject): Long = ev.optLong("until").takeIf { it > 0 }
        ?: ev.optJSONArray("ex")?.let { a -> if (a.length() > 0) a.optJSONObject(a.length() - 1)?.optLong("at") ?: 0L else 0L } ?: 0L

    /** "Remind me in…": the same notification, its buttons now the durations, with when the time ends. */
    private fun laterMenu(ctx: Context, ev: JSONObject, channel: String): Notification.Builder {
        val pk = ev.optString("pk"); val ar = Exits.ar(ev); val now = System.currentTimeMillis()
        val ex = Exits.next(ev, now)
        val text = (if (ar) "متى أذكّرك؟" else "When should I remind you?") + (if (ex != null) (if (ar) " يخرج ${ex.name} ${ex.clock}" else " ${ex.name} ends at ${ex.clock}") else "")
        val b = builder(ctx, channel, ev.optString("title"), text)
        for (m in laterChoices(ev, now)) {
            val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_LATER).setData(Uri.parse("ontime://later/${Uri.encode(pk)}/$m"))
                .putExtra("ev", ev.toString()).putExtra("mins", m)
            b.addAction(Notification.Action.Builder(null, Exits.mins(m, ev), PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
        }
        untilNow(b, untilOf(ev))
        return b
    }

    private fun laterText(r: JSONObject): String { val at = Exits.clock(r.optLong("at"), r); return if (Exits.ar(r)) "بنذكّرك $at" else "Reminder at $at" }

    /** "Remind me in…" pressed: the adhan stops (if it plays) and the notification shows the durations. */
    fun showLater(ctx: Context, ev: JSONObject) {
        val pk = ev.optString("pk"); menuPk = pk
        if (AdhanService.playingPk() == pk) AdhanService.stop(ctx)   // what it leaves behind is the notification with the choices
        else post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
    }

    /** A duration picked: a reminder then, unless it would ring within 10 min of "before the time ends" (the earlier one stays). */
    fun later(ctx: Context, ev: JSONObject, mins: Long) {
        val pk = ev.optString("pk"); menuPk = null
        val now = System.currentTimeMillis(); val at = now + mins * 60_000L
        val ex = Exits.next(ev, now)
        if (ex == null || at <= ex.at) {
            val rem = Reminders.find(ctx, pk)
            val clash = rem != null && kotlin.math.abs(at - rem.optLong("at")) < 10 * 60_000L
            if (!clash || at < rem!!.optLong("at")) {
                if (clash) Reminders.remove(ctx, pk, "x")
                Reminders.put(ctx, JSONObject(ev.toString()).put("at", at).put("kind", "later"))
                Scheduler.reschedule(ctx)
            }
        }
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    /** The "remind me in" reminder itself: the prayer, when its time ends, and the user's buttons again. */
    fun laterNote(ctx: Context, r: JSONObject) {
        val ar = Exits.ar(r); val now = System.currentTimeMillis(); val ex = Exits.next(r, now)
        val e = JSONObject(r.toString()).put("title", if (ar) "تذكير: صلاة ${r.optString("title")}" else "Reminder: ${r.optString("title")}")
            .put("text", if (ex != null) (if (ar) "يخرج ${ex.name} ${ex.clock}" else "${ex.name} ends at ${ex.clock}") else r.optString("text"))
        post(ctx, prayerBuilder(ctx, e, if (r.optString("rs", "ding") == "quiet") CH_SILENT else CH_LATER, null), noteIdOf(r.optString("pk")))
    }

    private fun confirmText(rem: JSONObject): String {
        val ex = Exits.next(rem); val at = Exits.clock(rem.optLong("at"), rem)
        return if (Exits.ar(rem)) "بنذكّرك $at قبل خروج ${ex?.name ?: "الوقت"}" else "Reminder at $at, before ${ex?.name ?: "the time"} ends"
    }

    /** "20 min left until the chosen time for Asr ends", with "I prayed" and "In 10 min". */
    fun exitNote(ctx: Context, r: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(r, now) ?: return
        val pk = r.optString("pk"); val ar = Exits.ar(r)
        val left = maxOf(1L, Math.round((ex.at - now) / 60_000.0))
        val title = if (ar) "باقي ${Exits.mins(left, r)} على خروج ${ex.name}" else "$left min until ${ex.name} ends"
        val b = builder(ctx, if (r.optString("rs", "ding") == "ding") CH_EXIT else CH_SILENT, title, if (ar) "ينتهي ${ex.clock}" else "Ends at ${ex.clock}")
        b.addAction(action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk))
        if (ex.at - now > 11 * 60_000L)
            b.addAction(action(ctx, AlarmReceiver.ACTION_SNOOZE, pk, if (ar) "بعد ${Exits.mins(10, r)}" else "In 10 min", "rem", r.toString()))
        untilNow(b, ex.at)
        post(ctx, b, exitId(pk))
    }

    /** "Remind me before it ends": from the adhan notification or from the app. */
    fun remind(ctx: Context, ev: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(ev, now) ?: return
        val pk = ev.optString("pk")
        val at = ex.at - ev.optInt("mins", 20) * 60_000L
        if (at <= now + 5_000) { exitNote(ctx, ev); return }
        Reminders.put(ctx, JSONObject(ev.toString()).put("at", at))
        Scheduler.reschedule(ctx)
        // swap the button for a confirmation
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else if (activeNote(ctx, noteIdOf(pk))) post(ctx, prayerBuilder(ctx, ev, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    /** Cancel a reminder from the app: the adhan notification gets its "Remind me" button back. */
    fun unremind(ctx: Context, pk: String) {
        val r = Reminders.find(ctx, pk)
        Reminders.remove(ctx, pk)
        Scheduler.reschedule(ctx)
        if (AdhanService.playingPk() == pk) AdhanService.refresh()
        else if (r != null && activeNote(ctx, noteIdOf(pk))) post(ctx, prayerBuilder(ctx, r, CH_AFTER, null), noteIdOf(pk))
        WidgetUpdater.refresh(ctx, "actions")
    }

    fun snooze(ctx: Context, r: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(r, now) ?: return
        Reminders.put(ctx, JSONObject(r.toString()).put("at", minOf(now + 10 * 60_000L, ex.at - 60_000L)))
        cancel(ctx, exitId(r.optString("pk")))
        Scheduler.reschedule(ctx)
    }

    fun prayed(ctx: Context, pk: String) {
        if (menuPk == pk) menuPk = null
        Prayed.add(ctx, pk)
        Reminders.remove(ctx, pk)
        if (AdhanService.playingPk() == pk) AdhanService.dismiss(ctx)
        cancel(ctx, noteIdOf(pk)); cancel(ctx, exitId(pk))
        Scheduler.reschedule(ctx)
        WidgetUpdater.refresh(ctx, "actions"); WidgetUpdater.refresh(ctx, "prayed")
    }

    private fun activeNote(ctx: Context, id: Int): Boolean =
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.activeNotifications?.any { it.id == id } ?: false }.getOrDefault(false)

    fun post(ctx: Context, b: Notification.Builder, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java)?.notify(id, b.build()) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context, id: Int) { ctx.getSystemService(NotificationManager::class.java)?.cancel(id) }

    fun notify(ctx: Context, channel: String, title: String, text: String, id: Int) = post(ctx, builder(ctx, channel, title, text), id)

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Vibrate mode needs no special permission; we only undo it if we set it and it is still on. */
    private fun silence(ctx: Context, minutes: Int, title: String, text: String) {
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        try { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) { return }
        Scheduler.prefs(ctx).edit().putBoolean("weSilenced", true).apply()
        notify(ctx, CH_REMIND, title, text, RESTORE_CODE)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, RESTORE_CODE, Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_RESTORE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        Scheduler.setExact(am, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    fun restoreRinger(ctx: Context) {
        val p = Scheduler.prefs(ctx)
        if (!p.getBoolean("weSilenced", false)) return
        p.edit().putBoolean("weSilenced", false).apply()
        val audio = ctx.getSystemService(AudioManager::class.java) ?: return
        if (audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            try { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
        }
        cancel(ctx, RESTORE_CODE)
    }

    /** A bundled recording by name: the adhan voices, or the spoken lines (spk_…). 0 if there is none. */
    fun rawId(ctx: Context, name: String): Int = raw(name).takeIf { it != 0 }
        ?: when {
            name.startsWith("spk_") -> ctx.resources.getIdentifier(name, "raw", ctx.packageName)
            // the other voices (0.14): adhan_<voice>, adhan_<voice>_fajr, adhan_tk1_<voice> (one takbir), adhan_takbir_<voice> (four)
            name.matches(Regex("[a-z0-9_]+")) -> ctx.resources.getIdentifier("adhan_$name", "raw", ctx.packageName)
            else -> 0
        }

    /** Bundled recordings (sources in android/SOUNDS.md). */
    private fun raw(name: String): Int = when (name) {
        "afasy" -> R.raw.adhan_afasy
        "afasy_fajr" -> R.raw.adhan_afasy_fajr
        "qatami" -> R.raw.adhan_qatami
        "takbir_afasy" -> R.raw.adhan_takbir_afasy
        "takbir_qatami" -> R.raw.adhan_takbir_qatami
        else -> 0
    }

    /** What to play: the user's file, a bundled recording, or the phone's notification sound. Second: is it a full recording. */
    fun soundUri(ctx: Context, sound: String, userFile: Boolean = true): Pair<Uri?, Boolean> {
        if (sound == "file" && userFile) Scheduler.prefs(ctx).getString("soundUri_file", null)?.let { return Uri.parse(it) to true }
        if (sound == "notify") return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) to false
        val id = rawId(ctx, sound).takeIf { it != 0 } ?: R.raw.adhan_afasy
        return Uri.parse("android.resource://${ctx.packageName}/$id") to true
    }

    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun preview(ctx: Context, sound: String) {
        stopPreview()
        if (sound == "none") return
        if (sound == "vibrate") {
            val v = ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1))
            return
        }
        val uri = soundUri(ctx, sound).first ?: return
        try {
            preview = MediaPlayer().apply {
                setOnErrorListener { _, _, _ -> stopPreview(); true }
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                setOnCompletionListener { stopPreview() }
                prepare(); start()
            }
            main.postDelayed({ stopPreview() }, 12_000)
        } catch (_: Exception) { stopPreview() }
    }

    fun stopPreview() {
        main.removeCallbacksAndMessages(null)
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
    }
}
