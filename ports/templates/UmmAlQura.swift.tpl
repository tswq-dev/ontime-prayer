// UmmAlQura.swift — Umm al-Qura prayer times (reconstruction), Swift (iOS/macOS/Linux), Foundation only.
// Reproduces, to the minute, the prayer times shown by the official Umm al-Qura calendar website.
// Unofficial; not KACST code. Spec: ALGORITHM.md. Port of js/umq.js, verified on the same 14,657,797 values
// (ports/swift/Verify/main.swift). MIT licence. https://github.com/tswq-dev/ontime-prayer
import Foundation

/// Seven times as "HH:MM" strings in the requested time zone.
public struct PrayerTimes: Equatable {
    public let fajr: String, sunrise: String, dhuhr: String, asr: String, maghrib: String, isha: String, eid: String
}

public enum UmmAlQura {
    /// Prayer times for a Gregorian date "YYYY-MM-DD"; lat/lon in decimal degrees (north, east positive);
    /// zone = UTC offset in hours (Saudi Arabia: 3); ramadan = true in Ramadan (Isha = Maghrib + 120 min).
    /// Returns nil for an invalid date, coordinates or zone.
    public static func prayerTimes(date: String, lat: Double, lon: Double, zone: Double = 3, ramadan: Bool = false) -> PrayerTimes? {
        let p = date.split(separator: "-", omittingEmptySubsequences: false)
        guard p.count == 3, let y = Int(p[0]), let mo = Int(p[1]), let d = Int(p[2]) else { return nil }
        guard valid(y, mo, d, lat, lon, zone) else { return nil }
        let a = dayAstro(Double(y), Double(mo), Double(d), lon, zone)
        let raw = latEvents(a, lat)
        let ev = raw.ok ? raw : latEvents(a, lat < 0 ? -45 : 45)
        let dhuhr = sec(a, a.transit)
        let fajr: String
        if abs(lat) > 48 && (ev.fajrHA.isNaN || ev.fajrHA > fajrHALimit) {
            let pp = solsticeP(Double(y), lat, lon, zone)
            let night = mod86400(86400 - ev.maghrib + ev.sunrise)
            fajr = fmt(down(ev.sunrise - pp * night))
        } else { fajr = fmt(up(ev.fajr)) }
        return PrayerTimes(fajr: fajr, sunrise: fmt(down(ev.sunrise)), dhuhr: fmt(up(dhuhr)), asr: fmt(up(ev.asr)),
                           maghrib: fmt(up(ev.maghrib)), isha: fmt(up(ev.maghrib + (ramadan ? 7200 : 5400))),
                           eid: ev.eid.isNaN ? fmt(down(ev.sunrise + 900)) : fmt(up(ev.eid)))
    }

    /// ΔT in seconds as the server uses it (exposed for testing).
    public static func deltaT(_ y: Double, _ m: Double, _ d: Double, _ z: Double) -> Double {
        let t = (julianDay(y, m, d, 0, 0, 0, 0, z) - 2451545) / 36525
        let yy = 2000 + t * 100
        if yy > 2000 { return 102.3 + t * (123.5 + t * 32.5) }
        if yy < 1620 { return yy < 948 ? 2715.6 + t * (573.36 + t * 46.5) : 50.6 + t * (67.5 + t * 22.5) }
        let i = min(Int(((yy - 1620) / 2).rounded(.towardZero)), dtTable.count - 2)
        let f = yy / 2 - Double(i + 810)
        return (dtTable[i] + (dtTable[i + 1] - dtTable[i]) * f) / 10
    }

    /// The website rounds GPS coordinates to 6 decimals (shortest decimal form, ties away from zero).
    public static func siteCoordinate(_ x: Double) -> Double {
        guard var v = Decimal(string: "\(x)") else { return x }
        var r = Decimal(); NSDecimalRound(&r, &v, 6, .plain)
        return Double(NSDecimalNumber(decimal: r).stringValue) ?? x
    }

    // ---------------------------------------------------------------- tables
    static let lTerms: [[[Double]]] = [%L_TERMS%]
    static let bTerms: [[[Double]]] = [%B_TERMS%]
    static let dtTable: [Double] = [%DT_TABLE%]

    static let d2r = Double.pi / 180, r2d = 180 / Double.pi
    static let gmstOff = 0.146457 / 3600, epsOff = -0.039131 / 3600, sunriseOff = 0.0032001
    static let h0 = -0.8333, fajrAlt = -18.5, eidAlt = 4.2
    static let fajrHALimit = 151.84401, twAdd = 58.99367

    static func trunc(_ x: Double) -> Double { x.rounded(.towardZero) }
    static func floorD(_ x: Double) -> Double { x.rounded(.down) }
    static func mod86400(_ x: Double) -> Double { (x.truncatingRemainder(dividingBy: 86400) + 86400).truncatingRemainder(dividingBy: 86400) }
    static func limitDeg(_ x0: Double) -> Double { let x = x0 / 360; var l = 360 * (x - floorD(x)); if l < 0 { l += 360 }; return l }
    static func limit180(_ x0: Double) -> Double { let x = x0 / 360; var l = 360 * (x - floorD(x)); if l < -180 { l += 360 } else if l > 180 { l -= 360 }; return l }
    static func limit01(_ x: Double) -> Double { var l = x - floorD(x); if l < 0 { l += 1 }; return l }

    // NREL-SPA julian_day, Gregorian correction always applied
    static func julianDay(_ year0: Double, _ month0: Double, _ day: Double, _ hour: Double, _ minute: Double, _ second: Double, _ dut1: Double, _ tz: Double) -> Double {
        var year = year0, month = month0
        let dayDecimal = day + (hour - tz + (minute + (second + dut1) / 60) / 60) / 24
        if month < 3 { month += 12; year -= 1 }
        var jd = trunc(365.25 * (year + 4716)) + trunc(30.6001 * (month + 1)) + dayDecimal - 1524.5
        let a = trunc(year / 100)
        jd += (2 - a + trunc(a / 4))
        return jd
    }

    // civil date <-> day number from 2000-01-01 (proleptic Gregorian)
    static func daysFromCivil(_ y0: Double, _ m: Double, _ d: Double) -> Double {
        let y = y0 - (m <= 2 ? 1 : 0)
        let era = floorD(y / 400), yoe = y - era * 400
        let doy = floorD((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1
        let doe = yoe * 365 + floorD(yoe / 4) - floorD(yoe / 100) + doy
        return era * 146097 + doe - 719468 - 10957
    }
    static func civilFromDays(_ z0: Double) -> (Double, Double, Double) {
        let z = z0 + 719468 + 10957
        let era = floorD(z / 146097), doe = z - era * 146097
        let yoe = floorD((doe - floorD(doe / 1460) + floorD(doe / 36524) - floorD(doe / 146096)) / 365)
        let doy = doe - (365 * yoe + floorD(yoe / 4) - floorD(yoe / 100))
        let mp = floorD((5 * doy + 2) / 153)
        let d = doy - floorD((153 * mp + 2) / 5) + 1
        let m = mp + (mp < 10 ? 3 : -9)
        return (yoe + era * 400 + (m <= 2 ? 1 : 0), m, d)
    }

    static func series(_ terms: [[[Double]]], _ tau: Double) -> Double {
        var sum = 0.0
        for i in 0..<terms.count {
            var s = 0.0
            for t in terms[i] { s += t[0] * cos(t[1] + t[2] * tau) }
            sum += s * pow(tau, Double(i))
        }
        return sum
    }
    // Sun alpha/delta (deg) at JDE, obliquity eps (deg); no nutation, no aberration
    static func sunAD(_ jde: Double, _ eps: Double) -> (Double, Double) {
        let tau = (jde - 2451545) / 365250
        let ll = limitDeg(series(lTerms, tau) * r2d)
        let bb = series(bTerms, tau) * r2d
        let lam = limitDeg(ll + 180), beta = -bb
        let l = lam * d2r, b = beta * d2r, e = eps * d2r
        let alpha = limitDeg(atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l)) * r2d)
        let delta = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l)) * r2d
        return (alpha, delta)
    }
    static func laskar(_ jme: Double) -> Double {
        let u = jme / 10
        let inner = 27.87 + u * (5.79 + u * 2.45)
        let mid = -249.67 + u * (-39.05 + u * (7.12 + u * inner))
        return 84381.448 + u * (-4680.93 + u * (-1.55 + u * (1999.25 + u * (-51.38 + u * mid))))
    }
    static func gmst(_ jd: Double) -> Double {
        let jc = (jd - 2451545) / 36525
        return limitDeg(280.46061837 + 360.98564736629 * (jd - 2451545) + jc * jc * (0.000387933 - jc / 38710000))
    }
    static func interp(_ ad: [Double], _ n: Double) -> Double {
        var a = ad[1] - ad[0], b = ad[2] - ad[1]
        if abs(a) >= 2 { a = limit01(a) }
        if abs(b) >= 2 { b = limit01(b) }
        return ad[1] + n * (a + b + (b - a) * n) / 2
    }
    static func altHA(_ alt: Double, _ phi: Double, _ dec: Double) -> Double {
        let c = (sin(alt * d2r) - sin(phi * d2r) * sin(dec * d2r)) / (cos(phi * d2r) * cos(dec * d2r))
        if c < -1 || c > 1 { return .nan }
        return acos(c) * r2d
    }

    struct Astro {
        let lon: Double, dT: Double, err: Double, nu: Double, alpha: [Double], delta: [Double]
        var m0 = 0.0, transit = 0.0
        func h(_ m: Double) -> Double { UmmAlQura.limit180(nu + 360.985647 * m + lon - UmmAlQura.interp(alpha, m + dT / 86400)) }
    }

    // astronomy for a date/zone (latitude independent)
    static func dayAstro(_ y: Double, _ mo: Double, _ d: Double, _ lon: Double, _ z: Double) -> Astro {
        let jdReq = julianDay(y, mo, d, 0, 0, 0, 0, z)
        let t = (jdReq - 2451545) / 36525
        let hours = floorD(t * 36525 * 24 + z)
        let cdays = floorD((12 + hours) / 24)
        let c = civilFromDays(cdays)
        let jd0 = julianDay(c.0, c.1, c.2, 0, 0, 0, 0, z)
        let err = (((jd0 - 2451545) - (cdays - 0.5)) + z / 24) * 86400
        let dT = deltaT(y, mo, d, z)
        let eps = (laskar((jdReq + dT / 86400 - 2451545) / 365250) / 3600) + epsOff
        var alpha = [Double](), delta = [Double]()
        for k in -1...1 { let r = sunAD(jd0 + Double(k) + dT / 86400, eps); alpha.append(r.0); delta.append(r.1) }
        let nu = gmst(jd0) + gmstOff
        var a = Astro(lon: lon, dT: dT, err: err, nu: nu, alpha: alpha, delta: delta)
        var m0 = (alpha[1] - lon - nu) / 360
        if m0 < 0 { m0 += 1 }
        a.m0 = m0
        var tr = m0                                    // transit iterated like rise/set (|step| < 0.0002 day)
        for _ in 0..<20 { let st = -a.h(tr) / 360; tr += st; if abs(st) < 0.0002 { break } }
        if tr < 0 { tr += 1 }
        a.transit = tr
        return a
    }

    static func newton(_ a: Astro, _ phi: Double, _ m0: Double) -> Double? {
        var m = m0
        let sp = sin(phi * d2r), cp = cos(phi * d2r)
        for _ in 0..<20 {
            let n = m + a.dT / 86400
            let dec = interp(a.delta, n) * d2r
            let hh = limit180(a.nu + 360.985647 * m + a.lon - interp(a.alpha, n)) * d2r
            let h = asin(sp * sin(dec) + cp * cos(dec) * cos(hh)) * r2d
            let dm = (h - h0) * cos(h * d2r) / (360 * cos(dec) * cp * sin(hh))
            m += dm
            if abs(dm) < 0.0002 { return m }
            if abs(dm) > 5 { return nil }
        }
        return nil
    }
    static func intoDay(_ s: Double) -> Double { s < 0 ? s + 86400 : (s >= 86400 ? s - 86400 : s) }
    static func sec(_ a: Astro, _ m: Double) -> Double { m * 86400 + a.err }

    struct Events { var ok = false, sunrise = Double.nan, maghrib = Double.nan, fajrHA = Double.nan, fajr = Double.nan, asr = Double.nan, eid = Double.nan }

    // latitude-dependent events (seconds, raw)
    static func latEvents(_ a: Astro, _ phi: Double) -> Events {
        var r = Events()
        let h0d = altHA(h0, phi, a.delta[1])
        if !h0d.isNaN {
            var mr = a.m0 - h0d / 360; if mr < 0 { mr += 1 }
            var ms = a.m0 + h0d / 360; if ms >= 1 { ms -= 1 }
            if let r1 = newton(a, phi, mr), let r2 = newton(a, phi, ms) {
                r.sunrise = intoDay(sec(a, r1) + sunriseOff)
                r.maghrib = intoDay(sec(a, r2))
                let day = mod86400(r.maghrib - r.sunrise)
                r.ok = day >= 3600 && 86400 - day >= 3600
            }
        }
        let d1 = a.delta[2]
        r.fajrHA = altHA(fajrAlt, phi, d1)
        r.fajr = sec(a, a.transit - r.fajrHA / 360)
        let asrAlt = atan(1 / (1 + tan(abs(phi - d1) * d2r))) * r2d
        r.asr = sec(a, a.transit + altHA(asrAlt, phi, d1) / 360)
        let eidHA = altHA(eidAlt, phi, d1)
        r.eid = eidHA.isNaN ? .nan : sec(a, a.transit - eidHA / 360)
        return r
    }

    static func fmt(_ m: Double) -> String {
        if m.isNaN { return "NaN:NaN" }
        let h = trunc(m / 60), mi = m - 60 * h
        func f(_ v: Double) -> String { let s = String(Int64(abs(v))); return (v < 0 ? "-" : "") + (s.count < 2 ? "0" + s : s) }
        return f(h) + ":" + f(mi)
    }
    static func up(_ s: Double) -> Double { let t = trunc(s); return trunc((t + 59) / 60) }
    static func down(_ s: Double) -> Double { trunc(trunc(s) / 60) }

    private static let pLock = NSLock()
    private static var pCache = [String: Double]()
    static func solsticeP(_ y: Double, _ phi: Double, _ lon: Double, _ z: Double) -> Double {
        let key = "\(y)|\(phi < 0)|\(lon)|\(z)"
        pLock.lock(); if let p = pCache[key] { pLock.unlock(); return p }; pLock.unlock()
        let a = dayAstro(y, phi < 0 ? 12 : 6, 21, lon, z)
        let e = latEvents(a, phi < 0 ? -45 : 45)
        let night = 86400 - e.maghrib + e.sunrise   // as the server: not reduced mod 86400
        let p = (e.sunrise - e.fajr + twAdd) / night
        pLock.lock(); pCache[key] = p; pLock.unlock()
        return p
    }

    static func valid(_ y: Int, _ mo: Int, _ d: Int, _ lat: Double, _ lon: Double, _ z: Double) -> Bool {
        if !(abs(lat) <= 90 && abs(lon) <= 180) { return false }
        if !(z >= -12 && z <= 14) { return false }
        let c = civilFromDays(daysFromCivil(Double(y), Double(mo), Double(d)))
        return c.0 == Double(y) && c.1 == Double(mo) && c.2 == Double(d)
    }
}
