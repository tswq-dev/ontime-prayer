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
            setOf("city", "date", "count") to 120, setOf("city", "date", "count", "times", "exit", "iqama") to 350)
        sheet("sheet-9-table", rows.flatMap { (r, h) -> listOf("sky", "light").map { bg -> Item(r.joinToString("+") + " $bg", draw("table", WStyle(bg, "kufi", r), ar, duha, 340, h, fonts)) } })
        val dh = day.getLong("dhuhr")
        sheet("sheet-10-states", listOf(
            Item("exit: no time now", draw("exit", WStyle("dark", "kufi", emptySet()), ar, dh - 10 * mn, 340, 90, fonts)),
            Item("exit: fajr", draw("exit", WStyle("sky", "kufi", emptySet()), ar, day.getLong("fajr") + 30 * mn, 340, 90, fonts)),
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
            Item("script dark, not stretched", draw("script", dflt("script").copy(bg = "dark", kash = false), ar, fajrEve, 360, 100, fonts)),
            Item("actions 360x196", draw("actions", WStyle("dark", "kufi", setOf("count", "time")), ar, fajrEve, 360, 196, fonts)),
            Item("exit just before 360x110", draw("exit", dflt("exit").copy(bg = "dark"), ar, fajrEve, 360, 110, fonts)),
            Item("line 360x130", draw("line", dflt("line").copy(bg = "dark", rows = setOf("time", "hijri")), ar, fajrEve, 360, 130, fonts))) +
            listOf("kufi", "ruqaa", "naskh").flatMap { f -> listOf("l", "m", "b").map { w ->
                Item("$f name $w / times $w", draw("script", WStyle("dark", f, setOf("time", "count"), nw = w, dw = w), ar, asr, 340, 90, fonts)) } })

        // previews for the widget picker: golden afternoon, the clear ones on green so they show on any picker
        for (t in types) {
            val st = dflt(t).let { if (it.bg == "clear") it.copy(bg = "dark") else it }
            save(draw(t, st, ar, asr, sz(t).first, sz(t).second, fonts, 2f), File(out, "wp_$t.png"))
        }
    }
}
