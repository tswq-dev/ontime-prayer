package com.tswq.ontimeprayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        fun json(key: String) = intent.getStringExtra(key)?.let { runCatching { JSONObject(it) }.getOrNull() }
        when (intent.action) {
            Scheduler.ACTION_RESTORE -> Alerts.restoreRinger(ctx)
            Scheduler.ACTION_FIRE -> {
                val ev = json("ev")
                if (ev != null) Scheduler.markFired(ctx, ev.optString("id"), ev.optLong("at"))
                Scheduler.reschedule(ctx)
                // Skip alerts delivered very late (phone was off): they would only confuse.
                if (ev != null && System.currentTimeMillis() - ev.optLong("at") < 10 * 60_000) {
                    val pr = goAsync()   // keeps the process alive while a spoken line plays
                    Alerts.fire(ctx, ev) { pr.finish() }
                }
            }
            Scheduler.ACTION_REMINDER -> {
                val r = json("rem")
                if (r != null) Reminders.remove(ctx, r.optString("pk"))
                Scheduler.reschedule(ctx)
                if (r != null && !Prayed.has(ctx, r.optString("pk"))) {
                    Alerts.channels(ctx); Alerts.exitNote(ctx, r)
                    if (r.optString("rs", "ding") == "speak") { val pr = goAsync(); Speaker.play(ctx, r.optString("xspk")) { pr.finish() } }
                }
            }
            ACTION_REMIND -> json("ev")?.let { Alerts.channels(ctx); Alerts.remind(ctx, it) }
            ACTION_SNOOZE -> json("rem")?.let { Alerts.snooze(ctx, it) }
            ACTION_PRAYED -> intent.getStringExtra("pk")?.let { Alerts.prayed(ctx, it) }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.tswq.ontimeprayer.REMIND"
        const val ACTION_SNOOZE = "com.tswq.ontimeprayer.SNOOZE"
        const val ACTION_PRAYED = "com.tswq.ontimeprayer.PRAYED"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.reschedule(ctx)
        runCatching { WidgetUpdater.updateAll(ctx) }
    }
}
