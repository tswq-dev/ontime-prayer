import Foundation

/// One end of a prayer's time, as the page worked it out (already formatted).
struct WEx { let at: Date; let name: String; let clock: String }

/// One day: its times, the ends of each time, and the Hijri date and weekday (from the page).
struct WDay {
    let d: String
    let t: [String: Date]
    let ex: [String: [WEx]]
    let hd: String, hm: String, hy: String, wd: String
}

/// What the widgets show, read from the page's month of days. The widgets never compute prayer times.
struct WData {
    static let pray = ["fajr", "dhuhr", "asr", "maghrib", "isha"]
    static let ksa = TimeZone(secondsFromGMT: 3 * 3600)!

    let en: Bool, arabDigits: Bool, h12: Bool, iqc: Bool
    let city: String
    let names: [String: String]
    let iq: [String: Int]
    let days: [WDay]

    static func load() -> WData? { SharedStore.read().flatMap(parse) }

    static func parse(_ data: Data) -> WData? {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let arr = o["days"] as? [[String: Any]], !arr.isEmpty else { return nil }
        func ms(_ v: Any?) -> Date? { (v as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue / 1000) } }
        func int(_ v: Any?) -> Int? { (v as? NSNumber)?.intValue }
        func str(_ v: Any?) -> String { if let s = v as? String { return s }; if let n = v as? NSNumber { return n.stringValue }; return "" }
        let keys = ["fajr", "sunrise", "duha", "dhuhr", "asr", "maghrib", "isha"]
        let days: [WDay] = arr.map { j in
            var t: [String: Date] = [:]
            for k in keys { if let x = ms(j[k]) { t[k] = x } }
            var ex: [String: [WEx]] = [:]
            if let e = j["ex"] as? [String: Any] {
                for (k, v) in e {
                    guard let l = v as? [[String: Any]] else { continue }
                    ex[k] = l.compactMap { i in ms(i["at"]).map { WEx(at: $0, name: str(i["name"]), clock: str(i["clock"])) } }
                }
            }
            return WDay(d: str(j["d"]), t: t, ex: ex, hd: str(j["hd"]), hm: str(j["hm"]), hy: str(j["hy"]), wd: str(j["wd"]))
        }
        var iq: [String: Int] = [:]
        if let q = o["iq"] as? [String: Any] { for (k, v) in q { if let n = int(v) { iq[k] = n } } }
        return WData(en: str(o["lang"]) == "en", arabDigits: int(o["dg"]) == 1, h12: int(o["f12"]) == 1, iqc: (int(o["iqc"]) ?? 1) == 1,
                     city: str(o["city"]), names: o["names"] as? [String: String] ?? [:], iq: iq, days: days)
    }

    // MARK: days and prayers

    static var cal: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = ksa; return c }

    static func key(_ date: Date) -> String {
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    func day(_ date: Date, _ off: Int = 0) -> WDay? {
        let k = WData.key(date)
        guard let i = days.firstIndex(where: { $0.d == k }), days.indices.contains(i + off) else { return nil }
        return days[i + off]
    }

    /// The prayer the widget is about; [iqAt] is set while we wait for its iqama (the countdown then runs to it).
    struct Hero { let k: String; let at: Date; let iqAt: Date?; var target: Date { iqAt ?? at } }

    func next(_ now: Date) -> Hero? {
        for o in 0...1 {
            guard let d = day(now, o) else { continue }
            for k in WData.pray { if let t = d.t[k], t > now { return Hero(k: k, at: t, iqAt: nil) } }
        }
        return nil
    }

    func prev(_ now: Date) -> Hero? {
        for o in [0, -1] {
            guard let d = day(now, o) else { continue }
            for k in WData.pray.reversed() { if let t = d.t[k], t <= now { return Hero(k: k, at: t, iqAt: nil) } }
        }
        return nil
    }

    func iqama(_ k: String, _ at: Date) -> Date { at.addingTimeInterval(Double(iq[k] ?? 20) * 60) }

    func hero(_ now: Date) -> Hero? {
        if iqc, let p = prev(now) { let q = iqama(p.k, p.at); if now < q { return Hero(k: p.k, at: p.at, iqAt: q) } }
        return next(now)
    }

    /// The moments the widget changes over the next hours: each prayer, each iqama (when that countdown is on), midnight.
    func changes(from now: Date, hours: Double = 36) -> [Date] {
        let end = now.addingTimeInterval(hours * 3600)
        var s = Set<Date>()
        for o in -1...2 {
            guard let d = day(now, o) else { continue }
            for k in WData.pray { if let t = d.t[k] { s.insert(t); if iqc { s.insert(iqama(k, t)) } } }
            if let sr = d.t["sunrise"] { s.insert(sr) }
        }
        let start = WData.cal.startOfDay(for: now)
        for o in 1...2 { if let m = WData.cal.date(byAdding: .day, value: o, to: start) { s.insert(m) } }
        return s.filter { $0 > now && $0 <= end }.sorted()
    }

    // MARK: words

    func name(_ k: String) -> String { names[k] ?? k }

    func digits(_ s: String) -> String {
        guard arabDigits else { return s }
        return String(s.map { c -> Character in
            if c.isASCII, let v = c.wholeNumberValue, let u = UnicodeScalar(UInt32(0x0660 + v)) { return Character(u) }
            return c
        })
    }

    /// "3:12 م" / "3:12 PM", in Saudi time like the page.
    func clock(_ date: Date, mer: Bool = true) -> String {
        let c = WData.cal
        var h = c.component(.hour, from: date); let m = c.component(.minute, from: date)
        var tail = ""
        if h12 { tail = h < 12 ? (en ? "AM" : "ص") : (en ? "PM" : "م"); h = h % 12 == 0 ? 12 : h % 12 }
        return digits(String(format: "%d:%02d", h, m)) + (mer && !tail.isEmpty ? " " + tail : "")
    }

    func hijri(_ d: WDay) -> String { digits([d.hd, d.hm].filter { !$0.isEmpty }.joined(separator: " ")) }

    /// For the ticking countdowns: the language the user chose in the app, with its digits.
    var locale: Locale {
        en ? Locale(identifier: arabDigits ? "en_US@numbers=arab" : "en_US")
           : Locale(identifier: arabDigits ? "ar_SA@numbers=arab" : "ar_SA@numbers=latn")
    }

    // MARK: a sample day, for the widget gallery before the app has sent anything

    static func sample(_ now: Date = Date()) -> WData {
        let start = cal.startOfDay(for: now)
        func at(_ o: Int, _ h: Int, _ m: Int) -> Date { start.addingTimeInterval(Double(o * 86400 + h * 3600 + m * 60)) }
        let hm: [(String, Int, Int)] = [("fajr", 5, 6), ("sunrise", 6, 26), ("duha", 6, 46), ("dhuhr", 11, 50), ("asr", 14, 56), ("maghrib", 17, 15), ("isha", 18, 45)]
        let days = (-1...1).map { o -> WDay in
            var t: [String: Date] = [:]
            for (k, h, m) in hm { t[k] = at(o, h, m) }
            return WDay(d: key(at(o, 12, 0)), t: t, ex: [:], hd: "10", hm: "جمادى الآخرة", hy: "1448", wd: "الجمعة")
        }
        return WData(en: false, arabDigits: false, h12: true, iqc: false, city: "مكة المكرمة",
                     names: ["fajr": "الفجر", "sunrise": "الشروق", "duha": "الضحى", "dhuhr": "الظهر", "asr": "العصر", "maghrib": "المغرب", "isha": "العشاء"],
                     iq: [:], days: days)
    }
}
