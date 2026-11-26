import WidgetKit
import SwiftUI

// The Android widgets on the iPhone (0.31): each design drawn by the same art as Android (ios/Art, KArt = WidgetArt.kt)
// into a picture the size of the widget, once a minute. Its look (background, font, what it shows) is chosen in the app's
// «ويدجت الشاشة الرئيسية», saved per design in the App Group (wstyles.json).

struct ArtEntry: TimelineEntry {
    let date: Date
    let type: String
    let fam: String
}

/// The month the page pushed and the looks the user picked, read once and again when the files change.
enum ArtStore {
    private static var data: (Date?, KData?) = (nil, nil)
    private static var styles: (Date?, [String: Any]) = (nil, [:])
    private static func mtime(_ f: String) -> Date? {
        guard let u = SharedStore.container?.appendingPathComponent(f) else { return nil }
        return (try? FileManager.default.attributesOfItem(atPath: u.path))?[.modificationDate] as? Date
    }
    static func month() -> KData? {
        let m = mtime(SharedStore.file)
        if m == nil || m != data.0 { data = (m, KData.load(SharedStore.read())) }
        return data.1
    }
    static func style(_ type: String, _ fam: String) -> KStyle {
        let m = mtime("wstyles.json")
        if m != styles.0 { styles = (m, (SharedStore.read(file: "wstyles.json").flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]) }
        let font = month()?.o.optString("font")
        if let j = styles.1[type] as? [String: Any] { return KStyle.from(type, j, font) }
        return KArt.iosDefault(type, font, fam)
    }
}

struct ArtProvider: TimelineProvider {
    let type: String
    static func fam(_ f: WidgetFamily) -> String { f == .systemSmall ? "s" : f == .systemLarge || f == .systemExtraLarge ? "l" : "m" }
    func placeholder(in context: Context) -> ArtEntry { ArtEntry(date: Date(), type: type, fam: Self.fam(context.family)) }
    func getSnapshot(in context: Context, completion: @escaping (ArtEntry) -> Void) { completion(placeholder(in: context)) }
    /// A picture a minute for the next hour, then a fresh timeline (the countdowns are in the picture; each picture is kept
    /// by WidgetKit until its minute, so not too many).
    func getTimeline(in context: Context, completion: @escaping (Timeline<ArtEntry>) -> Void) {
        let f = Self.fam(context.family), now = Date()
        let m0 = Calendar.current.dateInterval(of: .minute, for: now)?.start ?? now
        var es = [ArtEntry(date: now, type: type, fam: f)]
        for i in 1...60 { es.append(ArtEntry(date: m0.addingTimeInterval(Double(i) * 60), type: type, fam: f)) }
        completion(Timeline(entries: es, policy: .atEnd))
    }
}

struct ArtView: View {
    let e: ArtEntry
    @Environment(\.displayScale) private var scale
    var body: some View {
        GeometryReader { g in
            if let img = picture(g.size) {
                // its colours kept on a tinted or clear home screen (iOS 18+), as the other widgets
                if #available(iOS 18.0, *) { Image(decorative: img, scale: px).resizable().widgetAccentedRenderingMode(.fullColor) }
                else { Image(decorative: img, scale: px).resizable() }
            }
        }
        .containerBackground(for: .widget) {
            // the design covers it; seen only behind the arch of «النافذة», in the app's green
            LinearGradient(colors: [Color(red: 0.067, green: 0.349, blue: 0.290), Color(red: 0.039, green: 0.220, blue: 0.173)], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
    /// Pixels per point for the picture: under the screen's. WidgetKit drops a picture with more than about two thirds of the
    /// widget's own pixels («Widget archival failed due to image being too large»), leaving only the background (0.31.1:
    /// every widget was green); 0.78² is 0.61 of them.
    private var px: CGFloat { min(scale, 3) * 0.78 }
    private func picture(_ size: CGSize) -> CGImage? {
        guard size.width > 1, size.height > 1 else { return nil }
        KArt.square = true
        let now = Int(e.date.timeIntervalSince1970 * 1000)
        return KArt.render(e.type, ArtStore.style(e.type, e.fam), ArtStore.month(), now, Int(size.width * px), Int(size.height * px), px)
    }
}


/// One design as a widget: its name and line in the gallery, and its sizes.
func artConfig(_ type: String, _ name: String, _ desc: String) -> some WidgetConfiguration {
    let fams = (KArt.IOS.first { $0.0 == type }?.1 ?? ["m"]).map { $0 == "s" ? WidgetFamily.systemSmall : $0 == "l" ? .systemLarge : .systemMedium }
    return StaticConfiguration(kind: "art.\(type)", provider: ArtProvider(type: type)) { e in ArtView(e: e) }
        .configurationDisplayName(name)
        .description(desc)
        .supportedFamilies(fams)
        .contentMarginsDisabled()
}

struct ArtPano: Widget { var body: some WidgetConfiguration { artConfig("pano", "البانوراما", "سماء يومك الحية وموعد الصلاة القادمة.") } }
struct ArtWindow: Widget { var body: some WidgetConfiguration { artConfig("window", "النافذة", "السماء الآن من نافذة، والصلاة القادمة.") } }
struct ArtRing: Widget { var body: some WidgetConfiguration { artConfig("ring", "الحلقة", "حلقة تمتلئ حتى الصلاة القادمة.") } }
struct ArtOrbit: Widget { var body: some WidgetConfiguration { artConfig("orbit", "مدار اليوم", "الشمس على مدار يومك وليلتك.") } }
struct ArtScript: Widget { var body: some WidgetConfiguration { artConfig("script", "الخط", "اسم الصلاة القادمة بخط عربي كبير.") } }
struct ArtTable: Widget { var body: some WidgetConfiguration { artConfig("table", "الجدول", "أوقات اليوم، واختر من التطبيق ما يظهر فيه.") } }
struct ArtClock: Widget { var body: some WidgetConfiguration { artConfig("clock", "ساعة السماء", "ألوان السماء على مدار الأربع والعشرين ساعة.") } }
struct ArtMoon: Widget { var body: some WidgetConfiguration { artConfig("moon", "القمر", "القمر الليلة والتاريخ الهجري.") } }
struct ArtRibbon: Widget { var body: some WidgetConfiguration { artConfig("ribbon", "شريط اليوم", "يومك كله في شريط واحد.") } }
struct ArtExit: Widget { var body: some WidgetConfiguration { artConfig("exit", "خروج الوقت", "كم بقي على خروج وقت الصلاة.") } }
struct ArtLine: Widget { var body: some WidgetConfiguration { artConfig("line", "سطر واحد", "الصلاة القادمة في سطر، أو اليوم والتاريخ.") } }
struct ArtNight: Widget { var body: some WidgetConfiguration { artConfig("night", "الثلث الأخير", "متى يبدأ الثلث الأخير من الليل الليلة وكم بقي عليه.") } }
struct ArtRamadan: Widget { var body: some WidgetConfiguration { artConfig("ramadan", "رمضان", "الإفطار والإمساك في رمضان، وكم بقي على رمضان.") } }
struct ArtWhite: Widget { var body: some WidgetConfiguration { artConfig("white", "الأيام البيض", "الشهر الهجري والأيام البيض والاثنين والخميس.") } }
struct ArtColumn: Widget { var body: some WidgetConfiguration { artConfig("column", "العمود", "الصلوات الخمس في عمود، والقادمة بخط كبير.") } }

/// A bundle holds ten at most: the designs come in two.
struct ArtWidgetsA: WidgetBundle {
    var body: some Widget { ArtPano(); ArtWindow(); ArtRing(); ArtOrbit(); ArtScript(); ArtTable(); ArtClock(); ArtMoon() }
}
struct ArtWidgetsB: WidgetBundle {
    var body: some Widget { ArtColumn(); ArtRibbon(); ArtExit(); ArtLine(); ArtNight(); ArtRamadan(); ArtWhite() }
}
