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

    /** "25 د" / "25 min": the durations on notification buttons, short so Android does not cut them. */
    fun short(n: Long, r: JSONObject): String = if (!ar(r)) "$n min" else digits("$n", r) + " د"

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
        mark(ctx, pk, true)
    }

    /** Undo a mark (a second tap on a done circle in «صلواتي اليوم»). */
    fun remove(ctx: Context, pk: String) {
        val p = Scheduler.prefs(ctx); val o = JSONObject(p.getString("prayed", "{}") ?: "{}")
        o.remove(pk); p.edit().putString("prayed", o.toString()).apply()
        mark(ctx, pk, false)
    }

    // Each day's five prayers as bits (Fajr = 1 … Isha = 16), kept for a year: the widget's circles and the days in a row.
    private fun bit(pk: String): Pair<String, Int>? {
        val i = pk.indexOf(':'); if (i < 0) return null
        val b = WData.PRAY.indexOf(pk.substring(0, i)); return if (b < 0) null else pk.substring(i + 1) to (1 shl b)
    }
    private fun days(ctx: Context) = runCatching { JSONObject(Scheduler.prefs(ctx).getString("pdays", "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun mark(ctx: Context, pk: String, on: Boolean) {
        val (date, b) = bit(pk) ?: return
        val o = days(ctx); val m = o.optInt(date)
        o.put(date, if (on) m or b else m and b.inv())
        val oldest = WData.dateKey(System.currentTimeMillis() - 400 * WData.DAY)
        for (k in o.keys().asSequence().toList()) if (k < oldest || o.optInt(k) == 0) o.remove(k)
        Scheduler.prefs(ctx).edit().putString("pdays", o.toString()).apply()
    }
    /** The prayers marked on a day (marks from before 0.14 are read from the short list too). */
    fun mask(ctx: Context, date: String): Int {
        var m = days(ctx).optInt(date)
        val o = runCatching { JSONObject(Scheduler.prefs(ctx).getString("prayed", "{}") ?: "{}") }.getOrDefault(JSONObject())
        for (k in o.keys()) bit(k)?.let { (d, b) -> if (d == date) m = m or b }
        return m
    }
    /** Days in a row with all five marked, ending on [date] (counted once all five are in) or the day before. */
    fun streak(ctx: Context, dayMs: Long): Int {
        var t = dayMs; var n = 0
        if (mask(ctx, WData.dateKey(t)) != 31) t -= WData.DAY
        while (n < 400 && mask(ctx, WData.dateKey(t)) == 31) { n++; t -= WData.DAY }
        return n
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
