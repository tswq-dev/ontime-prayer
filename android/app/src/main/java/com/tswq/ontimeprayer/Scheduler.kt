package com.tswq.ontimeprayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The page sends 30 days of alert events. Only the next [BATCH] are handed to AlarmManager
 * (it caps alarms per app); every time one fires, the next batch is set from the stored list.
 */
object Scheduler {
    private const val PREFS = "ontime"
    private const val BATCH = 24
    const val ACTION_FIRE = "com.tswq.ontimeprayer.FIRE"
    const val ACTION_RESTORE = "com.tswq.ontimeprayer.RESTORE"

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, json: String) {
        JSONArray(json) // validate
        prefs(ctx).edit().putString("events", json).apply()
        reschedule(ctx)
    }

    fun markFired(ctx: Context, id: String, at: Long) {
        val p = prefs(ctx)
        val fired = JSONObject(p.getString("fired", "{}") ?: "{}")
        fired.put(id, at)
        val cutoff = System.currentTimeMillis() - 2 * 86_400_000L
        val keys = fired.keys().asSequence().toList()
        for (k in keys) if (fired.optLong(k) < cutoff) fired.remove(k)
        p.edit().putString("fired", fired.toString()).apply()
    }

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val p = prefs(ctx)
        val arr = JSONArray(p.getString("events", "[]") ?: "[]")
        val fired = JSONObject(p.getString("fired", "{}") ?: "{}")
        val now = System.currentTimeMillis()
        val next = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optLong("at") > now - 60_000 && !fired.has(it.optString("id")) }
            .sortedBy { it.optLong("at") }
            .take(BATCH)
        for (i in 0 until BATCH) {
            PendingIntent.getBroadcast(ctx, i, fireIntent(ctx), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { am.cancel(it); it.cancel() }
        }
        next.forEachIndexed { i, ev ->
            val pi = PendingIntent.getBroadcast(
                ctx, i, fireIntent(ctx).putExtra("ev", ev.toString()),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setExact(am, maxOf(ev.optLong("at"), now + 500), pi)
        }
    }

    fun setExact(am: AlarmManager, at: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun fireIntent(ctx: Context) = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_FIRE)
}
