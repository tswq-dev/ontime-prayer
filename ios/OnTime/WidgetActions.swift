import Foundation

/// The app's half of the widget buttons (0.33): what a press leaves to the app, where the notifications are.
enum WidgetActions {
    /// At launch: take the presses from now on, move the old «صلّيت» marks into the group, and do what waited.
    static func install() {
        WidgetPress.handler = { fx in await MainActor.run { WidgetActions.apply(fx) } }
        let ud = UserDefaults.standard
        if !ud.bool(forKey: "marksMoved") {
            let old = (ud.dictionary(forKey: "prayed") as? [String: Double] ?? [:]).keys
            if !old.isEmpty { PrayMarks.set(Array(old), true) }
            ud.set(true, forKey: "marksMoved")
        }
        mirror()
        catchUp()
    }

    /// When the app opens: the steps of presses that ran outside it.
    static func catchUp() { for fx in WidgetPress.waiting() { apply(fx) } }

    static func apply(_ fx: String) {
        let p = fx.split(separator: "|", maxSplits: 1).map(String.init), arg = p.count > 1 ? p[1] : ""
        switch p[0] {
        case "prayed": Reminders.prayed(["pk": arg])
        case "unremind": Reminders.cancel(arg)
        case "remind":
            if let e = Scheduler.parse(arg) { Reminders.before(e, mins: (e["mins"] as? NSNumber)?.intValue ?? 20) }
        case "rebuild": Scheduler.rebuild(); Alarms.sync()
        default: break
        }
        mirror()
        WidgetPress.reload()
    }

    /// The reminders set, copied to the group for «ذكّرني قبل خروج الوقت ✓» on the widget.
    static func mirror() {
        let r = Reminders.all().mapValues { Int($0) }
        if r != PrayMarks.rem { PrayMarks.rem = r }
    }
}
