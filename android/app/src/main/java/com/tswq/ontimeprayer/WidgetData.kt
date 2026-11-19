package com.tswq.ontimeprayer

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * What the widgets know, pushed by the page (the times come from the formula there): about a month of days with
 * every prayer time, their end times, the Hijri date, plus the city, language, digits and clock format.
 * Widgets never compute prayer times themselves.
 */
class WData(val o: JSONObject) {
    val ar = o.optString("lang", "ar") != "en"
    val arabDigits = o.optInt("dg") == 1
    val h12 = o.optInt("f12", 1) == 1
    val city: String = o.optString("city")
    private val names = o.optJSONObject("names") ?: JSONObject()
    private val iq = o.optJSONObject("iq") ?: JSONObject()
    private val days: List<JSONObject> = o.optJSONArray("days").let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) } }

    fun name(k: String): String = names.optString(k, k)
    fun iqMin(k: String): Int = iq.optInt(k, 20)

    /** The day (Saudi date) that contains [now]; null when the pushed month has run out. */
    fun day(now: Long, offset: Int = 0): JSONObject? {
        val key = dateKey(now + offset * DAY)
        return days.firstOrNull { it.optString("d") == key }
    }

    val valid get() = days.isNotEmpty()

    /** A prayer time; [iqAt] is set while we wait for its iqama (the countdown then runs to it). */
    data class Ev(val k: String, val at: Long, val iqAt: Long = 0L) { val target get() = if (iqAt > 0) iqAt else at }
    /** What the widgets are about: after the adhan, until the iqama (if that countdown is on), the same prayer; else the next one. */
    fun hero(now: Long): Ev? {
        if (o.optInt("iqc", 1) == 1) prev(now)?.let { p -> val iq = p.at + iqMin(p.k) * 60_000L; if (now < iq) return Ev(p.k, p.at, iq) }
        return next(now)
    }

    /** The next of the five prayers after [now], and the one before it. */
    fun next(now: Long): Ev? {
        for (o in 0..1) { val d = day(now, o) ?: continue; for (k in PRAY) { val t = d.optLong(k); if (t > now) return Ev(k, t) } }
        return null
    }
    fun prev(now: Long): Ev? {
        for (o in 0 downTo -1) { val d = day(now, o) ?: continue; for (k in PRAY.reversed()) { val t = d.optLong(k); if (t in 1..now) return Ev(k, t) } }
        return null
    }

    data class Win(val k: String, val start: Long, val end: Long, val first: Long, val names: List<String>, val ex: JSONArray)

    /** The prayer whose time we are in now (Duha included), with its end(s) as the page computed them. */
    fun current(now: Long): Win? {
        var best: Win? = null
        for (o in -1..0) {
            val d = day(now, o) ?: continue
            val exAll = d.optJSONObject("ex") ?: continue
            for (k in EXK) {
                val start = d.optLong(k); val ex = exAll.optJSONArray(k) ?: continue
                if (ex.length() == 0 || start <= 0) continue
                val end = ex.getJSONObject(ex.length() - 1).optLong("at")
                if (now in start until end && (best == null || start > best.start))
                    best = Win(k, start, end, ex.getJSONObject(0).optLong("at"), (0 until ex.length()).map { ex.getJSONObject(it).optString("name") }, ex)
            }
        }
        return best
    }

    fun digits(s: String) = if (arabDigits) s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("") else s

    /** "5:39" (12 or 24 hours), and its AM/PM mark separately. */
    fun clock(ms: Long): String {
        val c = cal(ms); var h = c.get(Calendar.HOUR_OF_DAY)
        if (h12) h = if (h % 12 == 0) 12 else h % 12
        return digits("$h:" + c.get(Calendar.MINUTE).toString().padStart(2, '0'))
    }
    fun ampm(ms: Long): String = if (!h12) "" else if (cal(ms).get(Calendar.HOUR_OF_DAY) < 12) (if (ar) "ص" else "AM") else (if (ar) "م" else "PM")

    /** "1 س 24 د" / "1 h 24 min", rounded up to the minute. */
    fun dur(ms: Long): String {
        val m = maxOf(0L, (ms + 59_999) / 60_000); val h = m / 60; val mi = m % 60
        return digits(if (ar) (if (h > 0) "$h س $mi د" else "$mi د") else (if (h > 0) "$h h $mi min" else "$mi min"))
    }
    fun inTxt(ms: Long) = (if (ar) "بعد " else "in ") + dur(ms)
    fun inTxt(e: Ev, now: Long) = if (e.iqAt > 0) (if (ar) "الإقامة بعد " else "Iqama in ") + dur(e.iqAt - now) else inTxt(e.at - now)
    fun leftTxt(ms: Long) = (if (ar) "باقي " else "") + dur(ms) + (if (ar) "" else " left")

    companion object {
        const val DAY = 86_400_000L
        val PRAY = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
        val SIX = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
        val EXK = listOf("fajr", "duha", "dhuhr", "asr", "maghrib", "isha")
        private val KSA = TimeZone.getTimeZone("GMT+03:00")
        fun cal(ms: Long): Calendar = Calendar.getInstance(KSA).apply { timeInMillis = ms }
        // Built by hand: String.format follows the phone's language, and in Arabic it writes ٢٠٢٦ (the days never matched).
        fun dateKey(ms: Long): String { val c = cal(ms); fun p(n: Int) = n.toString().padStart(2, '0')
            return c.get(Calendar.YEAR).toString() + "-" + p(c.get(Calendar.MONTH) + 1) + "-" + p(c.get(Calendar.DAY_OF_MONTH)) }
        fun minuteOfDay(ms: Long): Int { val c = cal(ms); return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) }

        fun load(prefs: android.content.SharedPreferences): WData? =
            prefs.getString("widgetData", null)?.let { runCatching { WData(JSONObject(it)) }.getOrNull() }
    }
}

/**
 * One widget's own look, chosen in the app: background (live sky, light, green, clear, the phone's colours),
 * the font of the prayer name, what it shows, and what a tap opens.
 */
data class WStyle(val bg: String, val font: String, val rows: Set<String>, val tap: String = "app") {
    fun has(k: String) = k in rows
    fun toJson(): JSONObject = JSONObject().put("v", 2).put("bg", bg).put("font", font).put("rows", JSONArray(rows.toList())).put("tap", tap)
    companion object {
        /** What each widget can show; the first set is what it shows until the user changes it. */
        val SHOW: Map<String, Pair<List<String>, Set<String>>> = mapOf(
            "pano" to (listOf("count", "time", "hijri", "exit", "labels", "sky") to setOf("count", "time", "labels", "sky")),
            "window" to (listOf("count", "time", "hijri", "sky") to setOf("count", "sky")),
            "ring" to (listOf("count", "time", "prev") to setOf("count", "prev")),
            "orbit" to (listOf("count", "time", "hijri", "exit") to setOf("count", "time")),
            "script" to (listOf("count", "time", "hijri", "exit") to setOf("count", "time")),
            "table" to (listOf("city", "date", "count", "times", "exit", "iqama") to setOf("times", "exit")),
            "clock" to (listOf("count", "time") to setOf("count", "time")),
            "moon" to (listOf("wd", "next") to setOf("wd", "next")),
            "ribbon" to (listOf("count", "labels") to setOf("count", "labels")),
            "exit" to (listOf("time", "bar") to setOf("time", "bar")),
            "actions" to (listOf("count", "time") to setOf("count", "time")),
            "line" to (listOf("count", "time", "hijri") to setOf("count", "time")))
        val BGS = setOf("sky", "light", "dark", "clear", "dyn")

        /** Until the user picks, a widget uses the name font chosen for the app's own screen. */
        fun defaultFor(type: String, font: String? = null) = WStyle(if (type == "script" || type == "line") "clear" else "sky",
            font?.takeIf { it in setOf("kufi", "ruqaa", "naskh") } ?: "kufi", SHOW[type]?.second ?: emptySet())
        fun from(type: String, j: JSONObject?, font: String? = null): WStyle {
            val d = defaultFor(type, font); if (j == null) return d
            val r = j.optJSONArray("rows")
            // looks saved before "what it shows" existed keep the defaults (only the table had rows then)
            val rows = if (r == null || (j.optInt("v") < 2 && type != "table")) d.rows else (0 until r.length()).map { r.optString(it) }.toSet()
            return WStyle(j.optString("bg", d.bg).takeIf { it in BGS } ?: d.bg, j.optString("font", d.font), rows, j.optString("tap", "app"))
        }
    }
}
