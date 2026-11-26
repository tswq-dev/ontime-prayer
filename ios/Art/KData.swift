import Foundation

// WidgetData.kt in Swift (0.31): what the widgets know, pushed by the page (`widgetData`, the same JSON as on Android),
// and one widget's own look (KStyle = WStyle). Widgets never compute prayer times themselves.

/// org.json's JSONObject, as far as the art reads it.
struct J {
    let o: [String: Any]
    init(_ o: [String: Any]) { self.o = o }
    init?(_ any: Any?) { guard let d = any as? [String: Any] else { return nil }; o = d }
    func has(_ k: String) -> Bool { o[k] != nil && !(o[k] is NSNull) }
    func optLong(_ k: String, _ d: Int = 0) -> Int {
        if let n = o[k] as? NSNumber { return n.intValue }
        if let s = o[k] as? String, let v = Double(s) { return Int(v) }
        return d
    }
    func optInt(_ k: String, _ d: Int = 0) -> Int { optLong(k, d) }
    func optDouble(_ k: String, _ d: Double = 0) -> Double { (o[k] as? NSNumber)?.doubleValue ?? (o[k] as? String).flatMap(Double.init) ?? d }
    func optString(_ k: String, _ d: String = "") -> String {
        if let s = o[k] as? String { return s }
        if let n = o[k] as? NSNumber { return n.stringValue }
        return d
    }
    func optBoolean(_ k: String, _ d: Bool = false) -> Bool { (o[k] as? NSNumber)?.boolValue ?? d }
    func optJSONObject(_ k: String) -> J? { J(o[k]) }
    func optJSONArray(_ k: String) -> [Any]? { o[k] as? [Any] }
}

final class KData {
    let o: J
    let ar: Bool, arabDigits: Bool, h12: Bool, city: String
    private let names: J, iq: J
    private let days: [J]
    init(_ j: [String: Any]) {
        o = J(j)
        ar = o.optString("lang", "ar") != "en"
        arabDigits = o.optInt("dg") == 1
        h12 = o.optInt("f12", 1) == 1
        city = o.optString("city")
        names = o.optJSONObject("names") ?? J([:])
        iq = o.optJSONObject("iq") ?? J([:])
        days = (o.optJSONArray("days") ?? []).compactMap { J($0) }
        fri = o.optInt("fri", 1) == 1
        since = o.optString("ia") == "since"
        switch J(j).optString("count") { case "hms", "hmc": appCf = "clock"; case "m": appCf = "m"; case "text": appCf = "text"; default: appCf = "hm" }
    }
    static func load(_ data: Data?) -> KData? {
        guard let d = data, let j = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return nil }
        return KData(j)
    }

    /// Friday: «الجمعة» in place of «الظهر» (the page's switch, on by default).
    let fri: Bool
    /// After the adhan: the iqama countdown ("iq") or the time since the adhan ("since").
    let since: Bool
    /// Set before drawing: the day whose dhuhr the widget is about (today until asr, then tomorrow).
    var dhuhrDay = 0
    func isFri(_ ms: Int) -> Bool { KData.cal().component(.weekday, from: KData.date(ms)) == 6 }
    func name(_ k: String) -> String { k == "dhuhr" && fri && dhuhrDay > 0 && isFri(dhuhrDay) ? (ar ? "الجمعة" : "Jumuah") : names.optString(k, k) }
    func iqMin(_ k: String) -> Int { iq.optInt(k, 20) }

    /// The day (local date) that contains [now]; nil when the pushed month has run out.
    func day(_ now: Int, _ offset: Int = 0) -> J? {
        let key = KData.dateKey(now + offset * KData.DAY)
        return days.first { $0.optString("d") == key }
    }
    var valid: Bool { !days.isEmpty }

    /// A prayer time; [iqAt] is set while we wait for its iqama (the countdown then runs to it).
    struct Ev { let k: String; let at: Int; var iqAt = 0; var target: Int { iqAt > 0 ? iqAt : at } }
    /// What the widgets are about: after the adhan, until the iqama (if that countdown is on), the same prayer; else the next one.
    func hero(_ now: Int) -> Ev? {
        if o.optInt("iqc", 1) == 1, let p = prev(now) {
            // no iqama wait on Friday's dhuhr: it follows the khutbah
            if p.k == "dhuhr" && fri && isFri(p.at) { return next(now) }
            let iqt = p.at + iqMin(p.k) * 60_000; if now < iqt { return Ev(k: p.k, at: p.at, iqAt: iqt) }
        }
        return next(now)
    }
    /// The next of the five prayers after [now], and the one before it.
    func next(_ now: Int) -> Ev? {
        for o in 0...1 { guard let d = day(now, o) else { continue }; for k in KData.PRAY { let t = d.optLong(k); if t > now { return Ev(k: k, at: t) } } }
        return nil
    }
    func prev(_ now: Int) -> Ev? {
        for o in [0, -1] { guard let d = day(now, o) else { continue }; for k in KData.PRAY.reversed() { let t = d.optLong(k); if t >= 1 && t <= now { return Ev(k: k, at: t) } } }
        return nil
    }

    struct Win { let k: String; let start: Int; let end: Int; let first: Int; let names: [String]; let ex: [J] }
    /// The prayer whose time we are in now (Duha included), with its end(s) as the page computed them.
    func current(_ now: Int) -> Win? {
        var best: Win? = nil
        for o in [-1, 0] {
            guard let d = day(now, o), let exAll = d.optJSONObject("ex") else { continue }
            for k in KData.EXK {
                let start = d.optLong(k)
                guard let exA = exAll.optJSONArray(k) else { continue }
                let ex = exA.compactMap { J($0) }
                if ex.isEmpty || start <= 0 { continue }
                let end = ex[ex.count - 1].optLong("at")
                if now >= start && now < end && (best == nil || start > best!.start) {
                    best = Win(k: k, start: start, end: end, first: ex[0].optLong("at"), names: ex.map { $0.optString("name") }, ex: ex)
                }
            }
        }
        return best
    }

    // ---------- for the extra widgets ----------
    /// Weekday of a pushed day, 0 = Sunday … 6 = Saturday.
    func wdi(_ d: J) -> Int {
        if d.has("wdi") { return d.optInt("wdi") }
        let p = d.optString("d").split(separator: "-").compactMap { Int($0) }
        guard p.count == 3, let dt = KData.cal().date(from: DateComponents(year: p[0], month: p[1], day: p[2], hour: 12)) else { return 0 }
        return KData.cal().component(.weekday, from: dt) - 1
    }
    /// The Hijri day and month number (0 when the page did not send it).
    func hd(_ d: J) -> Int { Int(d.optString("hd")) ?? 0 }
    func hmn(_ d: J) -> Int { d.optInt("hmn", 0) }
    /// Minutes of imsak before Fajr (Ramadan widget).
    var imsak: Int { o.optInt("ims", 10) }
    /// Tonight, from the maghrib that begins it to the fajr that ends it (before fajr: the night we are in).
    func night(_ now: Int) -> (Int, Int)? {
        guard let d0 = day(now) else { return nil }
        if now < d0.optLong("fajr") { guard let p = day(now, -1) else { return nil }; return (p.optLong("maghrib"), d0.optLong("fajr")) }
        guard let n = day(now, 1) else { return nil }; return (d0.optLong("maghrib"), n.optLong("fajr"))
    }
    /// The day whose five prayers count now: from its fajr to the next fajr (Isha after midnight is still yesterday's).
    func prayerDay(_ now: Int) -> J? { guard let d0 = day(now) else { return nil }; return now < d0.optLong("fajr") ? day(now, -1) : d0 }
    /// The length of the Hijri month that contains [now] (29 or 30), from where the next month begins in the pushed days.
    func monthLen(_ now: Int) -> Int {
        guard let h = day(now).map({ hd($0) }) else { return 30 }
        for o in 1...31 { guard let dd = day(now, o) else { break }; if hd(dd) == 1 { return day(now, o - 1).map { hd($0) } ?? 30 } }
        return h >= 29 ? h : 30
    }
    /// Days from today to the next day with this Hijri month and day, within the pushed days; -1 if not there.
    func daysTo(_ now: Int, _ month: Int, _ dayN: Int, _ max: Int = 34) -> Int {
        for o in 0...max { guard let dd = day(now, o) else { break }; if hmn(dd) == month && hd(dd) == dayN { return o } }
        return -1
    }

    /// Digits as the user chose them, whichever kind the text came with.
    func digits(_ s: String) -> String {
        String(s.map { ch -> Character in
            if arabDigits, let v = ch.wholeNumberValue, ch.isASCII { return Character(UnicodeScalar(0x0660 + v)!) }
            if !arabDigits, let u = ch.unicodeScalars.first, (0x0660...0x0669).contains(u.value) { return Character(UnicodeScalar(0x30 + u.value - 0x0660)!) }
            return ch
        })
    }
    /// "5:39" (12 or 24 hours), and its AM/PM mark separately.
    func clock(_ ms: Int) -> String {
        let c = KData.cal().dateComponents([.hour, .minute], from: KData.date(ms)); var h = c.hour ?? 0
        if h12 { h = h % 12 == 0 ? 12 : h % 12 }
        return digits("\(h):" + String(c.minute ?? 0).padStart(2, "0"))
    }
    func ampm(_ ms: Int) -> String { !h12 ? "" : (KData.cal().component(.hour, from: KData.date(ms)) < 12 ? (ar ? "ص" : "AM") : (ar ? "م" : "PM")) }

    /// The countdown's look, set for each widget before it is drawn: "hm" 1 س 24 د, "clock" 1:24, "m" 84 دقيقة, "text" ساعة و24 دقيقة.
    var cf = "hm"
    /// Until the user picks one for a widget, it follows the app's own countdown.
    let appCf: String
    private func pl(_ n: Int, _ one: String, _ two: String, _ few: String, _ many: String) -> String {
        n == 1 ? one : n == 2 ? two : (3...10).contains(n) ? "\(n) \(few)" : "\(n) \(many)"
    }
    /// A countdown rounds up (so «بعد ١٠» never hides a longer wait); time since the adhan ([up]) counts whole minutes.
    func dur(_ ms: Int, _ up: Bool = false) -> String {
        let m = max(0, up ? ms / 60_000 : (ms + 59_999) / 60_000), h = m / 60, mi = m % 60
        let s: String
        switch cf {
        case "clock": s = h == 0 ? (ar ? "\(mi) د" : "\(mi) min") : "\(h):" + String(mi).padStart(2, "0")
        case "m": s = ar ? pl(m, "دقيقة", "دقيقتين", "دقائق", "دقيقة") : "\(m) min"
        case "text":
            if !ar { s = [h > 0 ? "\(h) hour" + (h > 1 ? "s" : "") : nil, (mi > 0 || h == 0) ? "\(mi) minute" + (mi != 1 ? "s" : "") : nil].compactMap { $0 }.joined(separator: " ") }
            else {
                let hs = h > 0 ? pl(h, "ساعة", "ساعتين", "ساعات", "ساعة") : ""
                if h > 0 && mi == 15 { s = "\(hs) وربع" }
                else if h > 0 && mi == 30 { s = "\(hs) ونصف" }
                else if h > 0 && mi == 45 { s = pl(h + 1, "ساعة", "ساعتين", "ساعات", "ساعة") + " إلا ربع" }
                else { let ms2 = mi > 0 ? pl(mi, "دقيقة", "دقيقتين", "دقائق", "دقيقة") : ""
                    s = !hs.isEmpty && !ms2.isEmpty ? "\(hs) و\(ms2)" : (!hs.isEmpty ? hs : (!ms2.isEmpty ? ms2 : "أقل من دقيقة")) }
            }
        default: s = ar ? (h > 0 ? "\(h) س \(mi) د" : "\(mi) د") : (h > 0 ? "\(h) h \(mi) min" : "\(mi) min")
        }
        return digits(s)
    }
    func inTxt(_ ms: Int) -> String { (ar ? "بعد " : "in ") + dur(ms) }
    func inTxt(_ e: Ev, _ now: Int) -> String { e.iqAt > 0 ? (since ? sinceTxt(now - e.at) : (ar ? "الإقامة بعد " : "Iqama in ") + dur(e.iqAt - now)) : inTxt(e.at - now) }
    /// after the adhan, next to the prayer's name: «العصر منذ ٥ د»
    func sinceTxt(_ ms: Int) -> String { ms < 60_000 ? (ar ? "الآن" : "now") : (ar ? "منذ " + dur(ms, true) : dur(ms, true) + " ago") }
    /// on its own (the iqama bar, beside the iqama's time): the adhan named
    func adhanSince(_ ms: Int) -> String { ms < 60_000 ? (ar ? "الأذان الآن" : "Adhan now") : (ar ? "الأذان منذ " + dur(ms, true) : "Adhan " + dur(ms, true) + " ago") }
    /// The time a countdown shows: to the iqama or the next prayer, or (the user's pick) the time since the adhan.
    func leftMs(_ e: Ev, _ now: Int) -> Int { e.iqAt > 0 && since ? now - e.at : e.target - now }
    /// The count beside a prayer: its countdown, or after the adhan «الآن» then the whole minutes since.
    func count(_ e: Ev, _ now: Int) -> String { e.iqAt > 0 && since ? (now - e.at < 60_000 ? (ar ? "الآن" : "now") : dur(now - e.at, true)) : dur(e.target - now) }
    func leftTxt(_ ms: Int) -> String { (ar ? "بقي " : "") + dur(ms) + (ar ? "" : " left") }

    static let DAY = 86_400_000
    static let PRAY = ["fajr", "dhuhr", "asr", "maghrib", "isha"]
    static let SIX = ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"]
    static let EXK = ["fajr", "duha", "dhuhr", "asr", "maghrib", "isha"]
    // the phone's own time zone, like the page
    static func cal() -> Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = .current; return c }
    static func date(_ ms: Int) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    static func dateKey(_ ms: Int) -> String {
        let c = cal().dateComponents([.year, .month, .day], from: date(ms))
        return String(c.year ?? 0) + "-" + String(c.month ?? 0).padStart(2, "0") + "-" + String(c.day ?? 0).padStart(2, "0")
    }
    static func minuteOfDay(_ ms: Int) -> Int { let c = cal().dateComponents([.hour, .minute], from: date(ms)); return (c.hour ?? 0) * 60 + (c.minute ?? 0) }
    static func hourOf(_ ms: Int) -> Int { cal().component(.hour, from: date(ms)) }
    /// Midnight that begins the local day of [ms].
    static func midnight(_ ms: Int) -> Int { Int(cal().startOfDay(for: date(ms)).timeIntervalSince1970 * 1000) }
}

/// One widget's own look, chosen in the app (WStyle in WidgetData.kt).
struct KStyle {
    var bg: String, font: String, rows: Set<String>, tap = "app"
    /// weight of the prayer's name and of the times and text: "l", "m", "b"
    var nw = "m", dw = "m"
    /// how far the name is stretched with kashida: 0 not at all … 7 fills its space to the frame
    var kl = 7
    /// the countdown's format ("" follows the app's own)
    var cf = ""
    /// the sky background pinned to a moment of the day, "maghrib:6"; "" follows the time
    var sp = ""
    /// where the table puts the city and the date: "l", "c", "b"
    var hp = "l"
    /// what the one-line widget shows: "pray", "day", "hijri", "greg", "dayh"
    var ln = "pray"
    var kash: Bool { kl > 0 }
    func has(_ k: String) -> Bool { rows.contains(k) }
    func toJson() -> [String: Any] {
        ["v": 2, "bg": bg, "font": font, "rows": Array(rows), "tap": tap, "nw": nw, "dw": dw, "kl": kl, "kash": kl > 0, "kv": 3, "cf": cf, "sp": sp, "hp": hp, "ln": ln]
    }

    /// What each widget can show; the second is what it shows until the user changes it.
    static let SHOW: [String: ([String], Set<String>)] = [
        "pano": (["count", "time", "hijri", "exit", "labels", "sky"], ["count", "time", "labels", "sky"]),
        "window": (["count", "time", "hijri", "sky"], ["count", "sky"]),
        "ring": (["count", "time", "prev"], ["count", "prev"]),
        "orbit": (["count", "time", "hijri", "exit"], ["count", "time"]),
        "script": (["count", "time", "hijri", "exit"], ["count", "time"]),
        "table": (["city", "date", "count", "times", "exit", "iqama", "sunrise", "duha"], ["times", "exit"]),
        "clock": (["count", "time"], ["count", "time"]),
        "moon": (["wd", "next"], ["wd", "next"]),
        "ribbon": (["count", "labels"], ["count", "labels"]),
        "exit": (["time", "bar"], ["time", "bar"]),
        "actions": (["count", "time"], ["count", "time"]),
        "line": (["count", "time", "hijri"], ["count", "time"]),
        "prayed": (["streak", "names", "now"], ["streak", "names", "now"]),
        "night": (["mid", "count", "sky"], ["mid", "count", "sky"]),
        "ramadan": (["imsak", "fajr", "prog"], ["imsak", "fajr", "prog"]),
        "white": (["monthu", "days"], ["monthu", "days"]),
        "column": (["count", "time", "sunrise", "duha", "exit", "hijri", "moon"], ["count", "exit", "hijri", "moon"]),
        // the iPhone's own (0.32)
        "date": (["wd", "hijri", "greg"], ["wd", "hijri", "greg"]),
        "alarm": ([], [])]
    static let BGS: Set<String> = ["sky", "light", "dark", "navy", "clear", "dyn"]
    static let CFS: Set<String> = ["hm", "clock", "m", "text"]
    static let LNS: Set<String> = ["pray", "day", "hijri", "greg", "dayh"]

    static func klFor(_ type: String) -> Int { ["script", "actions", "table", "column"].contains(type) ? 7 : 0 }
    /// Until the user picks, a widget uses the name font chosen for the app's own screen.
    static func defaultFor(_ type: String, _ font: String? = nil) -> KStyle {
        let bg: String
        switch type { case "script", "line": bg = "clear"; case "prayed", "ramadan": bg = "dark"; case "white": bg = "light"; default: bg = "sky" }
        let f = font.flatMap { ["kufi", "ruqaa", "naskh"].contains($0) ? $0 : nil } ?? "kufi"
        return KStyle(bg: bg, font: f, rows: SHOW[type]?.1 ?? [], tap: type == "prayed" ? "pray" : "app", kl: klFor(type))
    }
    static func from(_ type: String, _ j: [String: Any]?, _ font: String? = nil) -> KStyle {
        let d = defaultFor(type, font); guard let jj = j else { return d }
        let o = J(jj)
        let r = o.optJSONArray("rows")
        let rows: Set<String> = (r == nil || (o.optInt("v") < 2 && type != "table")) ? d.rows : Set(r!.compactMap { $0 as? String })
        let wt = { (k: String) -> String in let v = o.optString(k, "m"); return ["l", "m", "b"].contains(v) ? v : "m" }
        let kl: Int
        if type == "script" || type == "actions" { kl = o.has("kl") ? o.optInt("kl", 7).coerceIn(0, 7) : (o.optBoolean("kash", true) ? 7 : 0) }
        else { kl = (o.optInt("kv") >= (type == "table" ? 3 : 2) && o.has("kl")) ? o.optInt("kl", d.kl).coerceIn(0, 7) : d.kl }
        let bg = o.optString("bg", d.bg), cf = o.optString("cf", ""), sp = o.optString("sp", ""), hp = o.optString("hp", "l"), ln = o.optString("ln", "pray")
        let spOk = sp.range(of: "^(fajr|sunrise|dhuhr|asr|maghrib|isha):-?\\d{1,4}$", options: .regularExpression) != nil
        return KStyle(bg: BGS.contains(bg) ? bg : d.bg, font: o.optString("font", d.font), rows: rows, tap: o.optString("tap", d.tap), nw: wt("nw"), dw: wt("dw"), kl: kl,
                      cf: CFS.contains(cf) ? cf : "", sp: spOk ? sp : "", hp: ["l", "c", "b"].contains(hp) ? hp : "l", ln: LNS.contains(ln) ? ln : "pray")
    }
}
