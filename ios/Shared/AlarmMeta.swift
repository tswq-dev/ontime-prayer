import Foundation
#if canImport(AlarmKit)
import AlarmKit

/// What each alarm carries (0.26), shared by the app (which schedules it) and the widget extension (its snooze countdown
/// on the lock screen and in the Dynamic Island): a prayer alarm of the user's, or the adhan of a prayer set to sound on silent.
@available(iOS 26.0, *)
struct OnTimeAlarm: AlarmMetadata {
    var kind: String = "alarm"   // "alarm" or "adhan"
    var key: String = ""         // the alarm's id, or the adhan's event id
    var title: String = ""
}
#endif
