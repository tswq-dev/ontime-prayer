import SwiftUI
import WidgetKit

/// «المنبّه القادم» on the lock screen. 0.33.1: built as «الحلقة» and «الخط» are (they work; this one showed grey): an entry each
/// minute, the alarm read as the gallery's alarm design reads it (alarms.json in the App Group, written by the app whenever it
/// sets the alarms), the times written by the month's own ways; and with nothing to read it says so rather than going grey.
struct AlarmNextView: View {
    let e: RingEntry
    @Environment(\.widgetFamily) private var family

    private func dayName(_ d: KData?, _ at: Int, _ now: Int) -> String {
        let ar = d?.ar ?? true
        let n = Int((Double(KData.midnight(at) - KData.midnight(now)) / Double(KData.DAY)).rounded())
        if n == 0 { return ar ? "اليوم" : "Today" }
        if n == 1 { return ar ? "غداً" : "Tomorrow" }
        let names = ar ? ["الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"] : ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
        return names[(KData.cal().component(.weekday, from: KData.date(at)) + 6) % 7]
    }
    private func clock(_ d: KData?, _ at: Int) -> String {
        guard let d else {
            let f = DateFormatter(); f.dateFormat = "h:mm"; f.timeZone = TimeZone(identifier: "Asia/Riyadh"); return f.string(from: KData.date(at))
        }
        let a = d.ampm(at); return d.clock(at) + (a.isEmpty ? "" : " " + a)
    }

    var body: some View {
        let now = Int(e.date.timeIntervalSince1970 * 1000), d = ArtStore.month(), a = ArtStore.alarm(now), ar = d?.ar ?? true
        let none = a.any ? (ar ? "لا منبّه مفعّل" : "No alarm on") : (ar ? "لا منبّهات بعد" : "No alarms yet")
        Group {
            if family == .accessoryInline {
                if a.at > now { Text("\(Image(systemName: "alarm")) \(clock(d, a.at))") } else { Text("\(Image(systemName: "alarm")) —") }
            } else {
                VStack(alignment: .leading, spacing: 1) {
                    if a.at > now {
                        HStack(spacing: 4) {
                            Image(systemName: "alarm.fill")
                            Text(clock(d, a.at)).font(.headline).widgetAccentable()
                        }
                        Text(a.title).font(.caption).lineLimit(1)
                        Text(dayName(d, a.at, now)).font(.caption2).foregroundStyle(.secondary)
                    } else {
                        Label(none, systemImage: "alarm")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .environment(\.layoutDirection, ar ? .rightToLeft : .leftToRight)
        .containerBackground(for: .widget) { Color.clear }
    }
}

struct AlarmNextWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "alarm", provider: RingProvider()) { e in AlarmNextView(e: e) }
            .configurationDisplayName("المنبّه القادم")
            .description("وقت منبّهك القادم واسمه.")
            // on the home screen it is a design of the gallery (art.alarm, 0.32); this one is for the lock screen
            .supportedFamilies([.accessoryInline, .accessoryRectangular])
    }
}
