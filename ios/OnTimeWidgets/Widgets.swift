import WidgetKit
import SwiftUI
import AppIntents

// MARK: timeline

struct WEntry: TimelineEntry {
    let date: Date
    let cfg: WCfg
    let data: WData?
    var off = 0   // the table's day, moved with its arrows (0 = today)
}

/// The table widget's arrows (0.29): which day it shows, for ten minutes after a tap, then back to today. Kept in the App Group.
enum TableShift {
    static let life: TimeInterval = 600
    static func get() -> (off: Int, until: Date) {
        guard let d = SharedStore.read(file: "table.json"), let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return (0, .distantPast) }
        let until = Date(timeIntervalSince1970: ((o["at"] as? NSNumber)?.doubleValue ?? 0) + life)
        return until > Date() ? ((o["off"] as? NSNumber)?.intValue ?? 0, until) : (0, .distantPast)
    }
    static func shift(_ n: Int) {
        let v = n == 0 ? 0 : max(-1, min(6, get().off + n))
        if let j = try? JSONSerialization.data(withJSONObject: ["off": v, "at": Date().timeIntervalSince1970]), let s = String(data: j, encoding: .utf8) { SharedStore.write(s, file: "table.json") }
    }
}

/// ‹ › on the table widget: the day before or after (up to a week ahead); «اليوم» goes back.
struct TableDayIntent: AppIntent {
    static var title: LocalizedStringResource = "اليوم"
    static var isDiscoverable: Bool = false
    @Parameter(title: "delta") var delta: Int
    init() { delta = 0 }
    init(delta: Int) { self.delta = delta }
    func perform() async throws -> some IntentResult { TableShift.shift(delta); return .result() }
}

/// Entries at every moment the widget changes (each prayer, iqama, midnight) for the next day and a half;
/// the countdowns tick on their own in between.
enum Timelines {
    static func make(_ cfg: WCfg, now: Date = Date(), table: Bool = false) -> Timeline<WEntry> {
        WFonts.register()
        guard let d = WData.load(), d.day(now) != nil else {
            return Timeline(entries: [WEntry(date: now, cfg: cfg, data: nil)], policy: .after(now.addingTimeInterval(3600)))
        }
        var set = Set([now] + d.changes(from: now))
        // the countdown in our words («بعد ١٠ دقائق»): an entry each minute for the next four hours (entries, not reloads)
        if cfg.count == .words {
            let base = (now.timeIntervalSince1970 / 60).rounded(.down) * 60
            for i in 1...240 { set.insert(Date(timeIntervalSince1970: base + Double(i) * 60)) }
        }
        let sh = table ? TableShift.get() : (off: 0, until: Date.distantPast)
        if sh.off != 0 { set.insert(sh.until.addingTimeInterval(1)) }
        let dates = set.filter { $0 >= now }.sorted()
        let entries = dates.map { WEntry(date: $0, cfg: cfg, data: d, off: $0 < sh.until ? sh.off : 0) }
        let last = cfg.count == .words ? now.addingTimeInterval(4 * 3600) : (dates.last ?? now.addingTimeInterval(3600))
        return Timeline(entries: entries, policy: .after(last))
    }
    static func preview(_ cfg: WCfg) -> WEntry { WFonts.register(); return WEntry(date: Date(), cfg: cfg, data: WData.load() ?? WData.sample()) }
}

struct NextProvider: AppIntentTimelineProvider {
    func cfg(_ c: NextConfig) -> WCfg { WCfg(bg: c.bg, font: c.font, count: c.count) }
    func placeholder(in context: Context) -> WEntry { WEntry(date: Date(), cfg: WCfg(), data: WData.sample()) }
    func snapshot(for configuration: NextConfig, in context: Context) async -> WEntry { Timelines.preview(cfg(configuration)) }
    func timeline(for configuration: NextConfig, in context: Context) async -> Timeline<WEntry> { Timelines.make(cfg(configuration)) }
}

struct TableProvider: AppIntentTimelineProvider {
    func cfg(_ c: TableConfig) -> WCfg { WCfg(bg: c.bg, font: c.font, count: c.count, sunrise: c.sunrise) }
    func placeholder(in context: Context) -> WEntry { WEntry(date: Date(), cfg: WCfg(), data: WData.sample()) }
    func snapshot(for configuration: TableConfig, in context: Context) async -> WEntry { Timelines.preview(cfg(configuration)) }
    func timeline(for configuration: TableConfig, in context: Context) async -> Timeline<WEntry> { Timelines.make(cfg(configuration), table: true) }
}

struct DateProvider: AppIntentTimelineProvider {
    func cfg(_ c: DateConfig) -> WCfg { WCfg(bg: c.bg, font: c.font, count: .off) }
    func placeholder(in context: Context) -> WEntry { WEntry(date: Date(), cfg: WCfg(bg: .navy), data: WData.sample()) }
    func snapshot(for configuration: DateConfig, in context: Context) async -> WEntry { Timelines.preview(cfg(configuration)) }
    func timeline(for configuration: DateConfig, in context: Context) async -> Timeline<WEntry> { Timelines.make(cfg(configuration)) }
}

struct LockProvider: TimelineProvider {
    func placeholder(in context: Context) -> WEntry { WEntry(date: Date(), cfg: WCfg(), data: WData.sample()) }
    func getSnapshot(in context: Context, completion: @escaping (WEntry) -> Void) { completion(Timelines.preview(WCfg())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<WEntry>) -> Void) { completion(Timelines.make(WCfg())) }
}

// MARK: widgets

struct NextWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "next", intent: NextConfig.self, provider: NextProvider()) { e in
            HomeView(e: e, kind: .next)
        }
        .configurationDisplayName("الصلاة القادمة")
        .description("اسمها ووقتها وكم باقي عليها، والتاريخ الهجري.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct TableWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "table", intent: TableConfig.self, provider: TableProvider()) { e in
            HomeView(e: e, kind: .table)
        }
        .configurationDisplayName("جدول اليوم")
        .description("أوقات اليوم كلها، والقادمة مظللة.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct DateWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "date", intent: DateConfig.self, provider: DateProvider()) { e in
            DateView(e: e)
        }
        .configurationDisplayName("التاريخ واليوم")
        .description("اليوم والتاريخ الهجري والميلادي على شاشة القفل.")
        // on the home screen it is a design of the gallery now (art.date, 0.32); this one stays for the lock screen
        .supportedFamilies([.accessoryRectangular, .accessoryInline])
    }
}

struct LockWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "lock", provider: LockProvider()) { e in
            LockView(e: e)
        }
        .configurationDisplayName("الصلاة القادمة")
        .description("على شاشة القفل: الصلاة القادمة ووقتها وكم باقي.")
        .supportedFamilies([.accessoryInline, .accessoryCircular, .accessoryRectangular])
    }
}

@main
struct OnTimeWidgets: WidgetBundle {
    init() { WFonts.register() }
    var body: some Widget {
        // the Android designs, drawn by the same art (0.31); they take the place of «الصلاة القادمة» here
        ArtWidgetsA().body
        ArtWidgetsB().body
        ArtWidgetsC().body
        LockWidget()
        LockRingWidget()
        // «الخط»، «قمر الليلة»، «شريط اليوم»، «الصلوات الخمس»، «الثلث الأخير» (0.33)
        LockMoreWidgets().body
        DateWidget()
        AlarmNextWidget()
        NextLive()
        // a snoozed alarm counting down (AlarmKit, iOS 26)
        if #available(iOS 26.0, *) { AlarmLive() }
    }
}

// MARK: pieces

/// The countdown in the style the user picked; it ticks by itself (no reloads).
struct Countdown: View {
    let from: Date, to: Date, style: WCount
    var d: WData? = nil
    var lead = true   // «بعد» before the words, where nothing else says it
    var body: some View {
        switch style {
        case .timer: Text(timerInterval: from...max(from, to), countsDown: true).monospacedDigit()
        case .words:
            if let d { Text((lead ? (d.en ? "in " : "بعد ") : "") + d.words(to.timeIntervalSince(from))) } else { Text(to, style: .relative) }
        case .off: EmptyView()
        }
    }
}

/// When the app has not sent anything yet (or the shared folder is missing): a calm word, no crash.
struct OpenAppView: View {
    var body: some View {
        VStack(spacing: 6) {
            Text("الركن الثاني").font(.custom(WFont.kufi.ps, size: 22)).foregroundStyle(Color.hex(0xF4ECDC))
            Text("افتح التطبيق مرة").font(.custom(WFonts.plex, size: 13)).foregroundStyle(Color.hex(0xE2C27A))
            Text("Open the app once").font(.custom(WFonts.plex, size: 10)).foregroundStyle(Color.hex(0xF4ECDC).opacity(0.6))
        }
        .multilineTextAlignment(.center)
    }
}

// MARK: home screen

struct HomeView: View {
    enum Kind { case next, table }
    let e: WEntry
    let kind: Kind
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme
    @Environment(\.widgetRenderingMode) private var mode
    @Environment(\.displayScale) private var scale

    @ViewBuilder
    func inner(_ p: Palette) -> some View {
        if let d = e.data, let h = d.hero(e.date), let day = d.day(e.date) {
            content(d, h, day, p)
                .environment(\.layoutDirection, d.en ? .leftToRight : .rightToLeft)
                .environment(\.locale, d.locale)
        } else {
            OpenAppView()
        }
    }

    /// With tinted or clear icons (iOS 18+) the system would paint text in its own colour: the widget is drawn as one picture
    /// (as Android's are) and shown in full colour, so it keeps the app's colours (0.29).
    @MainActor
    func picture(_ p: Palette, _ size: CGSize) -> UIImage? {
        let v = inner(p).padding(12).frame(width: size.width, height: size.height)
            .background(LinearGradient(colors: [p.top, p.bottom], startPoint: .top, endPoint: .bottom))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        let r = ImageRenderer(content: v); r.scale = scale
        return r.uiImage
    }

    var body: some View {
        let p = Palette.of(e.cfg.bg, dark: scheme == .dark)
        Group {
            if #available(iOS 18.0, *) {
                if mode == .accented && e.data != nil {
                    GeometryReader { g in
                        if let img = picture(p, g.size) { Image(uiImage: img).resizable().widgetAccentedRenderingMode(.fullColor) } else { inner(p) }
                    }
                } else {
                    inner(p)
                }
            } else {
                inner(p)
            }
        }
        .containerBackground(for: .widget) {
            if e.data == nil { LinearGradient(colors: [.hex(0x11594A), .hex(0x0A382C)], startPoint: .top, endPoint: .bottom) }
            else { LinearGradient(colors: [p.top, p.bottom], startPoint: .top, endPoint: .bottom) }
        }
    }

    @ViewBuilder
    func content(_ d: WData, _ h: WData.Hero, _ day: WDay, _ p: Palette) -> some View {
        switch (kind, family) {
        case (.next, .systemMedium): NextMedium(d: d, h: h, day: day, p: p, cfg: e.cfg, now: e.date)
        case (.next, _): NextSmall(d: d, h: h, day: day, p: p, cfg: e.cfg, now: e.date)
        case (.table, .systemLarge): TableLarge(d: d, h: h, day: d.day(e.date, e.off) ?? day, p: p, cfg: e.cfg, now: e.date, off: e.off)
        default: TableMedium(d: d, h: h, day: d.day(e.date, e.off) ?? day, p: p, cfg: e.cfg, now: e.date, off: e.off)
        }
    }
}

/// "Next" or, after the adhan while the iqama countdown runs, "until the iqama".
func heroLabel(_ d: WData, _ h: WData.Hero) -> String {
    h.iqAt != nil ? (d.en ? "Until the iqama" : "حتى الإقامة") : (d.en ? "Next prayer" : "الصلاة القادمة")
}

struct NextSmall: View {
    let d: WData, h: WData.Hero, day: WDay, p: Palette, cfg: WCfg, now: Date
    var body: some View {
        VStack(spacing: 2) {
            Text(heroLabel(d, h)).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
            Text(d.name(h.k)).font(.custom(cfg.font.ps, size: 34)).foregroundStyle(p.ink).lineLimit(1).minimumScaleFactor(0.6)
            Text(d.clock(h.at)).font(.custom(WFonts.plexBold, size: 15)).foregroundStyle(p.acc).widgetAccentable()
            Countdown(from: now, to: h.target, style: cfg.count, d: d).font(.custom(WFonts.plexBold, size: 14)).foregroundStyle(p.ink)
                .multilineTextAlignment(.center)
            Spacer(minLength: 0)
            Text(d.hijri(day)).font(.custom(WFonts.plex, size: 10)).foregroundStyle(p.mute).lineLimit(1).minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct NextMedium: View {
    let d: WData, h: WData.Hero, day: WDay, p: Palette, cfg: WCfg, now: Date
    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 3) {
                Text(heroLabel(d, h)).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
                Text(d.name(h.k)).font(.custom(cfg.font.ps, size: 40)).foregroundStyle(p.ink).lineLimit(1).minimumScaleFactor(0.6)
                Text(d.clock(h.at)).font(.custom(WFonts.plexBold, size: 16)).foregroundStyle(p.acc).widgetAccentable()
                Countdown(from: now, to: h.target, style: cfg.count, d: d).font(.custom(WFonts.plexBold, size: 15)).foregroundStyle(p.ink)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            Rectangle().fill(p.mute.opacity(0.35)).frame(width: 1).padding(.vertical, 6)
            VStack(alignment: .leading, spacing: 3) {
                Text(d.digits(day.wd + " · " + d.hijri(day))).font(.custom(WFonts.plex, size: 10)).foregroundStyle(p.mute).lineLimit(1).minimumScaleFactor(0.7)
                ForEach(WData.pray, id: \.self) { k in
                    let on = k == h.k
                    HStack {
                        Text(d.name(k)).font(.custom(WFonts.plex, size: 12))
                        Spacer(minLength: 4)
                        Text(day.t[k].map { d.clock($0) } ?? "–").font(.custom(on ? WFonts.plexBold : WFonts.plex, size: 12))
                    }
                    .foregroundStyle(on ? p.acc : p.ink)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }
}

/// The day's times side by side; in Arabic they run right to left (the layout direction does it).
struct TableMedium: View {
    let d: WData, h: WData.Hero, day: WDay, p: Palette, cfg: WCfg, now: Date
    var off = 0
    var keys: [String] { cfg.sunrise ? ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"] : WData.pray }
    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 6) {
                DayArrows(d: d, day: day, p: p, off: off, size: 11)
                Spacer()
                Text(d.city).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
            }
            .lineLimit(1)
            HStack(spacing: 4) {
                ForEach(keys, id: \.self) { k in
                    let on = off == 0 && k == h.k
                    VStack(spacing: 2) {
                        Text(d.name(k)).font(.custom(cfg.font.ps, size: 15)).lineLimit(1).minimumScaleFactor(0.6)
                        Text(day.t[k].map { d.clock($0, mer: false) } ?? "–").font(.custom(on ? WFonts.plexBold : WFonts.plex, size: 13)).monospacedDigit()
                    }
                    .foregroundStyle(on ? p.acc : p.ink)
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10).fill(on ? p.pill : Color.clear))
                }
            }
            if cfg.count != .off && off == 0 {
                HStack(spacing: 4) {
                    Text(d.name(h.k) + (h.iqAt != nil ? (d.en ? " · iqama in" : " · الإقامة بعد") : (d.en ? " in" : " بعد")))
                    Countdown(from: now, to: h.target, style: cfg.count, d: d, lead: false)
                }
                .font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct TableLarge: View {
    let d: WData, h: WData.Hero, day: WDay, p: Palette, cfg: WCfg, now: Date
    var off = 0
    var keys: [String] { cfg.sunrise ? ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"] : WData.pray }
    var body: some View {
        VStack(spacing: 6) {
            HStack(alignment: .center) {
                DayArrows(d: d, day: day, p: p, off: off, size: 13, year: true)
                Spacer()
                Text(d.city).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
            }
            .lineLimit(1)
            VStack(spacing: 0) {
                Text(heroLabel(d, h)).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
                Text(d.name(h.k)).font(.custom(cfg.font.ps, size: 34)).foregroundStyle(p.ink).lineLimit(1)
                if off == 0 {
                    Countdown(from: now, to: h.target, style: cfg.count, d: d).font(.custom(WFonts.plexBold, size: 15)).foregroundStyle(p.acc)
                        .multilineTextAlignment(.center).widgetAccentable()
                }
            }
            .padding(.vertical, 2)
            VStack(spacing: 4) {
                ForEach(keys, id: \.self) { k in
                    let on = off == 0 && k == h.k
                    HStack(alignment: .firstTextBaseline) {
                        Text(d.name(k)).font(.custom(cfg.font.ps, size: 18))
                        Spacer(minLength: 6)
                        if let end = day.ex[k]?.last {
                            Text((d.en ? "until " : "حتى ") + end.clock).font(.custom(WFonts.plex, size: 10)).foregroundStyle(on ? p.acc.opacity(0.8) : p.mute)
                        }
                        Text(day.t[k].map { d.clock($0) } ?? "–").font(.custom(on ? WFonts.plexBold : WFonts.plex, size: 15)).monospacedDigit()
                    }
                    .foregroundStyle(on ? p.acc : p.ink)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10).fill(on ? p.pill : Color.clear))
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The table's day with ‹ › to move between days, and «اليوم» to come back (interactive, iOS 17).
struct DayArrows: View {
    let d: WData, day: WDay, p: Palette, off: Int, size: CGFloat
    var year = false
    var body: some View {
        HStack(spacing: 6) {
            Button(intent: TableDayIntent(delta: -1)) { Image(systemName: "chevron.backward").font(.system(size: size, weight: .bold)) }.buttonStyle(.plain).foregroundStyle(p.acc)
            VStack(alignment: .leading, spacing: 0) {
                Text(d.digits(day.wd + " · " + d.hijri(day) + (year && !day.hy.isEmpty ? " " + day.hy : ""))).font(.custom(off == 0 ? WFonts.plex : WFonts.plexBold, size: size)).foregroundStyle(off == 0 ? p.mute : p.ink)
            }
            Button(intent: TableDayIntent(delta: 1)) { Image(systemName: "chevron.forward").font(.system(size: size, weight: .bold)) }.buttonStyle(.plain).foregroundStyle(p.acc)
            if off != 0 {
                Button(intent: TableDayIntent(delta: 0)) { Text(d.en ? "Today" : "اليوم").font(.custom(WFonts.plexBold, size: size)) }.buttonStyle(.plain).foregroundStyle(p.acc)
            }
        }
        .lineLimit(1)
    }
}

// MARK: the date and the day (0.29), as on Android: the day and the date in the name font

struct DateView: View {
    let e: WEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        let p = Palette.of(e.cfg.bg, dark: scheme == .dark), f = e.cfg.font.ps
        Group {
            if let d = e.data, let day = d.day(e.date) {
                let hj = d.hijri(day) + (day.hy.isEmpty ? "" : " " + d.digits(day.hy))
                Group {
                    switch family {
                    // the city with the date (0.33), as the owner asked
                    case .accessoryInline: Text(d.digits(day.wd) + " · " + hj + (d.city.isEmpty ? "" : " · " + d.city))
                    case .accessoryRectangular:
                        VStack(alignment: .leading, spacing: 0) {
                            Text(day.wd).font(.custom(f, size: 20)).widgetAccentable().lineLimit(1)
                            Text(hj).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                            let g2 = [d.digits(day.gr), d.city].filter { !$0.isEmpty }.joined(separator: " · ")
                            if !g2.isEmpty { Text(g2).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    case .systemMedium:
                        VStack(spacing: 6) {
                            Text(day.wd).font(.custom(f, size: 40)).foregroundStyle(p.ink).lineLimit(1).minimumScaleFactor(0.6)
                            Text(hj).font(.custom(f, size: 24)).foregroundStyle(p.acc).lineLimit(1).minimumScaleFactor(0.6)
                            if !day.gr.isEmpty { Text(d.digits(day.gr)).font(.custom(WFonts.plex, size: 13)).foregroundStyle(p.mute).lineLimit(1) }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    default:
                        VStack(spacing: 2) {
                            Text(day.wd).font(.custom(f, size: 24)).foregroundStyle(p.ink).lineLimit(1).minimumScaleFactor(0.6)
                            Text(d.digits(day.hd)).font(.custom(f, size: 46)).foregroundStyle(p.acc).lineLimit(1)
                            Text(day.hm + (day.hy.isEmpty ? "" : " " + d.digits(day.hy))).font(.custom(WFonts.plexBold, size: 12)).foregroundStyle(p.ink).lineLimit(1).minimumScaleFactor(0.7)
                            if !day.gr.isEmpty { Text(d.digits(day.gr)).font(.custom(WFonts.plex, size: 10)).foregroundStyle(p.mute).lineLimit(1).minimumScaleFactor(0.7) }
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                    }
                }
                .environment(\.layoutDirection, d.en ? .leftToRight : .rightToLeft)
                .environment(\.locale, d.locale)
            } else {
                OpenAppView()
            }
        }
        .containerBackground(for: .widget) {
            if family == .accessoryInline || family == .accessoryRectangular { Color.clear }
            else { LinearGradient(colors: [p.top, p.bottom], startPoint: .top, endPoint: .bottom) }
        }
    }
}

// MARK: lock screen

struct LockView: View {
    let e: WEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            // with «الإقامة» off the count goes straight to the next prayer (0.34)
            if let d = e.data, let h = LockStyle.current().show.contains("iqama") ? d.hero(e.date) : d.next(e.date) {
                lock(d, h, LockStyle.current())
                    .environment(\.layoutDirection, d.en ? .leftToRight : .rightToLeft)
                    .environment(\.locale, d.locale)
            } else {
                if family == .accessoryInline { Text("الركن الثاني: افتح التطبيق") } else { Text("افتح التطبيق").font(.system(size: 12)) }
            }
        }
        .containerBackground(for: .widget) { Color.clear }
    }

    @ViewBuilder
    func lock(_ d: WData, _ h: WData.Hero, _ ls: LockStyle) -> some View {
        let nm = ls.name(d.name(h.k)), tm = ls.show.contains("time"), cn = ls.show.contains("count")
        switch family {
        case .accessoryInline:
            Text(nm + (tm || !cn ? " " + d.clock(h.at) : ""))
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(nm).font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.5)
                    if tm || !cn { Text(d.clock(h.at, mer: false)).font(.system(size: 15, weight: .bold)).minimumScaleFactor(0.6).widgetAccentable() }
                    else { Countdown(from: e.date, to: h.target, style: .timer).font(.system(size: 12, weight: .semibold)).minimumScaleFactor(0.5).widgetAccentable() }
                }
                .padding(4)
            }
        default:
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(nm).font(.system(size: 16, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6).widgetAccentable()
                    if tm || !cn { Text(d.clock(h.at)).font(.system(size: 15, weight: .semibold)) }
                }
                if cn { Countdown(from: e.date, to: h.target, style: .timer).font(.system(size: 14, weight: .medium)) }
                let low = [ls.show.contains("hijri") ? d.day(e.date).map { d.hijri($0) } ?? "" : "", ls.show.contains("city") ? (ArtStore.month()?.city ?? "") : ""].filter { !$0.isEmpty }.joined(separator: " · ")
                if !low.isEmpty { Text(low).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
