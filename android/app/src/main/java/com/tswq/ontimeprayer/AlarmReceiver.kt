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
                if (r != null) Reminders.remove(ctx, r.optString("pk"), Reminders.kindOf(r))
                Scheduler.reschedule(ctx)
                if (r != null && !Prayed.has(ctx, r.optString("pk"))) {
                    Alerts.channels(ctx)
                    if (Reminders.kindOf(r) == "later") Alerts.laterNote(ctx, r)
                    else {
                        Alerts.exitNote(ctx, r)
                        if (r.optString("rs", "ding") == "speak") { val pr = goAsync(); Speaker.play(ctx, r.optString("xspk")) { pr.finish() } }
                    }
                }
            }
            ACTION_REMIND -> json("ev")?.let { Alerts.channels(ctx); Alerts.remind(ctx, it) }
            ACTION_SNOOZE -> json("rem")?.let { Alerts.snooze(ctx, it) }
            ACTION_LATER_MENU -> json("ev")?.let { Alerts.channels(ctx); Alerts.showLater(ctx, it) }
            ACTION_LATER -> json("ev")?.let { Alerts.later(ctx, it, intent.getLongExtra("mins", 15)) }
            ACTION_BEFORE_MENU -> json("ev")?.let { Alerts.channels(ctx); Alerts.showBefore(ctx, it) }
            ACTION_BEFORE -> json("ev")?.let { Alerts.channels(ctx); Alerts.before(ctx, it, intent.getLongExtra("mins", 20)) }
            ACTION_PRAYED -> intent.getStringExtra("pk")?.let { Alerts.prayed(ctx, it) }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.tswq.ontimeprayer.REMIND"
        const val ACTION_SNOOZE = "com.tswq.ontimeprayer.SNOOZE"
        const val ACTION_PRAYED = "com.tswq.ontimeprayer.PRAYED"
        const val ACTION_LATER_MENU = "com.tswq.ontimeprayer.LATER_MENU"
        const val ACTION_LATER = "com.tswq.ontimeprayer.LATER"
        const val ACTION_BEFORE_MENU = "com.tswq.ontimeprayer.BEFORE_MENU"
        const val ACTION_BEFORE = "com.tswq.ontimeprayer.BEFORE"
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.reschedule(ctx)
        runCatching { WidgetUpdater.updateAll(ctx) }
    }
}
