package com.tswq.ontimeprayer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Plays a short spoken line ("the Asr adhan is near") on the notification stream, so it stays quiet
 * when the phone is on silent or vibrate. The lines are a couple of seconds long; [done] runs when it ends.
 */
object Speaker {
    private val main = Handler(Looper.getMainLooper())

    fun play(ctx: Context, name: String, done: () -> Unit) {
        // a tone the user picked from the phone's own (tone_rem: the reminders), else a bundled line
        val tone = name.startsWith("tone_")
        val uri = if (tone) Alerts.soundUri(ctx, name).first else Alerts.rawId(ctx, name).takeIf { it != 0 }?.let { Uri.parse("android.resource://${ctx.packageName}/$it") }
        if (uri == null) { done(); return }
        var finished = false
        var mp: MediaPlayer? = null
        fun end() {
            if (finished) return
            finished = true
            mp?.let { runCatching { it.release() } }
            done()
        }
        try {
            mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(ctx, uri)
                setOnCompletionListener { main.post { end() } }
                setOnErrorListener { _, _, _ -> main.post { end() }; true }
                prepare(); start()
            }
            main.postDelayed({ end() }, if (tone) 20_000 else 8_000)
        } catch (e: Exception) { end() }
    }
}
