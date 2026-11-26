import WidgetKit
import SwiftUI

/// «الحلقة» on the lock screen (0.32): a ring that fills from the last prayer to the next (or to the iqama while it is
/// awaited), the next prayer's name and its count inside, as the ring design of the home screen; the iPhone tints it.
struct RingEntry: TimelineEntry { let date: Date }

struct RingProvider: TimelineProvider {
    func placeholder(in context: Context) -> RingEntry { RingEntry(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (RingEntry) -> Void) { completion(RingEntry(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<RingEntry>) -> Void) {
        let now = Date(), m0 = Calendar.current.dateInterval(of: .minute, for: now)?.start ?? now
        completion(Timeline(entries: [RingEntry(date: now)] + (1...60).map { RingEntry(date: m0.addingTimeInterval(Double($0) * 60)) }, policy: .atEnd))
    }
}

struct RingLockView: View {
    let e: RingEntry
    var body: some View {
        content.containerBackground(for: .widget) { AccessoryWidgetBackground() }
    }
    @ViewBuilder private var content: some View {
        let now = Int(e.date.timeIntervalSince1970 * 1000)
        if let d = ArtStore.month(), let nx = d.hero(now) {
            let pv = d.prev(now)
            let prog: Double = nx.iqAt > 0 ? frac(now - nx.at, nx.iqAt - nx.at) : pv.map { frac(now - $0.at, nx.at - $0.at) } ?? 0
            let _ = { d.cf = d.appCf; d.dhuhrDay = d.day(nx.at)?.optLong("dhuhr") ?? now }()
            Gauge(value: prog) {
                Text("")
            } currentValueLabel: {
                VStack(spacing: -1) {
                    Text(d.name(nx.k)).font(.system(size: 12, weight: .semibold)).minimumScaleFactor(0.6).lineLimit(1)
                    Text(d.count(nx, now)).font(.system(size: 9, weight: .regular)).minimumScaleFactor(0.6).lineLimit(1)
                }
            }
            .gaugeStyle(.accessoryCircularCapacity)
        } else {
            Image(systemName: "moon.stars")
        }
    }
    private func frac(_ a: Int, _ b: Int) -> Double { b <= 0 ? 0 : min(1, max(0, Double(a) / Double(b))) }
}

struct LockRingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "lockring", provider: RingProvider()) { e in RingLockView(e: e) }
            .configurationDisplayName("الحلقة")
            .description("حلقة تمتلئ حتى الصلاة القادمة، على شاشة القفل.")
            .supportedFamilies([.accessoryCircular])
    }
}
