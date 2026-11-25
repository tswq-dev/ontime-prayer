import Foundation
import ActivityKit

/// The next prayer counting down on the Lock Screen and in the Dynamic Island (0.29), in the app's colours: a Live Activity,
/// started by the app when it opens (iOS starts none from the background without a server) and lasting up to 8 hours.
struct NextPrayerAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        var name: String
        var at: Date
        var clock: String
    }
    var ar: Bool
    var city: String
}
