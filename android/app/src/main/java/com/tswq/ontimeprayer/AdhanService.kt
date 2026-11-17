package com.tswq.ontimeprayer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject

/**
 * Plays the adhan on the alarm stream (heard even in silent mode). Its notification has "Stop",
 * "Remind me before it ends" and "I prayed"; when the sound ends or is stopped, the notification
 * stays quietly with the last two, unless it was swiped away or "I prayed" was pressed.
 */
class AdhanService : Service() {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var ev: JSONObject? = null
    private var noAfter = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_DISMISS -> { noAfter = true; stopSelf(); return START_NOT_STICKY }
        }
        Alerts.channels(this)
        val e = intent?.getStringExtra("ev")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (e == null) { stopSelf(); return START_NOT_STICKY }
        // A new adhan while another plays: keep the earlier one's notification with its buttons.
        ev?.let { old -> if (old.optString("pk") != e.optString("pk")) Alerts.post(this, Alerts.prayerBuilder(this, old, Alerts.CH_AFTER, null), Alerts.noteId(old)) }
        ev = e; instance = this
        val n = note()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTE_ID, n)

        player?.release(); player = null
        main.removeCallbacksAndMessages(null)
        val sound = Alerts.soundOf(e)
        // The user's file first; if it can no longer be read (deleted, moved), fall back to the built-in voice.
        var recorded = play(Alerts.soundUri(this, sound))
        if (recorded == null) recorded = play(Alerts.soundUri(this, sound, userFile = false))
        if (recorded == null) { stopSelf(); return START_NOT_STICKY }
        main.postDelayed({ stopSelf() }, if (recorded) 6 * 60_000L else 30_000L)
        return START_NOT_STICKY
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, AdhanService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun note(): Notification = Alerts.prayerBuilder(this, ev!!, Alerts.CH_ADHAN, pi(ACTION_STOP, 1))
        .setCategory(Notification.CATEGORY_ALARM)
        .setAutoCancel(false)
        .setDeleteIntent(pi(ACTION_DISMISS, 2))
        .build()

    private fun play(src: Pair<android.net.Uri?, Boolean>): Boolean? {
        val uri = src.first ?: return null
        player?.release(); player = null
        return try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AdhanService, uri)
                setOnCompletionListener { stopSelf() }
                setOnErrorListener { _, _, _ -> stopSelf(); true }
                prepare(); start()
            }
            src.second
        } catch (e: Exception) { player?.release(); player = null; null }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        if (instance === this) instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val e = ev
        if (e != null && !noAfter && Exits.hasEx(e) && !Prayed.has(this, e.optString("pk")))
            Alerts.post(this, Alerts.prayerBuilder(this, e, Alerts.CH_AFTER, null), Alerts.noteId(e))
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.tswq.ontimeprayer.STOP_ADHAN"
        const val ACTION_DISMISS = "com.tswq.ontimeprayer.DISMISS_ADHAN"
        const val NOTE_ID = 7
        private var instance: AdhanService? = null

        fun playingPk(): String? = instance?.ev?.optString("pk")

        /** Redraw the playing notification (after "Remind me" it shows the confirmation). */
        fun refresh() {
            val s = instance ?: return
            runCatching { s.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, s.note()) }
        }

        /** "I prayed" while it plays: stop, and leave nothing behind. */
        fun dismiss(ctx: Context) {
            instance?.noAfter = true
            ctx.stopService(Intent(ctx, AdhanService::class.java))
        }
    }
}
