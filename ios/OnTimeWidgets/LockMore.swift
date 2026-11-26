import WidgetKit
import SwiftUI

/// More for the lock screen (0.33): «الخط», «قمر الليلة», «شريط اليوم», «الصلوات الخمس» and «الثلث الأخير».
/// Drawn by SwiftUI from the month the gallery designs read (the lock screen tints everything, so they are shapes and
/// text in white of different strengths); an entry each minute, as «الحلقة».

private func msOf(_ d: Date) -> Int { Int(d.timeIntervalSince1970 * 1000) }

/// The month, ready for [now]: Friday's name for the day of the next prayer, and the app's way of writing the count.
private func lockMonth(_ now: Int) -> KData? {
    guard let d = ArtStore.month(), let nx = d.hero(now) else { return nil }
    d.cf = d.appCf; d.dhuhrDay = d.day(nx.at)?.optLong("dhuhr") ?? now
    return d
}

/// The no-data case, the direction of the language and the background, shared by all five.
private struct LockFrame<Content: View>: View {
    let e: RingEntry
    var round = false
    @ViewBuilder let content: (KData, Int) -> Content
    var body: some View {
        let now = msOf(e.date)
        Group {
            if let d = lockMonth(now) {
                content(d, now).environment(\.layoutDirection, d.ar ? .rightToLeft : .leftToRight)
            } else {
                // before the app has written its times
                Image(systemName: "moon.stars").font(.system(size: round ? 20 : 16))
            }
        }
        .containerBackground(for: .widget) { if round { AccessoryWidgetBackground() } else { Color.clear } }
    }
}

// MARK: «الخط»: the next prayer's name in the app's name font

struct ScriptLockView: View {
    let e: RingEntry
    var body: some View {
        LockFrame(e: e) { d, now in
            if let nx = d.hero(now) {
                HStack(alignment: .center, spacing: 6) {
                    Text(d.name(nx.k))
                        .font(.custom((WFont(rawValue: d.o.optString("font")) ?? .kufi).ps, size: 36))
                        .lineLimit(1).minimumScaleFactor(0.45).widgetAccentable()
                    Spacer(minLength: 2)
                    VStack(alignment: .trailing, spacing: 1) {
                        Text(d.clock(nx.at) + (d.ampm(nx.at).isEmpty ? "" : " " + d.ampm(nx.at)))
                            .font(.system(size: 16, weight: .bold)).lineLimit(1).minimumScaleFactor(0.7)
                        Text(d.inTxt(nx, now)).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.6)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

// MARK: «قمر الليلة»: tonight's moon as it is in the sky, and the Hijri day

/// The lit part of the moon (on the right while it waxes, as seen from Saudi Arabia), as the moon design draws it.
struct MoonLit: Shape {
    let age: Double
    func path(in r: CGRect) -> Path {
        let k = (1 - cos(2 * .pi * age / 29.530588853)) / 2, waxing = age < 14.765
        let rad = min(r.width, r.height) / 2, c = CGPoint(x: r.midX, y: r.midY)
        let disc = Path(ellipseIn: CGRect(x: c.x - rad, y: c.y - rad, width: 2 * rad, height: 2 * rad))
        let half = disc.intersection(Path(CGRect(x: waxing ? c.x : c.x - rad, y: c.y - rad, width: rad, height: 2 * rad)))
        let e = rad * CGFloat(abs(1 - 2 * k))
        let oval = Path(ellipseIn: CGRect(x: c.x - e, y: c.y - rad, width: 2 * e, height: 2 * rad))
        return k < 0.5 ? half.subtracting(oval) : half.union(oval)
    }
}

struct MoonLockView: View {
    let e: RingEntry
    var body: some View {
        LockFrame(e: e, round: true) { d, now in
            let dd = d.day(now)
            VStack(spacing: 1) {
                ZStack {
                    Circle().fill(.white.opacity(0.22))
                    MoonLit(age: KArt.moonAge(now)).fill(.white).widgetAccentable()
                }
                .frame(width: 28, height: 28)
                Text(d.digits(dd?.optString("hd") ?? "")).font(.system(size: 15, weight: .bold)).lineLimit(1)
                Text(dd?.optString("hm") ?? "").font(.system(size: 8, weight: .medium)).lineLimit(1).minimumScaleFactor(0.6)
            }
            .padding(.horizontal, 6)
        }
    }
}

// MARK: «شريط اليوم»: the day from midnight to midnight, its five prayers, and where we are now

struct DayStrip: View {
    let marks: [CGFloat], next: Int?, now: CGFloat, rtl: Bool
    private func x(_ f: CGFloat, _ w: CGFloat) -> CGFloat { let p = 5 + (w - 10) * min(1, max(0, f)); return rtl ? w - p : p }
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height, mid = h / 2, a = x(0, w), b = x(now, w)
            ZStack {
                Capsule().fill(.white.opacity(0.25)).frame(width: w - 6, height: 4).position(x: w / 2, y: mid)
                Capsule().fill(.white.opacity(0.65)).frame(width: max(4, abs(b - a)), height: 4).position(x: (a + b) / 2, y: mid)
                ForEach(0..<marks.count, id: \.self) { i in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(.white.opacity(i == next ? 1 : 0.75))
                        .frame(width: i == next ? 3 : 2, height: i == next ? h : h * 0.6)
                        .position(x: x(marks[i], w), y: mid)
                }
                Circle().fill(.white).frame(width: 9, height: 9).position(x: b, y: mid).widgetAccentable()
            }
        }
        // the positions are worked out for each language above
        .environment(\.layoutDirection, .leftToRight)
    }
}

struct StripLockView: View {
    let e: RingEntry
    var body: some View {
        LockFrame(e: e) { d, now in
            if let nx = d.hero(now), let dd = d.day(now) {
                let m0 = KData.midnight(now)
                let f: (Int) -> CGFloat = { CGFloat($0 - m0) / CGFloat(KData.DAY) }
                VStack(alignment: .leading, spacing: 7) {
                    HStack(spacing: 6) {
                        Text(d.name(nx.k)).font(.system(size: 16, weight: .bold)).widgetAccentable()
                        Text(d.clock(nx.at)).font(.system(size: 14, weight: .semibold))
                        Spacer(minLength: 2)
                        Text(d.count(nx, now)).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.6)
                    }
                    DayStrip(marks: KData.PRAY.map { f(dd.optLong($0)) },
                             next: KData.PRAY.indices.first { KData.PRAY[$0] == nx.k && dd.optLong(nx.k) == nx.at },
                             now: f(now), rtl: d.ar)
                        .frame(height: 16)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
        }
    }
}

// MARK: «الصلوات الخمس»: the day's table in one line, the next one bolder

struct FiveLockView: View {
    let e: RingEntry
    var body: some View {
        LockFrame(e: e) { d, now in
            // the day of the next prayer: after Isha it is tomorrow's, from its Fajr
            if let nx = d.hero(now), let dd = d.day(nx.at) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(d.name(nx.k) + " · " + d.inTxt(nx, now))
                        .font(.system(size: 12, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.7)
                    HStack(spacing: 2) {
                        ForEach(KData.PRAY, id: \.self) { k in
                            let t = dd.optLong(k), isN = k == nx.k && t == nx.at
                            VStack(spacing: 0) {
                                Text(d.name(k)).font(.system(size: 10, weight: isN ? .bold : .regular)).lineLimit(1).minimumScaleFactor(0.6)
                                Text(d.clock(t)).font(.system(size: isN ? 14 : 12, weight: isN ? .bold : .medium)).lineLimit(1).minimumScaleFactor(0.6)
                            }
                            .padding(.vertical, 2)
                            .frame(maxWidth: .infinity)
                            .background { if isN { RoundedRectangle(cornerRadius: 6).fill(.white.opacity(0.2)) } }
                            .opacity(t <= now && !isN ? 0.55 : 1)
                            .widgetAccentable(isN)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }
        }
    }
}

// MARK: «الثلث الأخير»: the night filling from Maghrib to Fajr, and when its last third begins

struct ThirdLockView: View {
    let e: RingEntry
    var body: some View {
        LockFrame(e: e, round: true) { d, now in
            if let n = d.night(now), n.1 > n.0 {
                let m = n.0, f = n.1, third = m + (f - m) * 2 / 3, inThird = now >= third && now < f
                let p = CGFloat(min(1, max(0, Double(now - m) / Double(f - m))))
                ZStack {
                    ZStack {
                        Circle().stroke(.white.opacity(0.22), lineWidth: 5)
                        Circle().trim(from: 2.0 / 3, to: 1).stroke(.white.opacity(0.5), lineWidth: 5)
                        Circle().trim(from: 0, to: p).stroke(.white, style: StrokeStyle(lineWidth: 5, lineCap: .round)).widgetAccentable()
                    }
                    .rotationEffect(.degrees(-90))
                    .scaleEffect(x: d.ar ? -1 : 1, y: 1)
                    VStack(spacing: 0) {
                        Text(d.ar ? "الثلث الأخير" : "Last third").font(.system(size: 8, weight: .medium)).lineLimit(1).minimumScaleFactor(0.6)
                        Text(d.clock(third)).font(.system(size: 15, weight: .bold)).lineLimit(1).minimumScaleFactor(0.6)
                        Text(inThird ? (d.ar ? "بقي " + d.dur(f - now) : d.dur(f - now) + " left") : d.inTxt(third - now))
                            .font(.system(size: 8)).lineLimit(1).minimumScaleFactor(0.5)
                    }
                    .padding(.horizontal, 9)
                }
                .padding(3)
            }
        }
    }
}

// MARK: the widgets

private func lockConfig<V: View>(_ kind: String, _ name: String, _ desc: String,
                                 _ fams: [WidgetFamily], _ v: @escaping (RingEntry) -> V) -> some WidgetConfiguration {
    StaticConfiguration(kind: kind, provider: RingProvider()) { e in v(e) }
        .configurationDisplayName(name)
        .description(desc)
        .supportedFamilies(fams)
}

struct LockScriptWidget: Widget {
    var body: some WidgetConfiguration {
        lockConfig("lockscript", "الخط", "اسم الصلاة القادمة بخط التطبيق، ووقتها وكم باقي.", [.accessoryRectangular]) { ScriptLockView(e: $0) }
    }
}
struct LockMoonWidget: Widget {
    var body: some WidgetConfiguration {
        lockConfig("lockmoon", "قمر الليلة", "القمر كما هو في السماء الليلة، واليوم الهجري.", [.accessoryCircular]) { MoonLockView(e: $0) }
    }
}
struct LockStripWidget: Widget {
    var body: some WidgetConfiguration {
        lockConfig("lockstrip", "شريط اليوم", "يومك على شريط: الصلوات الخمس وأين أنت الآن، والقادمة.", [.accessoryRectangular]) { StripLockView(e: $0) }
    }
}
struct LockFiveWidget: Widget {
    var body: some WidgetConfiguration {
        lockConfig("lockfive", "الصلوات الخمس", "أوقات اليوم في سطر، والقادمة أوضح.", [.accessoryRectangular]) { FiveLockView(e: $0) }
    }
}
struct LockThirdWidget: Widget {
    var body: some WidgetConfiguration {
        lockConfig("lockthird", "الثلث الأخير", "الليل يمتلئ من المغرب إلى الفجر، ومتى يبدأ ثلثه الأخير.", [.accessoryCircular]) { ThirdLockView(e: $0) }
    }
}

/// A bundle of its own: a bundle takes ten widgets at most.
struct LockMoreWidgets: WidgetBundle {
    var body: some Widget {
        LockScriptWidget()
        LockMoonWidget()
        LockStripWidget()
        LockFiveWidget()
        LockThirdWidget()
    }
}
