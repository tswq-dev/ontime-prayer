import UIKit
import UserNotifications
import BackgroundTasks

/// Notifications (what happens when one comes or is tapped) and the background refresh that keeps them coming.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static let refreshId = "com.itqan.ontime.refresh"

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        Fonts.register()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshId, using: nil) { task in
            // iOS keeps 64 alerts at most: put the next ones in place, and ask for the next refresh
            Scheduler.rebuild()
            Alarms.sync()
            LiveNext.update()
            Self.planRefresh()
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { task.setTaskCompleted(success: true) }
        }
        return true
    }

    static func planRefresh() {
        let r = BGAppRefreshTaskRequest(identifier: refreshId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 4 * 3600)
        try? BGTaskScheduler.shared.submit(r)
    }

    // While the app is open: the adhan plays in full on its own screen; other alerts show as usual.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let ev = Scheduler.event(of: notification.request.content.userInfo)
        if let ev, ev["kind"] as? String == "adhan", Sounds.isRecording(ev["sound"] as? String ?? "") {
            AppState.shared.show(ev, fromStart: true)
            completionHandler([.banner, .list])
        } else {
            completionHandler([.banner, .list, .sound])
        }
    }

    // A tap on an alert or one of its buttons.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let ev = Scheduler.event(of: response.notification.request.content.userInfo) ?? [:]
        let a = response.actionIdentifier
        let info = response.notification.request.content.userInfo
        if a == "S", let al = (info["al"] as? NSNumber)?.intValue, let at = (info["at"] as? NSNumber)?.doubleValue { Alarms.skip(al, at: at) }   // "skip this time"
        else if a == "W", let al = (info["al"] as? NSNumber)?.intValue, let base = (info["base"] as? NSNumber)?.doubleValue { Alarms.awake(al, base: base) }   // «نعم، استيقظت»
        else if a == "U", let al = (info["al"] as? NSNumber)?.intValue { Alarms.unsnooze(info["u"] as? String ?? "", al: al) }   // «إيقاف المنبّه»
        else if a == "P" { Reminders.prayed(ev) }
        else if a.hasPrefix("B:"), let m = Int(a.dropFirst(2)) { Reminders.before(ev, mins: m); Reminders.confirm(ev) }
        else if a.hasPrefix("L:"), let m = Int(a.dropFirst(2)) { Reminders.later(ev, mins: m); Reminders.confirm(ev) }
        else if a == "X", let pk = ev["pk"] as? String { Reminders.cancel(pk) }   // «إلغاء التذكير»
        else if a == UNNotificationDefaultActionIdentifier, ev["kind"] as? String == "adhan", Sounds.isRecording(ev["sound"] as? String ?? "") {
            // tapped soon after the adhan: it goes on in full (after the 30 s the notification could play)
            let at = (ev["at"] as? Double ?? 0) / 1000, since = Date().timeIntervalSince1970 - at
            if since < 15 * 60 { AppState.shared.show(ev, fromStart: since > 60) }
        }
        WebViewController.shared.pushState()
        completionHandler()
    }
}
