package com.tswq.ontimeprayer

import android.app.AlarmManager
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
    const val CH_TONE = "tone"
    const val CH_VIBRATE = "vibrate"
    const val CH_REMIND = "remind"
    private const val RESTORE_CODE = 900

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ar = ctx.resources.configuration.locales[0].language == "ar"
        fun t(a: String, e: String) = if (ar) a else e
        // The adhan itself is played by AdhanService, so its channel stays silent.
        nm.createNotificationChannel(NotificationChannel(CH_ADHAN, t("الأذان", "Adhan"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_TONE, t("نغمة هادئة", "Soft tone"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_VIBRATE, t("اهتزاز فقط", "Vibrate only"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 400, 250, 400)
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, t("التذكير والإقامة", "Reminders and iqama"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun fire(ctx: Context, ev: JSONObject) {
        channels(ctx)
        val title = ev.optString("title"); val text = ev.optString("text"); val voice = ev.optString("voice")
        when (ev.optString("kind")) {
            "adhan" -> when (voice) {
                "makkah", "madinah", "takbir" -> {
                    val i = Intent(ctx, AdhanService::class.java).putExtra("voice", voice).putExtra("title", title).putExtra("text", text)
                    try {
                        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                    } catch (e: Exception) {
                        notify(ctx, CH_TONE, title, text, ev.optString("id").hashCode())
                    }
                }
                "vibrate" -> notify(ctx, CH_VIBRATE, title, text, ev.optString("id").hashCode())
                "none" -> {}
                else -> notify(ctx, CH_TONE, title, text, ev.optString("id").hashCode())
            }
            "pre", "iqama" -> notify(ctx, if (voice == "vibrate") CH_VIBRATE else CH_REMIND, title, text, ev.optString("id").hashCode())
            "silence" -> silence(ctx, ev.optInt("min", 20), title, text)
        }
    }

    fun notify(ctx: Context, channel: String, title: String, text: String, id: Int) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val b = android.app.Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
        try { nm.notify(id, b.build()) } catch (_: SecurityException) {}
    }

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
        ctx.getSystemService(NotificationManager::class.java)?.cancel(RESTORE_CODE)
    }

    /** Sound for a voice: a bundled recording (res/raw/adhan_<voice>) if present, else a system sound. */
    fun soundUri(ctx: Context, voice: String): Pair<Uri?, Boolean> {
        val id = ctx.resources.getIdentifier("adhan_$voice", "raw", ctx.packageName)
        if (id != 0) return Uri.parse("android.resource://${ctx.packageName}/$id") to true
        val type = if (voice == "tone") RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM
        return (RingtoneManager.getDefaultUri(type) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)) to false
    }

    private var preview: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun preview(ctx: Context, voice: String) {
        stopPreview()
        if (voice == "none") return
        if (voice == "vibrate") {
            val v = ctx.getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 250, 400), -1))
            return
        }
        val uri = soundUri(ctx, voice).first ?: return
        try {
            preview = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                setOnCompletionListener { stopPreview() }
                prepare(); start()
            }
            main.postDelayed({ stopPreview() }, 8000)
        } catch (_: Exception) { stopPreview() }
    }

    fun stopPreview() {
        main.removeCallbacksAndMessages(null)
        preview?.let { runCatching { it.stop() }; it.release() }
        preview = null
    }
}
