import Foundation
import AppIntents
import WidgetKit

/// «صلّيت» marks, «كتم الأذان القادم» and the reminders set (0.33), kept in the App Group so the widgets «بأزرار» and
/// «صلواتي اليوم» show them and their buttons can change them. A mark is a bit per prayer for each day (Fajr = 1), as on Android.
enum PrayMarks {
    static let file = "marks.json"
    static let PRAY = ["fajr", "dhuhr", "asr", "maghrib", "isha"]

    private static func load() -> [String: Any] {
        SharedStore.read(file: file).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } ?? [:]
    }
    private static func save(_ o: [String: Any]) {
        guard let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        SharedStore.write(s, file: file)
    }

    /// "fajr:2026-11-26" → the day and its bit.
    static func bit(_ pk: String) -> (date: String, b: Int)? {
        let p = pk.split(separator: ":", maxSplits: 1).map(String.init)
        guard p.count == 2, let i = PRAY.firstIndex(of: p[0]) else { return nil }
        return (p[1], 1 << i)
    }
    static func pk(_ k: String, _ at: Int) -> String { k + ":" + KData.dateKey(at) }

    static func days() -> [String: Int] { (load()["days"] as? [String: Any] ?? [:]).compactMapValues { ($0 as? NSNumber)?.intValue } }
    static func mask(_ date: String) -> Int { days()[date] ?? 0 }
    static func has(_ pk: String) -> Bool { bit(pk).map { mask($0.date) & $0.b != 0 } ?? false }

    static func set(_ pk: String, _ on: Bool) { set([pk], on) }
    static func set(_ pks: [String], _ on: Bool) {
        var o = load(), d = o["days"] as? [String: Any] ?? [:]
        for pk in pks {
            guard let bb = bit(pk) else { continue }
            let m = (d[bb.date] as? NSNumber)?.intValue ?? 0, n = on ? m | bb.b : m & ~bb.b
            d[bb.date] = n == 0 ? nil : n
        }
        // a year and a bit is enough for the days in a row
        let oldest = KData.dateKey(Int(Date().timeIntervalSince1970 * 1000) - 400 * KData.DAY)
        o["days"] = d.filter { $0.key >= oldest }
        save(o)
    }

    /// Days in a row with all five marked, ending on the day of [dayMs] (counted once all five are in) or the day before.
    static func streak(_ dayMs: Int) -> Int {
        let ds = days(), c = KData.cal()
        var t = KData.date(dayMs), n = 0
        if ds[KData.dateKey(dayMs)] != 31 { t = c.date(byAdding: .day, value: -1, to: t) ?? t }
        while n < 400, ds[KData.dateKey(Int(t.timeIntervalSince1970 * 1000))] == 31 {
            n += 1; t = c.date(byAdding: .day, value: -1, to: t) ?? t
        }
        return n
    }

    /// The adhan to come silently (its time), 0 for none.
    static var mute: Int {
        get { (load()["mute"] as? NSNumber)?.intValue ?? 0 }
        set { var o = load(); o["mute"] = newValue == 0 ? nil : newValue; save(o) }
    }
    static func muted(_ at: Int) -> Bool { let m = mute; return m > 0 && abs(m - at) <= 60_000 }

    /// The reminders set (prayer → when), copied here by the app.
    static var rem: [String: Int] {
        get { (load()["rem"] as? [String: Any] ?? [:]).compactMapValues { ($0 as? NSNumber)?.intValue } }
        set { var o = load(); o["rem"] = newValue; save(o) }
    }

    /// The prayer whose time we are in (the five only), for «صلّيت».
    static func current(_ d: KData, _ now: Int) -> String? {
        guard let cur = d.current(now), PRAY.contains(cur.k) else { return nil }
        return pk(cur.k, cur.start)
    }
    /// «ذكّرني قبل خروج الوقت»: the time we are in, else the next prayer, while it has an end ahead; as the reminder the
    /// notifications set (the page's fields).
    static func remindTarget(_ d: KData, _ now: Int) -> [String: Any]? {
        let k: String, start: Int
        if let cur = d.current(now) { k = cur.k; start = cur.start } else if let nx = d.next(now) { k = nx.k; start = nx.at } else { return nil }
        guard let ex = d.day(start)?.optJSONObject("ex")?.optJSONArray(k) as? [[String: Any]],
              ex.contains(where: { (($0["at"] as? NSNumber)?.intValue ?? 0) > now }) else { return nil }
        return ["pk": pk(k, start), "key": k, "title": d.name(k), "ex": ex, "lang": d.ar ? "ar" : "en", "dg": d.arabDigits ? 1 : 0,
                "f12": d.h12 ? 1 : 0, "mins": d.o.optInt("mins", 20), "rs": d.o.optString("rs", "speak"), "xspk": "spk_exit_" + k]
    }

    /// What the buttons widget shows now.
    static func btns(_ d: KData?, _ now: Int) -> KArt.Btns {
        guard let d else { return KArt.Btns() }
        let pk = current(d, now), rt = remindTarget(d, now)
        let rp = rt?["pk"] as? String ?? ""
        return KArt.Btns(prayed: pk.map(has) ?? false, muted: d.next(now).map { mute == $0.at } ?? false,
                         reminded: rt != nil && (rem[rp] ?? 0) > now, canPray: pk != nil, canRemind: rt != nil)
    }
    /// What «صلواتي اليوم» shows now.
    static func marks(_ d: KData?, _ now: Int) -> KArt.Marks {
        guard let pd = d?.prayerDay(now) else { return KArt.Marks() }
        return KArt.Marks(done: mask(pd.optString("d")), streak: streak(pd.optLong("fajr")))
    }
}

/// A button on a widget: "pray", "mute", "remind", or "p0"…"p4" for a circle of «صلواتي اليوم». A Live Activity intent, so the
/// iPhone runs it in the app, where the notifications are (it marks the day itself in either place).
struct WidgetPressIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Widget button"
    static var isDiscoverable: Bool = false
    @Parameter(title: "which") var which: String
    init() { which = "" }
    init(_ which: String) { self.which = which }
    func perform() async throws -> some IntentResult {
        await WidgetPress.run(which)
        return .result()
    }
}

enum WidgetPress {
    /// Set by the app when it starts: what only the app can do after a press ("prayed|<pk>", "remind|<json>", "unremind|<pk>",
    /// "rebuild"). Without it (run in the extension) the step waits in the group for the app to open.
    static var handler: ((String) async -> Void)?
    static let queue = "presses.json"

    static func run(_ which: String) async {
        let now = Int(Date().timeIntervalSince1970 * 1000)
        guard let d = KData.load(SharedStore.read()) else { return }
        var fx: String? = nil
        switch which {
        case "pray":
            if let pk = PrayMarks.current(d, now) { PrayMarks.set(pk, true); fx = "prayed|" + pk }
        case "p0", "p1", "p2", "p3", "p4":
            // mark that prayer once its time has come, or take the mark off
            if let pd = d.prayerDay(now), let i = Int(which.dropFirst()) {
                let k = PrayMarks.PRAY[i], t = pd.optLong(k)
                if t >= 1 && t <= now {
                    let pk = PrayMarks.pk(k, t)
                    if PrayMarks.has(pk) { PrayMarks.set(pk, false) } else { PrayMarks.set(pk, true); fx = "prayed|" + pk }
                }
            }
        case "mute":
            if let nx = d.next(now) { PrayMarks.mute = PrayMarks.mute == nx.at ? 0 : nx.at; fx = "rebuild" }
        case "remind":
            if let rt = PrayMarks.remindTarget(d, now), let pk = rt["pk"] as? String {
                if (PrayMarks.rem[pk] ?? 0) > now { var r = PrayMarks.rem; r[pk] = nil; PrayMarks.rem = r; fx = "unremind|" + pk }
                else if let j = try? JSONSerialization.data(withJSONObject: rt), let s = String(data: j, encoding: .utf8) {
                    // shown as set at once; the app puts the reminder in
                    var r = PrayMarks.rem; r[pk] = now + 3_600_000; PrayMarks.rem = r; fx = "remind|" + s
                }
            }
        default: break
        }
        if let fx {
            if let h = handler { await h(fx) } else { wait(fx) }
        }
        reload()
    }

    static func reload() {
        WidgetCenter.shared.reloadTimelines(ofKind: "art.actions")
        WidgetCenter.shared.reloadTimelines(ofKind: "art.prayed")
    }

    private static func wait(_ fx: String) {
        var l = (SharedStore.read(file: queue).flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String] }) ?? []
        l.append(fx)
        if let d = try? JSONSerialization.data(withJSONObject: Array(l.suffix(20))), let s = String(data: d, encoding: .utf8) { SharedStore.write(s, file: queue) }
    }
    /// The steps that waited (the app calls this when it opens).
    static func waiting() -> [String] {
        guard let l = SharedStore.read(file: queue).flatMap({ (try? JSONSerialization.jsonObject(with: $0)) as? [String] }), !l.isEmpty else { return [] }
        SharedStore.write("[]", file: queue)
        return l
    }
}
