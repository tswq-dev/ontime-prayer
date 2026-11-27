import Foundation
import ActivityKit

/// «عدّ الصلاة القادمة على شاشة القفل» (0.29): starts or moves on the Live Activity when the app opens or sends new times
/// (an update also works from the background refresh); it ends when the option is off.
enum LiveNext {
    static func update() {
        let w = Alarms.widget()
        let on = (w["live"] as? NSNumber)?.intValue == 1
        let acts = Activity<NextPrayerAttributes>.activities
        guard on, ActivityAuthorizationInfo().areActivitiesEnabled, let n = next(w) else {
            for a in acts { Task { await a.end(nil, dismissalPolicy: .immediate) } }
            return
        }
        let ls = LockStyle(w["lk"] as? [String: Any])
        let content = ActivityContent(state: NextPrayerAttributes.ContentState(name: ls.name(n.name), at: n.at, clock: n.clock, font: ls.font, theme: ls.theme,
                                                                               showTime: ls.show.contains("time"), showCount: ls.show.contains("count"), showCity: ls.show.contains("city")),
                                      staleDate: n.at)
        if let a = acts.first {
            Task { await a.update(content) }
            for b in acts.dropFirst() { Task { await b.end(nil, dismissalPolicy: .immediate) } }
        } else {
            _ = try? Activity.request(attributes: NextPrayerAttributes(ar: Fmt.ar(Alarms.fmt()), city: w["city"] as? String ?? ""), content: content, pushType: nil)
        }
    }

    /// The next of the five prayers, from the days the page sends.
    static func next(_ w: [String: Any]) -> (name: String, at: Date, clock: String)? {
        let names = w["names"] as? [String: String] ?? [:], now = Scheduler.nowMs
        for d in (w["days"] as? [[String: Any]] ?? []) {
            for k in ["fajr", "dhuhr", "asr", "maghrib", "isha"] {
                let t = Alarms.num(d[k])
                if t > now { return (names[k] ?? k, Date(timeIntervalSince1970: t / 1000), Alarms.clock(t, Alarms.fmt())) }
            }
        }
        return nil
    }
}
