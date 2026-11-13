package com.tswq.ontimeprayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_RESTORE -> Alerts.restoreRinger(ctx)
            Scheduler.ACTION_FIRE -> {
                val ev = intent.getStringExtra("ev")?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (ev != null) {
                    Scheduler.markFired(ctx, ev.optString("id"), ev.optLong("at"))
                    // Skip alerts delivered very late (phone was off): they would only confuse.
                    if (System.currentTimeMillis() - ev.optLong("at") < 10 * 60_000) Alerts.fire(ctx, ev)
                }
                Scheduler.reschedule(ctx)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Scheduler.reschedule(ctx)
    }
}
