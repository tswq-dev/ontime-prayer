package com.tswq.ontimeprayer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    // stopping it with the phone's buttons: power, volume, turning the phone face down
    private var screenRx: BroadcastReceiver? = null
    private var volRx: BroadcastReceiver? = null
    private var session: MediaSession? = null
    private var flip: SensorEventListener? = null
    private var awake: PowerManager.WakeLock? = null
    private var startedAt = 0L

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
        listen(e.optString("stop", "pvf"))
        return START_NOT_STICKY
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, AdhanService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** The adhan screen may open (full screen when the phone is locked or its screen is off; a pop-up otherwise). */
    private fun fullScreenOk(): Boolean = ev?.optInt("fs", 1) == 1 &&
        (Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true)

    private fun note(): Notification {
        val b = Alerts.prayerBuilder(this, ev!!, Alerts.CH_ADHAN, pi(ACTION_STOP, 1))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(false)
            .setDeleteIntent(pi(ACTION_DISMISS, 2))
        if (ev?.optInt("fs", 1) == 1) b.setFullScreenIntent(PendingIntent.getActivity(this, 3,
            Intent(this, AdhanActivity::class.java).putExtra("ev", ev.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE), true)
        return b.build()
    }

    private fun listen(ways: String) {
        unlisten(); startedAt = System.currentTimeMillis()
        val pm = getSystemService(PowerManager::class.java)
        val wasOn = pm?.isInteractive == true
        if ('p' in ways) {
            // like a call: the power button stops it. With the screen on, keep it on while the adhan plays,
            // so it is the button that turns it off, not the screen timeout.
            if (wasOn) runCatching { @Suppress("DEPRECATION") awake = pm?.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ontime:adhan")?.apply { acquire(7 * 60_000L) } }
            val viaScreen = !wasOn && !fullScreenOk()   // screen off and no adhan screen: pressing power turns it on
            screenRx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (System.currentTimeMillis() - startedAt < 2_500) return
                    if (i.action == Intent.ACTION_SCREEN_OFF || (viaScreen && i.action == Intent.ACTION_SCREEN_ON)) stopSelf()
                }
            }
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenRx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenRx, f)
        }
        if ('v' in ways) {
            // a playing session that takes the volume buttons (even with the screen off on most phones)…
            session = runCatching { MediaSession(this, "ontime-adhan").apply {
                setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                    override fun onAdjustVolume(direction: Int) { main.post { stopSelf() } }
                })
                setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                isActive = true
            } }.getOrNull()
            // …and, where the phone keeps the buttons for itself, any change of volume
            volRx = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (System.currentTimeMillis() - startedAt > 1_500) stopSelf() }
            }
            val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(volRx, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(volRx, f)
        }
        if ('f' in ways) {
            // turned face down (after being face up at some point while it plays), held for half a second
            val sm = getSystemService(SensorManager::class.java); val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sm != null && acc != null) {
                var up = false; var downSince = 0L
                flip = object : SensorEventListener {
                    override fun onSensorChanged(se: SensorEvent) {
                        val z = se.values[2]
                        if (z > -3f) { up = true; downSince = 0L }
                        else if (up && z < -8f) { if (downSince == 0L) downSince = System.currentTimeMillis() else if (System.currentTimeMillis() - downSince > 500) main.post { stopSelf() } }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.registerListener(flip, acc, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun unlisten() {
        screenRx?.let { runCatching { unregisterReceiver(it) } }; screenRx = null
        volRx?.let { runCatching { unregisterReceiver(it) } }; volRx = null
        session?.let { runCatching { it.isActive = false; it.release() } }; session = null
        flip?.let { l -> runCatching { getSystemService(SensorManager::class.java)?.unregisterListener(l) } }; flip = null
        awake?.let { runCatching { if (it.isHeld) it.release() } }; awake = null
    }

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
        unlisten()
        AdhanActivity.close()
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

        /** "Stop" from the adhan screen: like the notification's button (the quiet note with its buttons stays). */
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, AdhanService::class.java).setAction(ACTION_STOP)) }

        /** "I prayed" while it plays: stop, and leave nothing behind. */
        fun dismiss(ctx: Context) {
            instance?.noAfter = true
            ctx.stopService(Intent(ctx, AdhanService::class.java))
        }
    }
}
