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
    /// The next alarm after [now], for «المنبّه القادم» (alarms.json, written by the app).
    static func alarm(_ now: Int) -> KArt.AlarmInfo {
        guard let d = SharedStore.read(file: "alarms.json"), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return KArt.AlarmInfo() }
        let j = J(o), next = (o["next"] as? [Any] ?? []).compactMap { J($0) }.first { $0.optLong("at") > now }
        return KArt.AlarmInfo(at: next?.optLong("at") ?? 0, title: next?.optString("title") ?? "", snoozed: false, count: j.optInt("count"), any: j.optBoolean("any"))
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
    var body: some View {
        GeometryReader { g in
            // drawn as shapes, not a picture (0.31.2): WidgetKit keeps every entry until its minute, and an hour of pictures
            // passed its limits, so only the background showed (all green)
            if let rec = shapes(g.size) { ArtVectorView(rec: rec) }
            // the buttons of «بأزرار» and the circles of «صلواتي اليوم» work in the widget (0.33)
            if e.type == "actions" || e.type == "prayed" { ArtButtons(type: e.type, size: g.size, rtl: ArtStore.month()?.ar ?? true) }
        }
        .containerBackground(for: .widget) {
            // the design covers it; seen only behind the arch of «النافذة», in the app's green
            LinearGradient(colors: [Color(red: 0.067, green: 0.349, blue: 0.290), Color(red: 0.039, green: 0.220, blue: 0.173)], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }
    private func shapes(_ size: CGSize) -> ARecorder? {
        guard size.width > 1, size.height > 1 else { return nil }
        KArt.square = true; KArt.wall = true
        let now = Int(e.date.timeIntervalSince1970 * 1000)
        if e.type == "alarm" { KArt.alarm = ArtStore.alarm(now) }
        if e.type == "prayed" { KArt.marks = PrayMarks.marks(ArtStore.month(), now) }
        return KArt.record(e.type, ArtStore.style(e.type, e.fam), ArtStore.month(), now, size.width, size.height,
                           btns: e.type == "actions" ? PrayMarks.btns(ArtStore.month(), now) : KArt.Btns())
    }
}

/// Where the art draws the buttons, as buttons: the three of «بأزرار» in its bottom part, and the five circles of «صلواتي اليوم»
/// (in a row when wide, round a ring when square). Laid out as the art lays them out (KArt.actions, KArt.prayed).
struct ArtButtons: View {
    let type: String, size: CGSize, rtl: Bool
    private func hit(_ which: String, _ r: CGRect) -> some View {
        Group {
            // the marks in the widget itself (at once); muting and reminding through the app, where the notifications are
            if which == "pray" || which.hasPrefix("p") && which.count == 2 {
                Button(intent: WidgetMarkIntent(which)) { Rectangle().fill(Color.white.opacity(0.001)) }
            } else {
                Button(intent: WidgetPressIntent(which)) { Rectangle().fill(Color.white.opacity(0.001)) }
            }
        }
            .buttonStyle(.plain)
            .frame(width: r.width, height: r.height)
            .position(x: r.midX, y: r.midY)
    }
    private var rects: [(String, CGRect)] {
        let w = size.width, h = size.height
        if type == "actions" {
            let s = KArt.fitScale("actions", w, h), pad = 14 * s, gap = 8 * s, top = h * KArt.BTN_TOP
            let bh = h - top - pad, bw = (w - 2 * pad - 2 * gap) / 3
            return ["pray", "mute", "remind"].enumerated().map { (i: Int, k: String) -> (String, CGRect) in
                let col: CGFloat = CGFloat(rtl ? i : 2 - i)
                let l: CGFloat = w - pad - (col + 1) * bw - col * gap
                return (k, CGRect(x: l, y: top, width: bw, height: bh))
            }
        }
        if w / h > 1.8 {
            let left = rtl ? w * KArt.PR_PAD : w * KArt.PR_TITLE, cw = w * KArt.PR_CELL
            return (0...4).map { (i: Int) -> (String, CGRect) in
                let cell: CGFloat = CGFloat(rtl ? 4 - i : i)
                return ("p\(i)", CGRect(x: left + cw * cell, y: 0, width: cw, height: h))
            }
        }
        // round: Fajr at the top, counter-clockwise in Arabic
        let s: CGFloat = min(w, h), R: CGFloat = s * 0.34, t: CGFloat = s * 0.3
        var out: [(String, CGRect)] = []
        for i in 0...4 {
            let deg: Double = rtl ? -126.0 - 72.0 * Double(i) : -54.0 + 72.0 * Double(i)
            let a: Double = deg * Double.pi / 180.0
            let cx: CGFloat = w / 2 + R * CGFloat(cos(a)), cy: CGFloat = h / 2 + R * CGFloat(sin(a))
            out.append(("p\(i)", CGRect(x: cx - t / 2, y: cy - t / 2, width: t, height: t)))
        }
        return out
    }
    var body: some View {
        ZStack { ForEach(rects, id: \.0) { r in hit(r.0, r.1) } }
            .frame(width: size.width, height: size.height)
            .environment(\.layoutDirection, .leftToRight)
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
struct ArtDate: Widget { var body: some WidgetConfiguration { artConfig("date", "التاريخ واليوم", "اليوم والتاريخ الهجري والميلادي بخط الاسم.") } }
struct ArtAlarm: Widget { var body: some WidgetConfiguration { artConfig("alarm", "المنبّه القادم", "وقت منبّهك القادم واسمه.") } }
struct ArtActions: Widget { var body: some WidgetConfiguration { artConfig("actions", "بأزرار", "الصلاة القادمة وثلاثة أزرار: صلّيت، كتم الأذان القادم، ذكّرني قبل خروج الوقت.") } }
struct ArtPrayed: Widget { var body: some WidgetConfiguration { artConfig("prayed", "صلواتي اليوم", "دائرة لكل صلاة، اضغطها إذا صلّيت، وأيامك المتتالية.") } }
struct ArtColumn: Widget { var body: some WidgetConfiguration { artConfig("column", "العمود", "الصلوات الخمس في عمود، والقادمة بخط كبير.") } }

/// A bundle holds ten at most: the designs come in two.
struct ArtWidgetsA: WidgetBundle {
    var body: some Widget { ArtPano(); ArtWindow(); ArtRing(); ArtOrbit(); ArtScript(); ArtTable(); ArtClock(); ArtMoon() }
}
struct ArtWidgetsB: WidgetBundle {
    var body: some Widget { ArtColumn(); ArtRibbon(); ArtExit(); ArtLine(); ArtNight(); ArtRamadan(); ArtWhite(); ArtDate(); ArtAlarm() }
}
/// The two with buttons (0.33).
struct ArtWidgetsC: WidgetBundle {
    var body: some Widget { ArtActions(); ArtPrayed() }
}
