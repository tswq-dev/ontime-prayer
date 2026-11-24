import SwiftUI
import WidgetKit
import ActivityKit
import AlarmKit

/// A snoozed alarm on the lock screen and in the Dynamic Island (0.26): its name and the time left until it goes off again.
/// AlarmKit asks for this whenever an alarm can count down (the snooze); the alarm's own alert is drawn by the system.
@available(iOS 26.0, *)
struct AlarmLive: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: AlarmAttributes<OnTimeAlarm>.self) { context in
            HStack(spacing: 12) {
                Image(systemName: "alarm.fill").font(.title2).foregroundStyle(Color(red: 0.91, green: 0.80, blue: 0.54))
                VStack(alignment: .leading, spacing: 2) {
                    Text(context.attributes.metadata?.title ?? "").font(.headline).lineLimit(1)
                    AlarmLeft(state: context.state).font(.subheadline).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .activityBackgroundTint(Color(red: 0.11, green: 0.17, blue: 0.32))
            .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Image(systemName: "alarm.fill").foregroundStyle(Color(red: 0.91, green: 0.80, blue: 0.54)) }
                DynamicIslandExpandedRegion(.center) { Text(context.attributes.metadata?.title ?? "").font(.headline).lineLimit(1) }
                DynamicIslandExpandedRegion(.trailing) { AlarmLeft(state: context.state).monospacedDigit() }
            } compactLeading: {
                Image(systemName: "alarm.fill").foregroundStyle(Color(red: 0.91, green: 0.80, blue: 0.54))
            } compactTrailing: {
                AlarmLeft(state: context.state).monospacedDigit().frame(maxWidth: 52)
            } minimal: {
                Image(systemName: "alarm.fill").foregroundStyle(Color(red: 0.91, green: 0.80, blue: 0.54))
            }
        }
    }
}

@available(iOS 26.0, *)
private struct AlarmLeft: View {
    let state: AlarmPresentationState
    var body: some View {
        if case let .countdown(c) = state.mode {
            Text(timerInterval: Date.now...max(Date.now, c.fireDate), countsDown: true)
        } else {
            Text(" ")
        }
    }
}
