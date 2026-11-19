package com.tswq.ontimeprayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * End of prayer times. The page sends, with each prayer, the moments its time ends ("ex": the chosen time
 * for Asr and Isha first, then the end), already named and formatted. This keeps the "I prayed" marks and
 * the reminders the user asked for, so they survive a restart.
 */
object Exits {
    data class Ex(val at: Long, val name: String, val clock: String)

    /** The first end still ahead (at least half a minute away). */
    fun next(r: JSONObject, now: Long = System.currentTimeMillis()): Ex? {
        val a = r.optJSONArray("ex") ?: return null
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optLong("at") > now + 30_000) return Ex(o.optLong("at"), o.optString("name"), o.optString("clock"))
        }
        return null
    }

    fun hasEx(r: JSONObject) = (r.optJSONArray("ex")?.length() ?: 0) > 0

    fun ar(r: JSONObject) = r.optString("lang", "ar") != "en"

    /** Minutes in words: «دقيقة»، «دقيقتين»، «5 دقائق»، «15 دقيقة» / "1 min", "15 min". */
    fun mins(n: Long, r: JSONObject): String = if (!ar(r)) "$n min" else when {
        n == 1L -> "دقيقة"; n == 2L -> "دقيقتين"; n in 3..10 -> digits("$n", r) + " دقائق"; else -> digits("$n", r) + " دقيقة" }

    fun digits(s: String, r: JSONObject): String =
        if (r.optInt("dg") == 1) s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("") else s

    /** Clock in Saudi time, like the page: 12 or 24 hours, Latin or Arabic digits. */
    fun clock(ms: Long, r: JSONObject): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("GMT+03:00")).apply { timeInMillis = ms }
        var h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        var suffix = ""
        if (r.optInt("f12", 1) == 1) {
            suffix = if (h < 12) (if (ar(r)) " ص" else " AM") else (if (ar(r)) " م" else " PM")
            h = if (h % 12 == 0) 12 else h % 12
        }
        return digits("$h:" + m.toString().padStart(2, '0'), r) + suffix
    }
}

object Prayed {
    fun has(ctx: Context, pk: String): Boolean =
        pk.isNotEmpty() && JSONObject(Scheduler.prefs(ctx).getString("prayed", "{}") ?: "{}").has(pk)

    fun add(ctx: Context, pk: String) {
        if (pk.isEmpty()) return
        val p = Scheduler.prefs(ctx)
        val o = JSONObject(p.getString("prayed", "{}") ?: "{}")
        val now = System.currentTimeMillis()
        o.put(pk, now)
        for (k in o.keys().asSequence().toList()) if (o.optLong(k) < now - 3 * 86_400_000L) o.remove(k)
        p.edit().putString("prayed", o.toString()).apply()
    }
}

object Reminders {
    fun all(ctx: Context): List<JSONObject> {
        val a = runCatching { JSONArray(Scheduler.prefs(ctx).getString("reminders", "[]") ?: "[]") }.getOrNull() ?: JSONArray()
        val now = System.currentTimeMillis()
        // drop reminders whose prayer time is already over
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { Exits.next(it, now - 30_000) != null }
    }

    private fun save(ctx: Context, l: List<JSONObject>) {
        val a = JSONArray(); l.sortedBy { it.optLong("at") }.take(Scheduler.REM_SLOTS).forEach { a.put(it) }
        Scheduler.prefs(ctx).edit().putString("reminders", a.toString()).apply()
    }

    /** "x": before the time ends (the default); "later": "remind me in 15 min". */
    fun kindOf(r: JSONObject): String = r.optString("kind", "x")

    fun find(ctx: Context, pk: String, kind: String = "x") = all(ctx).firstOrNull { it.optString("pk") == pk && kindOf(it) == kind }

    fun put(ctx: Context, r: JSONObject) = save(ctx, all(ctx).filter { !(it.optString("pk") == r.optString("pk") && kindOf(it) == kindOf(r)) } + r)

    /** Every reminder of this prayer ("I prayed"). */
    fun remove(ctx: Context, pk: String) = save(ctx, all(ctx).filter { it.optString("pk") != pk })

    fun remove(ctx: Context, pk: String, kind: String) = save(ctx, all(ctx).filter { !(it.optString("pk") == pk && kindOf(it) == kind) })
}
