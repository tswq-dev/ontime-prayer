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
    private val L_TERMS: Array<Array<DoubleArray>> = arrayOf(arrayOf(doubleArrayOf(1.75347, 0.0, 0.0), doubleArrayOf(0.033417, 4.669257, 6283.0758499914), doubleArrayOf(0.000349, 4.626102, 12566.1516999828), doubleArrayOf(3.497056e-05, 2.74411800971, 5753.3848848968), doubleArrayOf(3.417571e-05, 2.82886579606, 3.523118349), doubleArrayOf(3.135896e-05, 3.62767041758, 77713.7714681205), doubleArrayOf(2.676218e-05, 4.41808351397, 7860.4193924392), doubleArrayOf(2.342687e-05, 6.13516237631, 3930.2096962196), doubleArrayOf(1.324292e-05, 0.74246356352, 11506.7697697936), doubleArrayOf(1.273166e-05, 2.03709655772, 529.6909650946), doubleArrayOf(1.199167e-05, 1.10962944315, 1577.3435424478), doubleArrayOf(9.9025e-06, 5.23268129594, 5884.9268465832), doubleArrayOf(9.01855e-06, 2.04505443513, 26.2983197998), doubleArrayOf(8.57223e-06, 3.50849156957, 398.1490034082), doubleArrayOf(7.79786e-06, 1.17882652114, 5223.6939198022), doubleArrayOf(7.53141e-06, 2.53339053818, 5507.5532386674), doubleArrayOf(5.05264e-06, 4.58292563052, 18849.2275499742), doubleArrayOf(4.92379e-06, 4.20506639861, 775.522611324), doubleArrayOf(3.56655e-06, 2.91954116867, 0.0673103028), doubleArrayOf(3.17087e-06, 5.84901952218, 11790.6290886588), doubleArrayOf(2.84125e-06, 1.89869034186, 796.2980068164), doubleArrayOf(2.71039e-06, 0.31488607649, 10977.078804699), doubleArrayOf(2.4281e-06, 0.34481140906, 5486.777843175), doubleArrayOf(2.0616e-06, 4.80646606059, 2544.3144198834), doubleArrayOf(2.05385e-06, 1.86947813692, 5573.1428014331), doubleArrayOf(2.02261e-06, 2.45767795458, 6069.7767545534), doubleArrayOf(1.55516e-06, 0.83306073807, 213.299095438), doubleArrayOf(1.32212e-06, 3.41118275555, 2942.4634232916), doubleArrayOf(1.26184e-06, 1.0830263021, 20.7753954924), doubleArrayOf(1.15132e-06, 0.64544911683, 0.9803210682), doubleArrayOf(1.02851e-06, 0.63599846727, 4694.0029547076), doubleArrayOf(1.01895e-06, 0.97569221824, 15720.8387848784), doubleArrayOf(1.01724e-06, 4.26679821365, 7.1135470008), doubleArrayOf(9.9206e-07, 6.20992940258, 2146.1654164752), doubleArrayOf(9.7607e-07, 0.6810127227, 155.4203994342), doubleArrayOf(8.5803e-07, 5.98322631256, 161000.6857376741), doubleArrayOf(8.5128e-07, 1.29870743025, 6275.9623029906), doubleArrayOf(8.4711e-07, 3.67080093025, 71430.69561812909), doubleArrayOf(7.9637e-07, 1.807913307, 17260.1546546904), doubleArrayOf(7.8756e-07, 3.03698313141, 12036.4607348882), doubleArrayOf(7.4651e-07, 1.75508916159, 5088.6288397668), doubleArrayOf(7.3874e-07, 3.50319443167, 3154.6870848956), doubleArrayOf(7.3547e-07, 4.67926565481, 801.8209311238), doubleArrayOf(6.9627e-07, 0.83297596966, 9437.762934887), doubleArrayOf(6.2449e-07, 3.97763880587, 8827.3902698748), doubleArrayOf(6.1148e-07, 1.81839811024, 7084.8967811152), doubleArrayOf(5.6963e-07, 2.78430398043, 6286.5989683404), doubleArrayOf(5.6116e-07, 4.38694880779, 14143.4952424306), doubleArrayOf(5.5577e-07, 3.47006009062, 6279.5527316424), doubleArrayOf(5.1992e-07, 0.18914945834, 12139.5535091068), doubleArrayOf(5.1605e-07, 1.33282746983, 1748.016413067), doubleArrayOf(5.1145e-07, 0.28306864501, 5856.4776591154), doubleArrayOf(4.9e-07, 0.48735065033, 1194.4470102246), doubleArrayOf(4.1036e-07, 5.36817351402, 8429.2412664666), doubleArrayOf(4.0938e-07, 2.39850881707, 19651.048481098), doubleArrayOf(3.92e-07, 6.16832995016, 10447.3878396044), doubleArrayOf(3.677e-07, 6.04133859347, 10213.285546211), doubleArrayOf(3.6596e-07, 2.56955238628, 1059.3819301892), doubleArrayOf(3.5954e-07, 1.70876111898, 2352.8661537718), doubleArrayOf(3.5566e-07, 1.77597314691, 6812.766815086), doubleArrayOf(3.3291e-07, 0.59309499459, 17789.845619785), doubleArrayOf(3.0412e-07, 0.44294464135, 83996.84731811189), doubleArrayOf(3.0047e-07, 2.73975123935, 1349.8674096588), doubleArrayOf(2.5352e-07, 3.16470953405, 4690.4798363586)),
        arrayOf(doubleArrayOf(6283.319667, 0.0, 0.0), doubleArrayOf(0.002061, 2.678235, 6283.0758499914), doubleArrayOf(4.30343e-05, 2.63512650414, 12566.1516999828), doubleArrayOf(4.25264e-06, 1.59046980729, 3.523118349), doubleArrayOf(1.19261e-06, 5.79557487799, 26.2983197998), doubleArrayOf(1.08977e-06, 2.96618001993, 1577.3435424478), doubleArrayOf(9.3478e-07, 2.59212835365, 18849.2275499742), doubleArrayOf(7.2122e-07, 1.13846158196, 529.6909650946), doubleArrayOf(6.7768e-07, 1.87472304791, 398.1490034082), doubleArrayOf(6.7327e-07, 4.40918235168, 5507.5532386674), doubleArrayOf(5.9027e-07, 2.8879703846, 5223.6939198022), doubleArrayOf(5.5976e-07, 2.17471680261, 155.4203994342), doubleArrayOf(4.5407e-07, 0.39803079805, 796.2980068164), doubleArrayOf(3.6369e-07, 0.46624739835, 775.522611324), doubleArrayOf(2.8958e-07, 2.64707383882, 7.1135470008), doubleArrayOf(2.0844e-07, 5.34138275149, 0.9803210682), doubleArrayOf(1.9097e-07, 1.84628332577, 5486.777843175), doubleArrayOf(1.8508e-07, 4.96855124577, 213.299095438), doubleArrayOf(1.7293e-07, 2.99116864949, 6275.9623029906), doubleArrayOf(1.6233e-07, 0.03216483047, 2544.3144198834), doubleArrayOf(1.5832e-07, 1.43049285325, 2146.1654164752), doubleArrayOf(1.4615e-07, 1.20532366323, 10977.078804699), doubleArrayOf(1.2461e-07, 2.83432285512, 1748.016413067), doubleArrayOf(1.1877e-07, 3.25804815607, 5088.6288397668), doubleArrayOf(1.1808e-07, 5.2737979048, 1194.4470102246), doubleArrayOf(1.1514e-07, 2.07502418155, 4694.0029547076), doubleArrayOf(1.0641e-07, 0.76614199202, 553.5694028424), doubleArrayOf(9.969e-08, 1.30262991097, 6286.5989683404), doubleArrayOf(9.721e-08, 4.23925472239, 1349.8674096588), doubleArrayOf(9.452e-08, 2.69957062864, 242.728603974), doubleArrayOf(8.577e-08, 5.64475868067, 951.7184062506), doubleArrayOf(7.576e-08, 5.30062664886, 2352.8661537718), doubleArrayOf(6.385e-08, 2.65033984967, 9437.762934887), doubleArrayOf(6.101e-08, 4.66632584188, 4690.4798363586)),
        arrayOf(doubleArrayOf(0.000529, 0.0, 0.0), doubleArrayOf(8.719837e-05, 1.07209665242, 6283.0758499914), doubleArrayOf(3.09125e-06, 0.86728818832, 12566.1516999828), doubleArrayOf(2.7339e-07, 0.05297871691, 3.523118349), doubleArrayOf(1.6334e-07, 5.18826691036, 26.2983197998), doubleArrayOf(1.5752e-07, 3.6845788943, 155.4203994342), doubleArrayOf(9.541e-08, 0.75742297675, 18849.2275499742), doubleArrayOf(8.937e-08, 2.05705419118, 77713.7714681205), doubleArrayOf(6.952e-08, 0.8267330541, 775.522611324), doubleArrayOf(5.064e-08, 4.66284525271, 1577.3435424478), doubleArrayOf(4.061e-08, 1.03057162962, 7.1135470008), doubleArrayOf(3.81e-08, 3.4405080349, 5573.1428014331), doubleArrayOf(3.463e-08, 5.14074632811, 796.2980068164), doubleArrayOf(3.169e-08, 6.05291851171, 5507.5532386674), doubleArrayOf(3.02e-08, 1.19246506441, 242.728603974), doubleArrayOf(2.886e-08, 6.11652627155, 529.6909650946), doubleArrayOf(2.714e-08, 0.30637881025, 398.1490034082), doubleArrayOf(2.538e-08, 2.27992810679, 553.5694028424), doubleArrayOf(2.371e-08, 4.38118838167, 5223.6939198022), doubleArrayOf(2.079e-08, 3.75435330484, 0.9803210682)),
        arrayOf(doubleArrayOf(2.89226e-06, 5.84384198723, 6283.0758499914), doubleArrayOf(3.4955e-07, 0.0, 0.0), doubleArrayOf(1.6819e-07, 5.48766912348, 12566.1516999828), doubleArrayOf(2.962e-08, 5.19577265202, 155.4203994342), doubleArrayOf(1.288e-08, 4.72200252235, 3.523118349), doubleArrayOf(7.14e-09, 5.30045809128, 18849.2275499742), doubleArrayOf(6.35e-09, 5.96925937141, 242.728603974)),
        arrayOf(doubleArrayOf(1.14084e-06, 3.14159265359, 0.0), doubleArrayOf(7.717e-08, 4.13446589358, 6283.0758499914), doubleArrayOf(7.65e-09, 3.83803776214, 12566.1516999828)),
        arrayOf(doubleArrayOf(8.78e-09, 3.14159265359, 0.0)))
    private val B_TERMS: Array<Array<DoubleArray>> = arrayOf(arrayOf(doubleArrayOf(2.7962e-06, 3.19870156017, 84334.66158130829), doubleArrayOf(1.01643e-06, 5.42248619256, 5507.5532386674), doubleArrayOf(8.0445e-07, 3.88013204458, 5223.6939198022), doubleArrayOf(4.3806e-07, 3.70444689758, 2352.8661537718), doubleArrayOf(3.1933e-07, 4.00026369781, 1577.3435424478)),
        arrayOf(doubleArrayOf(9.03e-08, 3.8972906189, 5507.5532386674), doubleArrayOf(6.177e-08, 1.73038850355, 5223.6939198022)))
    private val DT_TABLE = doubleArrayOf(1240.0, 1150.0, 1060.0, 980.0, 910.0, 850.0, 790.0, 740.0, 700.0, 650.0, 620.0, 580.0, 550.0, 530.0, 500.0, 480.0, 460.0, 440.0, 420.0, 400.0, 370.0, 350.0, 330.0, 310.0, 280.0, 260.0, 240.0, 220.0, 200.0, 180.0, 160.0, 140.0, 130.0, 120.0, 110.0, 100.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 100.0, 100.0, 100.0, 100.0, 100.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 120.0, 120.0, 120.0, 120.0, 120.0, 130.0, 130.0, 130.0, 130.0, 140.0, 140.0, 140.0, 150.0, 150.0, 150.0, 150.0, 160.0, 160.0, 160.0, 160.0, 160.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 160.0, 160.0, 150.0, 140.0, 137.0, 131.0, 127.0, 125.0, 125.0, 125.0, 125.0, 125.0, 125.0, 123.0, 120.0, 114.0, 106.0, 96.0, 86.0, 75.0, 66.0, 60.0, 57.0, 56.0, 57.0, 59.0, 62.0, 65.0, 68.0, 71.0, 73.0, 75.0, 77.0, 78.0, 79.0, 75.0, 64.0, 54.0, 29.0, 16.0, -10.0, -27.0, -36.0, -47.0, -54.0, -52.0, -55.0, -56.0, -58.0, -59.0, -62.0, -64.0, -61.0, -47.0, -27.0, 0.0, 26.0, 54.0, 77.0, 105.0, 134.0, 160.0, 182.0, 202.0, 212.0, 224.0, 235.0, 239.0, 243.0, 240.0, 239.0, 239.0, 237.0, 240.0, 243.0, 253.0, 262.0, 273.0, 282.0, 291.0, 300.0, 307.0, 314.0, 322.0, 331.0, 340.0, 350.0, 365.0, 383.0, 402.0, 422.0, 445.0, 465.0, 485.0, 505.0, 522.0, 538.0, 549.0, 558.0, 569.0, 580.0)

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
