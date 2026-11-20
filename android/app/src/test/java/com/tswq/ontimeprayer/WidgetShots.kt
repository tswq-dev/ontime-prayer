package com.tswq.ontimeprayer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Draws every widget with the app's own drawing code (real fonts, real canvas) for a day of sample data,
 * at several moments, so the designs can be checked by eye. CI publishes build/shots, and the wp_*.png
 * pictures become the previews in the phone's widget picker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetShots {
    private val out = File("build/shots").apply { mkdirs() }
    private class Item(val label: String, val bmp: Bitmap)

    private fun draw(type: String, st: WStyle, d: WData?, now: Long, wDp: Int, hDp: Int, fonts: WFonts, s: Float = 1.5f, b: WidgetArt.Btns = WidgetArt.Btns()): Bitmap =
        if (type == "actions") WidgetArt.renderActions(st, d, now, (wDp * s).toInt(), (hDp * s).toInt(), s, fonts, b)
        else WidgetArt.render(type, st, d, now, (wDp * s).toInt(), (hDp * s).toInt(), s, fonts)

    private fun save(b: Bitmap, f: File) = FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun sheet(name: String, items: List<Item>) {
        val w = 1160; val m = 36; val lab = 34
        var x = m; var y = m; var rowH = 0; val pos = ArrayList<Pair<Int, Int>>()
        for (it in items) {
            if (x > m && x + it.bmp.width > w - m) { x = m; y += rowH + lab + m / 2; rowH = 0 }
            pos += x to y; x += it.bmp.width + m; rowH = max(rowH, it.bmp.height)
        }
        val h = y + rowH + lab + m
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val cv = Canvas(bmp)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w * .6f, h.toFloat(), 0xFF46677A.toInt(), 0xFF1F2F39.toInt(), Shader.TileMode.CLAMP) })
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
        items.forEachIndexed { i, it -> val (px, py) = pos[i]; cv.drawBitmap(it.bmp, px.toFloat(), py.toFloat(), null); cv.drawText(it.label, px.toFloat(), (py + it.bmp.height + 26).toFloat(), tp) }
        save(bmp, File(out, "$name.png"))
    }

    @Test fun shots() {
        // as on a phone set to Arabic (String.format would write Arabic-Indic digits here)
        java.util.Locale.setDefault(java.util.Locale("ar", "SA"))
        val ctx = RuntimeEnvironment.getApplication()
        val fonts = WFonts.get(ctx.assets)
        val all = JSONObject(javaClass.classLoader!!.getResource("wdata.json")!!.readText())
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Riyadh"))   // the sample day is in Saudi time
        val ar = WData(all.getJSONObject("ar")); val en = WData(all.getJSONObject("en"))
        val day = all.getJSONObject("ar").getJSONArray("days").getJSONObject(1)
        val ex = day.getJSONObject("ex"); val mn = 60_000L
        val asrEnd = ex.getJSONArray("asr").getJSONObject(0).getLong("at")
        val moments = linkedMapOf(
            "1-fajr" to day.getLong("fajr") + 25 * mn, "2-duha" to day.getLong("duha") + 120 * mn, "3-asr" to asrEnd - 20 * mn,
            "4-maghrib" to day.getLong("maghrib") + 12 * mn, "5-night" to day.getLong("isha") + 180 * mn)
        val types = WidgetArt.TYPES
        org.junit.Assert.assertEquals("2026-11-19", WData.dateKey(asrEnd))
        org.junit.Assert.assertNotNull("the day must be found with an Arabic locale", ar.day(asrEnd))
        fun dflt(t: String) = WStyle.defaultFor(t)
        fun sz(t: String) = WidgetUpdater.defaultDp(t)

        for ((label, now) in moments)
            sheet("sheet-$label", types.map { t -> Item(t, draw(t, dflt(t), ar, now, sz(t).first, sz(t).second, fonts)) })
        val asr = moments["3-asr"]!!
        sheet("sheet-6-en", types.map { t -> Item(t, draw(t, dflt(t), en, asr, sz(t).first, sz(t).second, fonts)) })
        sheet("sheet-7-backgrounds", types.filter { it != "pano" && it != "window" }.flatMap { t ->
            listOf("light", "dark", "clear").map { bg -> Item("$t $bg", draw(t, dflt(t).copy(bg = bg), ar, asr, sz(t).first, sz(t).second, fonts)) } })
        val mg = moments["4-maghrib"]!!
        sheet("sheet-8-fonts", listOf("script", "orbit", "line", "actions", "ring").flatMap { t ->
            listOf("kufi", "ruqaa", "naskh").map { f -> Item("$t $f", draw(t, dflt(t).copy(font = f, bg = "dark"), ar, mg, sz(t).first, sz(t).second, fonts)) } })
        val duha = moments["2-duha"]!!
        val rows = listOf(setOf("times") to 90, setOf("times", "exit") to 120, setOf("count") to 70, setOf("count", "times", "exit") to 170,
            setOf("city", "date", "count") to 120, setOf("city", "date", "count", "times", "exit", "iqama") to 350,
            setOf("times", "exit", "sunrise") to 120, setOf("times", "exit", "sunrise", "duha") to 120)
        sheet("sheet-9-table", rows.flatMap { (r, h) -> listOf("sky", "light").map { bg -> Item(r.joinToString("+") + " $bg", draw("table", WStyle(bg, "kufi", r), ar, duha, 340, h, fonts)) } })
        val dh = day.getLong("dhuhr")
        sheet("sheet-10-states", listOf(
            Item("exit: no time now", draw("exit", dflt("exit").copy(bg = "dark", rows = emptySet()), ar, dh - 10 * mn, 340, 90, fonts)),
            Item("exit: fajr", draw("exit", dflt("exit").copy(rows = emptySet()), ar, day.getLong("fajr") + 30 * mn, 340, 90, fonts)),
            Item("actions: done", draw("actions", WStyle("dark", "kufi", emptySet()), ar, asr, 340, 170, fonts, b = WidgetArt.Btns(prayed = true, muted = true, reminded = true))),
            Item("actions: nothing to pray", draw("actions", WStyle("light", "kufi", emptySet()), ar, dh - 10 * mn, 340, 170, fonts, b = WidgetArt.Btns(canPray = false))),
            Item("no data", draw("ring", dflt("ring"), null, asr, 170, 170, fonts)),
            Item("ring 2x2 big", draw("ring", dflt("ring"), ar, moments["5-night"]!!, 250, 250, fonts)),
            Item("pano wide", draw("pano", dflt("pano"), ar, mg, 520, 200, fonts))))

        // what each widget shows: everything on, then only the name
        sheet("sheet-11-show-all", types.map { t -> val all = WStyle.SHOW[t]!!.first.toSet()
            Item("$t: all", draw(t, dflt(t).copy(rows = all), ar, asr, sz(t).first, sz(t).second, fonts)) })
        sheet("sheet-12-show-none", types.filter { it != "table" }.map { t -> Item("$t: none", draw(t, dflt(t).copy(rows = emptySet()), ar, asr, sz(t).first, sz(t).second, fonts)) })
        // the phone's colours (a sample palette) and the framed sky widgets
        WidgetArt.dyn = intArrayOf(0xFFF3DDE6.toInt(), 0xFFE9D3F0.toInt(), 0xFF3B1F33.toInt(), 0xFF6E5468.toInt(), 0xFF9C3F78.toInt())
        sheet("sheet-13-phone-colours", listOf("pano", "window", "orbit", "ring", "table", "exit", "line", "moon").map { t ->
            Item("$t dyn", draw(t, dflt(t).copy(bg = "dyn"), ar, asr, sz(t).first, sz(t).second, fonts)) } +
            listOf("light", "dark").flatMap { bg -> listOf("pano", "window").map { t -> Item("$t $bg", draw(t, dflt(t).copy(bg = bg), ar, mg, sz(t).first, sz(t).second, fonts)) } })

        // after the Dhuhr adhan, before its iqama: the widgets stay on Dhuhr with "Iqama in …"
        val iqNow = day.getLong("dhuhr") + 6 * mn
        sheet("sheet-14-iqama", types.map { t -> Item(t, draw(t, dflt(t).let { if (t == "table") it.copy(rows = setOf("count", "times", "iqama")) else it }, ar, iqNow, sz(t).first, if (t == "table") 200 else sz(t).second, fonts)) })

        // sizes like the owner's phone, the stretched name, and the three weights
        val fajrEve = day.getLong("isha") + 6 * 60 * mn
        sheet("sheet-15-balance", listOf(
            Item("script 360x100 clear", draw("script", dflt("script"), ar, fajrEve, 360, 100, fonts)),
            Item("script dark, not stretched", draw("script", dflt("script").copy(bg = "dark", kl = 0), ar, fajrEve, 360, 100, fonts)),
            Item("actions 360x196", draw("actions", WStyle("dark", "kufi", setOf("count", "time")), ar, fajrEve, 360, 196, fonts)),
            Item("exit just before 360x110", draw("exit", dflt("exit").copy(bg = "dark"), ar, fajrEve, 360, 110, fonts)),
            Item("line 360x130", draw("line", dflt("line").copy(bg = "dark", rows = setOf("time", "hijri")), ar, fajrEve, 360, 130, fonts)),
            Item("script stretch 3", draw("script", dflt("script").copy(bg = "dark", kl = 3), ar, fajrEve, 360, 100, fonts)),
            Item("script stretch 7 (default)", draw("script", dflt("script").copy(bg = "dark"), ar, asr, 360, 100, fonts)),
            Item("actions stretch 7", draw("actions", WStyle("dark", "kufi", setOf("count", "time")), ar, asr, 360, 196, fonts)),
            Item("line: the name alone", draw("line", dflt("line").copy(bg = "dark", rows = emptySet()), ar, fajrEve, 360, 100, fonts)),
            Item("line: name + time", draw("line", dflt("line").copy(bg = "dark", rows = setOf("time")), ar, fajrEve, 360, 100, fonts)),
            Item("exit tall, before fajr", draw("exit", dflt("exit").copy(bg = "dark"), ar, fajrEve, 360, 130, fonts)),
            Item("exit tall, asr with bar", draw("exit", dflt("exit").copy(bg = "dark"), ar, asr, 360, 130, fonts)),
            Item("table tall", draw("table", WStyle("dark", "kufi", setOf("times", "exit")), ar, asr, 360, 130, fonts))) +
            listOf("kufi", "ruqaa", "naskh").flatMap { f -> listOf("l", "m", "b").map { w ->
                Item("$f name $w / times $w", draw("script", WStyle("dark", f, setOf("time", "count"), nw = w, dw = w), ar, asr, 340, 90, fonts)) } })

        // the four extra widgets (0.14): each in its two sizes, through the day; Ramadan from a copy of the days set in Ramadan
        WidgetArt.dyn = null
        val rj = JSONObject(all.getJSONObject("ar").toString()); val rd = rj.getJSONArray("days")
        for (i in 0 until rd.length()) rd.getJSONObject(i).put("hmn", if (i <= 16) 9 else 10).put("hd", (if (i <= 16) 13 + i else i - 16).toString()).put("hm", if (i <= 16) "رمضان" else "شوال")
        val ram = WData(rj)
        fun ex(t: String, st: WStyle, d: WData, now: Long, w: Int, h: Int) = draw(t, st, d, now, w, h, fonts)
        WidgetArt.marks = WidgetArt.Marks(0b00011, 6)
        val pr = listOf(Item("prayed 4x1 asr, 2 done", ex("prayed", dflt("prayed"), ar, asr, 340, 90)),
            Item("prayed 2x2", ex("prayed", dflt("prayed"), ar, asr, 170, 170)),
            Item("prayed light 2x2", ex("prayed", dflt("prayed").copy(bg = "light"), ar, asr, 170, 170)))
        WidgetArt.marks = WidgetArt.Marks(0b11111, 7)
        val pr2 = listOf(Item("prayed all done, night", ex("prayed", dflt("prayed").copy(bg = "sky"), ar, moments["5-night"]!!, 340, 90)),
            Item("prayed en", ex("prayed", dflt("prayed"), en, asr, 340, 90)))
        WidgetArt.marks = WidgetArt.Marks(0, 0)
        val pr3 = listOf(Item("prayed none, no names", ex("prayed", dflt("prayed").copy(rows = setOf("now")), ar, day.getLong("fajr") + 20 * mn, 340, 90)))
        sheet("sheet-16-extra", pr + pr2 + pr3 + listOf(
            Item("night 2x2 evening", ex("night", dflt("night"), ar, mg, 170, 170)),
            Item("night 2x2 in the third", ex("night", dflt("night"), ar, day.getLong("fajr") + WData.DAY - 50 * mn, 170, 170)),
            Item("night 4x1 at night", ex("night", dflt("night"), ar, moments["5-night"]!!, 340, 90)),
            Item("night 4x1 light, day", ex("night", dflt("night").copy(bg = "light"), ar, asr, 340, 90)),
            Item("night en", ex("night", dflt("night"), en, moments["5-night"]!!, 340, 90)),
            Item("ramadan 4x2 fasting", ex("ramadan", dflt("ramadan"), ram, asr, 340, 170)),
            Item("ramadan 4x2 suhoor", ex("ramadan", dflt("ramadan"), ram, moments["5-night"]!!, 340, 170)),
            Item("ramadan 2x2 fasting", ex("ramadan", dflt("ramadan"), ram, asr, 170, 170)),
            Item("ramadan 2x2 suhoor sky", ex("ramadan", dflt("ramadan").copy(bg = "sky"), ram, moments["5-night"]!!, 170, 170)),
            Item("ramadan 4x2 outside", ex("ramadan", dflt("ramadan"), ar, asr, 340, 170)),
            Item("ramadan 2x2 outside light", ex("ramadan", dflt("ramadan").copy(bg = "light"), ar, asr, 170, 170)),
            Item("white 4x2", ex("white", dflt("white"), ar, asr, 340, 170)),
            Item("white 4x2 dark", ex("white", dflt("white").copy(bg = "dark"), ar, asr, 340, 170)),
            Item("white 2x2", ex("white", dflt("white"), ar, asr, 170, 170)),
            Item("white 4x2 on the 14th", ex("white", dflt("white"), ar, asr + 5 * WData.DAY, 340, 170)),
            Item("white en", ex("white", dflt("white"), en, asr, 340, 170))))
        // the sky background pinned to a moment of the day (0.18.9), all at the same real time (before Maghrib)
        val now9 = day.getLong("maghrib") - 84 * mn
        sheet("sheet-23-sky-pin", listOf("", "fajr:20", "sunrise:8", "dhuhr:0", "asr:40", "maghrib:6", "isha:90").flatMap { sp -> listOf(
            Item("table " + sp.ifEmpty { "live" }, draw("table", dflt("table").copy(bg = "sky", sp = sp), ar, now9, 340, 170, fonts)),
            Item("pano " + sp.ifEmpty { "live" }, draw("pano", dflt("pano").copy(sp = sp), ar, now9, 340, 170, fonts))) })
        // the countdown formats (0.18.1), 1 h 24 min before Maghrib
        val c84 = day.getLong("maghrib") - 84 * mn
        sheet("sheet-17-countdown", listOf("hm", "clock", "m", "text").flatMap { f -> listOf(
            Item("script $f", draw("script", dflt("script").copy(cf = f), ar, c84, 340, 90, fonts)),
            Item("clock $f", draw("clock", dflt("clock").copy(cf = f), ar, c84, 170, 170, fonts)),
            Item("ring $f", draw("ring", dflt("ring").copy(cf = f), ar, c84, 170, 170, fonts)),
            Item("line en $f", draw("line", dflt("line").copy(cf = f), en, c84, 340, 90, fonts))) })

        // the stretched name in more widgets (0.18.2), at 7 (fills its space, never past it): before Maghrib in the default size,
        // before Isha in a second size; the clear ones on green to show the frame; English stays as it is
        val ish = day.getLong("isha") - 40 * mn
        val more = linkedMapOf("pano" to (520 to 200), "window" to (250 to 250), "orbit" to (300 to 150), "line" to (360 to 130),
            "exit" to (360 to 130), "ring" to (250 to 250), "clock" to (250 to 250))
        fun k7(t: String, f: String = "kufi") = dflt(t).copy(font = f, kl = 7).let { if (it.bg == "clear") it.copy(bg = "dark") else it }
        sheet("sheet-18-kashida", more.flatMap { (t, big) -> listOf("kufi", "ruqaa", "naskh").map { f -> Item("$t $f 7", draw(t, k7(t, f), ar, c84, sz(t).first, sz(t).second, fonts)) } +
            listOf("kufi", "ruqaa", "naskh").map { f -> Item("$t ${big.first}x${big.second} $f 7", draw(t, k7(t, f), ar, ish, big.first, big.second, fonts)) } } +
            more.keys.map { t -> Item("$t en 7", draw(t, k7(t), en, ish, sz(t).first, sz(t).second, fonts)) })

        // the table's big headline: countdown at the end, the name from the start up to it, as tall as the free band;
        // stretched at 7 (the table's default) or plain at 0. Before Maghrib, before Isha and before Dhuhr; English mirrored.
        // "+count": the default rows (times, exit) and the countdown; "5 rows": city, date, count, times, exit
        val dz = day.getLong("dhuhr") - 50 * mn
        val cte = setOf("count", "times", "exit"); val full = setOf("city", "date", "count", "times", "exit"); val cdc = setOf("city", "date", "count")
        fun tb(label: String, bg: String, f: String, r: Set<String>, kl: Int, d: WData, now: Long, w: Int, h: Int) =
            Item("$label ${w}x$h $f $kl $bg", draw("table", WStyle(bg, f, r, kl = kl), d, now, w, h, fonts))
        sheet("sheet-19-table-headline",
            listOf("kufi", "ruqaa", "naskh").flatMap { f -> listOf(7, 0).map { k -> tb("maghrib +count", "sky", f, cte, k, ar, c84, sz("table").first, sz("table").second) } } +
            listOf("kufi", "ruqaa", "naskh").map { f -> tb("isha 5 rows", "light", f, full, 7, ar, ish, 340, 170) } +
            listOf("kufi", "ruqaa", "naskh").map { f -> tb("dhuhr city+date+count", "sky", f, cdc, 7, ar, dz, 340, 120) } +
            listOf(tb("dhuhr city+date+count", "light", "naskh", cdc, 0, ar, dz, 340, 120),
                tb("isha 5 rows", "sky", "kufi", full, 7, ar, ish, 360, 130), tb("isha 5 rows", "sky", "naskh", full, 0, ar, ish, 360, 130),
                tb("maghrib count only", "light", "kufi", setOf("count"), 7, ar, c84, 340, 70), tb("maghrib count only", "light", "naskh", setOf("count"), 7, ar, c84, 340, 70),
                tb("isha 5 rows+iqama", "sky", "kufi", full + "iqama", 7, ar, ish, 340, 350),
                tb("dhuhr iqama wait", "sky", "ruqaa", setOf("count", "times", "iqama"), 7, ar, iqNow, 340, 200),
                tb("en maghrib +count", "sky", "kufi", cte, 7, en, c84, sz("table").first, sz("table").second),
                tb("en isha count only", "light", "kufi", setOf("count"), 7, en, ish, 340, 70)))

        // the column (2×4, "2x4" = its default 170x340): the next prayer stretched over the day's rows. Before Maghrib and while
        // waiting for the Isha iqama, each font on its own background; then the other sizes (shorter ones drop the moon,
        // the date, the exit line in turn; taller ones grow). Labels are short so they fit under the narrow ones
        val iqIsha = day.getLong("isha") + 6 * mn
        val colAll = WStyle.SHOW["column"]!!.first.toSet(); val colDef = WStyle.SHOW["column"]!!.second
        fun cl(label: String, f: String, bg: String, d: WData, now: Long, w: Int, h: Int, r: Set<String> = colDef, kl: Int = 7) =
            Item("$label $f $bg" + (if (kl != 7) " kl $kl" else ""), draw("column", WStyle(bg, f, r, kl = kl), d, now, w, h, fonts))
        val fb = listOf("kufi" to "sky", "ruqaa" to "light", "naskh" to "dark")
        sheet("sheet-20-column",
            fb.map { (f, bg) -> cl("2x4 maghrib", f, bg, ar, c84, 170, 340) } + fb.map { (f, bg) -> cl("2x4 isha iq.", f, bg, ar, iqIsha, 170, 340) } +
            fb.map { (f, bg) -> cl("170x250", f, bg, ar, c84, 170, 250) } +
            listOf(cl("250x400 isha, all rows", "kufi", "dark", ar, ish, 250, 400, colAll), cl("250x400 dhuhr", "naskh", "light", ar, dz, 250, 400),
                cl("250x400 duha, all rows", "ruqaa", "sky", ar, day.getLong("duha") + 30 * mn, 250, 400, colAll),
                cl("170x190", "kufi", "sky", ar, c84, 170, 190), cl("2x4 night", "kufi", "sky", ar, moments["5-night"]!!, 170, 340),
                cl("2x4 name only", "naskh", "sky", ar, dz, 170, 340, emptySet()), cl("2x4", "kufi", "dark", ar, c84, 170, 340, kl = 0),
                cl("2x4 en +time", "kufi", "sky", en, c84, 170, 340, colDef + "time")))

        // the moon made tall: today's prayers fill the middle once each row gets 19 dp; the default size stays as it was
        sheet("sheet-21-moon-tall", listOf(
            Item("moon 170x170 default", draw("moon", dflt("moon"), ar, c84, 170, 170, fonts)),
            Item("moon 170x250 (too short: as before)", draw("moon", dflt("moon"), ar, c84, 170, 250, fonts)),
            Item("moon 170x340 sky", draw("moon", dflt("moon"), ar, c84, 170, 340, fonts)),
            Item("moon 170x340 dark isha iqama", draw("moon", dflt("moon").copy(bg = "dark"), ar, iqIsha, 170, 340, fonts)),
            Item("moon 220x420 light", draw("moon", dflt("moon").copy(bg = "light"), ar, ish, 220, 420, fonts)),
            Item("moon 220x420 sky, no next", draw("moon", dflt("moon").copy(rows = setOf("wd")), ar, c84, 220, 420, fonts)),
            Item("moon 170x340 en", draw("moon", dflt("moon"), en, c84, 170, 340, fonts))))

        // the table's headline centred by the drawn name's own ink between the city/date line (or the frame) and the times row
        sheet("sheet-22-table-centred", listOf("kufi", "naskh").flatMap { f -> listOf(
            tb("maghrib +count", "sky", f, cte, 7, ar, c84, 340, 170), tb("isha 5 rows", "light", f, full, 7, ar, ish, 340, 170),
            tb("maghrib 5 rows", "sky", f, full, 7, ar, c84, 340, 170), tb("isha 5 rows", "sky", f, full, 7, ar, ish, 360, 130),
            tb("dhuhr 5 rows", "dark", f, full, 7, ar, dz, 340, 200), tb("dhuhr +count", "dark", f, cte, 7, ar, dz, 340, 200)) } +
            listOf(tb("isha 5 rows", "light", "naskh", full, 0, ar, ish, 340, 170), tb("en maghrib 5 rows", "sky", "kufi", full, 7, en, c84, 340, 170)))
        WidgetArt.marks = WidgetArt.Marks(0b00011, 6)

        // previews for the widget picker: golden afternoon, the clear ones on green so they show on any picker
        for (t in types) {
            val st = dflt(t).let { if (it.bg == "clear") it.copy(bg = "dark") else it }
            save(draw(t, st, ar, asr, sz(t).first, sz(t).second, fonts, 2f), File(out, "wp_$t.png"))
        }
    }
}
