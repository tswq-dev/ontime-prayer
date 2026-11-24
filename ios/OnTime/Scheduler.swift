import Foundation
import UserNotifications

/// The page sends a month of alerts (each with its time, kind, text and sound); iOS keeps 64 at most, so the next
/// two days go in whole and after them the adhan alone, with a last note to open the app. Rebuilt whenever the app
/// opens, when the page sends a new list, and on background refresh.
enum Scheduler {
    static let center = UNUserNotificationCenter.current()
    static var defaults: UserDefaults { .standard }

    static func save(_ json: String) {
        defaults.set(json, forKey: "events")
        rebuild()
    }

    static func parse(_ s: String) -> [String: Any]? {
        guard let d = s.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: d)) as? [String: Any]
    }
    static func events() -> [[String: Any]] {
        guard let d = (defaults.string(forKey: "events") ?? "[]").data(using: .utf8),
              let a = (try? JSONSerialization.jsonObject(with: d)) as? [[String: Any]] else { return [] }
        return a
    }
    static func event(of info: [AnyHashable: Any]) -> [String: Any]? { (info["ev"] as? String).flatMap(parse) }
    static func at(_ e: [String: Any]) -> Double { (e["at"] as? NSNumber)?.doubleValue ?? 0 }
    static var nowMs: Double { Date().timeIntervalSince1970 * 1000 }

    static func rebuild() {
        let now = nowMs, near = now + 48 * 3600 * 1000
        let evs = events().filter { at($0) > now + 1000 }.sorted { at($0) < at($1) }
        setCategories(evs.first { $0["kind"] as? String == "adhan" })
        center.getPendingNotificationRequests { reqs in
            // the reminders the user set stay, and the alarms' notes (Alarms.notes) keep their room; everything else is put in again
            let keep = reqs.filter { $0.identifier.hasPrefix("rem:") }
            center.removePendingNotificationRequests(withIdentifiers: reqs.map(\.identifier).filter { !$0.hasPrefix("rem:") && !$0.hasPrefix("al:") })
            let room = max(0, 64 - keep.count - 1 - (Alarms.supported ? Alarms.list().count + 1 : 0))
            var chosen: [[String: Any]] = []
            for e in evs where chosen.count < room {
                let kind = e["kind"] as? String ?? ""
                if kind == "silence" { continue }               // iOS lets no app switch the ringer
                if at(e) <= near || kind == "adhan" { chosen.append(e) }
            }
            for e in chosen { if let c = content(e) { add(e["id"] as? String ?? UUID().uuidString, c, at(e)) } }
            if let last = chosen.last {
                let ar = (last["lang"] as? String ?? "ar") != "en"
                let c = UNMutableNotificationContent()
                c.title = ar ? "افتح «الركن الثاني»" : "Open On Time"
                c.body = ar ? "لتستمر التنبيهات في وقتها." : "So the alerts keep coming on time."
                c.sound = .default
                add("refresh", c, at(last) + 60_000)
            }
            Alarms.notes()
        }
    }

    static func add(_ id: String, _ c: UNNotificationContent, _ atMs: Double) {
        let date = Date(timeIntervalSince1970: atMs / 1000)
        var cal = Calendar(identifier: .gregorian); cal.timeZone = .current
        let comps = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        center.add(UNNotificationRequest(identifier: id, content: c, trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)))
    }

    static func hasEx(_ e: [String: Any]) -> Bool { !((e["ex"] as? [[String: Any]]) ?? []).isEmpty && !(e["pk"] as? String ?? "").isEmpty }

    /// What one alert shows and sounds like.
    static func content(_ e: [String: Any]) -> UNMutableNotificationContent? {
        let c = UNMutableNotificationContent()
        let kind = e["kind"] as? String ?? "", rs = e["rs"] as? String ?? "ding"
        c.title = e["title"] as? String ?? ""
        c.body = e["text"] as? String ?? ""
        c.threadIdentifier = e["key"] as? String ?? ""
        c.userInfo = ["ev": WebViewController.json(e)]
        switch kind {
        case "adhan":
            let s = e["sound"] as? String ?? "notify"
            if s == "none" { return nil }
            // an adhan set as an alarm (iOS 26) sounds there; its notification stays, quiet, for its buttons
            c.sound = (UserDefaults.standard.stringArray(forKey: "akAdhan") ?? []).contains(e["id"] as? String ?? "") ? nil : Sounds.notificationSound(s)
            c.interruptionLevel = .timeSensitive
            if hasEx(e), let x = Reminders.nextExit(e) { c.body += " · " + Fmt.ends(e, x); c.categoryIdentifier = "ADHAN" }
        case "pre", "iqama", "note":
            c.sound = Sounds.reminderSound(rs, spk: e["spk"] as? String)
        case "exit":
            guard let x = Reminders.nextExit(e) else { return nil }
            let left = max(1, Int(((x.at - at(e)) / 60_000).rounded()))
            c.title = Fmt.leftTitle(e, mins: left, name: x.name)
            c.body = Fmt.endsAt(e, x)
            c.sound = Sounds.reminderSound(rs, spk: e["xspk"] as? String)
            c.categoryIdentifier = "REM"
        default:
            return nil
        }
        return c
    }

    /// The buttons (on a long press): «ذكّرني قبل خروج الوقت» and «ذكّرني بعد» with the user's durations, and «صلّيت».
    static func setCategories(_ e: [String: Any]?) {
        let ar = (e?["lang"] as? String ?? "ar") != "en"
        let btns = (e?["btns"] as? String ?? "exit,later").split(separator: ",").map(String.init)
        let before = (e?["before"] as? [Int]) ?? [10, 20, 30], later = (e?["later"] as? [Int]) ?? [15, 25, 35]
        var acts: [UNNotificationAction] = []
        if btns.contains("exit") { for m in before { acts.append(UNNotificationAction(identifier: "B:\(m)", title: ar ? "ذكّرني قبل خروج الوقت بـ\(Fmt.mins(m, e ?? [:]))" : "Remind me \(m) min before it ends", options: [])) } }
        if btns.contains("later") { for m in later { acts.append(UNNotificationAction(identifier: "L:\(m)", title: ar ? "ذكّرني بعد \(Fmt.mins(m, e ?? [:]))" : "Remind me in \(m) min", options: [])) } }
        let pray = UNNotificationAction(identifier: "P", title: ar ? "صلّيت" : "I prayed", options: [])
        acts.append(pray)
        let snooze = UNNotificationAction(identifier: "L:10", title: ar ? "ذكّرني بعد \(Fmt.mins(10, e ?? [:]))" : "Remind me in 10 min", options: [])
        let skip = UNNotificationAction(identifier: "S", title: ar ? "تخطَّ هذه المرة" : "Skip this time", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "ADHAN", actions: acts, intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: "REM", actions: [pray, snooze], intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: "ALSOON", actions: [skip], intentIdentifiers: [], options: [])])
    }
}

/// One reminder per prayer (the last one picked replaces the other), and «صلّيت».
enum Reminders {
    struct Exit { let at: Double; let name: String; let clock: String }

    static func nextExit(_ e: [String: Any], after: Double = Scheduler.nowMs) -> Exit? {
        for x in (e["ex"] as? [[String: Any]]) ?? [] {
            let at = (x["at"] as? NSNumber)?.doubleValue ?? 0
            if at > after { return Exit(at: at, name: x["name"] as? String ?? "", clock: x["clock"] as? String ?? "") }
        }
        return nil
    }

    static func all() -> [String: Double] { (UserDefaults.standard.dictionary(forKey: "reminders") as? [String: Double] ?? [:]).filter { $0.value > Scheduler.nowMs } }
    private static func store(_ pk: String, _ at: Double?) {
        var r = all(); r[pk] = at; UserDefaults.standard.set(r, forKey: "reminders")
    }

    /// «ذكّرني قبل خروج الوقت»: [mins] before the time ends.
    static func before(_ e: [String: Any], mins: Int, done: (() -> Void)? = nil) {
        guard let x = nextExit(e), let pk = e["pk"] as? String else { done?(); return }
        let at = max(Scheduler.nowMs + 3_000, x.at - Double(mins) * 60_000)
        let c = UNMutableNotificationContent()
        let left = max(1, Int(((x.at - at) / 60_000).rounded()))
        c.title = Fmt.leftTitle(e, mins: left, name: x.name)
        c.body = Fmt.endsAt(e, x)
        put(pk, e, c, at, spk: e["xspk"] as? String)
        done?()
    }

    /// «ذكّرني بعد»: in [mins], if that is before the time ends.
    static func later(_ e: [String: Any], mins: Int) {
        guard let pk = e["pk"] as? String else { return }
        let at = Scheduler.nowMs + Double(mins) * 60_000
        let x = nextExit(e)
        if let x, at > x.at { return }
        let ar = (e["lang"] as? String ?? "ar") != "en"
        let c = UNMutableNotificationContent()
        let t = (e["title"] as? String ?? "").replacingOccurrences(of: ar ? "تذكير: صلاة " : "Reminder: ", with: "")
        c.title = ar ? "تذكير: صلاة \(t)" : "Reminder: \(t)"
        c.body = x.map { Fmt.ends(e, $0) } ?? (e["text"] as? String ?? "")
        put(pk, e, c, at, spk: nil)
    }

    private static func put(_ pk: String, _ e: [String: Any], _ c: UNMutableNotificationContent, _ at: Double, spk: String?) {
        var ev = e; ev["title"] = e["title"]
        c.sound = Sounds.reminderSound(e["rs"] as? String ?? "ding", spk: spk)
        c.categoryIdentifier = "REM"
        c.threadIdentifier = e["key"] as? String ?? ""
        c.interruptionLevel = .timeSensitive
        c.userInfo = ["ev": WebViewController.json(ev)]
        Scheduler.center.removePendingNotificationRequests(withIdentifiers: ["rem:\(pk)"])
        Scheduler.add("rem:\(pk)", c, at)
        store(pk, at)
    }

    static func cancel(_ pk: String) {
        Scheduler.center.removePendingNotificationRequests(withIdentifiers: ["rem:\(pk)"])
        store(pk, nil)
    }

    /// «صلّيت»: no reminder for it any more, and its alerts leave the notification list.
    static func prayed(_ e: [String: Any]) {
        guard let pk = e["pk"] as? String else { return }
        cancel(pk)
        var p = UserDefaults.standard.dictionary(forKey: "prayed") as? [String: Double] ?? [:]
        p[pk] = Scheduler.nowMs
        p = p.filter { $0.value > Scheduler.nowMs - 400 * 86_400_000 }
        UserDefaults.standard.set(p, forKey: "prayed")
        Scheduler.center.getDeliveredNotifications { ns in
            let ids = ns.filter { Scheduler.event(of: $0.request.content.userInfo)?["pk"] as? String == pk }.map(\.request.identifier)
            Scheduler.center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }
}

/// Texts in the user's language and digits.
enum Fmt {
    static func ar(_ e: [String: Any]) -> Bool { (e["lang"] as? String ?? "ar") != "en" }
    static func digits(_ s: String, _ e: [String: Any]) -> String {
        guard (e["dg"] as? Int ?? 0) == 1 else { return s }
        let a = Array("٠١٢٣٤٥٦٧٨٩")
        return String(s.map { c in c.wholeNumberValue.map { a[$0] } ?? c })
    }
    static func mins(_ n: Int, _ e: [String: Any]) -> String {
        guard ar(e) else { return "\(n) min" }
        switch n { case 1: return "دقيقة"; case 2: return "دقيقتين"; case 3...10: return digits("\(n)", e) + " دقائق"; default: return digits("\(n)", e) + " دقيقة" }
    }
    static func leftTitle(_ e: [String: Any], mins n: Int, name: String) -> String { ar(e) ? "بقي \(mins(n, e)) على خروج \(name)" : "\(n) min until \(name) ends" }
    static func endsAt(_ e: [String: Any], _ x: Reminders.Exit) -> String { ar(e) ? "ينتهي \(x.clock)" : "Ends at \(x.clock)" }
    static func ends(_ e: [String: Any], _ x: Reminders.Exit) -> String { ar(e) ? "يخرج \(x.name) \(x.clock)" : "\(x.name) ends at \(x.clock)" }
}
