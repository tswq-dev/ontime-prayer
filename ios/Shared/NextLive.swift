import Foundation
import ActivityKit

/// The next prayer counting down on the Lock Screen and in the Dynamic Island (0.29), in the app's colours: a Live Activity,
/// started by the app when it opens (iOS starts none from the background without a server) and lasting up to 8 hours.
/// Its choices (0.34) travel in the state, so a change shows without starting it again.
struct NextPrayerAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var name: String
        var at: Date
        var clock: String
        var font: String = "kufi"
        var theme: String = "navy"
        var showTime: Bool = true
        var showCount: Bool = true
        var showCity: Bool = true
    }
    var ar: Bool
    var city: String
}

/// «تخصيص الصلاة القادمة على شاشة القفل والجزيرة» (0.34): the page's S.lk, sent in the widgets' data.
struct LockStyle {
    var font = "kufi", kl = 0, theme = "navy", show: Set<String> = ["time", "count", "iqama", "hijri", "city"]
    init(_ o: [String: Any]?) {
        guard let o else { return }
        font = o["font"] as? String ?? font; kl = (o["kl"] as? NSNumber)?.intValue ?? 0; theme = o["theme"] as? String ?? theme
        if let s = o["show"] as? [String] { show = Set(s) }
    }
    /// The widgets' data as the widget extension reads it.
    static func current() -> LockStyle {
        let o = SharedStore.read().flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] }
        return LockStyle(o?["lk"] as? [String: Any])
    }
    /// The name stretched with kashida at its last joining letter, when chosen.
    func name(_ n: String) -> String {
        guard kl > 0 else { return n }
        let c = Array(n), noJoin: Set<Character> = ["ا", "أ", "إ", "آ", "د", "ذ", "ر", "ز", "و", "ؤ", "ة", "ى", "ء", " "]
        let arabic: (Character) -> Bool = { ($0.unicodeScalars.first?.value ?? 0) >= 0x0621 && ($0.unicodeScalars.first?.value ?? 0) <= 0x064A }
        guard c.count > 2 else { return n }
        for i in stride(from: c.count - 2, through: 1, by: -1) where arabic(c[i]) && arabic(c[i + 1]) && !noJoin.contains(c[i]) {
            return String(c[...i]) + "ـــ" + String(c[(i + 1)...])
        }
        return n
    }
}
