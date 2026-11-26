import SwiftUI
import WidgetKit

/// «المنبّه القادم» (0.27): the next prayer alarm, in the alarm screen's night navy, its time large in Kufi and gold, what it is for,
/// and the day. The app writes the next few (alarms.json in the App Group) whenever it sets them; the timeline moves to the next
/// one as each time passes.
struct AlarmItem { let at: Double; let title: String }
struct AlarmEntry: TimelineEntry {
    let date: Date
    let item: AlarmItem?
    let count: Int
    let any: Bool
    let ar: Bool, dg: Bool, f12: Bool
}

struct AlarmProvider: TimelineProvider {
    private func load() -> (items: [AlarmItem], count: Int, any: Bool, ar: Bool, dg: Bool, f12: Bool) {
        guard let d = SharedStore.read(file: "alarms.json"), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return ([], 0, false, true, false, true) }
        let items = ((o["next"] as? [[String: Any]]) ?? []).map { AlarmItem(at: ($0["at"] as? NSNumber)?.doubleValue ?? 0, title: $0["title"] as? String ?? "") }
        return (items, (o["count"] as? NSNumber)?.intValue ?? 0, (o["any"] as? NSNumber)?.boolValue ?? false,
                (o["lang"] as? String ?? "ar") != "en", (o["dg"] as? NSNumber)?.intValue == 1, (o["f12"] as? NSNumber)?.intValue != 0)
    }
    private func entry(_ at: Date, _ l: (items: [AlarmItem], count: Int, any: Bool, ar: Bool, dg: Bool, f12: Bool)) -> AlarmEntry {
        let ms = at.timeIntervalSince1970 * 1000
        return AlarmEntry(date: at, item: l.items.first { $0.at > ms }, count: l.count, any: l.any, ar: l.ar, dg: l.dg, f12: l.f12)
    }
    func placeholder(in context: Context) -> AlarmEntry {
        AlarmEntry(date: Date(), item: AlarmItem(at: Date().addingTimeInterval(3600).timeIntervalSince1970 * 1000, title: "قبل أذان الفجر بـ٢٠ دقيقة"), count: 1, any: true, ar: true, dg: false, f12: true)
    }
    func getSnapshot(in context: Context, completion: @escaping (AlarmEntry) -> Void) {
        let l = load(); completion(l.items.isEmpty && context.isPreview ? placeholder(in: context) : entry(Date(), l))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<AlarmEntry>) -> Void) {
        let l = load(), now = Date()
        var es = [entry(now, l)]
        for i in l.items where i.at > now.timeIntervalSince1970 * 1000 { es.append(entry(Date(timeIntervalSince1970: i.at / 1000 + 1), l)) }
        completion(Timeline(entries: es, policy: .after(es.last.map { max($0.date, now.addingTimeInterval(3600)) } ?? now.addingTimeInterval(6 * 3600))))
    }
}

struct AlarmNextView: View {
    let e: AlarmEntry
    @Environment(\.widgetFamily) private var family
    private let gold = Color.hex(0xE9CD8A), cream = Color.hex(0xF3E6C4)

    private func digits(_ s: String) -> String {
        guard e.dg else { return s }
        let a = Array("٠١٢٣٤٥٦٧٨٩"); return String(s.map { c in c.wholeNumberValue.map { a[$0] } ?? c })
    }
    private func clock(_ ms: Double) -> (String, String) {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Asia/Riyadh") ?? .current
        let c = cal.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: ms / 1000))
        var h = c.hour ?? 0; var mer = ""
        if e.f12 { mer = h < 12 ? (e.ar ? "ص" : "AM") : (e.ar ? "م" : "PM"); h = h % 12 == 0 ? 12 : h % 12 }
        return (digits("\(h):" + String(format: "%02d", c.minute ?? 0)), mer)
    }
    private func day(_ ms: Double) -> String {
        var cal = Calendar(identifier: .gregorian); cal.timeZone = TimeZone(identifier: "Asia/Riyadh") ?? .current
        let a = Date(timeIntervalSince1970: ms / 1000)
        let d = cal.dateComponents([.day], from: cal.startOfDay(for: e.date), to: cal.startOfDay(for: a)).day ?? 0
        if d == 0 { return e.ar ? "اليوم" : "Today" }
        if d == 1 { return e.ar ? "غداً" : "Tomorrow" }
        let names = e.ar ? ["الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"] : ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
        return names[cal.component(.weekday, from: a) - 1]
    }

    private func countText() -> String {
        guard e.count > 1 else { return "" }
        if !e.ar { return " · \(e.count) on" }
        return " · " + (e.count == 2 ? "منبّهان" : digits("\(e.count)") + (e.count <= 10 ? " منبّهات" : " منبّهاً"))
    }

    var body: some View {
        Group {
            switch family {
            case .accessoryInline:
                if let i = e.item { let c = clock(i.at); Text("\(Image(systemName: "alarm")) \(c.0) \(c.1)") } else { Text("\(Image(systemName: "alarm")) —") }
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 1) {
                    if let i = e.item { let c = clock(i.at)
                        HStack(spacing: 4) { Image(systemName: "alarm.fill"); Text("\(c.0) \(c.1)").font(.headline).widgetAccentable() }
                        Text(i.title).font(.caption).lineLimit(1)
                        Text(day(i.at)).font(.caption2).foregroundStyle(.secondary)
                    } else { Label(e.ar ? "لا منبّه مفعّل" : "No alarm on", systemImage: "alarm") }
                }
            default:
                VStack(alignment: .leading, spacing: 3) {
                    Image(systemName: "alarm").font(.system(size: 18, weight: .semibold)).foregroundStyle(gold)
                    Spacer(minLength: 0)
                    if let i = e.item {
                        let c = clock(i.at)
                        HStack(alignment: .lastTextBaseline, spacing: 4) {
                            Text(c.0).font(.custom(WFont.kufi.ps, size: 40)).foregroundStyle(gold).minimumScaleFactor(0.5).lineLimit(1)
                            if !c.1.isEmpty { Text(c.1).font(.custom(WFonts.plexBold, size: 12)).foregroundStyle(gold) }
                        }
                        Text(i.title).font(.custom(WFonts.plexBold, size: 12.5)).foregroundStyle(cream).lineLimit(2).minimumScaleFactor(0.8)
                        Text(day(i.at) + countText())
                            .font(.custom(WFonts.plex, size: 11)).foregroundStyle(cream.opacity(0.7)).lineLimit(1)
                    } else {
                        Text(e.any ? (e.ar ? "لا منبّه مفعّل" : "No alarm on") : (e.ar ? "لا منبّهات بعد" : "No alarms yet"))
                            .font(.custom(WFonts.plexBold, size: 14)).foregroundStyle(cream)
                        Text(e.ar ? "أضفها من التطبيق" : "Add them in the app").font(.custom(WFonts.plex, size: 11)).foregroundStyle(cream.opacity(0.7))
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
        }
        .environment(\.layoutDirection, e.ar ? .rightToLeft : .leftToRight)
        .containerBackground(for: .widget) {
            if family == .systemSmall { LinearGradient(colors: [.hex(0x1B2C52), .hex(0x0B1630)], startPoint: .topLeading, endPoint: .bottomTrailing) } else { Color.clear }
        }
    }
}

struct AlarmNextWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "alarm", provider: AlarmProvider()) { e in AlarmNextView(e: e) }
            .configurationDisplayName("المنبّه القادم")
            .description("وقت منبّهك القادم واسمه.")
            // on the home screen it is a design of the gallery now (art.alarm, 0.32); this one stays for the lock screen
            .supportedFamilies([.accessoryInline, .accessoryRectangular])
    }
}
