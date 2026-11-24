import Foundation
import SwiftUI
import UIKit
import UserNotifications
import AppIntents
import ActivityKit
import AlarmKit

/// Real alarms on iOS 26+ (AlarmKit, 0.26): the prayer alarms the page keeps (the same list as Android's PrayerAlarms),
/// and the adhan of the prayers set to «ينبهك حتى لو كان الجوال صامتاً». They sound through silent mode and Focus and show
/// on the lock screen. iOS has no alarm that follows the prayer times, so each ring is an alarm of its own on a fixed date:
/// the nearest first, for up to 30 days or as many as the phone accepts, renewed when the app opens, when the page sends
/// new times and in the background refresh, with a quiet note two days before the last one to open the app.
/// iOS lets an app use only the phone's default alarm sound reliably; an adhan voice is asked for by name and the phone
/// may play the default instead.
enum Alarms {
    static var ud: UserDefaults { .standard }
    static let day: Double = 86_400_000
    static var supported: Bool { if #available(iOS 26.0, *) { return true }; return false }

    // ---------- the list the page sends ----------
    static func save(_ json: String) {
        ud.set(json, forKey: "alarms")
        let ids = Set(list().map { id($0) })
        for k in ud.dictionaryRepresentation().keys where k.hasPrefix("alSkip_") { if let n = Int(k.dropFirst(7)), !ids.contains(n) { ud.removeObject(forKey: k) } }
        sync()
    }
    static func list() -> [[String: Any]] {
        guard let d = (ud.string(forKey: "alarms") ?? "[]").data(using: .utf8), let a = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return [] }
        return a
    }
    static func id(_ a: [String: Any]) -> Int { (a["id"] as? NSNumber)?.intValue ?? 0 }
    static func num(_ v: Any?) -> Double { (v as? NSNumber)?.doubleValue ?? 0 }

    /// The days the page sends for the widgets (a month of every time and its ends); the alarms never compute times.
    static func widget() -> [String: Any] {
        guard let d = (ud.string(forKey: "widgetData") ?? "").data(using: .utf8), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return [:] }
        return o
    }
    static func days() -> [[String: Any]] { widget()["days"] as? [[String: Any]] ?? [] }
    static func fmt() -> [String: Any] { let w = widget(); return ["lang": w["lang"] ?? "ar", "dg": w["dg"] ?? 0] }

    /// 0 = Sunday … 6 = Saturday, from "YYYY-MM-DD".
    static func weekday(_ iso: String) -> Int {
        let p = iso.split(separator: "-").compactMap { Int($0) }; guard p.count == 3 else { return -1 }
        var c = DateComponents(); c.year = p[0]; c.month = p[1]; c.day = p[2]
        let cal = Calendar(identifier: .gregorian)
        guard let d = cal.date(from: c) else { return -1 }
        return cal.component(.weekday, from: d) - 1
    }
    /// The time an alarm hangs on, on day [i]: a prayer, the first end the app shows for it, or the last third (that evening's maghrib to the next fajr).
    static func anchor(_ days: [[String: Any]], _ i: Int, _ k: String, _ end: Bool) -> Double {
        guard i < days.count else { return 0 }
        let d = days[i]
        if end { return ((d["ex"] as? [String: Any])?[k] as? [[String: Any]])?.first.map { num($0["at"]) } ?? 0 }
        if k == "third" {
            guard i + 1 < days.count else { return 0 }
            let m = num(d["maghrib"]), f = num(days[i + 1]["fajr"])
            return m > 0 && f > m ? m + (f - m) * 2 / 3 : 0
        }
        return num(d[k])
    }
    /// Every time an alarm goes off between [after] and [until] (ms), on its days.
    static func rings(_ a: [String: Any], after: Double, until: Double) -> [Double] {
        let ds = days(), on = (a["days"] as? [NSNumber])?.map { $0.intValue } ?? []
        let set = Set(on.isEmpty ? Array(0...6) : on)
        let off = num(a["off"]) * 60_000, k = a["k"] as? String ?? "fajr", end = (a["end"] as? NSNumber)?.boolValue ?? false
        var out: [Double] = []
        for i in 0..<ds.count where set.contains(weekday(ds[i]["d"] as? String ?? "")) {
            let t0 = anchor(ds, i, k, end); if t0 <= 0 { continue }
            let t = t0 + off; if t > after && t <= until { out.append(t) }
        }
        return out
    }

    /// The alarm's name, as the page writes it.
    static func title(_ a: [String: Any]) -> String {
        if let l = (a["label"] as? String)?.trimmingCharacters(in: .whitespaces), !l.isEmpty { return l }
        let e = fmt(), ar = Fmt.ar(e), k = a["k"] as? String ?? "fajr"
        let names = widget()["names"] as? [String: String] ?? [:]
        let nm = k == "third" ? (ar ? "الثلث الأخير" : "Last third") : (names[k] ?? k)
        let off = Int(num(a["off"])), m = abs(off)
        if (a["end"] as? NSNumber)?.boolValue == true { return ar ? "قبل خروج وقت \(nm) بـ\(Fmt.mins(max(1, m), e))" : "\(max(1, m)) min before \(nm) time ends" }
        if off == 0 { return k == "third" ? (ar ? "بداية الثلث الأخير" : "Start of the last third") : (ar ? "عند أذان \(nm)" : "At the \(nm) adhan") }
        let side = off < 0 ? (ar ? "قبل" : "before") : (ar ? "بعد" : "after")
        return ar ? "\(side) \(k == "third" ? nm : "أذان " + nm) بـ\(Fmt.mins(m, e))" : "\(m) min \(side) \(nm)"
    }

    // ---------- what the page shows: the next time, and a skipped one ----------
    static func state() -> [String: Any] {
        let now = Scheduler.nowMs; var o: [String: Any] = [:]
        for a in list() {
            let r = rings(a, after: now + 1_000, until: now + 40 * day); guard let first = r.first else { continue }
            let skip = ud.double(forKey: "alSkip_\(id(a))") == first
            var j: [String: Any] = ["at": first, "skip": skip]
            if skip, r.count > 1 { j["then"] = r[1] }
            o["\(id(a))"] = j
        }
        return o
    }
    /// "Skip the next one only" (from the page, or from the notice half an hour before), and "undo".
    static func skip(_ alarm: Int, _ on: Bool) {
        let now = Scheduler.nowMs
        guard let a = list().first(where: { id($0) == alarm }), let t = rings(a, after: now + 1_000, until: now + 40 * day).first else { return }
        if on { ud.set(t, forKey: "alSkip_\(alarm)") } else { ud.removeObject(forKey: "alSkip_\(alarm)") }
        sync()
    }
    static func skip(_ alarm: Int, at: Double) { ud.set(at, forKey: "alSkip_\(alarm)"); sync() }

    // ---------- the alarms to set ----------
    struct Want { let at: Double; let kind: String; let key: String; let title: String; let snooze: Int; let sound: String
        var sig: String { "\(Int(at))|\(kind)|\(key)|\(title)|\(snooze)|\(sound)" } }

    static func alarmSound(_ a: [String: Any]) -> String {
        guard let s = a["snd"] as? String, s != "tone", !s.isEmpty else { return "" }
        if a["k"] as? String == "fajr", Bundle.main.url(forResource: "n_\(s)_fajr", withExtension: "caf") != nil { return "n_\(s)_fajr.caf" }
        return Bundle.main.url(forResource: "n_\(s)", withExtension: "caf") != nil ? "n_\(s).caf" : ""
    }
    static func adhanSound(_ s: String) -> String { Bundle.main.url(forResource: "n_\(s)", withExtension: "caf") != nil ? "n_\(s).caf" : "" }

    static func wanted() -> [Want] {
        let now = Scheduler.nowMs, until = now + 30 * day
        var w: [Want] = []
        for a in list() where (a["on"] as? NSNumber)?.boolValue ?? true {
            let skip = ud.double(forKey: "alSkip_\(id(a))")
            let snz = (a["snz"] as? NSNumber)?.intValue ?? 10
            for t in rings(a, after: now + 5_000, until: until) where t != skip {
                w.append(Want(at: t, kind: "alarm", key: "\(id(a))", title: title(a), snooze: snz, sound: alarmSound(a)))
            }
        }
        for e in Scheduler.events() where e["kind"] as? String == "adhan" && (e["ring"] as? NSNumber)?.intValue == 1 {
            let t = Scheduler.at(e), s = e["sound"] as? String ?? ""
            guard t > now + 5_000, t <= until, s != "none" else { continue }
            w.append(Want(at: t, kind: "adhan", key: e["id"] as? String ?? "", title: e["text"] as? String ?? (e["title"] as? String ?? ""), snooze: 0, sound: adhanSound(s)))
        }
        return w.sorted { $0.at < $1.at }
    }

    // ---------- setting them ----------
    private static var running = false, again = false
    static func sync() {
        guard #available(iOS 26.0, *) else { return }
        DispatchQueue.main.async {
            if running { again = true; return }
            running = true
            Task {
                await AK.sync()
                DispatchQueue.main.async { running = false; if again { again = false; sync() } }
            }
        }
    }

    /// "Try the alarm now": the same alarm in five seconds.
    static func tryNow(_ json: String) {
        guard #available(iOS 26.0, *), let d = json.data(using: .utf8), let a = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return }
        let w = Want(at: Scheduler.nowMs + 5_000, kind: "alarm", key: "try", title: title(a), snooze: (a["snz"] as? NSNumber)?.intValue ?? 10, sound: alarmSound(a))
        Task { await AK.once(w) }
    }

    static func auth() -> String { ud.string(forKey: "alarmAuth") ?? "ask" }

    /// The quiet notes: half an hour before each alarm's next time, «منبّه ٤:٥٥ ص» with "skip this time"; and two days before
    /// the last alarm set, a note to open the app so they go on.
    static func notes() {
        let c = Scheduler.center
        c.getPendingNotificationRequests { reqs in
            c.removePendingNotificationRequests(withIdentifiers: reqs.map(\.identifier).filter { $0.hasPrefix("al:") })
            guard supported, auth() == "on" else { return }
            let now = Scheduler.nowMs, e = fmt(), ar = Fmt.ar(e)
            for a in list() where (a["on"] as? NSNumber)?.boolValue ?? true {
                let skip = ud.double(forKey: "alSkip_\(id(a))")
                guard let t = rings(a, after: now + 31 * 60_000, until: now + 40 * day).first(where: { $0 != skip }) else { continue }
                let n = UNMutableNotificationContent()
                n.title = (ar ? "منبّه " : "Alarm at ") + clock(t, e)
                n.body = title(a)
                n.categoryIdentifier = "ALSOON"
                n.userInfo = ["al": id(a), "at": t]
                n.interruptionLevel = .passive
                Scheduler.add("al:soon:\(id(a))", n, t - 30 * 60_000)
            }
            let last = ud.double(forKey: "akLast")
            if last > now + 3 * day {
                let n = UNMutableNotificationContent()
                n.title = ar ? "افتح «الركن الثاني»" : "Open On Time"
                n.body = ar ? "لتجديد الأذان والمنبّهات للأيام القادمة." : "To renew the adhan and the alarms for the days ahead."
                n.sound = .default
                Scheduler.add("al:refresh", n, last - 2 * day)
            }
        }
    }

    /// A clock time in the user's format and digits.
    static func clock(_ ms: Double, _ e: [String: Any]) -> String {
        let w = widget(), f12 = (w["f12"] as? NSNumber)?.intValue != 0, ar = Fmt.ar(e)
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Asia/Riyadh") ?? .current
        let c = cal.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: ms / 1000))
        var h = c.hour ?? 0; let m = c.minute ?? 0; var mer = ""
        if f12 { mer = h < 12 ? (ar ? " ص" : " AM") : (ar ? " م" : " PM"); h = h % 12 == 0 ? 12 : h % 12 }
        return Fmt.digits("\(h):" + String(format: "%02d", m), e) + mer
    }

    /// "The full adhan" on an adhan alarm opens the app on the adhan screen.
    static func openPending() {
        guard let key = ud.string(forKey: "openAdhan"), !key.isEmpty else { return }
        let at = ud.double(forKey: "openAdhanAt")
        ud.removeObject(forKey: "openAdhan")
        guard Date().timeIntervalSince1970 - at < 15 * 60, let ev = Scheduler.events().first(where: { $0["id"] as? String == key }) else { return }
        AppState.shared.show(ev, fromStart: true)
    }
}

@available(iOS 26.0, *)
enum AK {
    static var m: AlarmManager { AlarmManager.shared }

    static func authText() -> String {
        switch m.authorizationState {
        case .authorized: return "on"
        case .denied: return "off"
        default: return "ask"
        }
    }

    static func L(_ s: String) -> LocalizedStringResource { LocalizedStringResource(stringLiteral: s) }

    static func config(_ w: Alarms.Want, _ id: UUID) -> AlarmManager.AlarmConfiguration<OnTimeAlarm> {
        let ar = Fmt.ar(Alarms.fmt())
        let snooze = w.kind != "adhan" && w.snooze > 0
        let adhan = w.kind == "adhan"
        var second: AlarmButton? = nil
        var behavior: AlarmPresentation.Alert.SecondaryButtonBehavior? = nil
        if snooze { second = AlarmButton(text: L(ar ? "غفوة" : "Snooze"), textColor: .white, systemImageName: "zzz"); behavior = .countdown }
        else if adhan { second = AlarmButton(text: L(ar ? "الأذان كاملاً" : "Full adhan"), textColor: .white, systemImageName: "speaker.wave.2.fill"); behavior = .custom }
        let title = L(w.title)
        let alert: AlarmPresentation.Alert
        if #available(iOS 26.1, *) {
            alert = AlarmPresentation.Alert(title: title, secondaryButton: second, secondaryButtonBehavior: behavior)
        } else {
            alert = AlarmPresentation.Alert(title: title, stopButton: AlarmButton(text: L(ar ? "إيقاف" : "Stop"), textColor: .white, systemImageName: "stop.circle"),
                                            secondaryButton: second, secondaryButtonBehavior: behavior)
        }
        let presentation: AlarmPresentation = snooze
            ? AlarmPresentation(alert: alert, countdown: AlarmPresentation.Countdown(title: title),
                                paused: AlarmPresentation.Paused(title: title, resumeButton: AlarmButton(text: L(ar ? "متابعة" : "Resume"), textColor: .white, systemImageName: "play.fill")))
            : AlarmPresentation(alert: alert)
        let attributes = AlarmAttributes<OnTimeAlarm>(presentation: presentation, metadata: OnTimeAlarm(kind: w.kind, key: w.key, title: w.title),
                                                      tintColor: Color(red: 0.91, green: 0.80, blue: 0.54))
        let sound: AlertConfiguration.AlertSound = w.sound.isEmpty ? .default : .named(w.sound)
        let intent: (any LiveActivityIntent)? = adhan ? OpenAdhanIntent(alarmID: id.uuidString, key: w.key) : nil
        return AlarmManager.AlarmConfiguration<OnTimeAlarm>(
            countdownDuration: snooze ? Alarm.CountdownDuration(preAlert: nil, postAlert: TimeInterval(w.snooze * 60)) : nil,
            schedule: .fixed(Date(timeIntervalSince1970: w.at / 1000)),
            attributes: attributes, stopIntent: nil, secondaryIntent: intent, sound: sound)
    }

    /// Brings the phone's alarms in line with the list: removes those that went or changed, adds the missing ones nearest
    /// first, and stops where the phone accepts no more. An alarm going off or snoozed now is left alone.
    static func sync() async {
        let ud = UserDefaults.standard
        let want = Alarms.wanted()
        if !want.isEmpty && m.authorizationState == .notDetermined { _ = try? await m.requestAuthorization() }
        ud.set(authText(), forKey: "alarmAuth")
        let have = (try? m.alarms) ?? []
        let tries = Set(ud.stringArray(forKey: "akTry") ?? [])
        var map = (ud.dictionary(forKey: "akMap") as? [String: String] ?? [:]).filter { u in have.contains { $0.id.uuidString == u.key } }
        guard m.authorizationState == .authorized else {
            for a in have where a.state == .scheduled && !tries.contains(a.id.uuidString) { try? m.cancel(id: a.id) }
            ud.set([String: String](), forKey: "akMap"); finish([]); return
        }
        let wanted = Set(want.map(\.sig))
        for (u, s) in map where !wanted.contains(s) {
            if let a = have.first(where: { $0.id.uuidString == u }), a.state == .scheduled { try? m.cancel(id: a.id) }
            map[u] = nil
        }
        // ours from before that the list no longer knows
        for a in have where map[a.id.uuidString] == nil && a.state == .scheduled && !tries.contains(a.id.uuidString) { try? m.cancel(id: a.id) }
        let set = Set(map.values)
        var fails = 0
        for w in want where !set.contains(w.sig) {
            let id = UUID()
            do { _ = try await m.schedule(id: id, configuration: config(w, id)); map[id.uuidString] = w.sig; fails = 0 }
            catch { fails += 1; if fails >= 2 { break } }   // the phone takes no more
        }
        ud.set(map, forKey: "akMap")
        let done = Set(map.values)
        finish(want.filter { done.contains($0.sig) })
    }

    static func once(_ w: Alarms.Want) async {
        if m.authorizationState == .notDetermined { _ = try? await m.requestAuthorization() }
        UserDefaults.standard.set(authText(), forKey: "alarmAuth")
        guard m.authorizationState == .authorized else { DispatchQueue.main.async { WebViewController.shared.pushState() }; return }
        let id = UUID()
        var t = UserDefaults.standard.stringArray(forKey: "akTry") ?? []; t = Array((t + [id.uuidString]).suffix(5))
        UserDefaults.standard.set(t, forKey: "akTry")
        _ = try? await m.schedule(id: id, configuration: config(w, id))
    }

    /// Which adhans are now alarms (their notifications then come without a sound), and the last day set.
    static func finish(_ done: [Alarms.Want]) {
        let ud = UserDefaults.standard
        let adhan = done.filter { $0.kind == "adhan" }.map(\.key)
        let old = ud.stringArray(forKey: "akAdhan") ?? []
        ud.set(adhan, forKey: "akAdhan")
        ud.set(done.last?.at ?? 0, forKey: "akLast")
        DispatchQueue.main.async {
            if Set(old) != Set(adhan) { Scheduler.rebuild() } else { Alarms.notes() }
            WebViewController.shared.pushState()
        }
    }
}

/// «الأذان كاملاً» on an adhan alarm: stops it and opens the app on the adhan screen, with the full recording.
@available(iOS 26.0, *)
struct OpenAdhanIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Full adhan"
    static var openAppWhenRun: Bool = true
    static var isDiscoverable: Bool = false
    @Parameter(title: "alarmID") var alarmID: String
    @Parameter(title: "key") var key: String
    init() { alarmID = ""; key = "" }
    init(alarmID: String, key: String) { self.alarmID = alarmID; self.key = key }
    func perform() async throws -> some IntentResult {
        if let u = UUID(uuidString: alarmID) { try? AlarmManager.shared.stop(id: u) }
        UserDefaults.standard.set(key, forKey: "openAdhan")
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "openAdhanAt")
        await MainActor.run { Alarms.openPending() }
        return .result()
    }
}
