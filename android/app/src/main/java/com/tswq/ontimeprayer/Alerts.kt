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

    fun fire(ctx: Context, ev: JSONObject) {
        channels(ctx)
        val title = ev.optString("title"); val text = ev.optString("text"); val id = ev.optString("id").hashCode()
        val pk = ev.optString("pk")
        when (ev.optString("kind")) {
            "adhan" -> when (soundOf(ev)) {
                "none" -> {}
                "vibrate" -> post(ctx, prayerBuilder(ctx, ev, CH_VIBRATE, null), noteId(ev))
                "notify" -> notify(ctx, CH_REMIND, title, text, id)
                else -> {
                    val i = Intent(ctx, AdhanService::class.java).putExtra("ev", ev.toString())
                    try {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                    } catch (e: Exception) {
                        post(ctx, prayerBuilder(ctx, ev, CH_REMIND, null), noteId(ev))
                    }
                }
            }
            "pre", "iqama" -> notify(ctx, if (ev.optString("voice") == "vibrate") CH_VIBRATE else CH_REMIND, title, text, id)
            "silence" -> silence(ctx, ev.optInt("min", 20), title, text)
            "exit" -> if (!Prayed.has(ctx, pk)) exitNote(ctx, ev)
            "darura" -> if (!Prayed.has(ctx, pk)) {
                val b = builder(ctx, CH_EXIT, title, text)
                b.addAction(action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (Exits.ar(ev)) "صلّيت" else "I prayed", "pk", pk))
                post(ctx, b, exitId(pk))
            }
        }
    }

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
        val pk = ev.optString("pk"); val ar = Exits.ar(ev)
        val withEx = pk.isNotEmpty() && Exits.hasEx(ev)
        val rem = if (withEx) Reminders.find(ctx, pk) else null
        val b = builder(ctx, channel, ev.optString("title"), if (rem != null) confirmText(rem) else ev.optString("text"))
        if (stop != null) b.addAction(Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", stop).build())
        if (withEx && rem == null && Exits.next(ev) != null)
            b.addAction(action(ctx, AlarmReceiver.ACTION_REMIND, pk, if (ar) "ذكّرني قبل الخروج" else "Remind me before it ends", "ev", ev.toString()))
        if (withEx) b.addAction(action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk))
        return b
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
        val title = if (ar) "باقي ${Exits.digits(left.toString(), r)} د على خروج ${ex.name}" else "$left min until ${ex.name} ends"
        val b = builder(ctx, CH_EXIT, title, if (ar) "ينتهي ${ex.clock}" else "Ends at ${ex.clock}")
        b.addAction(action(ctx, AlarmReceiver.ACTION_PRAYED, pk, if (ar) "صلّيت" else "I prayed", "pk", pk))
        if (ex.at - now > 11 * 60_000L)
            b.addAction(action(ctx, AlarmReceiver.ACTION_SNOOZE, pk, if (ar) "بعد ${Exits.digits("10", r)} د" else "In 10 min", "rem", r.toString()))
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
    }

    fun snooze(ctx: Context, r: JSONObject) {
        val now = System.currentTimeMillis()
        val ex = Exits.next(r, now) ?: return
        Reminders.put(ctx, JSONObject(r.toString()).put("at", minOf(now + 10 * 60_000L, ex.at - 60_000L)))
        cancel(ctx, exitId(r.optString("pk")))
        Scheduler.reschedule(ctx)
    }

    fun prayed(ctx: Context, pk: String) {
        Prayed.add(ctx, pk)
        Reminders.remove(ctx, pk)
        if (AdhanService.playingPk() == pk) AdhanService.dismiss(ctx)
        cancel(ctx, noteIdOf(pk)); cancel(ctx, exitId(pk))
        Scheduler.reschedule(ctx)
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
        val id = raw(sound).takeIf { it != 0 } ?: R.raw.adhan_afasy
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
