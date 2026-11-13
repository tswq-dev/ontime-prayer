package com.tswq.ontimeprayer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Plays the adhan on the alarm stream (heard even in silent mode), with a stop button. */
class AdhanService : Service() {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        Alerts.channels(this)
        val voice = intent?.getStringExtra("voice") ?: "makkah"
        val ar = resources.configuration.locales[0].language == "ar"
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, Alerts.CH_ADHAN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(intent?.getStringExtra("title") ?: "")
            .setContentText(intent?.getStringExtra("text") ?: "")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(Alerts.openApp(this))
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, if (ar) "إيقاف" else "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTE_ID, n)

        player?.release(); player = null
        main.removeCallbacksAndMessages(null)
        val (uri, recorded) = Alerts.soundUri(this, voice)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(this@AdhanService, uri!!)
                setOnCompletionListener { stopSelf() }
                setOnErrorListener { _, _, _ -> stopSelf(); true }
                prepare(); start()
            }
        } catch (e: Exception) { stopSelf() }
        // A full recording ends by itself; a system sound is cut after half a minute.
        main.postDelayed({ stopSelf() }, if (recorded) 6 * 60_000L else 30_000L)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.tswq.ontimeprayer.STOP_ADHAN"
        const val NOTE_ID = 7
    }
}
