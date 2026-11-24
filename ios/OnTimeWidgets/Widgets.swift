import WidgetKit
import SwiftUI

// MARK: timeline

struct WEntry: TimelineEntry {
    let date: Date
    let cfg: WCfg
    let data: WData?
}

/// Entries at every moment the widget changes (each prayer, iqama, midnight) for the next day and a half;
/// the countdowns tick on their own in between.
enum Timelines {
    static func make(_ cfg: WCfg, now: Date = Date()) -> Timeline<WEntry> {
        WFonts.register()
        guard let d = WData.load(), d.day(now) != nil else {
            return Timeline(entries: [WEntry(date: now, cfg: cfg, data: nil)], policy: .after(now.addingTimeInterval(3600)))
        }
        let dates = [now] + d.changes(from: now)
        let entries = dates.map { WEntry(date: $0, cfg: cfg, data: d) }
        return Timeline(entries: entries, policy: .after(dates.last ?? now.addingTimeInterval(3600)))
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
    func timeline(for configuration: TableConfig, in context: Context) async -> Timeline<WEntry> { Timelines.make(cfg(configuration)) }
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
        NextWidget()
        TableWidget()
        LockWidget()
        // a snoozed alarm counting down (AlarmKit, iOS 26)
        if #available(iOS 26.0, *) { AlarmLive() }
    }
}

// MARK: pieces

/// The countdown in the style the user picked; it ticks by itself (no reloads).
struct Countdown: View {
    let from: Date, to: Date, style: WCount
    var body: some View {
        switch style {
        case .timer: Text(timerInterval: from...max(from, to), countsDown: true).monospacedDigit()
        case .words: Text(to, style: .relative)
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

    var body: some View {
        let p = Palette.of(e.cfg.bg, dark: scheme == .dark)
        Group {
            if let d = e.data, let h = d.hero(e.date), let day = d.day(e.date) {
                content(d, h, day, p)
                    .environment(\.layoutDirection, d.en ? .leftToRight : .rightToLeft)
                    .environment(\.locale, d.locale)
            } else {
                OpenAppView()
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
        case (.table, .systemLarge): TableLarge(d: d, h: h, day: day, p: p, cfg: e.cfg, now: e.date)
        default: TableMedium(d: d, h: h, day: day, p: p, cfg: e.cfg, now: e.date)
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
            Countdown(from: now, to: h.target, style: cfg.count).font(.custom(WFonts.plexBold, size: 14)).foregroundStyle(p.ink)
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
                Countdown(from: now, to: h.target, style: cfg.count).font(.custom(WFonts.plexBold, size: 15)).foregroundStyle(p.ink)
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
    var keys: [String] { cfg.sunrise ? ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"] : WData.pray }
    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(d.digits(day.wd + " · " + d.hijri(day))).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
                Spacer()
                Text(d.city).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
            }
            .lineLimit(1)
            HStack(spacing: 4) {
                ForEach(keys, id: \.self) { k in
                    let on = k == h.k
                    VStack(spacing: 2) {
                        Text(d.name(k)).font(.custom(cfg.font.ps, size: 15)).lineLimit(1).minimumScaleFactor(0.6)
                        Text(day.t[k].map { d.clock($0, mer: false) } ?? "–").font(.custom(on ? WFonts.plexBold : WFonts.plex, size: 13)).monospacedDigit()
                    }
                    .foregroundStyle(on ? p.acc : p.ink)
                    .frame(maxWidth: .infinity).padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10).fill(on ? p.pill : Color.clear))
                }
            }
            if cfg.count != .off {
                HStack(spacing: 4) {
                    Text(d.name(h.k) + (h.iqAt != nil ? (d.en ? " · iqama in" : " · الإقامة بعد") : (d.en ? " in" : " بعد")))
                    Countdown(from: now, to: h.target, style: cfg.count)
                }
                .font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute).lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct TableLarge: View {
    let d: WData, h: WData.Hero, day: WDay, p: Palette, cfg: WCfg, now: Date
    var keys: [String] { cfg.sunrise ? ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"] : WData.pray }
    var body: some View {
        VStack(spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(d.digits(day.wd)).font(.custom(WFonts.plexBold, size: 13)).foregroundStyle(p.ink)
                    Text(d.hijri(day) + (day.hy.isEmpty ? "" : " " + d.digits(day.hy))).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
                }
                Spacer()
                Text(d.city).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
            }
            .lineLimit(1)
            VStack(spacing: 0) {
                Text(heroLabel(d, h)).font(.custom(WFonts.plex, size: 11)).foregroundStyle(p.mute)
                Text(d.name(h.k)).font(.custom(cfg.font.ps, size: 34)).foregroundStyle(p.ink).lineLimit(1)
                Countdown(from: now, to: h.target, style: cfg.count).font(.custom(WFonts.plexBold, size: 15)).foregroundStyle(p.acc)
                    .multilineTextAlignment(.center).widgetAccentable()
            }
            .padding(.vertical, 2)
            VStack(spacing: 4) {
                ForEach(keys, id: \.self) { k in
                    let on = k == h.k
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

// MARK: lock screen

struct LockView: View {
    let e: WEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        Group {
            if let d = e.data, let h = d.hero(e.date) {
                lock(d, h)
                    .environment(\.layoutDirection, d.en ? .leftToRight : .rightToLeft)
                    .environment(\.locale, d.locale)
            } else {
                if family == .accessoryInline { Text("الركن الثاني: افتح التطبيق") } else { Text("افتح التطبيق").font(.system(size: 12)) }
            }
        }
        .containerBackground(for: .widget) { Color.clear }
    }

    @ViewBuilder
    func lock(_ d: WData, _ h: WData.Hero) -> some View {
        switch family {
        case .accessoryInline:
            Text(d.name(h.k) + " " + d.clock(h.at))
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Text(d.name(h.k)).font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.6)
                    Text(d.clock(h.at, mer: false)).font(.system(size: 15, weight: .bold)).minimumScaleFactor(0.6).widgetAccentable()
                }
                .padding(4)
            }
        default:
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 6) {
                    Text(d.name(h.k)).font(.system(size: 16, weight: .bold)).widgetAccentable()
                    Text(d.clock(h.at)).font(.system(size: 15, weight: .semibold))
                }
                Countdown(from: e.date, to: h.target, style: .timer).font(.system(size: 14, weight: .medium))
                if let day = d.day(e.date) { Text(d.hijri(day)).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}
