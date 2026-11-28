package com.tswq.ontimeprayer

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The widget typefaces: the three name fonts and IBM Plex for everything else, each in the weights the user can pick. */
class WFonts(private val kufiW: Map<Int, Typeface>, private val ruqaaW: Map<Int, Typeface>, private val naskhW: Map<Int, Typeface>, private val plexW: Map<Int, Typeface>) {
    val plex: Typeface get() = plexW[400]!!
    val plexB: Typeface get() = plexW[600]!!
    /** "l" light, "m" medium (the default look), "b" bold. */
    fun name(f: String, w: String = "m"): Typeface = when (f) {
        "ruqaa" -> ruqaaW[if (w == "l") 400 else 700]!!
        "naskh" -> naskhW[if (w == "l") 400 else 700]!!
        else -> kufiW[when (w) { "l" -> 400; "b" -> 700; else -> 600 }]!!
    }
    /** Ruqaa and Naskh have nothing past Bold: "bold" thickens their strokes when drawn. */
    fun heavy(f: String, w: String) = w == "b" && f != "kufi"
    fun text(w: String, bold: Boolean): Typeface = plexW[when (w) { "l" -> if (bold) 500 else 300; "b" -> if (bold) 700 else 600; else -> if (bold) 600 else 400 }]!!

    companion object {
        @Volatile private var cached: WFonts? = null
        private fun build(file: (String) -> Typeface?, kufi: (Int) -> Typeface?): WFonts {
            fun f(n: String, d: Typeface) = runCatching { file(n) }.getOrNull() ?: d
            return WFonts(listOf(400, 600, 700).associateWith { runCatching { kufi(it) }.getOrNull() ?: Typeface.DEFAULT_BOLD },
                mapOf(400 to f("ruqaa400", Typeface.DEFAULT), 700 to f("ruqaa", Typeface.DEFAULT_BOLD)),
                mapOf(400 to f("naskh400", Typeface.DEFAULT), 700 to f("naskh", Typeface.DEFAULT_BOLD)),
                mapOf(300 to f("plex300", Typeface.DEFAULT), 400 to f("plex400", Typeface.DEFAULT), 500 to f("plex500", Typeface.DEFAULT),
                    600 to f("plex600", Typeface.DEFAULT_BOLD), 700 to f("plex700", Typeface.DEFAULT_BOLD)))
        }
        fun get(assets: AssetManager): WFonts = cached ?: synchronized(this) {
            cached ?: build({ Typeface.createFromAsset(assets, "wfonts/$it.ttf") },
                { Typeface.Builder(assets, "wfonts/kufi.ttf").setFontVariationSettings("'wght' $it").build() }).also { cached = it }
        }
        /** For tests on the JVM. */
        fun fromDir(dir: File) = build({ Typeface.createFromFile(File(dir, "$it.ttf")) },
            { Typeface.Builder(File(dir, "kufi.ttf")).setFontVariationSettings("'wght' $it").build() })
    }
}

/**
 * Draws every widget into a bitmap, following the approved designs («ويدجت على وقتها: تصورات»).
 * Positions are fractions of the widget's size so any cell size works. In Arabic time runs right to left
 * (sunrise on the right, the dial turns counter-clockwise); in English the drawing is mirrored.
 */
object WidgetArt {
    val TYPES = listOf("pano", "window", "ring", "orbit", "script", "table", "clock", "moon", "ribbon", "exit", "actions", "line", "prayed", "night", "ramadan", "white", "column")

    /** The phone's own colours (Android 12+): light background, deeper background, text, softer text, accent. */
    @Volatile var dyn: IntArray? = null

    private class Sty(val kind: String, val fg: Int, val sub: Int, val acc: Int, val line: Int, val shadow: Boolean) {
        val clear get() = kind == "clear"
        val light get() = kind == "light"
    }

    private class X(val d: WData, val day: JSONObject, val now: Long, val st: Sty, val f: WFonts,
                    val u: Float, val rtl: Boolean, val style: WStyle, val w: Float, val h: Float) {
        /** The moment the sky background shows: now, or the moment the user pinned it to (today's prayer + minutes).
         *  Only the sky's colours and stars follow it; the sun, the moon and every time stay real. */
        val sky: Long = style.sp.takeIf { it.isNotEmpty() }?.split(':')?.let { p ->
            day.optLong(p[0]).takeIf { it > 0 }?.plus((p.getOrNull(1)?.toLongOrNull() ?: 0L) * 60_000L) } ?: now
        val nameTf = f.name(style.font, style.nw)
        val heavy = f.heavy(style.font, style.nw)
        val plex = f.text(style.dw, false)
        val plexB = f.text(style.dw, true)
        val next = d.hero(now)
        val prev = d.prev(now)
        val cur = d.current(now)
        fun t(k: String) = day.optLong(k)
        fun dp(v: Float) = v * u
        val dir get() = if (rtl) 1f else -1f
    }

    private fun c(hex: String) = Color.parseColor(hex)
    private fun a(alpha: Float, rgb: Int) = Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    private fun mix(x: Int, y: Int, f: Float): Int {
        val k = f.coerceIn(0f, 1f)
        return Color.rgb((Color.red(x) + (Color.red(y) - Color.red(x)) * k).toInt(), (Color.green(x) + (Color.green(y) - Color.green(x)) * k).toInt(), (Color.blue(x) + (Color.blue(y) - Color.blue(x)) * k).toInt())
    }

    private val GOLD = c("#E2C27A"); private val GOLD_L = c("#C99A4B"); private val INK = c("#0B3B2E"); private val AMBER = c("#E8963A")

    private fun sty(kind: String): Sty = when (kind) {
        "light" -> Sty(kind, c("#0B3B2E"), c("#5B6B5F"), c("#0B4D3B"), a(.14f, c("#0B4D3B")), false)
        "dark" -> Sty(kind, c("#F4ECDC"), a(.74f, c("#F4ECDC")), c("#E2C27A"), a(.28f, c("#E2C27A")), false)
        "clear" -> Sty(kind, Color.WHITE, a(.85f, Color.WHITE), c("#F3D58C"), a(.28f, Color.WHITE), true)
        // «ليلي»: the sky half an hour after Isha, navy (0.23); the writing white and only what is next in gold, as the sky
        // has it after Isha (0.30)
        "navy" -> Sty(kind, c("#FFF8EA"), a(.74f, c("#FFF8EA")), c("#E9CD8A"), a(.26f, c("#E2C27A")), false)
        "dyn" -> dyn?.let { Sty(kind, it[2], it[3], it[4], a(.16f, it[2]), false) } ?: sty("dark")
        else -> Sty("sky", c("#FFF8EA"), a(.8f, c("#FFF8EA")), c("#FBE3A6"), a(.22f, Color.WHITE), true)
    }

    // ---------- the sky: colours anchored to the day's own times ----------
    private fun skyKeys(x: X): List<Triple<Long, Int, Int>> {
        val m = 60_000L; val d = x.day
        fun k(t: Long, top: String, bot: String) = Triple(t, c(top), c(bot))
        return listOf(
            k(d.optLong("fajr") - 90 * m, "#070B1A", "#121A33"), k(d.optLong("fajr") - 10 * m, "#0B1430", "#1E2A50"),
            k((d.optLong("fajr") + d.optLong("sunrise")) / 2, "#1D2C58", "#C77E6A"), k(d.optLong("sunrise"), "#4A6FA8", "#F2B880"),
            k(d.optLong("sunrise") + 60 * m, "#4F8FD6", "#BFE0F5"), k(d.optLong("dhuhr"), "#3F86D9", "#A8D4F5"),
            k(d.optLong("asr"), "#4A88CF", "#CFE3F0"), k(d.optLong("maghrib") - 60 * m, "#5A86C0", "#F3D6A0"),
            k(d.optLong("maghrib"), "#3A3F72", "#F28C5A"), k(d.optLong("maghrib") + 40 * m, "#22275A", "#8A5A7A"),
            k(d.optLong("isha") + 10 * m, "#0E1530", "#1E2547"), k(d.optLong("isha") + 120 * m, "#070B1A", "#121A33"))
    }
    private fun skyAt(x: X, t: Long): Pair<Int, Int> {
        val ks = skyKeys(x)
        if (t <= ks.first().first || t >= ks.last().first) return c("#070B1A") to c("#121A33")
        for (i in 0 until ks.size - 1) if (t >= ks[i].first && t <= ks[i + 1].first) {
            val f = (t - ks[i].first).toFloat() / max(1L, ks[i + 1].first - ks[i].first)
            return mix(ks[i].second, ks[i + 1].second, f) to mix(ks[i].third, ks[i + 1].third, f)
        }
        return c("#070B1A") to c("#121A33")
    }
    private fun isDay(x: X) = x.now in x.t("sunrise")..x.t("maghrib")
    private fun starAlpha(x: X) = when { x.sky < x.t("fajr") + 600_000 || x.sky > x.t("isha") -> 1f; x.sky < x.t("sunrise") || x.sky > x.t("maghrib") -> .35f; else -> 0f }
    private fun lit(x: X): Float {
        val r = (x.now - (x.t("sunrise") - 1_800_000)) / 7_200_000f; val s = ((x.t("maghrib") + 1_200_000) - x.now) / 7_200_000f
        return min(r, s).coerceIn(0f, 1f)
    }
    /** Day: 0 at sunrise → 1 at maghrib. Night: 0 at maghrib → 1 at the next sunrise. */
    private fun dayFrac(x: X) = ((x.now - x.t("sunrise")).toFloat() / max(1L, x.t("maghrib") - x.t("sunrise"))).coerceIn(0f, 1f)
    private fun nightFrac(x: X): Float {
        val (m0, r1) = if (x.now > x.t("maghrib")) x.t("maghrib") to (x.d.day(x.now, 1)?.optLong("sunrise") ?: (x.t("sunrise") + WData.DAY))
                        else (x.d.day(x.now, -1)?.optLong("maghrib") ?: (x.t("maghrib") - WData.DAY)) to x.t("sunrise")
        return ((x.now - m0).toFloat() / max(1L, r1 - m0)).coerceIn(0f, 1f)
    }
    /** Moon age in days (0 new, ~14.8 full). */
    private fun moonAge(now: Long): Double { val jd = now / 86_400_000.0 + 2440587.5; val s = 29.530588853; return ((jd - 2451550.1) % s + s) % s }

    // ---------- small drawing helpers ----------
    private fun paint(color: Int = Color.WHITE) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private class NPaint(val heavy: Boolean) : Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private fun thicken(p: Paint) { if (p is NPaint && p.heavy) { p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = p.textSize * .035f; p.strokeJoin = Paint.Join.ROUND } }
    private fun tp(x: X, tf: Typeface, sizeDp: Float, color: Int, shadow: Boolean = x.st.shadow) = NPaint(tf === x.nameTf && x.heavy).apply {
        typeface = tf; textSize = x.dp(sizeDp); this.color = color
        if (shadow) setShadowLayer(x.dp(4f), 0f, x.dp(1f), 0x8C000000.toInt())
    }
    private fun fit(p: Paint, s: String, maxW: Float) { while (p.measureText(s) > maxW && p.textSize > 6f) p.textSize *= .94f }
    /** Draws text so it starts at [x0] in reading order (right edge in Arabic). */
    private fun text(cv: Canvas, x: X, s: String, x0: Float, y: Float, p: Paint, start: Boolean = true) {
        p.textAlign = if (start == x.rtl) Paint.Align.RIGHT else Paint.Align.LEFT
        thicken(p); cv.drawText(s, x0, y, p)
    }
    private fun center(cv: Canvas, s: String, cx: Float, y: Float, p: Paint) { p.textAlign = Paint.Align.CENTER; thicken(p); cv.drawText(s, cx, y, p) }

    /**
     * The prayer's name stretched with kashida (ـ) to fill [target] px, as a calligrapher would:
     * the elongation goes before the last joining letter («الفجـــر»), never inside lam-alef.
     */
    private const val TATWEEL = '\u0640'
    private val NONJOIN = "اأإآدذرزوؤةءى"
    fun stretch(p: Paint, s: String, target: Float, level: Int = 7): String {
        val spots = (0 until s.length - 1).filter { i -> val a = s[i]; val b = s[i + 1]
            a in '\u0620'..'\u064A' && b in '\u0620'..'\u064A' && a !in NONJOIN && !(a == 'ل' && b in "اأإآ") }
        val w0 = p.measureText(s)
        if (spots.isEmpty() || w0 >= target) return s
        // a font whose kashida has no width (Aref Ruqaa) is left as it is, not filled with invisible ones
        if (p.measureText(TATWEEL.toString()) < 2f) return s
        // the user's level, 1 … 7: 7 fills the space up to the frame, never past it
        val goal = w0 + (target - w0) * level.coerceIn(0, 7) / 7f
        // split between two joints, the first and the last after «ال» (the user's pick, like the page)
        val use = spots.filter { !(s.startsWith("ال") && it < 2) }.ifEmpty { spots }
        val at = if (use.size > 1) listOf(use.first(), use.last()) else listOf(use.first())
        fun build(n: Int): String { val sb = StringBuilder()
            for (i in s.indices) { sb.append(s[i]); val k = at.indexOf(i); if (k >= 0) sb.append(TATWEEL.toString().repeat(if (k == 0) (n + at.size - 1) / at.size else n / at.size)) }
            return sb.toString() }
        var out = s
        for (n in 1..80) { val t = build(n); if (p.measureText(t) > goal) break; out = t }
        return out
    }
    /** Room kept clear on each side of a stretched name: at least 4 real dp, even at the smallest fit scale (.8). */
    private fun kSafe(x: X) = x.dp(5f)
    /** The width [s] really takes, drawn from its start or centred: its advance, twice any ink past it (a swash), and a heavy name's stroke. */
    private fun inkW(p: Paint, s: String): Float {
        val b = Rect(); p.getTextBounds(s, 0, s.length, b); val adv = p.measureText(s)
        return adv + 2 * max(0f, max(-b.left.toFloat(), b.right - adv)) + (if (p is NPaint && p.heavy) p.textSize * .035f else 0f)
    }
    /**
     * The name stretched in the widgets beyond script and actions (0.18.2): toward [room] px at the user's level, then measured
     * again and a kashida taken off until it fits, so it never passes its space. Arabic only; wider than [room] already: as it is.
     */
    private fun kashName(x: X, p: Paint, s: String, room: Float): String {
        if (!x.d.ar || x.style.kl <= 0 || room <= 0f) return s
        var out = stretch(p, s, room - (inkW(p, s) - p.measureText(s)), x.style.kl)
        while (out.length > s.length && inkW(p, out) > room) { val i = out.indices.first { it >= s.length || out[it] != s[it] }; out = out.removeRange(i, i + 1) }
        return if (out.length > s.length && inkW(p, out) <= room) out else s
    }
    /** The width of an ellipse centred at [cy] (a circle when [rx] = [ry]) at the top or bottom of [s] on baseline [y], whichever is narrower. */
    private fun chord(p: Paint, s: String, y: Float, cy: Float, rx: Float, ry: Float): Float {
        // the kashida's own height counts too, should it reach lower than the letters
        val b = Rect(); val t = Rect(); p.getTextBounds(s, 0, s.length, b); p.getTextBounds(TATWEEL.toString(), 0, 1, t)
        val dy = max(abs(y + min(b.top, t.top) - cy), abs(y + max(b.bottom, t.bottom) - cy))
        return 2 * rx * sqrt(max(0f, 1 - (dy / ry) * (dy / ry)))
    }
    private fun startX(x: X, pad: Float) = if (x.rtl) x.w - pad else pad
    private fun endX(x: X, pad: Float) = if (x.rtl) pad else x.w - pad

    private fun card(cv: Canvas, x: X, r: Float = x.dp(22f), kind: String = x.st.kind) {
        val rect = RectF(0f, 0f, x.w, x.h)
        when (kind) {
            "clear" -> return
            "light" -> cv.drawRoundRect(rect, r, r, paint().apply { shader = LinearGradient(0f, 0f, 0f, x.h, c("#F5EEE1"), c("#EBE1CE"), Shader.TileMode.CLAMP) })
            "dark" -> cv.drawRoundRect(rect, r, r, paint().apply { shader = LinearGradient(0f, 0f, x.w * .35f, x.h, c("#11594A"), c("#0A382C"), Shader.TileMode.CLAMP) })
            "navy" -> { cv.drawRoundRect(rect, r, r, paint().apply { shader = LinearGradient(0f, 0f, x.w * .35f, x.h, c("#1B2C52"), c("#0B1630"), Shader.TileMode.CLAMP) })
                // a few faint stars, kept off the edges
                val m = x.dp(10f); for (i in STARS.indices step 2) cv.drawCircle(m + (x.w - 2 * m) * (if (x.rtl) 1 - STARS[i] else STARS[i]), m + (x.h - 2 * m) * STARS[i + 1], x.dp(if (i % 4 == 0) 1.1f else .8f), paint(a(if (i % 6 == 0) .5f else .28f, c("#F3E6C4")))) }
            "dyn" -> { val dy = dyn ?: intArrayOf(c("#11594A"), c("#0A382C"))
                cv.drawRoundRect(rect, r, r, paint().apply { shader = LinearGradient(0f, 0f, x.w * .35f, x.h, dy[0], dy[1], Shader.TileMode.CLAMP) }) }
            else -> { val (t, b) = skyAt(x, x.sky)
                cv.drawRoundRect(rect, r, r, paint().apply { shader = LinearGradient(0f, 0f, 0f, x.h, t, b, Shader.TileMode.CLAMP) })
                cv.drawRoundRect(rect, r, r, paint(a(.34f, c("#0A101E")))) }
        }
    }
    private fun pill(cv: Canvas, x: X, s: String, cx: Float, cy: Float, p: Paint, bg: Int) {
        val tw = p.measureText(s); val ph = p.textSize * 1.55f; val pw = tw + p.textSize * 1.6f
        cv.drawRoundRect(RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2), ph / 2, ph / 2, paint(bg))
        center(cv, s, cx, cy + p.textSize * .36f, Paint(p).apply { clearShadowLayer() })
    }
    private fun pillW(p: Paint, s: String) = p.measureText(s) + p.textSize * 1.6f
    /** "9 Jumada II" and "Asr until 5:01" (the end of the time we are in), for the optional lines. */
    private fun hijriTxt(x: X) = x.d.digits(x.day.optString("hd")) + " " + x.day.optString("hm")
    private fun exitTxt(x: X): String { val cw = x.cur ?: return ""; val b = if (x.now < cw.first) cw.first else cw.end
        return x.d.name(cw.k) + (if (x.d.ar) " حتى " else " until ") + x.d.clock(b) }
    private fun joinDot(vararg p: String) = p.filter { it.isNotEmpty() }.joinToString("  ·  ")
    private fun sun(cv: Canvas, x: X, px: Float, py: Float, rDp: Float) {
        val g = x.dp(rDp * 2.4f)
        cv.drawCircle(px, py, g, paint().apply { shader = RadialGradient(px, py, g, intArrayOf(a(.75f, c("#FFF0C0")), a(.3f, c("#FFE3A0")), 0), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP) })
        cv.drawCircle(px, py, x.dp(rDp), paint(c("#FFF6D8")))
    }
    /** The moon with tonight's phase (lit side on the right while waxing, as seen from Saudi Arabia). */
    private fun moon(cv: Canvas, x: X, px: Float, py: Float, r: Float) {
        val age = moonAge(x.now); val k = (1 - cos(2 * PI * age / 29.530588853)) / 2; val waxing = age < 14.765
        cv.drawCircle(px, py, r * 1.6f, paint().apply { shader = RadialGradient(px, py, r * 1.6f, intArrayOf(a(.18f, c("#F0E8D2")), 0), null, Shader.TileMode.CLAMP) })
        cv.drawCircle(px, py, r, paint(c("#2A3150")))
        val lit = Path(); val side = if (waxing) 1f else -1f
        // half disc on the lit side
        lit.addArc(RectF(px - r, py - r, px + r, py + r), -90f, 180f * side)
        lit.close()
        val e = (r * abs(1 - 2 * k)).toFloat(); val oval = Path().apply { addOval(RectF(px - e, py - r, px + e, py + r), Path.Direction.CW) }
        if (k < .5) lit.op(oval, Path.Op.DIFFERENCE) else lit.op(oval, Path.Op.UNION)
        cv.drawPath(lit, paint(c("#F4ECD3")))
    }
    private val STARS = floatArrayOf(.12f, .14f, .28f, .30f, .47f, .10f, .63f, .26f, .79f, .12f, .88f, .34f, .38f, .44f, .70f, .48f, .20f, .52f, .55f, .40f)
    private fun stars(cv: Canvas, x: X, l: Float, t: Float, w: Float, h: Float) {
        val al = starAlpha(x); if (al <= 0f || !x.style.has("sky")) return
        val p = paint(a(al, Color.WHITE))
        for (i in STARS.indices step 2) cv.drawCircle(l + w * (if (x.rtl) 1 - STARS[i] else STARS[i]), t + h * STARS[i + 1], x.dp(if (i % 4 == 0) 1.4f else 1f), p)
    }
    private fun hills(cv: Canvas, x: X, l: Float, t: Float, w: Float, h: Float) {
        val li = lit(x); val far = mix(c("#0F1520"), c("#8A93A0"), li); val near = mix(c("#07090D"), c("#6E5A44"), li)
        fun pth(pts: FloatArray): Path { val p = Path(); p.moveTo(l, t + h * pts[0]); var i = 1
            while (i + 5 < pts.size) { p.cubicTo(l + w * pts[i], t + h * pts[i + 1], l + w * pts[i + 2], t + h * pts[i + 3], l + w * pts[i + 4], t + h * pts[i + 5]); i += 6 }
            p.lineTo(l + w, t + h); p.lineTo(l, t + h); p.close(); return p }
        cv.drawPath(pth(floatArrayOf(.88f, .11f, .78f, .2f, .82f, .31f, .75f, .42f, .69f, .51f, .80f, .62f, .76f, .76f, .72f, .84f, .82f, 1f, .74f)), paint(far))
        cv.drawPath(pth(floatArrayOf(.94f, .17f, .86f, .31f, .93f, .48f, .88f, .65f, .84f, .81f, .94f, 1f, .87f)), paint(near))
    }

    // ---------- entry ----------
    fun render(type: String, style: WStyle, d: WData?, now: Long, wPx: Int, hPx: Int, dpPx: Float, fonts: WFonts): Bitmap {
        d?.let { it.cf = style.cf.ifEmpty { it.appCf }; it.dhuhrDay = (it.next(now)?.let { nx -> it.day(nx.at) } ?: it.day(now))?.optLong("dhuhr") ?: now }
        val bmp = Bitmap.createBitmap(max(1, wPx), max(1, hPx), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val day = d?.day(now)
        if (d == null || day == null) { empty(cv, wPx.toFloat(), hPx.toFloat(), dpPx, fonts, d?.ar ?: true); return bmp }
        val st = sty(if (type == "pano" || type == "window") (if (type == "window" && style.bg in setOf("light", "dark", "navy", "dyn")) style.bg else "sky") else style.bg)
        val x = X(d, day, now, st, fonts, dpPx * fitScale(type, wPx / dpPx, hPx / dpPx), d.ar, style, wPx.toFloat(), hPx.toFloat())
        when (type) {
            "pano" -> pano(cv, x); "window" -> window(cv, x); "ring" -> ring(cv, x); "orbit" -> orbit(cv, x)
            "script" -> script(cv, x); "table" -> table(cv, x); "clock" -> clock(cv, x); "moon" -> moonW(cv, x)
            "ribbon" -> ribbon(cv, x); "exit" -> exit(cv, x); "actions" -> actions(cv, x, Btns()); "line" -> line(cv, x)
            "prayed" -> prayed(cv, x); "night" -> nightW(cv, x); "ramadan" -> ramadan(cv, x); "white" -> white(cv, x)
            "column" -> column(cv, x)
        }
        return bmp
    }
    /** The states of the buttons widget: done (✓) or not, and whether there is anything to do. */
    data class Btns(val prayed: Boolean = false, val muted: Boolean = false, val reminded: Boolean = false, val canPray: Boolean = true, val canRemind: Boolean = true, val remAt: Long = 0L)

    fun renderActions(style: WStyle, d: WData?, now: Long, wPx: Int, hPx: Int, dpPx: Float, fonts: WFonts, b: Btns): Bitmap {
        d?.let { it.cf = style.cf.ifEmpty { it.appCf }; it.dhuhrDay = (it.next(now)?.let { nx -> it.day(nx.at) } ?: it.day(now))?.optLong("dhuhr") ?: now }
        val bmp = Bitmap.createBitmap(max(1, wPx), max(1, hPx), Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp); val day = d?.day(now)
        if (d == null || day == null) { empty(cv, wPx.toFloat(), hPx.toFloat(), dpPx, fonts, d?.ar ?: true); return bmp }
        actions(cv, X(d, day, now, sty(style.bg), fonts, dpPx * fitScale("actions", wPx / dpPx, hPx / dpPx), d.ar, style, wPx.toFloat(), hPx.toFloat()), b)
        return bmp
    }
    /** Widgets laid out in dp grow with the space they get (a 4×2 made taller, a big phone), keeping their balance. */
    private fun fitScale(type: String, wDp: Float, hDp: Float): Float {
        val wide = wDp / hDp > 1.8f
        val (dw, dh) = when (type) { "pano", "orbit", "actions" -> 340f to 170f; "exit", "line", "script" -> 340f to 90f; "ribbon" -> 340f to 96f
            // the extra ones come wide or square; square ones are laid out from their own size
            "prayed", "night" -> if (wide) 340f to 90f else return 1f
            "ramadan", "white" -> if (wide) 340f to 170f else return 1f
            else -> return 1f }
        return min(wDp / dw, hDp / dh).coerceIn(.8f, 2.2f)
    }
    private fun empty(cv: Canvas, w: Float, h: Float, u: Float, f: WFonts, ar: Boolean) {
        cv.drawRoundRect(RectF(0f, 0f, w, h), 22 * u, 22 * u, paint().apply { shader = LinearGradient(0f, 0f, w * .35f, h, c("#11594A"), c("#0A382C"), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = f.plexB; textSize = 13 * u; color = c("#F4ECDC"); textAlign = Paint.Align.CENTER }
        val s = if (ar) "افتح «الفجر الصادق» مرة لتظهر الأوقات" else "Open True Fajr once to show the times"
        fit(p, s, w - 24 * u); cv.drawText(s, w / 2, h / 2 + p.textSize * .35f, p)
    }

    // ---------- 1. panorama (4×2) and window (2×2): the real sky now ----------
    private fun pano(cv: Canvas, x: X) {
        val w = x.w; val h = x.h; val r = x.dp(22f)
        // light, green or the phone's colours: the sky on top and a strip for the text under it
        val strip = x.style.bg in setOf("light", "dark", "navy", "dyn"); val ss = sty(x.style.bg); val sh = if (strip) h * .64f else h
        cv.save(); val clip = Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) }; cv.clipPath(clip)
        if (strip) card(cv, x, r, x.style.bg)
        cv.save(); cv.clipRect(0f, 0f, w, sh)
        val (top, bot) = skyAt(x, x.sky)
        cv.drawRect(0f, 0f, w, sh, paint().apply { shader = LinearGradient(0f, 0f, 0f, sh, top, bot, Shader.TileMode.CLAMP) })
        stars(cv, x, 0f, 0f, w, sh * .6f)
        val cx = w / 2; val cy = sh * (if (strip) .80f else .576f); val rx = w * .393f; val ry = sh * (if (strip) .62f else .412f)
        fun pt(q: Float) = floatArrayOf(cx + x.dir * rx * cos(PI * q).toFloat(), cy - ry * sin(PI * q).toFloat())
        val arc = Path(); for (i in 0..60) { val q = pt(i / 60f); if (i == 0) arc.moveTo(q[0], q[1]) else arc.lineTo(q[0], q[1]) }
        cv.drawPath(arc, paint(a(.45f, c("#FFFAEB"))).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(1.4f); pathEffect = DashPathEffect(floatArrayOf(x.dp(2f), x.dp(5f)), 0f) })
        val span = max(1L, x.t("maghrib") - x.t("sunrise"))
        val day = isDay(x)
        val body = if (day) pt(dayFrac(x)) else { val m = .15f + .7f * nightFrac(x); floatArrayOf(cx + x.dir * w * .337f * cos(PI * m).toFloat(), cy - ry * .8f * sin(PI * m).toFloat()) }
        val lp = tp(x, x.plex, 10f, c("#FFF8EA"), true)
        for (k in listOf("sunrise", "dhuhr", "asr", "maghrib")) {
            val q = pt(((x.t(k) - x.t("sunrise")).toFloat() / span).coerceIn(0f, 1f)); val on = k == x.next?.k
            cv.drawCircle(q[0], q[1], x.dp(if (on) 5f else 3f), paint(if (on) c("#F3D58C") else a(.7f, c("#FFFAEB"))))
            if (!x.style.has("labels")) continue
            lp.typeface = if (on) x.plexB else x.plex; lp.alpha = if (on) 255 else 190
            val near = kotlin.math.hypot(q[0] - body[0], q[1] - x.dp(12f) - body[1]) < x.dp(22f)
            if (near) continue   // the sun is on it (it marks the time itself), as in the app
            center(cv, x.d.name(k), q[0], q[1] - x.dp(9f), lp)
        }
        if (day) sun(cv, x, body[0], body[1], 9f) else if (x.style.has("sky")) moon(cv, x, body[0], body[1], x.dp(8f))
        hills(cv, x, 0f, sh * .7f, w, sh * .3f)
        if (!strip) cv.drawRect(0f, sh * .54f, w, sh, paint().apply { shader = LinearGradient(0f, sh, 0f, sh * .54f, a(.42f, Color.BLACK), 0, Shader.TileMode.CLAMP) })
        cv.restore()
        // small lines in the top corners: the Hijri date and when the time we are in ends
        val cp = tp(x, x.plex, 10.5f, c("#FFF8EA"), true); val pad = x.dp(14f)
        if (x.style.has("hijri")) text(cv, x, hijriTxt(x), startX(x, pad), x.dp(20f), cp)
        if (x.style.has("exit")) text(cv, x, exitTxt(x), endX(x, pad), x.dp(20f), cp, false)
        val nx = x.next; if (nx == null) { cv.restore(); return }
        val fg = if (strip) ss.fg else c("#FFF8EA"); val sw = !strip
        val base = if (strip) sh + (h - sh) / 2 + x.dp(9f) else h - x.dp(16f)
        val np = tp(x, x.nameTf, if (strip) 24f else 30f, fg, sw); val tpp = tp(x, x.plex, if (strip) 16f else 19f, fg, sw)
        val inS = x.d.inTxt(nx, x.now); val ip = tp(x, x.plexB, 12.5f, if (strip) ss.acc else c("#FFF8EA"), false)
        var name = x.d.name(nx.k); val tm = x.d.clock(nx.at) + " " + x.d.ampm(nx.at)
        fit(np, name, w * .45f)
        // stretched, it fills what the time and the pill leave on its line
        name = kashName(x, np, name, w - 2 * pad - (if (x.style.has("time")) tpp.measureText(tm) + x.dp(8f) else 0f) - (if (x.style.has("count")) pillW(ip, inS) + x.dp(8f) else 0f) - 2 * kSafe(x))
        text(cv, x, name, startX(x, pad), base, np)
        if (x.style.has("time")) text(cv, x, tm, startX(x, pad) - x.dir * (np.measureText(name) + x.dp(8f)), base, tpp)
        if (x.style.has("count")) { val pw = pillW(ip, inS); pill(cv, x, inS, endX(x, pad) + x.dir * pw / 2, base - ip.textSize * .45f, ip, if (strip) ss.line else a(.3f, Color.BLACK)) }
        cv.restore()
    }
    private fun window(cv: Canvas, x: X) {
        // light, green or the phone's colours: a card behind the arch
        val framed = x.style.bg in setOf("light", "dark", "navy", "dyn")
        if (framed) card(cv, x, x.dp(22f), x.style.bg)
        val s = min(x.w, x.h) * (if (framed) .86f else 1f); val ox = (x.w - s) / 2; val oy = (x.h - s) / 2 + (if (framed) s * .04f else 0f); val k = s / 170f
        fun X0(v: Float) = ox + v * k
        fun Y0(v: Float) = oy + v * k
        val arch = Path().apply { moveTo(X0(8f), Y0(170f)); lineTo(X0(8f), Y0(78f)); cubicTo(X0(8f), Y0(40f), X0(46f), Y0(14f), X0(85f), Y0(4f))
            cubicTo(X0(124f), Y0(14f), X0(162f), Y0(40f), X0(162f), Y0(78f)); lineTo(X0(162f), Y0(170f)); close() }
        cv.save(); cv.clipPath(arch)
        val (top, bot) = skyAt(x, x.sky)
        cv.drawRect(X0(0f), Y0(0f), X0(170f), Y0(170f), paint().apply { shader = LinearGradient(0f, Y0(0f), 0f, Y0(170f), top, bot, Shader.TileMode.CLAMP) })
        stars(cv, x, X0(10f), Y0(20f), 150 * k, 90 * k)
        if (isDay(x)) { val q = dayFrac(x); sun(cv, x, X0(85f + x.dir * 62f * cos(PI * q).toFloat()), Y0(150f - 110f * sin(PI * q).toFloat()), 6f * k / x.u) }
        else if (x.style.has("sky")) { val m = .15f + .7f * nightFrac(x); moon(cv, x, X0(85f + x.dir * 50f * cos(PI * m).toFloat()), Y0(150f - 90f * sin(PI * m).toFloat()), 7 * k) }
        hills(cv, x, X0(0f), Y0(118f), 170 * k, 52 * k)
        cv.drawRect(X0(0f), Y0(95f), X0(170f), Y0(170f), paint().apply { shader = LinearGradient(0f, Y0(170f), 0f, Y0(95f), a(.4f, Color.BLACK), 0, Shader.TileMode.CLAMP) })
        cv.restore()
        val edge = Path().apply { moveTo(X0(8f), Y0(170f)); lineTo(X0(8f), Y0(78f)); cubicTo(X0(8f), Y0(40f), X0(46f), Y0(14f), X0(85f), Y0(4f)); cubicTo(X0(124f), Y0(14f), X0(162f), Y0(40f), X0(162f), Y0(78f)); lineTo(X0(162f), Y0(170f)) }
        cv.drawPath(edge, paint(GOLD_L).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f * k })
        val nx = x.next ?: return
        val lines = joinDot(if (x.style.has("time")) x.d.clock(nx.at) else "", if (x.style.has("count")) x.d.inTxt(nx, x.now) else "")
        val np = tp(x, x.nameTf, 22f, c("#FFF8EA")).apply { textSize = 22f * k }; var name = x.d.name(nx.k); fit(np, name, 130 * k)
        // stretched, it stays inside the arch: its straight sides (8 to 162 here) less the gold edge and a margin each side
        name = kashName(x, np, name, (154f - 2.2f) * k - 2 * max(kSafe(x), 6 * k))
        val ny = if (lines.isEmpty()) Y0(150f) else Y0(140f)
        if (x.style.has("hijri")) center(cv, hijriTxt(x), X0(85f), ny - np.textSize * 1.05f, tp(x, x.plex, 10f, c("#FFF8EA")).apply { textSize = 10f * k })
        center(cv, name, X0(85f), ny, np)
        if (lines.isNotEmpty()) { val lp = tp(x, x.plexB, 12f, c("#FFF8EA")).apply { textSize = 12f * k }; fit(lp, lines, 140 * k); center(cv, lines, X0(85f), Y0(158f), lp) }
    }
    // ---------- 2. ring (2×2) ----------
    private val RC = mapOf("fajr" to "#8FB3EA", "dhuhr" to "#E8C15A", "asr" to "#E89A4A", "maghrib" to "#EC7F62", "isha" to "#9FA2EE")
    private fun ring(cv: Canvas, x: X) {
        card(cv, x); val nx = x.next ?: return; val pv = x.prev
        val s = min(x.w, x.h); val cx = x.w / 2; val cy = x.h / 2; val r = s * .353f; val sw = s * .059f
        val ov = RectF(cx - r, cy - r, cx + r, cy + r)
        cv.drawCircle(cx, cy, r, paint(x.st.line).apply { style = Paint.Style.STROKE; strokeWidth = sw })
        val prog = if (nx.iqAt > 0) ((x.now - nx.at).toFloat() / max(1L, nx.iqAt - nx.at)).coerceIn(0f, 1f)
                   else if (pv == null) 0f else ((x.now - pv.at).toFloat() / max(1L, nx.at - pv.at)).coerceIn(0f, 1f)
        val col = if (x.st.kind == "dyn") x.st.acc else c(RC[nx.k] ?: "#E8C15A")
        cv.drawArc(ov, -90f, -360f * prog * x.dir, false, paint(col).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND })
        val k = s / 170f
        val sub = joinDot(if (x.style.has("time")) x.d.clock(nx.at) else "", if (x.style.has("count")) x.d.count(nx, x.now) else "")
        val top = if (nx.iqAt > 0) (if (x.d.since) (if (x.d.ar) "منذ الأذان" else "since the adhan") else if (x.d.ar) "حتى الإقامة" else "until iqama") else if (pv != null) x.d.name(pv.k) + (if (x.rtl) " ← " else " → ") + x.d.name(nx.k) else ""
        if (top.isNotEmpty() && x.style.has("prev")) center(cv, top, cx, cy - 18 * k, tp(x, x.plex, 10.5f, x.st.sub).apply { textSize = 10.5f * k })
        val np = tp(x, x.nameTf, 24f, x.st.fg).apply { textSize = 24f * k }; val ny = cy + (if (sub.isEmpty()) 16 else 9) * k; val n0 = x.d.name(nx.k)
        // stretched, it stays inside the ring's inner edge, at the height of its letters
        center(cv, kashName(x, np, n0, chord(np, n0, ny, cy, r - sw / 2, r - sw / 2) - 2 * kSafe(x)), cx, ny, np)
        if (sub.isNotEmpty()) { val p = tp(x, x.plexB, 13f, col).apply { textSize = 13f * k }; fit(p, sub, r * 1.5f); center(cv, sub, cx, cy + 28 * k, p) }
    }
    // ---------- 3. the day's orbit (4×2) ----------
    private fun orbit(cv: Canvas, x: X) {
        card(cv, x); val w = x.w; val h = x.h
        val cx = w / 2; val cy = h / 2; val rx = w * .421f; val ry = h * .365f
        val nx0 = x.next
        val np = tp(x, x.nameTf, 26f, x.st.fg); val tmP = tp(x, x.plex, 18f, x.st.fg)
        var name = nx0?.let { x.d.name(it.k) } ?: ""; val tm = if (x.style.has("time")) nx0?.let { x.d.clock(it.at) } ?: "" else ""
        val gap = x.dp(8f)
        // stretched, name and time stay inside the orbit at the height of the letters, clear of the dots on the horizon
        if (nx0 != null) { val half = min(rx - x.dp(8f), chord(np, name, cy + x.dp(2f), cy, rx, ry) / 2 - x.dp(1.3f))
            name = kashName(x, np, name, 2 * (half - kSafe(x)) - gap - tmP.measureText(tm)) }
        val tw = np.measureText(name) + gap + tmP.measureText(tm)
        val hp = paint(x.st.line).apply { strokeWidth = x.dp(1f) }
        if (nx0 == null) cv.drawLine(w * .03f, cy, w * .97f, cy, hp)
        else { cv.drawLine(w * .03f, cy, cx - tw / 2 - x.dp(10f), cy, hp); cv.drawLine(cx + tw / 2 + x.dp(10f), cy, w * .97f, cy, hp) }
        fun at(ang: Double) = floatArrayOf(cx + x.dir * rx * cos(ang).toFloat(), cy + ry * sin(ang).toFloat())
        val upper = Path(); val lower = Path()
        for (i in 0..60) { val p1 = at(-PI * i / 60); val p2 = at(PI * i / 60); if (i == 0) { upper.moveTo(p1[0], p1[1]); lower.moveTo(p2[0], p2[1]) } else { upper.lineTo(p1[0], p1[1]); lower.lineTo(p2[0], p2[1]) } }
        cv.drawPath(upper, paint(a(.55f, x.st.sub)).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(1.6f) })
        cv.drawPath(lower, paint(a(.45f, x.st.sub)).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(1.4f); pathEffect = DashPathEffect(floatArrayOf(x.dp(2f), x.dp(5f)), 0f) })
        val sr = x.t("sunrise"); val mg = x.t("maghrib")
        // angle on the orbit: day goes over the top (0 → -π), night under the horizon (π → 0 through +π/2)
        fun angOf(t: Long): Double {
            if (t in sr..mg) return -PI * (t - sr).toDouble() / max(1L, mg - sr)
            val m0 = if (t > mg) mg else (x.d.day(x.now, -1)?.optLong("maghrib") ?: (mg - WData.DAY))
            val r1 = if (t > mg) (x.d.day(x.now, 1)?.optLong("sunrise") ?: (sr + WData.DAY)) else sr
            return PI - PI * ((t - m0).toDouble() / max(1L, r1 - m0)).coerceIn(0.0, 1.0)
        }
        val day = isDay(x); val a0 = if (day) 0.0 else PI; val a1 = angOf(x.now)
        val trail = Path(); for (i in 0..40) { val q = at(a0 + (a1 - a0) * i / 40); if (i == 0) trail.moveTo(q[0], q[1]) else trail.lineTo(q[0], q[1]) }
        cv.drawPath(trail, paint(if (day) (if (x.st.light) GOLD_L else c("#F3D58C")) else x.st.sub).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(2.6f); strokeCap = Paint.Cap.ROUND })
        for (k in WData.SIX) {
            var t = x.t(k); if (k == "fajr" && t < x.now && !day && x.now > mg) t = x.d.day(x.now, 1)?.optLong("fajr") ?: t
            val q = at(angOf(t)); val on = k == x.next?.k
            if (on) { cv.drawCircle(q[0], q[1], x.dp(8f), paint(a(.3f, if (x.st.light) GOLD_L else GOLD))); cv.drawCircle(q[0], q[1], x.dp(5f), paint(if (x.st.light) GOLD_L else GOLD)) }
            else cv.drawCircle(q[0], q[1], x.dp(3f), paint(x.st.sub))
        }
        val s = at(a1)
        if (day) sun(cv, x, s[0], s[1], 7.5f) else cv.drawCircle(s[0], s[1], x.dp(4.5f), paint(x.st.sub).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(2f) })
        val nx = nx0 ?: return
        val y = cy + x.dp(2f); val xs = cx + x.dir * tw / 2
        text(cv, x, name, xs, y, np); text(cv, x, tm, xs - x.dir * (np.measureText(name) + gap), y, tmP)
        var yy = y + x.dp(20f)
        if (x.style.has("count")) { val ip = tp(x, x.plexB, 12f, if (x.st.light) c("#0B4D3B") else x.st.fg, false)
            pill(cv, x, x.d.inTxt(nx, x.now), cx, yy, ip, if (x.st.light) a(.09f, c("#0B4D3B")) else a(.14f, Color.WHITE)); yy += x.dp(20f) }
        val extra = joinDot(if (x.style.has("hijri")) hijriTxt(x) else "", if (x.style.has("exit")) exitTxt(x) else "")
        if (extra.isNotEmpty()) { val ep = tp(x, x.plex, 10.5f, x.st.sub); fit(ep, extra, x.w * .6f); center(cv, extra, cx, yy + x.dp(2f), ep) }
    }

    // ---------- 4. script (4×1, 2×2) and one line (4×1) ----------
    private fun script(cv: Canvas, x: X) {
        card(cv, x); val nx = x.next ?: return; val w = x.w; val h = x.h; val pad = x.dp(16f)
        // the other side: a stack of the lines the user picked, sized from the widget's height
        val lines = ArrayList<Pair<String, Paint>>()
        if (x.style.has("time")) lines += x.d.clock(nx.at) to tp(x, x.plex, 1f, x.st.fg).apply { textSize = h * .30f }
        if (x.style.has("count")) lines += x.d.inTxt(nx, x.now) to tp(x, x.plex, 1f, x.st.sub).apply { textSize = h * .15f }
        if (x.style.has("hijri")) lines += hijriTxt(x) to tp(x, x.plex, 1f, x.st.sub).apply { textSize = h * .13f }
        if (x.style.has("exit") && x.cur != null) lines += exitTxt(x) to tp(x, x.plex, 1f, x.st.sub).apply { textSize = h * .13f }
        val colW = if (lines.isEmpty()) 0f else lines.maxOf { (t, p) -> p.measureText(t) }.coerceAtMost(w * .38f)
        val room = w - 2 * pad - (if (lines.isEmpty()) 0f else colW + x.dp(18f))
        val np = tp(x, x.nameTf, 1f, x.st.fg).apply { textSize = h * .62f }
        var name = x.d.name(nx.k); fit(np, name, room)
        if (x.style.kash) name = stretch(np, name, room * (if (lines.isEmpty()) .92f else .97f), x.style.kl)
        val ny = h / 2 + np.textSize * .3f
        if (lines.isEmpty()) { center(cv, name, w / 2, ny, np); return }
        text(cv, x, name, startX(x, pad), ny, np)
        lines.forEach { (t, p) -> fit(p, t, w * .38f) }
        val total = lines.sumOf { (it.second.textSize * 1.2f).toDouble() }.toFloat()
        var y = h / 2 - total / 2
        for ((t, p) in lines) { y += p.textSize * 1.2f; text(cv, x, t, endX(x, pad), y - p.textSize * .22f, p, false) }
    }
    private fun line(cv: Canvas, x: X) {
        card(cv, x, x.dp(20f))
        if (x.style.ln != "pray") { lineText(cv, x); return }
        val nx = x.next ?: return
        val np = tp(x, x.nameTf, 1f, x.st.fg).apply { textSize = x.h * .36f }; val rp = tp(x, x.plex, 1f, x.st.fg).apply { textSize = x.h * .27f }
        val parts = ArrayList<Pair<String, Paint>>(); parts += x.d.name(nx.k) to np
        val rest = listOf(if (x.style.has("time")) x.d.clock(nx.at) else "", if (x.style.has("count")) x.d.inTxt(nx, x.now) else "", if (x.style.has("hijri")) hijriTxt(x) else "").filter { it.isNotEmpty() }
        rest.forEachIndexed { i, t -> if (i > 0) parts += "·" to rp; parts += t to rp }
        val gap = x.dp(8f); val total = parts.sumOf { it.second.measureText(it.first).toDouble() }.toFloat() + gap * (parts.size - 1)
        val scale = min((x.w - x.dp(32f)) / total, x.h * .62f / np.textSize); np.textSize *= scale; rp.textSize *= scale
        // stretched, the name takes what the height-bound size leaves free on the line (none when the width bounds it)
        val n0 = parts[0].first; val others = parts.drop(1).sumOf { it.second.measureText(it.first).toDouble() }.toFloat() + gap * scale * (parts.size - 1)
        parts[0] = kashName(x, np, n0, x.w - x.dp(32f) - 2 * kSafe(x) - others) to np
        var xs = x.w / 2 + x.dir * (total * scale + np.measureText(parts[0].first) - np.measureText(n0)) / 2; val y = x.h / 2 + (if (rest.isEmpty()) np.textSize * .3f else rp.textSize * .35f)
        for ((s, p) in parts) { text(cv, x, s, xs, y, p); xs -= x.dir * (p.measureText(s) + gap * scale) }
    }
    /** The one-line widget with the day or the date instead of the prayer (0.22.6): one line in the name's font, as large as the
     *  height allows and never past the frame, stretched with kashida like a name when the user picks it. */
    private fun lineText(cv: Canvas, x: X) {
        val dd = x.day; val ar = x.d.ar
        val hij = x.d.digits(dd.optString("hd") + " " + dd.optString("hm") + " " + dd.optString("hy")) + if (ar) "هـ" else " AH"
        val greg = dd.optString("gr").ifEmpty { dd.optString("d") }.let { x.d.digits(it) + if (ar && it.isNotEmpty()) "م" else "" }
        val s = when (x.style.ln) { "day" -> dd.optString("wd"); "hijri" -> hij; "greg" -> greg; else -> dd.optString("wd") + "  " + hij }.trim()
        if (s.isEmpty()) return
        val p = tp(x, x.nameTf, 1f, x.st.fg).apply { textSize = x.h * .5f }; val room = x.w - x.dp(32f)
        p.textSize *= min(1f, room / max(1f, p.measureText(s))); p.textSize = min(p.textSize, x.h * .62f)
        val out = kashName(x, p, s, room - 2 * kSafe(x))
        center(cv, out, x.w / 2, x.h / 2 + p.textSize * .3f, p)
    }
    // ---------- 5. table (4×1 up to 4×4): rows the user picks ----------
    private fun table(cv: Canvas, x: X) {
        card(cv, x); val nx = x.next ?: return
        val rows = x.style.rows; val showT = "times" in rows || rows.none { it in setOf("city", "date", "count", "iqama") }
        val hasHead = "city" in rows || "date" in rows; val hasCount = "count" in rows; val hasIq = "iqama" in rows
        val subRow = "exit" in rows || !hasCount
        // the city and the date on one line: on top at the end (over the countdown, the default), on top in the middle, or at the bottom (0.20)
        val headAt = if (!hasHead) "" else x.style.hp.takeIf { it == "c" || it == "b" } ?: "l"
        val need = (if (hasHead) 22f else 0f) + (if (hasCount) 34f else 0f) + (if (showT) (if (subRow) 60f else 48f) else 0f) + (if (hasIq) 32f else 0f) + 8f
        val k = minOf(1.8f, x.h / x.u / need, x.w / x.u / 240f); val u = x.u * k
        val w = x.w; val pad = 12 * u; val hb = Rect()
        // after Isha the row shows tomorrow
        val tday = if (WData.dateKey(nx.at) != WData.dateKey(x.now)) (x.d.day(nx.at) ?: x.day) else x.day
        fun tt(k: String) = tday.optLong(k)
        // ---- measure: every block by its ink, so the margins look the same above and below
        // never smaller than 10.5dp: a short widget (4×1) shortens the line instead of shrinking it to unreadable (0.22)
        val hp = tp(x, x.plex, 11.5f, x.st.sub).apply { textSize = max(11.5f * u, 10.5f * x.u) }
        val dd = x.day; val date = listOf(dd.optString("wd"), dd.optString("hd") + " " + dd.optString("hm") + " " + dd.optString("hy")).joinToString(" · ")
        // narrow (4×1): the year goes first, then the date, before the line would shrink past readable (0.22)
        val cityS = x.d.city.takeIf { "city" in rows && it.isNotEmpty() }
        val noYear = listOf(dd.optString("wd"), dd.optString("hd") + " " + dd.optString("hm")).joinToString(" · ")
        val heads = listOf(date, noYear, dd.optString("wd")).map { d -> listOfNotNull(cityS, x.d.digits(d).takeIf { "date" in rows }).joinToString(" · ") }.distinct()
        val hs = heads.firstOrNull { hp.measureText(it) <= (w - 2 * pad) / .85f } ?: heads.last()
        var hTop = 0f; var hH = 0f
        if (hasHead) { fit(hp, hs, w - 2 * pad); hp.getTextBounds(hs, 0, hs.length, hb); hTop = hb.top.toFloat(); hH = (hb.bottom - hb.top).toFloat() }
        val gH = 7 * u; val gR = 6 * u; val gI = 6 * u
        val rowH = if (subRow) 52 * u else 40 * u; val iqH = 28 * u
        // the gold cell of the times row reaches past its text; it counts half its padding, so the row's text sits as far from the frame as the header
        val rowIn = 3.5f * u
        val below = listOfNotNull(if (showT) rowH - 2 * rowIn else null, if (hasIq) iqH else null)
        val fixed = (if (hasHead) hH + gH else 0f) + below.sum() + (if (below.size > 1) gI - rowIn else 0f) + (if (hasCount && below.isNotEmpty()) gR - rowIn else 0f)
        val margin = max(8 * u, kSafe(x))
        // the headline: the name sized by the five names' ink (one size for every prayer, so it doesn't jump),
        // growing into the height the widget has spare (a share of it, so it never swallows the frame)
        var bandH = 0f; val np = tp(x, x.nameTf, 22f, x.st.fg); var t0 = 0f; var b0 = 0f
        val cp = tp(x, x.plexB, 21f, if (x.st.light) c("#0B4D3B") else x.st.acc)
        val inS = x.d.inTxt(nx, x.now) + if (!showT) "  " + x.d.clock(nx.at) else ""
        var name = ""; var ps = pad
        if (hasCount) {
            np.textSize = 100f
            for (s in (WData.PRAY + nx.k).distinct().map { x.d.name(it) } + TATWEEL.toString()) { np.getTextBounds(s, 0, s.length, hb); t0 = min(t0, hb.top.toFloat()); b0 = max(b0, hb.bottom.toFloat()) }
            val inkH = b0 - t0 + (if (np.heavy) 3.5f else 0f)
            val avail = x.h - 2 * margin - fixed; val nominal = 26 * u
            bandH = max(8 * u, if (avail > nominal) nominal + min((avail - nominal) * .6f, 12 * u) else avail)
            np.textSize = max(6f, 100f * bandH / inkH)
            cp.textSize = max(21f * u, np.textSize * .5f); fit(cp, inS, (w - 2 * pad) * .46f)
            // the name's room: from the start padding (more if its ink pokes past its start) to the countdown, less the gap
            val n0 = x.d.name(nx.k); np.getTextBounds(n0, 0, n0.length, hb)
            ps = max(pad, kSafe(x) + max(0f, if (x.rtl) hb.right - np.measureText(n0) else -hb.left.toFloat()))
            val room = w - ps - pad - cp.measureText(inS) - max(12 * u, x.dp(12f))
            // too wide as it is: smaller, not stretched, and the band shrinks with it (the spare goes back to the margins)
            val wide = inkW(np, n0) > room
            while (inkW(np, n0) > room && np.textSize > 6f) np.textSize *= .97f
            if (wide) bandH = inkH * np.textSize / 100f
            name = if (wide) n0 else kashName(x, np, n0, room)
        }
        val total = fixed + bandH
        // ---- place, from the top: the rest of the height is shared equally above and below
        var y = (x.h - total) / 2
        fun head(top: Float) { val base = top - hTop
            if (headAt == "l") text(cv, x, hs, endX(x, pad), base, hp, false) else center(cv, hs, w / 2, base, hp) }
        if (hasHead && headAt != "b") { head(y); y += hH + gH }
        if (hasCount) {
            // sized by all five names, placed by this one's own ink, in the middle of the band
            np.getTextBounds(name, 0, name.length, hb); val base = y + bandH / 2 - (hb.top + hb.bottom) / 2f
            // the countdown on the same baseline, smaller should its ink ever pass the band
            cp.getTextBounds(inS, 0, inS.length, hb)
            while ((base + hb.top < y - 2 * u || base + hb.bottom > y + bandH + 2 * u) && cp.textSize > 6f) { cp.textSize *= .95f; cp.getTextBounds(inS, 0, inS.length, hb) }
            text(cv, x, name, startX(x, ps), base, np)
            text(cv, x, inS, endX(x, pad), base, cp, false)
            y += bandH + (if (below.isNotEmpty()) gR - rowIn else 0f)
        }
        if (showT) {
            y -= rowIn
            // the five prayers; sunrise and Duha only if the user adds them
            val keys = listOf("fajr") + (if ("sunrise" in rows) listOf("sunrise") else emptyList()) + (if ("duha" in rows) listOf("duha") else emptyList()) + listOf("dhuhr", "asr", "maghrib", "isha")
            val n = keys.size; val cw = (w - 16 * u) / n
            val ex = tday.optJSONObject("ex")
            for ((i, key) in keys.withIndex()) {
                val col = if (x.rtl) i else n - 1 - i; val left = w - 8 * u - (col + 1) * cw; val ccx = left + cw / 2
                val on = key == nx.k; val past = !on && tt(key) <= x.now
                if (on) cv.drawRoundRect(RectF(left + 1 * u, y, left + cw - 1 * u, y + rowH), 14 * u, 14 * u, paint(if (x.st.light) GOLD_L else GOLD))
                val fg = if (on) INK else x.st.fg; val alpha = if (past) 115 else 255
                val p1 = tp(x, x.plex, 11f, fg, !on && x.st.shadow).apply { textSize = 11f * u; this.alpha = alpha }
                val p2 = tp(x, x.plexB, if (on) 16f else 14f, fg, !on && x.st.shadow).apply { textSize = (if (on) 16f else 14f) * u; this.alpha = alpha }
                fit(p2, x.d.clock(tt(key)), cw - 4 * u); fit(p1, x.d.name(key), cw - 4 * u)
                center(cv, x.d.name(key), ccx, y + 15 * u, p1)
                center(cv, x.d.clock(tt(key)), ccx, y + 33 * u, p2)
                if ("exit" in rows || (on && !hasCount)) {
                    val sub = if (on && !hasCount) x.d.inTxt(nx, x.now) else ex?.optJSONArray(key)?.let { if (it.length() > 0) (if (x.d.ar) "حتى " else "to ") + x.d.clock(it.getJSONObject(0).optLong("at")) else "" } ?: ""
                    val p3 = tp(x, if (on) x.plexB else x.plex, 9.5f, fg, !on && x.st.shadow).apply { textSize = 9.5f * u; this.alpha = if (on) 255 else if (past) 90 else 180 }
                    fit(p3, sub, cw - 6 * u); center(cv, sub, ccx, y + 47 * u, p3)
                }
            }
            y += rowH - rowIn + (if (hasIq) gI - rowIn else 0f)
        }
        if (hasIq) {
            val iq = nx.at + x.d.iqMin(nx.k) * 60_000L
            cv.drawRoundRect(RectF(8 * u, y, w - 8 * u, y + iqH), 12 * u, 12 * u, paint(x.st.line))
            val p = tp(x, x.plex, 12.5f, x.st.fg).apply { textSize = 12.5f * u }; val pb = tp(x, x.plexB, 12.5f, x.st.fg).apply { textSize = 12.5f * u }
            text(cv, x, (if (x.d.ar) "إقامة " else "Iqama ") + x.d.name(nx.k) + " " + x.d.clock(iq), startX(x, 18 * u), y + 19 * u, p)
            text(cv, x, if (x.d.since && x.now in nx.at until iq) x.d.adhanSince(x.now - nx.at) else x.d.inTxt(iq - x.now), endX(x, 18 * u), y + 19 * u, pb, false)
            y += iqH
        }
        if (hasHead && headAt == "b") head(y + gH)
    }

    // ---------- 6. sky clock (2×2) and moon (2×2) ----------
    private fun clock(cv: Canvas, x: X) {
        card(cv, x); val s = min(x.w, x.h); val cx = x.w / 2; val cy = x.h / 2
        val ro = s * .42f; val ri = ro * .62f; val rm = (ro + ri) / 2; val sw = ro - ri
        // the day's sky colours around the dial: midnight at the bottom, noon at the top
        val keys = skyKeys(x); val d0 = WData.minuteOfDay(x.now).toLong(); val midnight = x.now - d0 * 60_000L
        val pts = keys.map { ((it.first - midnight) / 60_000L).toFloat() / 1440f to mix(it.second, it.third, .5f) }.filter { it.first in 0f..1f }.sortedBy { it.first }
        val night = c("#0E152C")
        val pos = (listOf(0f) + pts.map { it.first } + listOf(1f)).toFloatArray(); val cols = (listOf(night) + pts.map { it.second } + listOf(night)).toIntArray()
        val sh = SweepGradient(cx, cy, cols, pos); val m = Matrix(); m.postRotate(90f, cx, cy); if (x.rtl) m.postScale(-1f, 1f, cx, cy); sh.setLocalMatrix(m)
        cv.drawCircle(cx, cy, rm, paint().apply { style = Paint.Style.STROKE; strokeWidth = sw; shader = sh })
        // midnight at the bottom; Arabic turns counter-clockwise (morning on the right), English clockwise
        fun onDial(t: Long): FloatArray { val f = (t - midnight) / 60_000.0 / 1440.0; val ang = PI / 2 - x.dir * 2 * PI * f
            return floatArrayOf(cx + rm * cos(ang).toFloat(), cy + rm * sin(ang).toFloat()) }
        for (k in WData.PRAY) { val q = onDial(x.t(k)); val on = k == x.next?.k
            cv.drawCircle(q[0], q[1], x.dp(if (on) 5.5f else 3.6f), paint(a(.25f, Color.BLACK))); cv.drawCircle(q[0], q[1], x.dp(if (on) 4.5f else 3f), paint(c("#F3D58C"))) }
        val q = onDial(x.now)
        if (isDay(x)) sun(cv, x, q[0], q[1], 7f) else moon(cv, x, q[0], q[1], x.dp(6.5f))
        val nx = x.next ?: return; val k = s / 150f
        val l2 = ArrayList<Pair<String, Paint>>()
        if (x.style.has("time")) l2 += x.d.clock(nx.at) to tp(x, x.plex, 12f, x.st.fg).apply { textSize = 12f * k }
        if (x.style.has("count")) l2 += x.d.count(nx, x.now) to tp(x, x.plex, 10.5f, x.st.sub).apply { textSize = 10.5f * k }
        var y = cy + (if (l2.isEmpty()) 7 else if (l2.size == 1) 4 else 2) * k
        val np = tp(x, x.nameTf, 17f, x.st.fg).apply { textSize = 17f * k }; val n0 = x.d.name(nx.k)
        // stretched, it stays inside the dial's inner edge, at the height of its letters
        center(cv, kashName(x, np, n0, chord(np, n0, y, cy, ri, ri) - 2 * kSafe(x)), cx, y, np)
        for ((t, p) in l2) { y += 14 * k; center(cv, t, cx, y, p) }
    }

    private fun moonW(cv: Canvas, x: X) {
        card(cv, x); val s = min(x.w, x.h); val k = s / 150f; val pad = 14 * k; val dd = x.day
        val big = tp(x, x.plexB, 26f, x.st.fg).apply { textSize = 26f * k }
        text(cv, x, x.d.digits(dd.optString("hd")), startX(x, pad), pad + 24 * k, big)
        text(cv, x, dd.optString("hm"), startX(x, pad), pad + 40 * k, tp(x, x.plex, 11f, x.st.sub).apply { textSize = 11f * k })
        moon(cv, x, endX(x, pad) + x.dir * 23 * k, pad + 23 * k, 21 * k)
        text(cv, x, (if (x.style.has("wd")) dd.optString("wd") + " · " else "") + x.d.digits(dd.optString("hy")), startX(x, pad), pad + 72 * k, tp(x, x.plex, 11f, x.st.sub).apply { textSize = 11f * k })
        // made tall, the empty middle takes today's five prayers as compact column rows, once each row gets 19 dp
        val nx = x.next; val foot = x.style.has("next") && nx != null
        val midTop = pad + 82 * k; val midBot = if (foot) x.h - pad - 38 * k else x.h - pad
        val rh = min((midBot - midTop) / 5, 28 * k)
        if (rh >= x.dp(19f)) prayerRows(cv, x, WData.PRAY, midTop + (midBot - midTop - 5 * rh) / 2, rh, 0f, pad * .55f, x.w - pad * .55f, min(rh * .48f, 12.5f * k))
        if (nx == null || !foot) return
        cv.drawLine(pad, x.h - pad - 30 * k, x.w - pad, x.h - pad - 30 * k, paint(x.st.line).apply { strokeWidth = x.dp(1f) })
        text(cv, x, x.d.name(nx.k), startX(x, pad), x.h - pad - 8 * k, tp(x, x.nameTf, 16f, x.st.fg).apply { textSize = 16f * k })
        text(cv, x, x.d.clock(nx.at), endX(x, pad), x.h - pad - 8 * k, tp(x, x.plex, 12f, x.st.fg).apply { textSize = 12f * k }, false)
    }

    // ---------- 7. the day ribbon (4×1) ----------
    private fun ribbon(cv: Canvas, x: X) {
        card(cv, x); val nx = x.next ?: return; val w = x.w; val pad = x.dp(14f)
        val k = min(1.2f, x.h / x.u / 96f); val u = x.u * k
        val np = tp(x, x.nameTf, 16f, x.st.fg).apply { textSize = 16f * u }
        text(cv, x, x.d.name(nx.k) + " " + x.d.clock(nx.at), startX(x, pad), 24 * u, np)
        if (x.style.has("count")) text(cv, x, x.d.inTxt(nx, x.now), endX(x, pad), 24 * u, tp(x, x.plex, 12f, x.st.sub).apply { textSize = 12f * u }, false)
        val top = if (x.style.has("labels")) 34 * u else 40 * u; val bh = 18 * u; val l = pad; val r = w - pad; val bw = r - l
        val midnight = x.now - WData.minuteOfDay(x.now) * 60_000L
        fun xAt(t: Long): Float { val f = ((t - midnight) / 60_000f / 1440f).coerceIn(0f, 1f); return if (x.rtl) r - bw * f else l + bw * f }
        val keys = skyKeys(x).map { ((it.first - midnight) / 60_000f / 1440f) to mix(it.second, it.third, .55f) }.filter { it.first in 0f..1f }
        val pos = (listOf(0f) + keys.map { it.first } + listOf(1f)).toFloatArray(); val night = c("#0E152C")
        val cols = (listOf(night) + keys.map { it.second } + listOf(night)).toIntArray()
        val grad = if (x.rtl) LinearGradient(r, 0f, l, 0f, cols, pos, Shader.TileMode.CLAMP) else LinearGradient(l, 0f, r, 0f, cols, pos, Shader.TileMode.CLAMP)
        cv.drawRoundRect(RectF(l, top, r, top + bh), bh / 2, bh / 2, paint().apply { shader = grad })
        val lp = tp(x, x.plex, 9.5f, x.st.sub).apply { textSize = 9.5f * u }
        for ((i, key) in WData.PRAY.withIndex()) {
            val xx = xAt(x.t(key)); cv.drawRect(xx - u, top - 3 * u, xx + u, top + bh + 3 * u, paint(a(.75f, Color.WHITE)))
            if (!x.style.has("labels")) continue
            val on = key == nx.k; lp.typeface = if (on) x.plexB else x.plex; lp.color = if (on) x.st.fg else x.st.sub
            center(cv, x.d.name(key) + " " + x.d.clock(if (on) nx.at else x.t(key)), xx, top + bh + (if (i % 2 == 0) 14f else 27f) * u, lp)
        }
        val nxp = xAt(x.now)
        cv.drawCircle(nxp, top + bh / 2, 7 * u, paint(Color.WHITE)); cv.drawCircle(nxp, top + bh / 2, 7 * u, paint(if (x.st.light) c("#0B4D3B") else x.st.acc).apply { style = Paint.Style.STROKE; strokeWidth = 3 * u })
    }

    // ---------- 8. time left (4×1) ----------
    private fun exit(cv: Canvas, x: X) {
        card(cv, x); val w = x.w; val pad = x.dp(16f); val k = min(1.2f, x.h / x.u / 92f); val u = x.u * k
        val cw = x.cur; val nx = x.next
        val title: String; val left: String; val frac: Float; val until: String; val urgent: Boolean
        if (cw != null) {
            val bnd = if (x.now < cw.first) cw.first else cw.end; val nm = if (x.now < cw.first) cw.names.first() else cw.names.last()
            title = nm.replaceFirstChar { it.uppercase() }; left = x.d.leftTxt(bnd - x.now); urgent = bnd - x.now <= 30 * 60_000L
            val st0 = if (x.now < cw.first) cw.start else cw.first
            frac = ((bnd - x.now).toFloat() / max(1L, bnd - st0)).coerceIn(0f, 1f)
            until = (if (x.d.ar) "ينتهي " else "Ends ") + x.d.clock(bnd) + " " + x.d.ampm(bnd)
        } else {
            // the next time that begins (Duha included)
            var nk = nx?.k ?: ""; var nt = nx?.at ?: 0L
            for (o in 0..1) { val dd = x.d.day(x.now, o) ?: continue
                for (k in WData.EXK) { val t = dd.optLong(k); if (t > x.now && (nt == 0L || t < nt)) { nk = k; nt = t } } }
            val nm = x.d.name(nk)
            title = if (nt == 0L) "" else if (x.d.ar) "قبيل $nm" else "Just before $nm"
            left = if (nt == 0L) "" else x.d.inTxt(nt - x.now); frac = 0f; urgent = false
            until = if (nt == 0L) "" else if (x.d.ar) "يدخل وقت $nm " + x.d.clock(nt) + " " + x.d.ampm(nt) else "$nm begins " + x.d.clock(nt) + " " + x.d.ampm(nt)
        }
        val bar = x.style.has("bar") && cw != null; val sub = x.style.has("time")
        // the lines as one block, as big as the widget's height allows and centred in it (a taller widget fills, not leaves space)
        val ts = if (bar) 17f else 21f; val ss = if (bar) 11f else 12.5f
        val ch = ts * .8f + (if (bar) 22f else 0f) + (if (sub) 14f + ss * .8f else 0f)
        val k2 = minOf(1.8f, (x.h / x.u - 24f) / ch, x.w / x.u / 220f).coerceAtLeast(.6f); val u2 = x.u * k2
        val tp1 = tp(x, x.nameTf, ts, x.st.fg).apply { textSize = ts * u2 }; fit(tp1, title, w * .55f)
        val ip = tp(x, x.plexB, 12.5f, if (urgent) c("#241406") else if (x.st.light) c("#0B4D3B") else x.st.fg, false).apply { textSize = 12.5f * u2 }
        val pw = pillW(ip, left); fit(ip, left, w * .4f)
        var y = (x.h - ch * u2) / 2 + ts * .8f * u2
        // stretched, the title fills up to the pill
        text(cv, x, kashName(x, tp1, title, w - 2 * pad - pw - x.dp(8f) - 2 * kSafe(x)), startX(x, pad), y, tp1)
        pill(cv, x, left, endX(x, pad) + x.dir * pw / 2, y - ts * .3f * u2, ip, if (urgent) AMBER else if (x.st.light) c("#E3ECE6") else a(.16f, Color.WHITE))
        if (bar) {
            val bt = y + 14 * u2; val bh = 8 * u2; val l = pad; val r = w - pad
            cv.drawRoundRect(RectF(l, bt, r, bt + bh), bh / 2, bh / 2, paint(x.st.line))
            if (frac > 0f) { val bw = (r - l) * frac
                // what is left stays on the starting side and empties towards it (Arabic: from the left to the right)
                val rect = if (x.rtl) RectF(r - bw, bt, r, bt + bh) else RectF(l, bt, l + bw, bt + bh)
                cv.drawRoundRect(rect, bh / 2, bh / 2, paint(if (urgent) AMBER else if (x.st.light) c("#0B4D3B") else x.st.acc)) }
            y = bt + bh
        }
        if (sub) { y += 14 * u2 + ss * .8f * u2; val sp = tp(x, x.plex, ss, x.st.sub).apply { textSize = ss * u2 }; fit(sp, until, w - 2 * pad); text(cv, x, until, startX(x, pad), y, sp) }
    }

    // ---------- 9. with buttons (4×2): the three buttons sit in the bottom 45% ----------
    const val BTN_TOP = .55f
    private fun actions(cv: Canvas, x: X, b: Btns) {
        card(cv, x, x.dp(24f)); val nx = x.next ?: return; val w = x.w; val h = x.h; val pad = x.dp(14f)
        val topH = h * BTN_TOP - pad * .6f
        val ns = topH * .5f
        val np = tp(x, x.nameTf, 1f, x.st.fg).apply { textSize = ns }; val tm = tp(x, x.plex, 1f, x.st.fg).apply { textSize = ns * .6f }
        val cp = tp(x, x.plexB, 1f, if (x.st.light) c("#0B4D3B") else x.st.acc).apply { textSize = ns * .4f }
        val cnt = if (x.style.has("count")) x.d.inTxt(nx, x.now) else ""; val tms = if (x.style.has("time")) x.d.clock(nx.at) else ""
        val side = (if (cnt.isEmpty()) 0f else cp.measureText(cnt) + x.dp(12f)) + (if (tms.isEmpty()) 0f else tm.measureText(tms) + x.dp(10f))
        var name = x.d.name(nx.k); fit(np, name, w - 2 * pad - side)
        if (x.style.kash) name = stretch(np, name, (w - 2 * pad - side) * .97f, x.style.kl)
        val yy = pad * .6f + topH / 2 + np.textSize * .3f
        text(cv, x, name, startX(x, pad), yy, np)
        if (tms.isNotEmpty()) text(cv, x, tms, startX(x, pad) - x.dir * (np.measureText(name) + x.dp(10f)), yy, tm)
        if (cnt.isNotEmpty()) text(cv, x, cnt, endX(x, pad), yy, cp, false)
        val labels = listOf(
            if (b.prayed) "✓ " + (if (x.d.ar) "صلّيت" else "Prayed") else (if (x.d.ar) "صلّيت" else "I prayed"),
            if (b.muted) "✓ " + (if (x.d.ar) "الأذان مكتوم" else "Muted") else (if (x.d.ar) "كتم الأذان القادم" else "Mute next adhan"),
            if (b.reminded) "✓ " + (if (x.d.ar) "سنذكّرك" else "Reminder") + (if (b.remAt > 0) " " + x.d.clock(b.remAt) + (x.d.ampm(b.remAt).let { if (it.isEmpty()) "" else " $it" }) else "") else (if (x.d.ar) "ذكّرني قبل خروج الوقت" else "Remind before it ends"))
        val on = listOf(b.canPray || b.prayed, true, b.canRemind || b.reminded)
        val top = h * BTN_TOP; val bh = h - top - pad; val gap = x.dp(8f); val bw = (w - 2 * pad - 2 * gap) / 3
        for (i in 0..2) {
            val col = if (x.rtl) i else 2 - i; val l = w - pad - (col + 1) * bw - col * gap
            val rect = RectF(l, top, l + bw, top + bh)
            val sc = cv.saveLayerAlpha(rect.left - 2, rect.top - 2, rect.right + 2, rect.bottom + 2, if (on[i]) 255 else 100)
            cv.drawRoundRect(rect, x.dp(16f), x.dp(16f), paint(if (x.st.light) a(.07f, c("#0B4D3B")) else a(.1f, Color.WHITE)))
            cv.drawRoundRect(rect, x.dp(16f), x.dp(16f), paint(x.st.line).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(1f) })
            val ic = paint(x.st.fg).apply { style = Paint.Style.STROKE; strokeWidth = x.dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val icx = l + bw / 2; val icy = top + bh * .36f; val s = x.dp(8f)
            when (i) {
                0 -> { val p = Path(); p.moveTo(icx - s, icy); p.lineTo(icx - s * .25f, icy + s * .7f); p.lineTo(icx + s, icy - s * .6f); cv.drawPath(p, ic) }
                1 -> { cv.drawArc(RectF(icx - s * .75f, icy - s, icx + s * .75f, icy + s * .5f), 180f, 180f, false, ic); cv.drawLine(icx - s * .75f, icy - s * .25f, icx - s * .75f, icy + s * .55f, ic)
                       cv.drawLine(icx + s * .75f, icy - s * .25f, icx + s * .75f, icy + s * .55f, ic); cv.drawLine(icx - s, icy + s * .55f, icx + s, icy + s * .55f, ic); cv.drawLine(icx - s, icy - s, icx + s, icy + s, ic) }
                else -> { cv.drawCircle(icx, icy + s * .1f, s * .8f, ic); cv.drawLine(icx, icy - s * .3f, icx, icy + s * .15f, ic); cv.drawLine(icx, icy + s * .15f, icx + s * .35f, icy + s * .35f, ic) }
            }
            val lp = tp(x, x.plexB, 11.5f, x.st.fg, false); fit(lp, labels[i], bw - x.dp(6f))
            center(cv, labels[i], icx, top + bh * .8f, lp)
            cv.restoreToCount(sc)
        }
    }

    // ---------- 10. the column (2×4): the next prayer big, the day's prayers under it, the Hijri date at the foot ----------
    /** The day the rows show: after Isha, tomorrow's (as the table). */
    private fun rowsDay(x: X): JSONObject { val nx = x.next ?: return x.day
        return if (WData.dateKey(nx.at) != WData.dateKey(x.now)) (x.d.day(nx.at) ?: x.day) else x.day }
    /** The row of the time we are in, if it is among [keys] on the day shown: it gets the line «حتى 6:50». */
    private fun exitKey(x: X, keys: List<String>): String? { val cw = x.cur ?: return null
        return cw.k.takeIf { it in keys && rowsDay(x).optLong(it) == cw.start } }
    /**
     * Prayer rows between [l] and [r]: the name at the start, the time at the end, the next one on a gold pill, the ones gone dimmed.
     * With [exitH] > 0 the time we are in gets a second line «حتى 6:50» under its name (inside the pill if it is the next one,
     * as while waiting for the iqama), and is not dimmed: it is still running. Returns the bottom.
     */
    private fun prayerRows(cv: Canvas, x: X, keys: List<String>, top: Float, rowH: Float, exitH: Float, l: Float, r: Float, ts: Float): Float {
        val tday = rowsDay(x); val nx = x.next; val cw = x.cur; val exK = if (exitH > 0f) exitKey(x, keys) else null
        val inset = min(rowH * .5f, x.dp(12f)); var y = top
        for (k in keys) {
            val on = k == nx?.k; val going = k == exK; val t = tday.optLong(k); val past = !on && !going && t <= x.now
            val hh = rowH + if (going) exitH else 0f
            if (on) cv.drawRoundRect(RectF(l, y, r, y + hh), rowH / 2, rowH / 2, paint(if (x.st.light) GOLD_L else GOLD))
            val fg = if (on) INK else x.st.fg; val al = if (past) 115 else 255; val sh = !on && x.st.shadow
            val np = tp(x, if (on) x.plexB else x.plex, 1f, fg, sh).apply { textSize = ts; alpha = al }
            val cp = tp(x, x.plexB, 1f, fg, sh).apply { textSize = ts * 1.08f; alpha = al }
            val nm = x.d.name(k); val tm = x.d.clock(t)
            fit(cp, tm, (r - l - 2 * inset) * .42f); fit(np, nm, (r - l - 2 * inset) * .54f)
            val base = y + rowH / 2 + ts * .36f; val s0 = if (x.rtl) r - inset else l + inset; val e0 = if (x.rtl) l + inset else r - inset
            text(cv, x, nm, s0, base, np); text(cv, x, tm, e0, base, cp, false)
            if (going && cw != null) {
                val b = if (x.now < cw.first) cw.first else cw.end
                val ep = tp(x, x.plex, 1f, if (on) INK else x.st.sub, sh).apply { textSize = ts * .8f; if (on) alpha = 210 }
                val es = (if (x.d.ar) "حتى " else "until ") + x.d.clock(b); fit(ep, es, r - l - 2 * inset)
                text(cv, x, es, s0, base + exitH - (ts - ep.textSize) * .36f, ep)
            }
            y += hh
        }
        return y
    }

    private fun column(cv: Canvas, x: X) {
        card(cv, x); val nx = x.next ?: return; val w = x.w; val h = x.h; val u0 = x.u
        val keys = listOf("fajr") + (if (x.style.has("sunrise")) listOf("sunrise") else emptyList()) + (if (x.style.has("duha")) listOf("duha") else emptyList()) + listOf("dhuhr", "asr", "maghrib", "isha")
        val cnt = if (x.style.has("count")) x.d.inTxt(nx, x.now) else ""; val tms = if (x.style.has("time")) clk(x, nx.at) else ""
        val sub = cnt.isNotEmpty() || tms.isNotEmpty()
        var moonOn = x.style.has("moon"); var hijOn = x.style.has("hijri"); var exOn = x.style.has("exit") && exitKey(x, keys) != null; var rowF = 1f; var nameB = 46f
        // the parts in dp at scale 1 (laid out for 150 dp of width): margins, name, countdown line, rows, exit line, foot
        fun foot() = if (moonOn) 22f else if (hijOn) 16f else 0f
        fun need() = 10f + nameB + (if (sub) 21f else 0f) + 10f + keys.size * 26f * rowF + (if (exOn) 15f else 0f) + (if (foot() > 0f) 8f + foot() else 0f) + 10f
        val kW = w / u0 / 150f
        fun kOf() = minOf(kW, h / u0 / need(), 1.7f)
        // shorter: the moon goes first, then the date, then the exit line, then the rows close up, then the name gives way;
        // only then does it all shrink. Only the height drops parts: a narrow one is smaller all over, not emptier
        while (h / u0 / need() < min(.9f, kW)) { when { moonOn -> moonOn = false; hijOn -> hijOn = false; exOn -> exOn = false
            rowF > .75f -> rowF -= .05f; nameB > 32f -> nameB -= 2f; else -> break } }
        val k = kOf().coerceAtLeast(.5f); val u = u0 * k
        // taller than the width lets it grow: rows open up a little, the name takes some (as far as the width lets it),
        // the rest goes to the gaps, so no band is left empty
        val free0 = max(0f, h - need() * u); val rowX = min(free0 * .5f, keys.size * 26f * rowF * u * .4f) / keys.size
        // the name: sized by the five names' ink so it doesn't jump when the prayer changes, stretched to the frame,
        // and centred in its band by its own ink
        val pad = max(kSafe(x), 7 * u); val room = w - 2 * pad; val hb = Rect()
        val np = tp(x, x.nameTf, 1f, x.st.fg).apply { textSize = 100f }; var t0 = 0f; var b0 = 0f
        for (s in (WData.PRAY + nx.k).distinct().map { x.d.name(it) } + TATWEEL.toString()) { np.getTextBounds(s, 0, s.length, hb); t0 = min(t0, hb.top.toFloat()); b0 = max(b0, hb.bottom.toFloat()) }
        val want = nameB * u + min((free0 - rowX * keys.size) * .4f, nameB * u * .6f)
        np.textSize = 100f * want / (b0 - t0 + (if (np.heavy) 3.5f else 0f))
        val n0 = x.d.name(nx.k)
        while (inkW(np, n0) > room && np.textSize > 6f) np.textSize *= .97f
        val band = (b0 - t0) * np.textSize / 100f
        val free = max(0f, h - (need() - nameB) * u - band - rowX * keys.size)
        val gTop = 10 * u + free * .2f; val gMid = 10 * u + free * (if (foot() > 0f) .3f else .4f); val gFoot = 8 * u + free * .3f
        var y = gTop
        val name = kashName(x, np, n0, room)
        np.getTextBounds(name, 0, name.length, hb)
        center(cv, name, w / 2, y + band / 2 - (hb.top + hb.bottom) / 2f, np)
        y += band
        if (sub) {
            // the countdown (or «الإقامة بعد …»), then the time if picked, as one centred line
            val cp = tp(x, x.plexB, 1f, if (x.st.light) c("#0B4D3B") else x.st.acc).apply { textSize = 13.5f * u }
            val sp = tp(x, x.plex, 1f, x.st.sub).apply { textSize = 13f * u }
            val dot = if (cnt.isNotEmpty() && tms.isNotEmpty()) "  ·  " else ""
            val tot = { cp.measureText(cnt) + sp.measureText(dot + tms) }
            while (tot() > w - 2 * pad && cp.textSize > 6f) { cp.textSize *= .95f; sp.textSize *= .95f }
            val base = y + 6 * u + cp.textSize * .76f; var xs = w / 2 + x.dir * tot() / 2
            if (cnt.isNotEmpty()) { text(cv, x, cnt, xs, base, cp); xs -= x.dir * cp.measureText(cnt) }
            if (tms.isNotEmpty()) text(cv, x, dot + tms, xs, base, sp)
            y += 21 * u
        }
        y += gMid
        val rowH = 26 * rowF * u + rowX; val ts = min(13.5f * u, rowH * .62f)
        y = prayerRows(cv, x, keys, y, rowH, if (exOn) 15 * u else 0f, 8 * u, w - 8 * u, ts)
        if (foot() <= 0f) return
        // the foot: tonight's moon and the Hijri date, centred together
        y += gFoot; val cy = y + foot() * u / 2
        val hs = if (hijOn) x.d.digits(x.day.optString("hd") + " " + x.day.optString("hm") + " " + x.day.optString("hy")) else ""
        val hp = tp(x, x.plex, 1f, x.st.sub).apply { textSize = 12f * u }; val mr = if (moonOn) 8 * u else 0f; val mg = if (moonOn && hs.isNotEmpty()) 7 * u else 0f
        fit(hp, hs, w - 2 * pad - 2 * mr - mg)
        val gw = 2 * mr + mg + hp.measureText(hs); val gs = w / 2 + x.dir * gw / 2
        if (moonOn) moon(cv, x, gs - x.dir * mr, cy, mr)
        if (hs.isNotEmpty()) text(cv, x, hs, gs - x.dir * (2 * mr + mg), cy + hp.textSize * .36f, hp)
    }

    // ---------- extra widgets (0.14): shared bits ----------
    private val WD_AR = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")
    private val WD_EN = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val WDS_AR = listOf("أحد", "اثن", "ثلا", "أرب", "خمي", "جمع", "سبت")
    private val WDS_EN = listOf("S", "M", "T", "W", "T", "F", "S")
    private fun wdName(x: X, i: Int) = (if (x.d.ar) WD_AR else WD_EN)[((i % 7) + 7) % 7]
    private fun isWide(x: X) = x.w / x.h > 1.8f
    /** "غداً", "بعد يومين", "بعد ٤ أيام", "بعد ١٢ يوماً" (English: tomorrow, in 4 days). */
    private fun inDays(x: X, n: Int, about: Boolean = false): String {
        val ar = x.d.ar; val v = x.d.digits(n.toString()); val ab = if (!about) "" else if (ar) "نحو " else "about "
        if (n <= 0) return if (ar) "اليوم" else "Today"
        if (n == 1 && !about) return if (ar) "غداً" else "Tomorrow"
        return if (ar) "بعد " + ab + when { n == 2 && !about -> "يومين"; n in 3..10 -> "$v أيام"; else -> "$v يوماً" } else "In $ab$v days"
    }
    /** The time with its ص/م, as one string. */
    private fun clk(x: X, t: Long) = (x.d.clock(t) + " " + x.d.ampm(t)).trim()
    /** A night sky card for the "live sky" look of the night widgets. */
    private fun nightCard(cv: Canvas, x: X) {
        val r = x.dp(22f)
        cv.drawRoundRect(RectF(0f, 0f, x.w, x.h), r, r, paint().apply { shader = LinearGradient(0f, 0f, 0f, x.h, intArrayOf(c("#0B1430"), c("#1E2547"), c("#2A2F5A")), floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP) })
    }
    private fun nightStars(cv: Canvas, x: X, l: Float, t: Float, w: Float, h: Float) {
        for (i in STARS.indices step 2) cv.drawCircle(l + w * (if (x.rtl) 1 - STARS[i] else STARS[i]), t + h * STARS[i + 1], x.dp(if (i % 4 == 0) 1.3f else .9f), paint(a(if (i % 6 == 0) .9f else .6f, Color.WHITE)))
    }
    /** An eight-pointed star (never six). */
    private fun star8(cv: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        val p = Path()
        for (i in 0 until 16) { val rr = if (i % 2 == 0) r else r * .62f; val an = -PI / 2 + i * PI / 8
            val px = cx + rr * cos(an).toFloat(); val py = cy + rr * sin(an).toFloat(); if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
        p.close(); cv.drawPath(p, paint(col))
    }
    private fun crescent(cv: Canvas, x: X, cx: Float, cy: Float, r: Float, col: Int) {
        val p = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        // the hollow faces the end side, as the young moon of Ramadan
        p.op(Path().apply { addCircle(cx - x.dir * r * .42f, cy - r * .18f, r * .84f, Path.Direction.CW) }, Path.Op.DIFFERENCE)
        cv.drawPath(p, paint(col))
    }
    /** Text made of a big part and a small one ("٢" + " من ٥"), centred together, in reading order. */
    private fun pair(cv: Canvas, x: X, big: String, small: String, cx: Float, y: Float, pb: Paint, ps: Paint) {
        thicken(pb); val wb = pb.measureText(big); val ws = ps.measureText(small); val l = cx - (wb + ws) / 2
        pb.textAlign = Paint.Align.LEFT; ps.textAlign = Paint.Align.LEFT
        if (x.rtl) { cv.drawText(big, l + ws, y, pb); cv.drawText(small, l, y, ps) } else { cv.drawText(big, l, y, pb); cv.drawText(small, l + wb, y, ps) }
    }

    // ---------- 13. my prayers today (4×1 and 2×2): a circle per prayer, filled by «صلّيت» ----------
    /** The marks of the day whose prayers count now (a bit per prayer, Fajr = 1) and the days in a row with all five. */
    data class Marks(val done: Int = 0, val streak: Int = 0)
    @Volatile var marks = Marks()
    /** The wide one's layout, as fractions of its width: the title block, the end margin and each circle's cell (its tap areas sit on these). */
    const val PR_TITLE = .32f; const val PR_PAD = .03f; const val PR_CELL = .13f

    private fun streakTxt(x: X, n: Int): String {
        val v = x.d.digits(n.toString())
        return if (x.d.ar) when { n == 1 -> "يوم كامل"; n == 2 -> "يومين متتاليين"; n <= 10 -> "$v أيام متتالية"; else -> "$v يوماً متتالياً" }
               else if (n == 1) "1 full day" else "$v days in a row"
    }
    private fun prayed(cv: Canvas, x: X) {
        card(cv, x)
        val pd = x.d.prayerDay(x.now) ?: x.day; val m = marks; val cur = x.cur?.k
        val n = (0..4).count { m.done and (1 shl it) != 0 }
        // 2 done, 1 its time now, 3 its time has passed, 0 still to come
        fun state(i: Int): Int { val k = WData.PRAY[i]; val t = pd.optLong(k)
            return when { m.done and (1 shl i) != 0 -> 2; t in 1..x.now && k == cur && x.style.has("now") -> 1; t in 1..x.now -> 3; else -> 0 } }
        val doneC = if (x.st.light) GOLD_L else x.st.acc; val nowC = if (x.st.light) c("#0B4D3B") else x.st.acc
        val chkC = if (x.st.light) Color.WHITE else INK; val goldT = if (x.st.light) GOLD_L else GOLD
        val countTxt = x.d.digits("$n") + (if (x.d.ar) " من " else " of ") + x.d.digits("5")
        fun circle(cx: Float, cy: Float, r: Float, st: Int) {
            when (st) {
                2 -> { cv.drawCircle(cx, cy, r, paint(doneC))
                    val p = Path(); p.moveTo(cx - r * .42f, cy + r * .02f); p.lineTo(cx - r * .1f, cy + r * .34f); p.lineTo(cx + r * .44f, cy - r * .3f)
                    cv.drawPath(p, paint(chkC).apply { style = Paint.Style.STROKE; strokeWidth = r * .16f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }) }
                1 -> { cv.drawCircle(cx, cy, r * 1.13f, paint(a(.18f, nowC)).apply { style = Paint.Style.STROKE; strokeWidth = r * .3f })
                    cv.drawCircle(cx, cy, r * .94f, paint(nowC).apply { style = Paint.Style.STROKE; strokeWidth = r * .13f }) }
                3 -> cv.drawCircle(cx, cy, r * .96f, paint(a(.6f, x.st.fg)).apply { style = Paint.Style.STROKE; strokeWidth = r * .08f })
                else -> cv.drawCircle(cx, cy, r * .96f, paint(a(.45f, x.st.fg)).apply { style = Paint.Style.STROKE; strokeWidth = r * .07f; pathEffect = DashPathEffect(floatArrayOf(r * .22f, r * .18f), 0f) })
            }
        }
        if (isWide(x)) {
            val u = x.u; val pad = 14 * u; val names = x.style.has("names")
            val tw = x.w * PR_TITLE - pad; val tx = startX(x, pad)
            val tl = tp(x, x.nameTf, 15f, x.st.fg); val title = if (x.d.ar) "صلواتي اليوم" else "My prayers today"; fit(tl, title, tw)
            text(cv, x, title, tx, x.h / 2 - 3 * u, tl)
            val sl = tp(x, x.plexB, 11f, goldT); val st2 = if (x.style.has("streak") && m.streak > 0) streakTxt(x, m.streak) else countTxt
            var lx = tx
            if (x.style.has("streak") && m.streak > 0) { star8(cv, tx - x.dir * 5 * u, x.h / 2 + 11 * u, 5.5f * u, goldT); lx = tx - x.dir * 14 * u }
            fit(sl, st2, tw - 14 * u); text(cv, x, st2, lx, x.h / 2 + 15 * u, sl)
            val r = min(x.w * PR_CELL * .36f, x.h * (if (names) .2f else .27f)); val cy = if (names) x.h * .41f else x.h / 2
            val lp = tp(x, x.plex, 10.5f, x.st.fg); fit(lp, x.d.name("maghrib"), x.w * PR_CELL * .98f)
            val left = if (x.rtl) x.w * PR_PAD else x.w * PR_TITLE
            for (i in 0..4) {
                val cell = if (x.rtl) 4 - i else i; val cx = left + x.w * PR_CELL * (cell + .5f); val st = state(i)
                circle(cx, cy, r, st)
                if (names) { lp.typeface = if (st == 1) x.plexB else x.plex; lp.color = if (st == 0) a(.65f, x.st.fg) else x.st.fg
                    center(cv, x.d.name(WData.PRAY[i]), cx, cy + r + lp.textSize * 1.25f, lp) }
            }
        } else {
            val s = min(x.w, x.h); val k = s / 150f; val cx = x.w / 2; val cy = x.h / 2; val R = s * .34f; val sw = s * .066f
            val oval = RectF(cx - R, cy - R, cx + R, cy + R); val gap = 9f; val sweep = 72f - gap
            for (i in 0..4) {
                val st = state(i)
                // Fajr at the top, round counter-clockwise in Arabic (time runs right to left), clockwise in English
                val start = if (x.rtl) -90f - gap / 2 - i * 72f else -90f + gap / 2 + i * 72f
                val col = when (st) { 2 -> doneC; 1 -> if (x.st.light) c("#0B4D3B") else x.st.fg; 3 -> a(.4f, x.st.fg); else -> x.st.line }
                cv.drawArc(oval, start, if (x.rtl) -sweep else sweep, false, paint(col).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = if (st == 0 || st == 3) sw * .72f else sw })
            }
            val lines = ArrayList<Pair<String, Paint>>()
            if (x.style.has("now") && cur != null && cur in WData.PRAY) lines += (x.d.name(cur) + (if (x.d.ar) " الآن" else " now")) to tp(x, x.plex, 11f, x.st.sub).apply { textSize = 11f * k }
            if (x.style.has("streak") && m.streak > 0) lines += streakTxt(x, m.streak) to tp(x, x.plexB, 10.5f, goldT).apply { textSize = 10.5f * k }
            var y = cy + (10 - 7 * lines.size) * k
            pair(cv, x, x.d.digits("$n"), (if (x.d.ar) " من " else " of ") + x.d.digits("5"), cx, y, tp(x, x.plexB, 30f, x.st.fg).apply { textSize = 30f * k }, tp(x, x.plex, 15f, x.st.sub).apply { textSize = 15f * k })
            for ((t, p) in lines) { y += 15 * k; fit(p, t, R * 1.5f); center(cv, t, cx, y, p) }
        }
    }

    // ---------- 14. the last third of the night (2×2 and 4×1) ----------
    private fun nightW(cv: Canvas, x: X) {
        val sky = x.st.kind == "sky"
        if (sky) nightCard(cv, x) else card(cv, x)
        val (m, f) = x.d.night(x.now) ?: return
        val third = m + (f - m) * 2 / 3; val mid = m + (f - m) / 2; val inThird = x.now in third until f
        val fg = x.st.fg; val sub = x.st.sub; val gold = if (x.st.light) GOLD_L else GOLD
        val track = if (x.st.light) a(.14f, c("#0B4D3B")) else a(.2f, Color.WHITE)
        val cnt = if (inThird) (if (x.d.ar) "الآن · بقي " + x.d.dur(f - x.now) + " للفجر" else "Now · " + x.d.dur(f - x.now) + " to Fajr") else x.d.inTxt(third - x.now)
        if (isWide(x)) {
            val u = x.u; val pad = 16 * u; val l = pad; val r = x.w - pad; val bw = r - l
            fun px(t: Long): Float { val q = ((t - m).toFloat() / max(1L, f - m)).coerceIn(0f, 1f); return if (x.rtl) r - bw * q else l + bw * q }
            val tl = tp(x, x.nameTf, 15f, fg); val title = if (x.d.ar) "الثلث الأخير" else "Last third"
            text(cv, x, title, startX(x, pad), 27 * u, tl)
            text(cv, x, clk(x, third), startX(x, pad) - x.dir * (tl.measureText(title) + 8 * u), 27 * u, tp(x, x.plexB, 14f, fg))
            if (x.style.has("count")) { val cp = tp(x, x.plex, 11.5f, if (inThird) gold else sub); fit(cp, cnt, bw * .45f); text(cv, x, cnt, endX(x, pad), 26 * u, cp, false) }
            val bt = 41 * u; val bh = 8 * u
            cv.drawRoundRect(RectF(l, bt, r, bt + bh), bh / 2, bh / 2, paint(track))
            val g0 = px(third); val g1 = px(f)
            cv.drawRoundRect(RectF(min(g0, g1), bt, max(g0, g1), bt + bh), bh / 2, bh / 2, paint(gold))
            if (x.style.has("mid")) { val mx = px(mid); cv.drawRect(mx - u, bt - 5 * u, mx + u, bt + bh + 5 * u, paint(a(.6f, fg))) }
            if (x.now in m..f) { val nx = px(x.now); cv.drawCircle(nx, bt + bh / 2, 9.5f * u, paint(a(.25f, fg))); cv.drawCircle(nx, bt + bh / 2, 7 * u, paint(if (x.st.light) c("#0B4D3B") else Color.WHITE)) }
            val lp = tp(x, x.plex, 10f, sub); val ly = bt + bh + 19 * u
            text(cv, x, x.d.name("maghrib") + " " + x.d.clock(m), startX(x, pad), ly, lp)
            text(cv, x, x.d.name("fajr") + " " + x.d.clock(f), endX(x, pad), ly, lp, false)
            if (x.style.has("mid")) center(cv, (if (x.d.ar) "منتصف الليل " else "Midnight ") + x.d.clock(mid), px(mid), ly, lp)
        } else {
            val s = min(x.w, x.h); val k = s / 150f; val pad = 14 * k
            if (x.style.has("sky")) { nightStars(cv, x, 0f, 0f, x.w, x.h * .5f); moon(cv, x, endX(x, pad) + x.dir * 15 * k, pad + 15 * k, 15 * k) }
            if (x.style.has("mid")) text(cv, x, (if (x.d.ar) "منتصف الليل " else "Midnight ") + x.d.clock(mid), startX(x, pad), pad + 11 * k, tp(x, x.plex, 10.5f, sub).apply { textSize = 10.5f * k })
            val bottom = x.h - pad; val segH = 4 * k; val gap = 3 * k; val sw3 = (x.w - 2 * pad - 2 * gap) / 3
            for (i in 0..2) { val col = if (x.rtl) i else 2 - i; val l0 = x.w - pad - (col + 1) * sw3 - col * gap
                cv.drawRoundRect(RectF(l0, bottom - segH, l0 + sw3, bottom), segH / 2, segH / 2, paint(if (i == 2) gold else track)) }
            if (x.now in m..f) { val q = ((x.now - m).toFloat() / (f - m)); val nx = if (x.rtl) x.w - pad - q * (x.w - 2 * pad) else pad + q * (x.w - 2 * pad)
                cv.drawCircle(nx, bottom - segH / 2, 3.6f * k, paint(if (x.st.light) c("#0B4D3B") else Color.WHITE)) }
            var y = bottom - segH - 7 * k
            if (x.style.has("count")) { val cp = tp(x, x.plexB, 11.5f, if (x.st.light) c("#0B4D3B") else fg, false).apply { textSize = 11.5f * k }; fit(cp, cnt, x.w - 2 * pad - 2 * cp.textSize)
                val pw = pillW(cp, cnt); val ph = cp.textSize * 1.55f
                pill(cv, x, cnt, startX(x, pad) - x.dir * pw / 2, y - ph / 2, cp, if (x.st.light) c("#E3ECE6") else a(.14f, Color.WHITE)); y -= ph + 9 * k }
            val tb = tp(x, x.plexB, 30f, fg).apply { textSize = 30f * k }; val tm = x.d.clock(third); val ap = x.d.ampm(third)
            text(cv, x, tm, startX(x, pad), y, tb)
            if (ap.isNotEmpty()) text(cv, x, ap, startX(x, pad) - x.dir * (tb.measureText(tm) + 5 * k), y, tp(x, x.plex, 14f, fg).apply { textSize = 14f * k })
            y -= tb.textSize + 4 * k
            val lt = tp(x, x.nameTf, 13f, sub).apply { textSize = 13f * k }; val title = if (x.d.ar) "الثلث الأخير من الليل" else "Last third of the night"; fit(lt, title, x.w - 2 * pad)
            text(cv, x, title, startX(x, pad), y, lt)
        }
    }

    // ---------- 15. Ramadan (4×2 and 2×2): iftar before maghrib, imsak after it; outside Ramadan, how far it is ----------
    private fun ramadan(cv: Canvas, x: X) {
        card(cv, x)
        val d = x.d; val now = x.now; val today = x.day; val tom = d.day(now, 1)
        val ramT = d.hmn(today) == 9; val ramN = tom != null && d.hmn(tom) == 9; val ims = d.imsak * 60_000L
        val fj = today.optLong("fajr"); val mg = today.optLong("maghrib")
        val ar = d.ar; val gold = if (x.st.light) GOLD_L else GOLD; val big = if (x.st.light) c("#0B4D3B") else gold
        // what to count down to
        var state = "wait"; var target = 0L; var dayNo = 0; var nextIms = fj - ims; var nextFajr = fj
        when {
            ramT && now < fj - ims -> { state = "suhoor"; target = fj - ims; dayNo = d.hd(today) }
            ramT && now < fj -> { state = "imsak"; target = fj; dayNo = d.hd(today) }
            ramT && now < mg -> { state = "fast"; target = mg; dayNo = d.hd(today); nextIms = (tom?.optLong("fajr") ?: (fj + WData.DAY)) - ims; nextFajr = nextIms + ims }
            ramN && tom != null -> { state = "suhoor"; target = tom.optLong("fajr") - ims; dayNo = d.hd(tom); nextIms = target; nextFajr = tom.optLong("fajr") }
            (ramT && !ramN) || (d.hmn(today) == 10 && d.hd(today) <= 3) -> state = "eid"
        }
        // outside Ramadan: days to the 1st (counted in the pushed days, else estimated from the Hijri date)
        var wait = d.daysTo(now, 9, 1); var about = false
        if (state == "wait" && wait < 0) { val hm = d.hmn(today); val left = d.monthLen(now) - d.hd(today) + 1
            if (hm in 1..12) { wait = left + (((if (hm < 9) 9 - hm - 1 else 12 - hm + 8)) * 29.53).toInt(); about = true } }
        val yr = d.digits(today.optString("hy").filter { it.isDigit() }.let { y -> if (state == "wait" && d.hmn(today) > 9) (y.toIntOrNull()?.plus(1) ?: 0).toString() else y })
        val nm = if (ar) "رمضان" else "Ramadan"
        val label = when (state) { "fast" -> if (ar) "الإفطار بعد" else "Iftar in"; "suhoor" -> if (ar) "الإمساك بعد" else "Imsak in"; "imsak" -> if (ar) "الفجر بعد" else "Fajr in"; else -> nm }
        val bigTxt = when (state) { "eid" -> if (ar) "عيد مبارك" else "Eid Mubarak"; "wait" -> if (wait >= 0) inDays(x, wait, about) else ""; else -> d.dur(target - now) }
        val atTxt = when (state) { "fast" -> d.name("maghrib") + " " + clk(x, mg); "suhoor" -> (if (ar) "الإمساك " else "Imsak ") + clk(x, target); "imsak" -> d.name("fajr") + " " + clk(x, target)
            "eid" -> if (ar) "تقبّل الله منا ومنكم" else "May Allah accept from us and you"; else -> if (ar) "اللهم بلّغنا رمضان" else "May we reach Ramadan" }
        val dayTxt = if (dayNo > 0) (if (ar) (if (state == "suhoor" && now >= mg) "سحور اليوم " else "اليوم ") + d.digits("$dayNo") else "Day " + d.digits("$dayNo")) else ""
        val len = d.monthLen(now); val doneDays = if (state == "wait" || state == "eid") 0 else (dayNo - 1).coerceAtLeast(0)
        if (isWide(x)) {
            val u = x.u; val pad = 16 * u
            text(cv, x, "$nm $yr".trim(), startX(x, pad), pad + 12 * u, tp(x, x.plex, 12f, x.st.sub))
            val t2 = if (state == "eid") (if (ar) "عيد الفطر" else "Eid al-Fitr") else if (dayTxt.isNotEmpty()) dayTxt else if (ar) "شهر الصيام" else "The month of fasting"
            text(cv, x, t2, startX(x, pad), pad + 34 * u, tp(x, x.nameTf, 17f, x.st.fg))
            crescent(cv, x, endX(x, pad) + x.dir * 20 * u, pad + 20 * u, 17 * u, gold)
            val prog = x.style.has("prog") && state != "wait" && state != "eid"
            val base = x.h - pad - (if (prog) 16 * u else 0f)
            text(cv, x, atTxt, startX(x, pad), base - 2 * u, tp(x, x.plex, 12f, x.st.fg))
            val bp = tp(x, if (state == "eid") x.nameTf else x.plexB, 30f, big); fit(bp, bigTxt, x.w * .6f)
            text(cv, x, bigTxt, startX(x, pad), base - 20 * u, bp)
            if (state != "eid" && state != "wait") text(cv, x, label, startX(x, pad), base - 20 * u - bp.textSize - 2 * u, tp(x, x.plex, 12f, x.st.sub))
            if (state in setOf("fast", "suhoor", "imsak")) {
                val rp = tp(x, x.plex, 11.5f, x.st.sub); var ry = base - 2 * u
                if (x.style.has("fajr")) { text(cv, x, d.name("fajr") + " " + clk(x, nextFajr), endX(x, pad), ry, rp, false); ry -= 19 * u }
                // while counting down to imsak its time is already under the count
                if (x.style.has("imsak") && state != "suhoor") text(cv, x, (if (ar) "الإمساك " else "Imsak ") + clk(x, nextIms), endX(x, pad), ry, rp, false)
            }
            if (prog) { val gap = 2 * u; val sh = 5 * u; val sw0 = (x.w - 2 * pad - (len - 1) * gap) / len; val y0 = x.h - pad - sh
                for (i in 0 until len) { val l0 = if (x.rtl) x.w - pad - (i + 1) * sw0 - i * gap else pad + i * (sw0 + gap)
                    cv.drawRoundRect(RectF(l0, y0, l0 + sw0, y0 + sh), sh / 2, sh / 2, paint(if (i < doneDays) gold else a(.2f, x.st.fg))) } }
        } else {
            val s = min(x.w, x.h); val k = s / 150f; val pad = 14 * k
            if (x.st.kind == "sky" && state != "fast") nightStars(cv, x, 0f, 0f, x.w, x.h * .45f)
            crescent(cv, x, endX(x, pad) + x.dir * 13 * k, pad + 13 * k, 12 * k, gold)
            val title = when (state) { "fast" -> if (ar) "الإفطار" else "Iftar"; "suhoor" -> if (ar) "السحور" else "Suhoor"; "imsak" -> if (ar) "الإمساك" else "Imsak"; "eid" -> if (ar) "العيد" else "Eid"; else -> nm }
            text(cv, x, title, startX(x, pad), pad + 13 * k, tp(x, x.nameTf, 15f, x.st.fg).apply { textSize = 15f * k })
            var y = x.h - pad
            if (x.style.has("prog") && state !in setOf("wait", "eid")) { val bh = 3 * k; cv.drawRoundRect(RectF(pad, y - bh, x.w - pad, y), bh / 2, bh / 2, paint(a(.2f, x.st.fg)))
                val fw = (x.w - 2 * pad) * doneDays / len; val rr = if (x.rtl) RectF(x.w - pad - fw, y - bh, x.w - pad, y) else RectF(pad, y - bh, pad + fw, y)
                if (fw > 0) cv.drawRoundRect(rr, bh / 2, bh / 2, paint(gold)); y -= bh + 7 * k }
            val l3 = if (dayNo > 0) (if (ar) "اليوم " + d.digits("$dayNo") + " من رمضان" else "Day " + d.digits("$dayNo") + " of Ramadan") else if (state == "wait") "$nm $yr".trim() else ""
            if (l3.isNotEmpty()) { text(cv, x, l3, startX(x, pad), y, tp(x, x.plexB, 11f, gold).apply { textSize = 11f * k }); y -= 18 * k }
            val bigLine = if (state in setOf("fast", "suhoor", "imsak")) (if (ar) "بعد " else "in ") + bigTxt else bigTxt
            val bp = tp(x, if (state == "eid") x.nameTf else x.plexB, 26f, x.st.fg).apply { textSize = 26f * k }; fit(bp, bigLine, x.w - 2 * pad)
            text(cv, x, bigLine, startX(x, pad), y, bp)
            y -= bp.textSize + 4 * k
            val l1 = if (state == "suhoor") (if (ar) "السحور · " else "Suhoor · ") + atTxt.substringAfter(if (ar) "الإمساك " else "Imsak ").let { (if (ar) "الإمساك " else "imsak ") + it } else atTxt
            val p1 = tp(x, x.plex, 11.5f, x.st.sub).apply { textSize = 11.5f * k }; fit(p1, l1, x.w - 2 * pad)
            text(cv, x, l1, startX(x, pad), y, p1)
        }
    }

    // ---------- 16. the white days (4×2 and 2×2): the Hijri month with the 13th–15th lit like the full moon ----------
    private fun white(cv: Canvas, x: X) {
        card(cv, x)
        val d = x.d; val ar = d.ar; val h0 = d.hd(x.day).coerceAtLeast(1); val len = d.monthLen(x.now); val w0 = d.wdi(x.day)
        val wd1 = ((w0 - (h0 - 1)) % 7 + 7) % 7
        val toWhite = when { h0 < 13 -> 13 - h0; h0 <= 15 -> 0; else -> len - h0 + 13 }
        val whiteWd = (w0 + toWhite) % 7
        val acc = if (x.st.light) c("#0B4D3B") else x.st.acc; val gold = if (x.st.light) GOLD_L else GOLD
        val bigTxt = if (toWhite == 0) (if (ar) "اليوم " + d.digits("$h0") else "Today, the " + d.digits("$h0") + "th") else inDays(x, toWhite)
        val wMonth = (d.day(x.now, toWhite) ?: x.day).optString("hm")
        val nums = d.digits("13") + " · " + d.digits("14") + " · " + d.digits("15")
        // Ashura and Arafah, when they are within ten days
        var special = ""
        if (x.style.has("days")) {
            val a = d.daysTo(x.now, 1, 10, 10); val r = d.daysTo(x.now, 12, 9, 10)
            val (o, nm) = listOf(a to (if (ar) "عاشوراء" else "Ashura"), r to (if (ar) "يوم عرفة" else "Arafah")).filter { it.first >= 0 }.minByOrNull { it.first } ?: (-1 to "")
            if (o >= 0) special = "$nm " + (if (o == 0) (if (ar) "اليوم" else "today") else if (o == 1) (if (ar) "غداً" else "tomorrow") else inDays(x, o))
        }
        if (isWide(x)) {
            val u = x.u; val pad = 14 * u
            val gw = min(x.w * .5f, 196 * u); val gl = if (x.rtl) pad else x.w - pad - gw; val cell = gw / 7
            val head = 14 * u; val rows = (wd1 + len + 6) / 7; val rowH = (x.h - 2 * pad - head) / rows; val dd = min(cell, rowH) * .9f
            fun colX(j: Int) = if (x.rtl) gl + gw - cell * (j + .5f) else gl + cell * (j + .5f)
            val hp = tp(x, x.plex, 9f, x.st.sub, false)
            val heads = if (ar) WDS_AR else WDS_EN
            for (j in 0..6) center(cv, heads[j], colX(j), pad + 9 * u, hp)
            val np = tp(x, x.plex, 10.5f, x.st.fg, false); np.textSize = min(10.5f * u, dd * .5f)
            for (n in 1..len) {
                val idx = wd1 + n - 1; val cx = colX(idx % 7); val cy = pad + head + rowH * (idx / 7 + .5f)
                val isW = n in 13..15; val today = n == h0
                if (isW) { cv.drawCircle(cx, cy, dd * .78f, paint().apply { shader = RadialGradient(cx, cy, dd * .78f, intArrayOf(a(.55f, c("#FFF0C0")), 0), null, Shader.TileMode.CLAMP) })
                    cv.drawCircle(cx, cy, dd / 2, paint(c("#FFF6D8"))); if (x.st.light) cv.drawCircle(cx, cy, dd / 2, paint(a(.7f, GOLD_L)).apply { style = Paint.Style.STROKE; strokeWidth = u }) }
                if (today) cv.drawCircle(cx, cy, dd / 2, paint(acc))
                np.typeface = if (today || isW) x.plexB else x.plex
                np.color = when { today -> if (x.st.light) c("#FFF8EA") else INK; isW -> INK; n < h0 -> a(.45f, x.st.fg); else -> x.st.fg }
                center(cv, d.digits("$n"), cx, cy + np.textSize * .36f, np)
                if (x.style.has("monthu") && (idx % 7 == 1 || idx % 7 == 4)) cv.drawCircle(cx, cy + dd * .44f, 1.7f * u, paint(gold))
            }
            val tw = x.w - gw - 3 * pad; val sx = startX(x, pad)
            val t1 = tp(x, x.nameTf, 15f, x.st.fg); val mt = (x.day.optString("hm") + " " + d.digits(x.day.optString("hy").filter { it.isDigit() })).trim(); fit(t1, mt, tw)
            text(cv, x, mt, sx, pad + 13 * u, t1)
            text(cv, x, (if (ar) "اليوم " else "Today, ") + wdName(x, w0) + " " + d.digits("$h0"), sx, pad + 31 * u, tp(x, x.plex, 11.5f, x.st.sub))
            val my = x.h * .5f
            text(cv, x, if (ar) "الأيام البيض" else "The white days", sx, my, tp(x, x.plex, 11.5f, x.st.sub))
            val bp = tp(x, x.plexB, 20f, x.st.fg); fit(bp, bigTxt, tw); text(cv, x, bigTxt, sx, my + 24 * u, bp)
            text(cv, x, nums, sx, my + 41 * u, tp(x, x.plex, 11f, x.st.sub))
            val by = x.h - pad - 2 * u
            if (special.isNotEmpty()) { val sp = tp(x, x.plexB, 11f, gold); fit(sp, special, tw); text(cv, x, special, sx, if (x.style.has("monthu")) by - 16 * u else by, sp) }
            if (x.style.has("monthu")) { cv.drawCircle(sx - x.dir * 2.5f * u, by - 3.5f * u, 2.2f * u, paint(gold))
                text(cv, x, if (ar) "الاثنين والخميس" else "Mondays and Thursdays", sx - x.dir * 9 * u, by, tp(x, x.plex, 10f, x.st.sub)) }
        } else {
            val s = min(x.w, x.h); val k = s / 150f; val pad = 14 * k
            val mx = endX(x, pad) + x.dir * 17 * k; val my = pad + 17 * k
            cv.drawCircle(mx, my, 30 * k, paint().apply { shader = RadialGradient(mx, my, 30 * k, intArrayOf(a(.5f, c("#FFF0C0")), 0), null, Shader.TileMode.CLAMP) })
            cv.drawCircle(mx, my, 15 * k, paint(c("#FFF6D8")))
            text(cv, x, if (ar) "الأيام البيض" else "White days", startX(x, pad), pad + 13 * k, tp(x, x.nameTf, 15f, x.st.fg).apply { textSize = 15f * k })
            if (special.isNotEmpty()) { val sp = tp(x, x.plexB, 10.5f, gold).apply { textSize = 10.5f * k }; fit(sp, special, x.w - 2 * pad - 34 * k); text(cv, x, special, startX(x, pad), pad + 30 * k, sp) }
            var y = x.h - pad
            val l3 = "$nums $wMonth".trim(); val p3 = tp(x, x.plex, 11f, x.st.sub).apply { textSize = 11f * k }; fit(p3, l3, x.w - 2 * pad)
            text(cv, x, l3, startX(x, pad), y, p3); y -= 17 * k
            if (toWhite > 0) { text(cv, x, (if (ar) "تبدأ " else "From ") + wdName(x, whiteWd), startX(x, pad), y, tp(x, x.plex, 11.5f, x.st.sub).apply { textSize = 11.5f * k }); y -= 18 * k }
            val bp = tp(x, x.plexB, 22f, x.st.fg).apply { textSize = 22f * k }; fit(bp, bigTxt, x.w - 2 * pad); text(cv, x, bigTxt, startX(x, pad), y, bp)
        }
    }
}
