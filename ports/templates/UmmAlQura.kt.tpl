// UmmAlQura.kt — Umm al-Qura prayer times (reconstruction), Kotlin (Android/JVM), no dependencies.
// Reproduces, to the minute, the prayer times shown by the official Umm al-Qura calendar website.
// Unofficial; not KACST code. Spec: ALGORITHM.md. Port of js/umq.js, verified on the same 14,657,797 values
// (ports/kotlin/Verify.kt). MIT licence. https://github.com/tswq-dev/ontime-prayer
package org.ontimeprayer

import kotlin.math.*

/** Seven times as "HH:MM" strings in the requested time zone. */
data class PrayerTimes(
    val fajr: String, val sunrise: String, val dhuhr: String, val asr: String,
    val maghrib: String, val isha: String, val eid: String
)

object UmmAlQura {
    /**
     * Prayer times for a Gregorian date.
     * @param date "YYYY-MM-DD"; @param lat, lon decimal degrees (north, east positive)
     * @param zone UTC offset in hours (Saudi Arabia: 3.0); @param ramadan true in Ramadan (Isha = Maghrib + 120 min)
     * @return null for an invalid date, coordinates or zone.
     */
    fun prayerTimes(date: String, lat: Double, lon: Double, zone: Double = 3.0, ramadan: Boolean = false): PrayerTimes? {
        val p = date.split("-"); if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null; val mo = p[1].toIntOrNull() ?: return null; val d = p[2].toIntOrNull() ?: return null
        if (!valid(y, mo, d, lat, lon, zone)) return null
        val a = dayAstro(y.toDouble(), mo.toDouble(), d.toDouble(), lon, zone)
        val raw = latEvents(a, lat)
        val ev = if (raw.ok) raw else latEvents(a, if (lat < 0) -45.0 else 45.0)
        val dhuhr = sec(a, a.transit)
        val fajr = if (abs(lat) > 48 && (ev.fajrHA.isNaN() || ev.fajrHA > FAJR_HA_LIMIT)) {
            val pp = solsticeP(y.toDouble(), lat, lon, zone)
            val night = ((86400 - ev.maghrib + ev.sunrise) % 86400 + 86400) % 86400
            fmt(down(ev.sunrise - pp * night))
        } else fmt(up(ev.fajr))
        return PrayerTimes(
            fajr = fajr,
            sunrise = fmt(down(ev.sunrise)),
            dhuhr = fmt(up(dhuhr)),
            asr = fmt(up(ev.asr)),
            maghrib = fmt(up(ev.maghrib)),
            isha = fmt(up(ev.maghrib + if (ramadan) 7200 else 5400)),
            eid = if (ev.eid.isNaN()) fmt(down(ev.sunrise + 900)) else fmt(up(ev.eid))
        )
    }

    /** ΔT in seconds as the server uses it (exposed for testing). */
    fun deltaT(y: Double, m: Double, d: Double, z: Double): Double {
        val t = (julianDay(y, m, d, 0.0, 0.0, 0.0, 0.0, z) - 2451545) / 36525
        val yy = 2000 + t * 100
        if (yy > 2000) return 102.3 + t * (123.5 + t * 32.5)
        if (yy < 1620) return if (yy < 948) 2715.6 + t * (573.36 + t * 46.5) else 50.6 + t * (67.5 + t * 22.5)
        val i = min(truncate((yy - 1620) / 2).toInt(), DT_TABLE.size - 2)
        val f = yy / 2 - (i + 810)
        return (DT_TABLE[i] + (DT_TABLE[i + 1] - DT_TABLE[i]) * f) / 10
    }

    /** The website rounds GPS coordinates to 6 decimals (shortest decimal form, ties away from zero). */
    fun siteCoordinate(x: Double): Double =
        java.math.BigDecimal(x.toString()).setScale(6, java.math.RoundingMode.HALF_UP).toDouble()

    // ---------------------------------------------------------------- tables
    private val L_TERMS: Array<Array<DoubleArray>> = arrayOf(%L_TERMS%)
    private val B_TERMS: Array<Array<DoubleArray>> = arrayOf(%B_TERMS%)
    private val DT_TABLE = doubleArrayOf(%DT_TABLE%)

    private const val D2R = PI / 180; private const val R2D = 180 / PI
    private const val GMST_OFF = 0.146457 / 3600; private const val EPS_OFF = -0.039131 / 3600; private const val SUNRISE_OFF = 0.0032001
    private const val H0 = -0.8333; private const val FAJR_ALT = -18.5; private const val EID_ALT = 4.2
    private const val FAJR_HA_LIMIT = 151.84401; private const val TW_ADD = 58.99367

    private fun limitDeg(x0: Double): Double { val x = x0 / 360; var l = 360 * (x - floor(x)); if (l < 0) l += 360; return l }
    private fun limit180(x0: Double): Double { val x = x0 / 360; var l = 360 * (x - floor(x)); if (l < -180) l += 360 else if (l > 180) l -= 360; return l }
    private fun limit01(x: Double): Double { var l = x - floor(x); if (l < 0) l += 1; return l }

    // NREL-SPA julian_day, Gregorian correction always applied
    private fun julianDay(year0: Double, month0: Double, day: Double, hour: Double, minute: Double, second: Double, dut1: Double, tz: Double): Double {
        var year = year0; var month = month0
        val dayDecimal = day + (hour - tz + (minute + (second + dut1) / 60) / 60) / 24
        if (month < 3) { month += 12; year-- }
        var jd = truncate(365.25 * (year + 4716)) + truncate(30.6001 * (month + 1)) + dayDecimal - 1524.5
        val a = truncate(year / 100)
        jd += (2 - a + truncate(a / 4))
        return jd
    }

    // civil date <-> day number from 2000-01-01 (proleptic Gregorian)
    private fun daysFromCivil(y0: Double, m: Double, d: Double): Double {
        val y = y0 - if (m <= 2) 1 else 0
        val era = floor(y / 400); val yoe = y - era * 400
        val doy = floor((153 * (m + if (m > 2) -3 else 9) + 2) / 5) + d - 1
        val doe = yoe * 365 + floor(yoe / 4) - floor(yoe / 100) + doy
        return era * 146097 + doe - 719468 - 10957
    }
    private fun civilFromDays(z0: Double): DoubleArray {
        val z = z0 + 719468 + 10957
        val era = floor(z / 146097); val doe = z - era * 146097
        val yoe = floor((doe - floor(doe / 1460) + floor(doe / 36524) - floor(doe / 146096)) / 365)
        val doy = doe - (365 * yoe + floor(yoe / 4) - floor(yoe / 100))
        val mp = floor((5 * doy + 2) / 153)
        val d = doy - floor((153 * mp + 2) / 5) + 1
        val m = mp + if (mp < 10) 3 else -9
        return doubleArrayOf(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
    }

    private fun series(terms: Array<Array<DoubleArray>>, tau: Double): Double {
        var sum = 0.0
        for (i in terms.indices) {
            var s = 0.0
            for (t in terms[i]) s += t[0] * cos(t[1] + t[2] * tau)
            sum += s * tau.pow(i)
        }
        return sum
    }
    // Sun alpha/delta (deg) at JDE, obliquity eps (deg); no nutation, no aberration
    private fun sunAD(jde: Double, eps: Double): DoubleArray {
        val tau = (jde - 2451545) / 365250
        val ll = limitDeg(series(L_TERMS, tau) * R2D)
        val bb = series(B_TERMS, tau) * R2D
        val lam = limitDeg(ll + 180); val beta = -bb
        val l = lam * D2R; val b = beta * D2R; val e = eps * D2R
        val alpha = limitDeg(atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l)) * R2D)
        val delta = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l)) * R2D
        return doubleArrayOf(alpha, delta)
    }
    private fun laskar(jme: Double): Double {
        val u = jme / 10
        return 84381.448 + u * (-4680.93 + u * (-1.55 + u * (1999.25 + u * (-51.38 + u * (-249.67 + u * (-39.05 + u * (7.12 + u * (27.87 + u * (5.79 + u * 2.45)))))))))
    }
    private fun gmst(jd: Double): Double {
        val jc = (jd - 2451545) / 36525
        return limitDeg(280.46061837 + 360.98564736629 * (jd - 2451545) + jc * jc * (0.000387933 - jc / 38710000))
    }
    private fun interp(ad: DoubleArray, n: Double): Double {
        var a = ad[1] - ad[0]; var b = ad[2] - ad[1]
        if (abs(a) >= 2) a = limit01(a)
        if (abs(b) >= 2) b = limit01(b)
        return ad[1] + n * (a + b + (b - a) * n) / 2
    }
    private fun altHA(alt: Double, phi: Double, dec: Double): Double {
        val c = (sin(alt * D2R) - sin(phi * D2R) * sin(dec * D2R)) / (cos(phi * D2R) * cos(dec * D2R))
        if (c < -1 || c > 1) return Double.NaN
        return acos(c) * R2D
    }

    private class Astro(val lon: Double, val dT: Double, val err: Double, val nu: Double, val alpha: DoubleArray, val delta: DoubleArray) {
        var m0 = 0.0; var transit = 0.0
        fun h(m: Double): Double = limit180(nu + 360.985647 * m + lon - interp(alpha, m + dT / 86400))
    }

    // astronomy for a date/zone (latitude independent)
    private fun dayAstro(y: Double, mo: Double, d: Double, lon: Double, z: Double): Astro {
        val jdReq = julianDay(y, mo, d, 0.0, 0.0, 0.0, 0.0, z)
        val t = (jdReq - 2451545) / 36525
        val hours = floor(t * 36525 * 24 + z)
        val cdays = floor((12 + hours) / 24)
        val c = civilFromDays(cdays)
        val jd0 = julianDay(c[0], c[1], c[2], 0.0, 0.0, 0.0, 0.0, z)
        val err = (((jd0 - 2451545) - (cdays - 0.5)) + z / 24) * 86400
        val dT = deltaT(y, mo, d, z)
        val eps = (laskar((jdReq + dT / 86400 - 2451545) / 365250) / 3600) + EPS_OFF
        val alpha = DoubleArray(3); val delta = DoubleArray(3)
        for (k in -1..1) { val r = sunAD(jd0 + k + dT / 86400, eps); alpha[k + 1] = r[0]; delta[k + 1] = r[1] }
        val nu = gmst(jd0) + GMST_OFF
        val a = Astro(lon, dT, err, nu, alpha, delta)
        var m0 = (alpha[1] - lon - nu) / 360
        if (m0 < 0) m0 += 1
        a.m0 = m0
        var tr = m0                                    // transit iterated like rise/set (|step| < 0.0002 day)
        for (i in 0 until 20) { val st = -a.h(tr) / 360; tr += st; if (abs(st) < 0.0002) break }
        if (tr < 0) tr += 1
        a.transit = tr
        return a
    }

    private fun newton(a: Astro, phi: Double, m0: Double): Double? {
        var m = m0
        val sp = sin(phi * D2R); val cp = cos(phi * D2R)
        for (i in 0 until 20) {
            val n = m + a.dT / 86400
            val dec = interp(a.delta, n) * D2R
            val hh = limit180(a.nu + 360.985647 * m + a.lon - interp(a.alpha, n)) * D2R
            val h = asin(sp * sin(dec) + cp * cos(dec) * cos(hh)) * R2D
            val dm = (h - H0) * cos(h * D2R) / (360 * cos(dec) * cp * sin(hh))
            m += dm
            if (abs(dm) < 0.0002) return m
            if (abs(dm) > 5) return null
        }
        return null
    }
    private fun intoDay(s: Double): Double = if (s < 0) s + 86400 else if (s >= 86400) s - 86400 else s
    private fun sec(a: Astro, m: Double): Double = m * 86400 + a.err

    private class Events { var ok = false; var sunrise = Double.NaN; var maghrib = Double.NaN; var fajrHA = Double.NaN
        var fajr = Double.NaN; var asr = Double.NaN; var eid = Double.NaN }

    // latitude-dependent events (seconds, raw)
    private fun latEvents(a: Astro, phi: Double): Events {
        val r = Events()
        val h0d = altHA(H0, phi, a.delta[1])
        if (!h0d.isNaN()) {
            var mr = a.m0 - h0d / 360; if (mr < 0) mr += 1
            var ms = a.m0 + h0d / 360; if (ms >= 1) ms -= 1
            val r1 = newton(a, phi, mr); val r2 = newton(a, phi, ms)
            if (r1 != null && r2 != null) {
                r.sunrise = intoDay(sec(a, r1) + SUNRISE_OFF)
                r.maghrib = intoDay(sec(a, r2))
                val day = ((r.maghrib - r.sunrise) % 86400 + 86400) % 86400
                r.ok = day >= 3600 && 86400 - day >= 3600
            }
        }
        val d1 = a.delta[2]
        r.fajrHA = altHA(FAJR_ALT, phi, d1)
        r.fajr = sec(a, a.transit - r.fajrHA / 360)
        val asrAlt = atan(1 / (1 + tan(abs(phi - d1) * D2R))) * R2D
        r.asr = sec(a, a.transit + altHA(asrAlt, phi, d1) / 360)
        val eidHA = altHA(EID_ALT, phi, d1)
        r.eid = if (eidHA.isNaN()) Double.NaN else sec(a, a.transit - eidHA / 360)
        return r
    }

    private fun fmt(m: Double): String {
        if (m.isNaN()) return "NaN:NaN"
        val h = truncate(m / 60); val mi = m - 60 * h
        fun f(v: Double) = (if (v < 0) "-" else "") + abs(v).toLong().toString().padStart(2, '0')
        return f(h) + ":" + f(mi)
    }
    private fun up(s: Double): Double { val t = truncate(s); return truncate((t + 59) / 60) }
    private fun down(s: Double): Double = truncate(truncate(s) / 60)

    private val pCache = java.util.concurrent.ConcurrentHashMap<String, Double>()
    private fun solsticeP(y: Double, phi: Double, lon: Double, z: Double): Double {
        val key = "$y|${phi < 0}|$lon|$z"
        pCache[key]?.let { return it }
        val lat45 = if (phi < 0) -45.0 else 45.0
        val a = dayAstro(y, if (phi < 0) 12.0 else 6.0, 21.0, lon, z)
        val e = latEvents(a, lat45)
        val night = 86400 - e.maghrib + e.sunrise   // as the server: not reduced mod 86400
        val p = (e.sunrise - e.fajr + TW_ADD) / night
        pCache[key] = p
        return p
    }

    private fun valid(y: Int, mo: Int, d: Int, lat: Double, lon: Double, z: Double): Boolean {
        if (!(abs(lat) <= 90 && abs(lon) <= 180)) return false
        if (!(z >= -12 && z <= 14)) return false
        val c = civilFromDays(daysFromCivil(y.toDouble(), mo.toDouble(), d.toDouble()))
        return c[0] == y.toDouble() && c[1] == mo.toDouble() && c[2] == d.toDouble()
    }
}
