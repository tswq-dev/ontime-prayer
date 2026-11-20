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

    // ---------- for the four extra widgets (0.14) ----------
    /** Weekday of a pushed day, 0 = Sunday … 6 = Saturday. */
    fun wdi(d: JSONObject): Int = if (d.has("wdi")) d.optInt("wdi") else runCatching {
        val p = d.optString("d").split("-").map { it.toInt() }
        Calendar.getInstance(KSA).apply { clear(); set(p[0], p[1] - 1, p[2], 12, 0) }.get(Calendar.DAY_OF_WEEK) - 1 }.getOrDefault(0)
    /** The Hijri day and month number (0 when the page did not send it). */
    fun hd(d: JSONObject): Int = d.optString("hd").toIntOrNull() ?: 0
    fun hmn(d: JSONObject): Int = d.optInt("hmn", 0)
    /** Minutes of imsak before Fajr (Ramadan widget). */
    val imsak get() = o.optInt("ims", 10)
    /** Tonight, from the maghrib that begins it to the fajr that ends it (before fajr: the night we are in). */
    fun night(now: Long): Pair<Long, Long>? {
        val d0 = day(now) ?: return null
        return if (now < d0.optLong("fajr")) (day(now, -1)?.optLong("maghrib") ?: return null) to d0.optLong("fajr")
        else d0.optLong("maghrib") to (day(now, 1)?.optLong("fajr") ?: return null)
    }
    /** The day whose five prayers count now: from its fajr to the next fajr (Isha after midnight is still yesterday's). */
    fun prayerDay(now: Long): JSONObject? { val d0 = day(now) ?: return null; return if (now < d0.optLong("fajr")) day(now, -1) else d0 }
    /** The length of the Hijri month that contains [now] (29 or 30), from where the next month begins in the pushed days. */
    fun monthLen(now: Long): Int {
        val h = day(now)?.let { hd(it) } ?: return 30
        for (o in 1..31) { val dd = day(now, o) ?: break; if (hd(dd) == 1) return day(now, o - 1)?.let { hd(it) } ?: 30 }
        return if (h >= 29) h else 30
    }
    /** Days from today to the next day with this Hijri month and day, within the pushed days; -1 if not there. */
    fun daysTo(now: Long, month: Int, dayN: Int, max: Int = 34): Int {
        for (o in 0..max) { val dd = day(now, o) ?: break; if (hmn(dd) == month && hd(dd) == dayN) return o }
        return -1
    }

    fun digits(s: String) = if (arabDigits) s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("") else s

    /** "5:39" (12 or 24 hours), and its AM/PM mark separately. */
    fun clock(ms: Long): String {
        val c = cal(ms); var h = c.get(Calendar.HOUR_OF_DAY)
        if (h12) h = if (h % 12 == 0) 12 else h % 12
        return digits("$h:" + c.get(Calendar.MINUTE).toString().padStart(2, '0'))
    }
    fun ampm(ms: Long): String = if (!h12) "" else if (cal(ms).get(Calendar.HOUR_OF_DAY) < 12) (if (ar) "ص" else "AM") else (if (ar) "م" else "PM")

    /** The countdown's look, set for each widget before it is drawn (0.18.1): "hm" 1 س 24 د, "clock" 1:24, "m" 84 دقيقة, "text" ساعة و24 دقيقة. */
    var cf = "hm"
    /** Until the user picks one for a widget, it follows the app's own countdown (seconds don't fit a picture drawn once a minute). */
    val appCf: String = when (o.optString("count")) { "hms", "hmc" -> "clock"; "m" -> "m"; "text" -> "text"; else -> "hm" }
    private fun pl(n: Long, one: String, two: String, few: String, many: String) = when { n == 1L -> one; n == 2L -> two; n in 3L..10L -> "$n $few"; else -> "$n $many" }

    /** The time left, rounded up to the minute, in the widget's countdown format. */
    fun dur(ms: Long): String {
        val m = maxOf(0L, (ms + 59_999) / 60_000); val h = m / 60; val mi = m % 60
        return digits(when (cf) {
            "clock" -> "$h:" + mi.toString().padStart(2, '0')
            "m" -> if (ar) pl(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة") else "$m min"
            "text" -> if (!ar) listOfNotNull(if (h > 0) "$h hour" + (if (h > 1) "s" else "") else null, if (mi > 0 || h == 0L) "$mi minute" + (if (mi != 1L) "s" else "") else null).joinToString(" ")
                else {
                    val hs = if (h > 0) pl(h, "ساعة", "ساعتين", "ساعات", "ساعة") else ""
                    when {
                        h > 0 && mi == 15L -> "$hs وربع"
                        h > 0 && mi == 30L -> "$hs ونص"
                        h > 0 && mi == 45L -> pl(h + 1, "ساعة", "ساعتين", "ساعات", "ساعة") + " إلا ربع"
                        else -> { val ms2 = if (mi > 0) pl(mi, "دقيقة", "دقيقتين", "دقائق", "دقيقة") else ""
                            if (hs.isNotEmpty() && ms2.isNotEmpty()) "$hs و$ms2" else hs.ifEmpty { ms2.ifEmpty { "أقل من دقيقة" } } }
                    }
                }
            else -> if (ar) (if (h > 0) "$h س $mi د" else "$mi د") else (if (h > 0) "$h h $mi min" else "$mi min")
        })
    }
    fun inTxt(ms: Long) = (if (ar) "بعد " else "in ") + dur(ms)
    fun inTxt(e: Ev, now: Long) = if (e.iqAt > 0) (if (ar) "الإقامة بعد " else "Iqama in ") + dur(e.iqAt - now) else inTxt(e.at - now)
    fun leftTxt(ms: Long) = (if (ar) "باقي " else "") + dur(ms) + (if (ar) "" else " left")

    companion object {
        const val DAY = 86_400_000L
        val PRAY = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
        val SIX = listOf("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha")
        val EXK = listOf("fajr", "duha", "dhuhr", "asr", "maghrib", "isha")
        // the phone's own time zone (0.18.8), like the page: the same in Saudi Arabia, right when abroad
        private val KSA: TimeZone get() = TimeZone.getDefault()
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
data class WStyle(val bg: String, val font: String, val rows: Set<String>, val tap: String = "app",
                  /** weight of the prayer's name and of the times and text: "l", "m", "b" */
                  val nw: String = "m", val dw: String = "m",
                  /** how far the name is stretched with kashida: 0 not at all … 7 fills its space to the frame
                   *  (until picked: 7 in script, actions and the table, 0 in the others, see [klFor]) */
                  val kl: Int = 7,
                  /** the countdown's format ("" follows the app's own) */
                  val cf: String = "") {
    val kash get() = kl > 0
    fun has(k: String) = k in rows
    fun toJson(): JSONObject = JSONObject().put("v", 2).put("bg", bg).put("font", font).put("rows", JSONArray(rows.toList())).put("tap", tap)
        .put("nw", nw).put("dw", dw).put("kl", kl).put("kash", kl > 0).put("kv", 3).put("cf", cf)
    companion object {
        /** What each widget can show; the first set is what it shows until the user changes it. */
        val SHOW: Map<String, Pair<List<String>, Set<String>>> = mapOf(
            "pano" to (listOf("count", "time", "hijri", "exit", "labels", "sky") to setOf("count", "time", "labels", "sky")),
            "window" to (listOf("count", "time", "hijri", "sky") to setOf("count", "sky")),
            "ring" to (listOf("count", "time", "prev") to setOf("count", "prev")),
            "orbit" to (listOf("count", "time", "hijri", "exit") to setOf("count", "time")),
            "script" to (listOf("count", "time", "hijri", "exit") to setOf("count", "time")),
            "table" to (listOf("city", "date", "count", "times", "exit", "iqama", "sunrise", "duha") to setOf("times", "exit")),
            "clock" to (listOf("count", "time") to setOf("count", "time")),
            "moon" to (listOf("wd", "next") to setOf("wd", "next")),
            "ribbon" to (listOf("count", "labels") to setOf("count", "labels")),
            "exit" to (listOf("time", "bar") to setOf("time", "bar")),
            "actions" to (listOf("count", "time") to setOf("count", "time")),
            "line" to (listOf("count", "time", "hijri") to setOf("count", "time")),
            "prayed" to (listOf("streak", "names", "now") to setOf("streak", "names", "now")),
            "night" to (listOf("mid", "count", "sky") to setOf("mid", "count", "sky")),
            "ramadan" to (listOf("imsak", "fajr", "prog") to setOf("imsak", "fajr", "prog")),
            "white" to (listOf("monthu", "days") to setOf("monthu", "days")),
            "column" to (listOf("count", "time", "sunrise", "duha", "exit", "hijri", "moon") to setOf("count", "exit", "hijri", "moon")))
        val BGS = setOf("sky", "light", "dark", "clear", "dyn")
        val CFS = setOf("hm", "clock", "m", "text")

        /** The stretch until the user picks one: script and actions were designed stretched, and so were the table's big headline
         *  and the column's; the others keep their plain look (0.18.2). */
        fun klFor(type: String) = if (type == "script" || type == "actions" || type == "table" || type == "column") 7 else 0

        /** Until the user picks, a widget uses the name font chosen for the app's own screen. */
        fun defaultFor(type: String, font: String? = null) = WStyle(when (type) { "script", "line" -> "clear"; "prayed", "ramadan" -> "dark"; "white" -> "light"; else -> "sky" },
            font?.takeIf { it in setOf("kufi", "ruqaa", "naskh") } ?: "kufi", SHOW[type]?.second ?: emptySet(), if (type == "prayed") "pray" else "app", kl = klFor(type))
        fun from(type: String, j: JSONObject?, font: String? = null): WStyle {
            val d = defaultFor(type, font); if (j == null) return d
            val r = j.optJSONArray("rows")
            // looks saved before "what it shows" existed keep the defaults (only the table had rows then)
            val rows = if (r == null || (j.optInt("v") < 2 && type != "table")) d.rows else (0 until r.length()).map { r.optString(it) }.toSet()
            val wt = { k: String -> j.optString(k, "m").takeIf { it in setOf("l", "m", "b") } ?: "m" }
            // the stretch: script and actions keep their old "kash" switch; the others count a saved "kl" only from 0.18.2 ("kv": 2),
            // since the page saves back every key it was given and older looks carry the 7 that was everyone's default then.
            // The table only with "kv": 3 (since its headline stretches): looks saved since 0.18.2 carry its old 0 with "kv": 2, though nobody picked it
            val kl = if (type == "script" || type == "actions") (if (j.has("kl")) j.optInt("kl", 7).coerceIn(0, 7) else if (j.optBoolean("kash", true)) 7 else 0)
                     else if (j.optInt("kv") >= (if (type == "table") 3 else 2) && j.has("kl")) j.optInt("kl", d.kl).coerceIn(0, 7) else d.kl
            return WStyle(j.optString("bg", d.bg).takeIf { it in BGS } ?: d.bg, j.optString("font", d.font), rows, j.optString("tap", d.tap), wt("nw"), wt("dw"), kl,
                j.optString("cf", "").takeIf { it in CFS } ?: "")
        }
    }
}
