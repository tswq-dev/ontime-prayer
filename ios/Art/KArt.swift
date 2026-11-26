import Foundation
import CoreGraphics

// WidgetArt.kt in Swift (0.31): every widget drawn into a picture exactly as on Android, following the approved designs
// («ويدجت على وقتها: تصورات»). Positions are fractions of the widget's size so any size works. In Arabic time runs right
// to left (sunrise on the right, the dial turns counter-clockwise); in English the drawing is mirrored.
// Kept line for line with the Kotlin: a change to one goes to the other.

/// The widget typefaces: the three name fonts and IBM Plex for everything else, each in the weights the user can pick.
final class KFonts {
    private let kufiW: [Int: Typeface], ruqaaW: [Int: Typeface], naskhW: [Int: Typeface], plexW: [Int: Typeface]
    static let shared = KFonts()
    init() {
        kufiW = [400: Typeface("kufi", wght: 400), 600: Typeface("kufi", wght: 600), 700: Typeface("kufi", wght: 700)]
        ruqaaW = [400: Typeface("ruqaa400"), 700: Typeface("ruqaa")]
        naskhW = [400: Typeface("naskh400"), 700: Typeface("naskh")]
        plexW = [300: Typeface("plex300"), 400: Typeface("plex400"), 500: Typeface("plex500"), 600: Typeface("plex600"), 700: Typeface("plex700")]
    }
    var plex: Typeface { plexW[400]! }
    var plexB: Typeface { plexW[600]! }
    /// "l" light, "m" medium (the default look), "b" bold.
    func name(_ f: String, _ w: String = "m") -> Typeface {
        switch f {
        case "ruqaa": return ruqaaW[w == "l" ? 400 : 700]!
        case "naskh": return naskhW[w == "l" ? 400 : 700]!
        default: return kufiW[w == "l" ? 400 : w == "b" ? 700 : 600]!
        }
    }
    /// Ruqaa and Naskh have nothing past Bold: "bold" thickens their strokes when drawn.
    func heavy(_ f: String, _ w: String) -> Bool { w == "b" && f != "kufi" }
    func text(_ w: String, _ bold: Bool) -> Typeface { plexW[w == "l" ? (bold ? 500 : 300) : w == "b" ? (bold ? 700 : 600) : (bold ? 600 : 400)]! }
}

@discardableResult func ap<T: AnyObject>(_ o: T, _ f: (T) -> Void) -> T { f(o); return o }

enum KArt {
    static let TYPES = ["pano", "window", "ring", "orbit", "script", "table", "clock", "moon", "ribbon", "exit", "actions", "line", "prayed", "night", "ramadan", "white", "column"]

    /// The phone's own colours (Android 12+ only): none on the iPhone.
    static var dyn: [AInt]? = nil
    /// On the iPhone the system rounds the widget's corners itself: the cards are drawn square, edge to edge.
    static var square = false
    /// No wallpaper behind a widget (the iPhone): what Android leaves see-through around the window's arch is filled (0.31.3).
    static var wall = false
    /// The iPhone's widget sizes for each design ("s" small, "m" medium, "l" large), the first its own (0.31). The buttons and
    /// «صلواتي» wait for buttons that work inside the widget.
    static let IOS: [(String, [String])] = [("pano", ["m", "l"]), ("window", ["s"]), ("ring", ["s"]), ("orbit", ["m"]), ("script", ["m", "s"]),
        ("table", ["m", "l"]), ("clock", ["s"]), ("moon", ["s"]), ("ribbon", ["m"]), ("exit", ["m"]), ("line", ["m"]), ("night", ["s", "m"]),
        ("ramadan", ["m", "s"]), ("white", ["m", "s"]), ("column", ["l"]), ("date", ["s", "m"]), ("alarm", ["s", "m"])]
    /// A widget's size in points on a 6.1-inch iPhone (for the previews in the app).
    static func iosSize(_ f: String) -> CGSize { f == "s" ? CGSize(width: 158, height: 158) : f == "m" ? CGSize(width: 338, height: 158) : CGSize(width: 338, height: 354) }
    /// The look a widget has until the user picks one on the iPhone: the same as on Android, but no see-through background
    /// (the iPhone shows no wallpaper behind a widget), and the large table with more of its rows.
    static func iosDefault(_ type: String, _ font: String?, _ fam: String = "") -> KStyle {
        var st = KStyle.defaultFor(type, font)
        if st.bg == "clear" { st.bg = "sky" }
        if type == "table" && fam == "l" { st.rows = ["city", "date", "count", "times", "exit", "iqama"] }
        if type == "date" { st.bg = "navy" }
        return st
    }

    struct Sty { let kind: String, fg: AInt, sub: AInt, acc: AInt, line: AInt, shadow: Bool
        var clear: Bool { kind == "clear" }
        var light: Bool { kind == "light" }
    }

    final class X {
        let d: KData, day: J, now: Int, st: Sty, f: KFonts, u: CGFloat, rtl: Bool, style: KStyle, w: CGFloat, h: CGFloat
        /// The moment the sky background shows: now, or the moment the user pinned it to (today's prayer + minutes).
        let sky: Int
        let nameTf: Typeface, heavy: Bool, plex: Typeface, plexB: Typeface
        let next: KData.Ev?, prev: KData.Ev?, cur: KData.Win?
        init(_ d: KData, _ day: J, _ now: Int, _ st: Sty, _ f: KFonts, _ u: CGFloat, _ rtl: Bool, _ style: KStyle, _ w: CGFloat, _ h: CGFloat) {
            self.d = d; self.day = day; self.now = now; self.st = st; self.f = f; self.u = u; self.rtl = rtl; self.style = style; self.w = w; self.h = h
            var sk = now
            if !style.sp.isEmpty { let p = style.sp.split(separator: ":").map(String.init); let t = day.optLong(p[0])
                if t > 0 { sk = t + (p.count > 1 ? (Int(p[1]) ?? 0) : 0) * 60_000 } }
            sky = sk
            nameTf = f.name(style.font, style.nw); heavy = f.heavy(style.font, style.nw)
            plex = f.text(style.dw, false); plexB = f.text(style.dw, true)
            next = d.hero(now); prev = d.prev(now); cur = d.current(now)
        }
        func t(_ k: String) -> Int { day.optLong(k) }
        func dp(_ v: CGFloat) -> CGFloat { v * u }
        var dir: CGFloat { rtl ? 1 : -1 }
    }

    static func c(_ hex: String) -> AInt { AColor.parseColor(hex) }
    static func a(_ alpha: CGFloat, _ rgb: AInt) -> AInt { AColor.argb(Int(alpha * 255).coerceIn(0, 255), AColor.red(rgb), AColor.green(rgb), AColor.blue(rgb)) }
    static func mix(_ x: AInt, _ y: AInt, _ f: CGFloat) -> AInt {
        let k = f.coerceIn(0, 1)
        return AColor.rgb(Int(CGFloat(AColor.red(x)) + CGFloat(AColor.red(y) - AColor.red(x)) * k), Int(CGFloat(AColor.green(x)) + CGFloat(AColor.green(y) - AColor.green(x)) * k),
                         Int(CGFloat(AColor.blue(x)) + CGFloat(AColor.blue(y) - AColor.blue(x)) * k))
    }

    static let GOLD = c("#E2C27A"), GOLD_L = c("#C99A4B"), INK = c("#0B3B2E"), AMBER = c("#E8963A")

    static func sty(_ kind: String) -> Sty {
        switch kind {
        case "light": return Sty(kind: kind, fg: c("#0B3B2E"), sub: c("#5B6B5F"), acc: c("#0B4D3B"), line: a(0.14, c("#0B4D3B")), shadow: false)
        case "dark": return Sty(kind: kind, fg: c("#F4ECDC"), sub: a(0.74, c("#F4ECDC")), acc: c("#E2C27A"), line: a(0.28, c("#E2C27A")), shadow: false)
        case "clear": return Sty(kind: kind, fg: AColor.WHITE, sub: a(0.85, AColor.WHITE), acc: c("#F3D58C"), line: a(0.28, AColor.WHITE), shadow: true)
        // «ليلي»: the sky half an hour after Isha, navy; the writing white and only what is next in gold (0.30)
        case "navy": return Sty(kind: kind, fg: c("#FFF8EA"), sub: a(0.74, c("#FFF8EA")), acc: c("#E9CD8A"), line: a(0.26, c("#E2C27A")), shadow: false)
        case "dyn": if let d = dyn { return Sty(kind: kind, fg: d[2], sub: d[3], acc: d[4], line: a(0.16, d[2]), shadow: false) }; return sty("dark")
        default: return Sty(kind: "sky", fg: c("#FFF8EA"), sub: a(0.8, c("#FFF8EA")), acc: c("#FBE3A6"), line: a(0.22, AColor.WHITE), shadow: true)
        }
    }

    // ---------- the sky: colours anchored to the day's own times ----------
    static func skyKeys(_ x: X) -> [(Int, AInt, AInt)] {
        let m = 60_000, d = x.day
        func k(_ t: Int, _ top: String, _ bot: String) -> (Int, AInt, AInt) { (t, c(top), c(bot)) }
        return [
            k(d.optLong("fajr") - 90 * m, "#070B1A", "#121A33"), k(d.optLong("fajr") - 10 * m, "#0B1430", "#1E2A50"),
            k((d.optLong("fajr") + d.optLong("sunrise")) / 2, "#1D2C58", "#C77E6A"), k(d.optLong("sunrise"), "#4A6FA8", "#F2B880"),
            k(d.optLong("sunrise") + 60 * m, "#4F8FD6", "#BFE0F5"), k(d.optLong("dhuhr"), "#3F86D9", "#A8D4F5"),
            k(d.optLong("asr"), "#4A88CF", "#CFE3F0"), k(d.optLong("maghrib") - 60 * m, "#5A86C0", "#F3D6A0"),
            k(d.optLong("maghrib"), "#3A3F72", "#F28C5A"), k(d.optLong("maghrib") + 40 * m, "#22275A", "#8A5A7A"),
            k(d.optLong("isha") + 10 * m, "#0E1530", "#1E2547"), k(d.optLong("isha") + 120 * m, "#070B1A", "#121A33")]
    }
    static func skyAt(_ x: X, _ t: Int) -> (AInt, AInt) {
        let ks = skyKeys(x)
        if t <= ks.first!.0 || t >= ks.last!.0 { return (c("#070B1A"), c("#121A33")) }
        for i in 0..<(ks.count - 1) where t >= ks[i].0 && t <= ks[i + 1].0 {
            let f = CGFloat(t - ks[i].0) / CGFloat(max(1, ks[i + 1].0 - ks[i].0))
            return (mix(ks[i].1, ks[i + 1].1, f), mix(ks[i].2, ks[i + 1].2, f))
        }
        return (c("#070B1A"), c("#121A33"))
    }
    static func isDay(_ x: X) -> Bool { x.now >= x.t("sunrise") && x.now <= x.t("maghrib") }
    static func starAlpha(_ x: X) -> CGFloat {
        if x.sky < x.t("fajr") + 600_000 || x.sky > x.t("isha") { return 1 }
        if x.sky < x.t("sunrise") || x.sky > x.t("maghrib") { return 0.35 }
        return 0
    }
    static func lit(_ x: X) -> CGFloat {
        let r = CGFloat(x.now - (x.t("sunrise") - 1_800_000)) / 7_200_000, s = CGFloat((x.t("maghrib") + 1_200_000) - x.now) / 7_200_000
        return min(r, s).coerceIn(0, 1)
    }
    /// Day: 0 at sunrise → 1 at maghrib. Night: 0 at maghrib → 1 at the next sunrise.
    static func dayFrac(_ x: X) -> CGFloat { (CGFloat(x.now - x.t("sunrise")) / CGFloat(max(1, x.t("maghrib") - x.t("sunrise")))).coerceIn(0, 1) }
    static func nightFrac(_ x: X) -> CGFloat {
        let (m0, r1): (Int, Int) = x.now > x.t("maghrib")
            ? (x.t("maghrib"), x.d.day(x.now, 1)?.optLong("sunrise") ?? (x.t("sunrise") + KData.DAY))
            : (x.d.day(x.now, -1)?.optLong("maghrib") ?? (x.t("maghrib") - KData.DAY), x.t("sunrise"))
        return (CGFloat(x.now - m0) / CGFloat(max(1, r1 - m0))).coerceIn(0, 1)
    }
    /// Moon age in days (0 new, ~14.8 full).
    static func moonAge(_ now: Int) -> Double { let jd = Double(now) / 86_400_000.0 + 2440587.5, s = 29.530588853; return ((jd - 2451550.1).truncatingRemainder(dividingBy: s) + s).truncatingRemainder(dividingBy: s) }

    // ---------- small drawing helpers ----------
    static func paint(_ color: AInt = AColor.WHITE) -> APaint { APaint(color) }
    static func thicken(_ p: APaint) { if p.heavy { p.style = .FILL_AND_STROKE; p.strokeWidth = p.textSize * 0.035; p.strokeJoin = .ROUND } }
    static func tp(_ x: X, _ tf: Typeface, _ sizeDp: CGFloat, _ color: AInt, _ shadow: Bool? = nil) -> APaint {
        let p = APaint(color); p.heavy = tf === x.nameTf && x.heavy
        p.typeface = tf; p.textSize = x.dp(sizeDp)
        if shadow ?? x.st.shadow { p.setShadowLayer(x.dp(4), 0, x.dp(1), 0x8C000000) }
        return p
    }
    static func fit(_ p: APaint, _ s: String, _ maxW: CGFloat) { while p.measureText(s) > maxW && p.textSize > 6 { p.textSize *= 0.94 } }
    /// Draws text so it starts at [x0] in reading order (right edge in Arabic).
    static func text(_ cv: ACanvas, _ x: X, _ s: String, _ x0: CGFloat, _ y: CGFloat, _ p: APaint, _ start: Bool = true) {
        p.textAlign = start == x.rtl ? .RIGHT : .LEFT
        thicken(p); cv.drawText(s, x0, y, p)
    }
    static func center(_ cv: ACanvas, _ s: String, _ cx: CGFloat, _ y: CGFloat, _ p: APaint) { p.textAlign = .CENTER; thicken(p); cv.drawText(s, cx, y, p) }

    /// The prayer's name stretched with kashida (ـ) to fill [target] px, as a calligrapher would:
    /// the elongation goes before the last joining letter («الفجـــر»), never inside lam-alef.
    static let TATWEEL: Character = "\u{0640}"
    static let NONJOIN = Set("اأإآدذرزوؤةءى")
    static func isAr(_ ch: Character) -> Bool { guard let u = ch.unicodeScalars.first?.value, ch.unicodeScalars.count == 1 else { return false }; return u >= 0x0620 && u <= 0x064A }
    static func stretch(_ p: APaint, _ s: String, _ target: CGFloat, _ level: Int = 7) -> String {
        let cs = Array(s)
        let spots = cs.count < 2 ? [] : (0..<(cs.count - 1)).filter { i in let a = cs[i], b = cs[i + 1]
            return isAr(a) && isAr(b) && !NONJOIN.contains(a) && !(a == "ل" && "اأإآ".contains(b)) }
        let w0 = p.measureText(s)
        if spots.isEmpty || w0 >= target { return s }
        // a font whose kashida has no width (Aref Ruqaa) is left as it is, not filled with invisible ones
        if p.measureText(String(TATWEEL)) < 2 { return s }
        // the user's level, 1 … 7: 7 fills the space up to the frame, never past it
        let goal = w0 + (target - w0) * CGFloat(level.coerceIn(0, 7)) / 7
        // split between two joints, the first and the last after «ال»
        var use = spots.filter { !(s.hasPrefix("ال") && $0 < 2) }; if use.isEmpty { use = spots }
        let at = use.count > 1 ? [use.first!, use.last!] : [use.first!]
        func build(_ n: Int) -> String { var sb = ""
            for i in cs.indices { sb.append(cs[i]); if let k = at.firstIndex(of: i) { sb += String(repeating: TATWEEL, count: k == 0 ? (n + at.count - 1) / at.count : n / at.count) } }
            return sb }
        var out = s
        for n in 1...80 { let t = build(n); if p.measureText(t) > goal { break }; out = t }
        return out
    }
    /// Room kept clear on each side of a stretched name: at least 4 real dp, even at the smallest fit scale (.8).
    static func kSafe(_ x: X) -> CGFloat { x.dp(5) }
    /// The width [s] really takes, drawn from its start or centred: its advance, twice any ink past it (a swash), and a heavy name's stroke.
    static func inkW(_ p: APaint, _ s: String) -> CGFloat {
        var b = Rect(); p.getTextBounds(s, 0, s.count, &b); let adv = p.measureText(s)
        return adv + 2 * max(0, max(-CGFloat(b.left), CGFloat(b.right) - adv)) + (p.heavy ? p.textSize * 0.035 : 0)
    }
    /// The name stretched toward [room] px at the user's level, then a kashida taken off until it fits. Arabic only.
    static func kashName(_ x: X, _ p: APaint, _ s: String, _ room: CGFloat) -> String {
        if !x.d.ar || x.style.kl <= 0 || room <= 0 { return s }
        var out = Array(stretch(p, s, room - (inkW(p, s) - p.measureText(s)), x.style.kl)); let sc = Array(s)
        while out.count > sc.count && inkW(p, String(out)) > room {
            let i = out.indices.first { $0 >= sc.count || out[$0] != sc[$0] } ?? out.count - 1; out.remove(at: i)
        }
        return out.count > sc.count && inkW(p, String(out)) <= room ? String(out) : s
    }
    /// The width of an ellipse centred at [cy] at the top or bottom of [s] on baseline [y], whichever is narrower.
    static func chord(_ p: APaint, _ s: String, _ y: CGFloat, _ cy: CGFloat, _ rx: CGFloat, _ ry: CGFloat) -> CGFloat {
        var b = Rect(), t = Rect(); p.getTextBounds(s, 0, s.count, &b); p.getTextBounds(String(TATWEEL), 0, 1, &t)
        let dy = max(abs(y + CGFloat(min(b.top, t.top)) - cy), abs(y + CGFloat(max(b.bottom, t.bottom)) - cy))
        return 2 * rx * sqrt(max(0, 1 - (dy / ry) * (dy / ry)))
    }
    static func startX(_ x: X, _ pad: CGFloat) -> CGFloat { x.rtl ? x.w - pad : pad }
    static func endX(_ x: X, _ pad: CGFloat) -> CGFloat { x.rtl ? pad : x.w - pad }

    static func card(_ cv: ACanvas, _ x: X, _ r0: CGFloat? = nil, _ kind0: String? = nil) {
        let r = square ? 0 : (r0 ?? x.dp(22)), kind = kind0 ?? x.st.kind
        let rect = RectF(0, 0, x.w, x.h)
        switch kind {
        case "clear": return
        case "light": cv.drawRoundRect(rect, r, r, ap(paint()) { $0.shader = ALinear(0, 0, 0, x.h, c("#F5EEE1"), c("#EBE1CE")) })
        case "dark": cv.drawRoundRect(rect, r, r, ap(paint()) { $0.shader = ALinear(0, 0, x.w * 0.35, x.h, c("#11594A"), c("#0A382C")) })
        case "navy":
            cv.drawRoundRect(rect, r, r, ap(paint()) { $0.shader = ALinear(0, 0, x.w * 0.35, x.h, c("#1B2C52"), c("#0B1630")) })
            // a few faint stars, kept off the edges
            let m = x.dp(10)
            for i in stride(from: 0, to: STARS.count, by: 2) {
                cv.drawCircle(m + (x.w - 2 * m) * (x.rtl ? 1 - STARS[i] : STARS[i]), m + (x.h - 2 * m) * STARS[i + 1], x.dp(i % 4 == 0 ? 1.1 : 0.8), paint(a(i % 6 == 0 ? 0.5 : 0.28, c("#F3E6C4"))))
            }
        case "dyn":
            let dy = dyn ?? [c("#11594A"), c("#0A382C")]
            cv.drawRoundRect(rect, r, r, ap(paint()) { $0.shader = ALinear(0, 0, x.w * 0.35, x.h, dy[0], dy[1]) })
        default:
            let (t, b) = skyAt(x, x.sky)
            cv.drawRoundRect(rect, r, r, ap(paint()) { $0.shader = ALinear(0, 0, 0, x.h, t, b) })
            cv.drawRoundRect(rect, r, r, paint(a(0.34, c("#0A101E"))))
        }
    }
    static func pill(_ cv: ACanvas, _ x: X, _ s: String, _ cx: CGFloat, _ cy: CGFloat, _ p: APaint, _ bg: AInt) {
        let tw = p.measureText(s), ph = p.textSize * 1.55, pw = tw + p.textSize * 1.6
        cv.drawRoundRect(RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2), ph / 2, ph / 2, paint(bg))
        center(cv, s, cx, cy + p.textSize * 0.36, ap(APaint(copy: p)) { $0.clearShadowLayer() })
    }
    static func pillW(_ p: APaint, _ s: String) -> CGFloat { p.measureText(s) + p.textSize * 1.6 }
    /// "9 Jumada II" and "Asr until 5:01" (the end of the time we are in), for the optional lines.
    static func hijriTxt(_ x: X) -> String { x.d.digits(x.day.optString("hd")) + " " + x.day.optString("hm") }
    static func exitTxt(_ x: X) -> String { guard let cw = x.cur else { return "" }; let b = x.now < cw.first ? cw.first : cw.end
        return x.d.name(cw.k) + (x.d.ar ? " حتى " : " until ") + x.d.clock(b) }
    static func joinDot(_ p: String...) -> String { p.filter { !$0.isEmpty }.joined(separator: "  ·  ") }
    static func sun(_ cv: ACanvas, _ x: X, _ px: CGFloat, _ py: CGFloat, _ rDp: CGFloat) {
        let g = x.dp(rDp * 2.4)
        cv.drawCircle(px, py, g, ap(paint()) { $0.shader = ARadial(px, py, g, [a(0.75, c("#FFF0C0")), a(0.3, c("#FFE3A0")), 0], [0, 0.4, 1]) })
        cv.drawCircle(px, py, x.dp(rDp), paint(c("#FFF6D8")))
    }
    /// The moon with tonight's phase (lit side on the right while waxing, as seen from Saudi Arabia).
    static func moon(_ cv: ACanvas, _ x: X, _ px: CGFloat, _ py: CGFloat, _ r: CGFloat) {
        let age = moonAge(x.now), k = (1 - cos(2 * .pi * age / 29.530588853)) / 2, waxing = age < 14.765
        cv.drawCircle(px, py, r * 1.6, ap(paint()) { $0.shader = ARadial(px, py, r * 1.6, [a(0.18, c("#F0E8D2")), 0], nil) })
        cv.drawCircle(px, py, r, paint(c("#2A3150")))
        let lit = APath(), side: CGFloat = waxing ? 1 : -1
        // half disc on the lit side
        lit.addArc(RectF(px - r, py - r, px + r, py + r), -90, 180 * side)
        lit.close()
        let e = r * CGFloat(abs(1 - 2 * k)), oval = ap(APath()) { $0.addOval(RectF(px - e, py - r, px + e, py + r), .CW) }
        if k < 0.5 { lit.op(oval, .DIFFERENCE) } else { lit.op(oval, .UNION) }
        cv.drawPath(lit, paint(c("#F4ECD3")))
    }
    static let STARS: [CGFloat] = [0.12, 0.14, 0.28, 0.30, 0.47, 0.10, 0.63, 0.26, 0.79, 0.12, 0.88, 0.34, 0.38, 0.44, 0.70, 0.48, 0.20, 0.52, 0.55, 0.40]
    static func stars(_ cv: ACanvas, _ x: X, _ l: CGFloat, _ t: CGFloat, _ w: CGFloat, _ h: CGFloat) {
        let al = starAlpha(x); if al <= 0 || !x.style.has("sky") { return }
        let p = paint(a(al, AColor.WHITE))
        for i in stride(from: 0, to: STARS.count, by: 2) { cv.drawCircle(l + w * (x.rtl ? 1 - STARS[i] : STARS[i]), t + h * STARS[i + 1], x.dp(i % 4 == 0 ? 1.4 : 1), p) }
    }
    static func hills(_ cv: ACanvas, _ x: X, _ l: CGFloat, _ t: CGFloat, _ w: CGFloat, _ h: CGFloat) {
        let li = lit(x), far = mix(c("#0F1520"), c("#8A93A0"), li), near = mix(c("#07090D"), c("#6E5A44"), li)
        func pth(_ pts: [CGFloat]) -> APath { let p = APath(); p.moveTo(l, t + h * pts[0]); var i = 1
            while i + 5 < pts.count { p.cubicTo(l + w * pts[i], t + h * pts[i + 1], l + w * pts[i + 2], t + h * pts[i + 3], l + w * pts[i + 4], t + h * pts[i + 5]); i += 6 }
            p.lineTo(l + w, t + h); p.lineTo(l, t + h); p.close(); return p }
        cv.drawPath(pth([0.88, 0.11, 0.78, 0.2, 0.82, 0.31, 0.75, 0.42, 0.69, 0.51, 0.80, 0.62, 0.76, 0.76, 0.72, 0.84, 0.82, 1, 0.74]), paint(far))
        cv.drawPath(pth([0.94, 0.17, 0.86, 0.31, 0.93, 0.48, 0.88, 0.65, 0.84, 0.81, 0.94, 1, 0.87]), paint(near))
    }

    // ---------- entry ----------
    /// The states of the buttons widget: done (✓) or not, and whether there is anything to do.
    struct Btns { var prayed = false, muted = false, reminded = false, canPray = true, canRemind = true }

    static func render(_ type: String, _ style: KStyle, _ d: KData?, _ now: Int, _ wPx: Int, _ hPx: Int, _ dpPx: CGFloat, _ fonts: KFonts = .shared, btns: Btns = Btns()) -> CGImage? {
        guard let cv = ACanvas(wPx, hPx) else { return nil }
        draw(cv, type, style, d, now, CGFloat(wPx), CGFloat(hPx), dpPx, fonts, btns)
        return cv.image
    }
    /// The same drawing kept as shapes, [w]×[h] points (a dp is a point), for SwiftUI to draw (the widgets, 0.31.2).
    static func record(_ type: String, _ style: KStyle, _ d: KData?, _ now: Int, _ w: CGFloat, _ h: CGFloat, _ fonts: KFonts = .shared, btns: Btns = Btns()) -> ARecorder? {
        guard let cv = ACanvas(vector: w, h) else { return nil }
        draw(cv, type, style, d, now, w, h, 1, fonts, btns)
        return cv.rec
    }
    static func draw(_ cv: ACanvas, _ type: String, _ style: KStyle, _ d: KData?, _ now: Int, _ wPx: CGFloat, _ hPx: CGFloat, _ dpPx: CGFloat, _ fonts: KFonts, _ btns: Btns) {
        if let dd = d { dd.cf = style.cf.isEmpty ? dd.appCf : style.cf
            dd.dhuhrDay = (dd.next(now).flatMap { nx in dd.day(nx.at) } ?? dd.day(now))?.optLong("dhuhr") ?? now }
        let day = d?.day(now)
        guard let dd = d, let dy = day else { empty(cv, wPx, hPx, dpPx, fonts, d?.ar ?? true); return }
        let st = sty(type == "pano" || type == "window" ? (type == "window" && ["light", "dark", "navy", "dyn"].contains(style.bg) ? style.bg : "sky") : style.bg)
        let x = X(dd, dy, now, st, fonts, dpPx * fitScale(type, wPx / dpPx, hPx / dpPx), dd.ar, style, wPx, hPx)
        switch type {
        case "pano": pano(cv, x)
        case "window": window(cv, x)
        case "ring": ring(cv, x)
        case "orbit": orbit(cv, x)
        case "script": script(cv, x)
        case "table": table(cv, x)
        case "clock": clock(cv, x)
        case "moon": moonW(cv, x)
        case "ribbon": ribbon(cv, x)
        case "exit": exit(cv, x)
        case "actions": actions(cv, x, btns)
        case "line": line(cv, x)
        case "prayed": prayed(cv, x)
        case "night": nightW(cv, x)
        case "ramadan": ramadan(cv, x)
        case "white": white(cv, x)
        case "column": column(cv, x)
        case "date": dateW(cv, x)
        case "alarm": alarmW(cv, x)
        default: break
        }
    }
    /// Widgets laid out in dp grow with the space they get (a 4×2 made taller, a big phone), keeping their balance.
    static func fitScale(_ type: String, _ wDp: CGFloat, _ hDp: CGFloat) -> CGFloat {
        let wide = wDp / hDp > 1.8
        let dw: CGFloat, dh: CGFloat
        switch type {
        case "pano", "orbit", "actions": (dw, dh) = (340, 170)
        case "exit", "line", "script": (dw, dh) = (340, 90)
        case "ribbon": (dw, dh) = (340, 96)
        // the extra ones come wide or square; square ones are laid out from their own size
        case "prayed", "night": if wide { (dw, dh) = (340, 90) } else { return 1 }
        case "ramadan", "white": if wide { (dw, dh) = (340, 170) } else { return 1 }
        default: return 1
        }
        return min(wDp / dw, hDp / dh).coerceIn(0.8, 2.2)
    }
    static func empty(_ cv: ACanvas, _ w: CGFloat, _ h: CGFloat, _ u: CGFloat, _ f: KFonts, _ ar: Bool) {
        cv.drawRoundRect(RectF(0, 0, w, h), square ? 0 : 22 * u, square ? 0 : 22 * u, ap(paint()) { $0.shader = ALinear(0, 0, w * 0.35, h, c("#11594A"), c("#0A382C")) })
        let p = APaint(c("#F4ECDC")); p.typeface = f.plexB; p.textSize = 13 * u; p.textAlign = .CENTER
        let s = ar ? "افتح «الركن الثاني» مرة لتظهر الأوقات" : "Open On Time once to show the times"
        fit(p, s, w - 24 * u); cv.drawText(s, w / 2, h / 2 + p.textSize * 0.35, p)
    }

    // ---------- 1. panorama (4×2) and window (2×2): the real sky now ----------
    static func pano(_ cv: ACanvas, _ x: X) {
        let w = x.w, h = x.h, r = square ? 0 : x.dp(22)
        // light, green or the phone's colours: the sky on top and a strip for the text under it
        let strip = ["light", "dark", "navy", "dyn"].contains(x.style.bg), ss = sty(x.style.bg), sh = strip ? h * 0.64 : h
        cv.save(); let clip = ap(APath()) { $0.addRoundRect(RectF(0, 0, w, h), r, r, .CW) }; cv.clipPath(clip)
        if strip { card(cv, x, r, x.style.bg) }
        cv.save(); cv.clipRect(0, 0, w, sh)
        let (top, bot) = skyAt(x, x.sky)
        cv.drawRect(0, 0, w, sh, ap(paint()) { $0.shader = ALinear(0, 0, 0, sh, top, bot) })
        stars(cv, x, 0, 0, w, sh * 0.6)
        let cx = w / 2, cy = sh * (strip ? 0.80 : 0.576), rx = w * 0.393, ry = sh * (strip ? 0.62 : 0.412)
        func pt(_ q: CGFloat) -> [CGFloat] { [cx + x.dir * rx * cos(.pi * q), cy - ry * sin(.pi * q)] }
        let arc = APath(); for i in 0...60 { let q = pt(CGFloat(i) / 60); if i == 0 { arc.moveTo(q[0], q[1]) } else { arc.lineTo(q[0], q[1]) } }
        cv.drawPath(arc, ap(paint(a(0.45, c("#FFFAEB")))) { $0.style = .STROKE; $0.strokeWidth = x.dp(1.4); $0.dash = [x.dp(2), x.dp(5)] })
        let span = max(1, x.t("maghrib") - x.t("sunrise"))
        let day = isDay(x)
        let body: [CGFloat] = day ? pt(dayFrac(x)) : { let m = 0.15 + 0.7 * nightFrac(x); return [cx + x.dir * w * 0.337 * cos(.pi * m), cy - ry * 0.8 * sin(.pi * m)] }()
        let lp = tp(x, x.plex, 10, c("#FFF8EA"), true)
        for k in ["sunrise", "dhuhr", "asr", "maghrib"] {
            let q = pt((CGFloat(x.t(k) - x.t("sunrise")) / CGFloat(span)).coerceIn(0, 1)), on = k == x.next?.k
            cv.drawCircle(q[0], q[1], x.dp(on ? 5 : 3), paint(on ? c("#F3D58C") : a(0.7, c("#FFFAEB"))))
            if !x.style.has("labels") { continue }
            lp.typeface = on ? x.plexB : x.plex; lp.alpha = on ? 255 : 190
            let near = hypot(q[0] - body[0], q[1] - x.dp(12) - body[1]) < x.dp(22)
            if near { continue }   // the sun is on it (it marks the time itself), as in the app
            center(cv, x.d.name(k), q[0], q[1] - x.dp(9), lp)
        }
        if day { sun(cv, x, body[0], body[1], 9) } else if x.style.has("sky") { moon(cv, x, body[0], body[1], x.dp(8)) }
        hills(cv, x, 0, sh * 0.7, w, sh * 0.3)
        if !strip { cv.drawRect(0, sh * 0.54, w, sh, ap(paint()) { $0.shader = ALinear(0, sh, 0, sh * 0.54, a(0.42, AColor.BLACK), 0) }) }
        cv.restore()
        // small lines in the top corners: the Hijri date and when the time we are in ends
        let cp = tp(x, x.plex, 10.5, c("#FFF8EA"), true), pad = x.dp(14)
        if x.style.has("hijri") { text(cv, x, hijriTxt(x), startX(x, pad), x.dp(20), cp) }
        if x.style.has("exit") { text(cv, x, exitTxt(x), endX(x, pad), x.dp(20), cp, false) }
        guard let nx = x.next else { cv.restore(); return }
        let fg = strip ? ss.fg : c("#FFF8EA"), sw = !strip
        let base = strip ? sh + (h - sh) / 2 + x.dp(9) : h - x.dp(16)
        let np = tp(x, x.nameTf, strip ? 24 : 30, fg, sw), tpp = tp(x, x.plex, strip ? 16 : 19, fg, sw)
        let inS = x.d.inTxt(nx, x.now), ip = tp(x, x.plexB, 12.5, strip ? ss.acc : c("#FFF8EA"), false)
        var name = x.d.name(nx.k); let tm = x.d.clock(nx.at) + " " + x.d.ampm(nx.at)
        fit(np, name, w * 0.45)
        // stretched, it fills what the time and the pill leave on its line
        name = kashName(x, np, name, w - 2 * pad - (x.style.has("time") ? tpp.measureText(tm) + x.dp(8) : 0) - (x.style.has("count") ? pillW(ip, inS) + x.dp(8) : 0) - 2 * kSafe(x))
        text(cv, x, name, startX(x, pad), base, np)
        if x.style.has("time") { text(cv, x, tm, startX(x, pad) - x.dir * (np.measureText(name) + x.dp(8)), base, tpp) }
        if x.style.has("count") { let pw = pillW(ip, inS); pill(cv, x, inS, endX(x, pad) + x.dir * pw / 2, base - ip.textSize * 0.45, ip, strip ? ss.line : a(0.3, AColor.BLACK)) }
        cv.restore()
    }
    static func window(_ cv: ACanvas, _ x: X) {
        // light, green or the phone's colours: a card behind the arch
        let framed = ["light", "dark", "navy", "dyn"].contains(x.style.bg)
        if framed { card(cv, x, x.dp(22), x.style.bg) }
        else if wall {
            // the iPhone: the same sky all round the arch, dimmed, as if seen from inside (it showed the app's green there)
            let (t0, b0) = skyAt(x, x.sky), r = square ? 0 : x.dp(22)
            cv.drawRoundRect(RectF(0, 0, x.w, x.h), r, r, ap(paint()) { $0.shader = ALinear(0, 0, 0, x.h, t0, b0) })
            cv.drawRoundRect(RectF(0, 0, x.w, x.h), r, r, paint(a(0.5, c("#0A101E"))))
        }
        let s = min(x.w, x.h) * (framed ? 0.86 : 1), ox = (x.w - s) / 2, oy = (x.h - s) / 2 + (framed ? s * 0.04 : 0), k = s / 170
        func X0(_ v: CGFloat) -> CGFloat { ox + v * k }
        func Y0(_ v: CGFloat) -> CGFloat { oy + v * k }
        let arch = ap(APath()) { $0.moveTo(X0(8), Y0(170)); $0.lineTo(X0(8), Y0(78)); $0.cubicTo(X0(8), Y0(40), X0(46), Y0(14), X0(85), Y0(4))
            $0.cubicTo(X0(124), Y0(14), X0(162), Y0(40), X0(162), Y0(78)); $0.lineTo(X0(162), Y0(170)); $0.close() }
        cv.save(); cv.clipPath(arch)
        let (top, bot) = skyAt(x, x.sky)
        cv.drawRect(X0(0), Y0(0), X0(170), Y0(170), ap(paint()) { $0.shader = ALinear(0, Y0(0), 0, Y0(170), top, bot) })
        stars(cv, x, X0(10), Y0(20), 150 * k, 90 * k)
        if isDay(x) { let q = dayFrac(x); sun(cv, x, X0(85 + x.dir * 62 * cos(.pi * q)), Y0(150 - 110 * sin(.pi * q)), 6 * k / x.u) }
        else if x.style.has("sky") { let m = 0.15 + 0.7 * nightFrac(x); moon(cv, x, X0(85 + x.dir * 50 * cos(.pi * m)), Y0(150 - 90 * sin(.pi * m)), 7 * k) }
        hills(cv, x, X0(0), Y0(118), 170 * k, 52 * k)
        cv.drawRect(X0(0), Y0(95), X0(170), Y0(170), ap(paint()) { $0.shader = ALinear(0, Y0(170), 0, Y0(95), a(0.4, AColor.BLACK), 0) })
        cv.restore()
        let edge = ap(APath()) { $0.moveTo(X0(8), Y0(170)); $0.lineTo(X0(8), Y0(78)); $0.cubicTo(X0(8), Y0(40), X0(46), Y0(14), X0(85), Y0(4)); $0.cubicTo(X0(124), Y0(14), X0(162), Y0(40), X0(162), Y0(78)); $0.lineTo(X0(162), Y0(170)) }
        cv.drawPath(edge, ap(paint(GOLD_L)) { $0.style = .STROKE; $0.strokeWidth = 2.2 * k })
        guard let nx = x.next else { return }
        let lines = joinDot(x.style.has("time") ? x.d.clock(nx.at) : "", x.style.has("count") ? x.d.inTxt(nx, x.now) : "")
        let np = ap(tp(x, x.nameTf, 22, c("#FFF8EA"))) { $0.textSize = 22 * k }; var name = x.d.name(nx.k); fit(np, name, 130 * k)
        // stretched, it stays inside the arch: its straight sides (8 to 162 here) less the gold edge and a margin each side
        name = kashName(x, np, name, (154 - 2.2) * k - 2 * max(kSafe(x), 6 * k))
        let ny = lines.isEmpty ? Y0(150) : Y0(140)
        if x.style.has("hijri") { center(cv, hijriTxt(x), X0(85), ny - np.textSize * 1.05, ap(tp(x, x.plex, 10, c("#FFF8EA"))) { $0.textSize = 10 * k }) }
        center(cv, name, X0(85), ny, np)
        if !lines.isEmpty { let lp = ap(tp(x, x.plexB, 12, c("#FFF8EA"))) { $0.textSize = 12 * k }; fit(lp, lines, 140 * k); center(cv, lines, X0(85), Y0(158), lp) }
    }
    // ---------- 2. ring (2×2) ----------
    static let RC = ["fajr": "#8FB3EA", "dhuhr": "#E8C15A", "asr": "#E89A4A", "maghrib": "#EC7F62", "isha": "#9FA2EE"]
    static func ring(_ cv: ACanvas, _ x: X) {
        card(cv, x); guard let nx = x.next else { return }; let pv = x.prev
        let s = min(x.w, x.h), cx = x.w / 2, cy = x.h / 2, r = s * 0.353, sw = s * 0.059
        let ov = RectF(cx - r, cy - r, cx + r, cy + r)
        cv.drawCircle(cx, cy, r, ap(paint(x.st.line)) { $0.style = .STROKE; $0.strokeWidth = sw })
        let prog: CGFloat = nx.iqAt > 0 ? (CGFloat(x.now - nx.at) / CGFloat(max(1, nx.iqAt - nx.at))).coerceIn(0, 1)
            : pv == nil ? 0 : (CGFloat(x.now - pv!.at) / CGFloat(max(1, nx.at - pv!.at))).coerceIn(0, 1)
        let col = x.st.kind == "dyn" ? x.st.acc : c(RC[nx.k] ?? "#E8C15A")
        cv.drawArc(ov, -90, -360 * prog * x.dir, false, ap(paint(col)) { $0.style = .STROKE; $0.strokeWidth = sw; $0.strokeCap = .ROUND })
        let k = s / 170
        let sub = joinDot(x.style.has("time") ? x.d.clock(nx.at) : "", x.style.has("count") ? x.d.count(nx, x.now) : "")
        let top = nx.iqAt > 0 ? (x.d.since ? (x.d.ar ? "منذ الأذان" : "since the adhan") : (x.d.ar ? "حتى الإقامة" : "until iqama"))
            : pv != nil ? x.d.name(pv!.k) + (x.rtl ? " ← " : " → ") + x.d.name(nx.k) : ""
        if !top.isEmpty && x.style.has("prev") { center(cv, top, cx, cy - 18 * k, ap(tp(x, x.plex, 10.5, x.st.sub)) { $0.textSize = 10.5 * k }) }
        let np = ap(tp(x, x.nameTf, 24, x.st.fg)) { $0.textSize = 24 * k }, ny = cy + (sub.isEmpty ? 16 : 9) * k, n0 = x.d.name(nx.k)
        // stretched, it stays inside the ring's inner edge, at the height of its letters
        center(cv, kashName(x, np, n0, chord(np, n0, ny, cy, r - sw / 2, r - sw / 2) - 2 * kSafe(x)), cx, ny, np)
        if !sub.isEmpty { let p = ap(tp(x, x.plexB, 13, col)) { $0.textSize = 13 * k }; fit(p, sub, r * 1.5); center(cv, sub, cx, cy + 28 * k, p) }
    }
    // ---------- 3. the day's orbit (4×2) ----------
    static func orbit(_ cv: ACanvas, _ x: X) {
        card(cv, x); let w = x.w, h = x.h
        let cx = w / 2, cy = h / 2, rx = w * 0.421, ry = h * 0.365
        let nx0 = x.next
        let np = tp(x, x.nameTf, 26, x.st.fg), tmP = tp(x, x.plex, 18, x.st.fg)
        var name = nx0.map { x.d.name($0.k) } ?? ""; let tm = x.style.has("time") ? (nx0.map { x.d.clock($0.at) } ?? "") : ""
        let gap = x.dp(8)
        // stretched, name and time stay inside the orbit at the height of the letters, clear of the dots on the horizon
        if nx0 != nil { let half = min(rx - x.dp(8), chord(np, name, cy + x.dp(2), cy, rx, ry) / 2 - x.dp(1.3))
            name = kashName(x, np, name, 2 * (half - kSafe(x)) - gap - tmP.measureText(tm)) }
        let tw = np.measureText(name) + gap + tmP.measureText(tm)
        let hp = ap(paint(x.st.line)) { $0.strokeWidth = x.dp(1) }
        if nx0 == nil { cv.drawLine(w * 0.03, cy, w * 0.97, cy, hp) }
        else { cv.drawLine(w * 0.03, cy, cx - tw / 2 - x.dp(10), cy, hp); cv.drawLine(cx + tw / 2 + x.dp(10), cy, w * 0.97, cy, hp) }
        func at(_ ang: Double) -> [CGFloat] { [cx + x.dir * rx * CGFloat(cos(ang)), cy + ry * CGFloat(sin(ang))] }
        let upper = APath(), lower = APath()
        for i in 0...60 { let p1 = at(-Double.pi * Double(i) / 60), p2 = at(Double.pi * Double(i) / 60)
            if i == 0 { upper.moveTo(p1[0], p1[1]); lower.moveTo(p2[0], p2[1]) } else { upper.lineTo(p1[0], p1[1]); lower.lineTo(p2[0], p2[1]) } }
        cv.drawPath(upper, ap(paint(a(0.55, x.st.sub))) { $0.style = .STROKE; $0.strokeWidth = x.dp(1.6) })
        cv.drawPath(lower, ap(paint(a(0.45, x.st.sub))) { $0.style = .STROKE; $0.strokeWidth = x.dp(1.4); $0.dash = [x.dp(2), x.dp(5)] })
        let sr = x.t("sunrise"), mg = x.t("maghrib")
        // angle on the orbit: day goes over the top (0 → -π), night under the horizon (π → 0 through +π/2)
        func angOf(_ t: Int) -> Double {
            if t >= sr && t <= mg { return -Double.pi * Double(t - sr) / Double(max(1, mg - sr)) }
            let m0 = t > mg ? mg : (x.d.day(x.now, -1)?.optLong("maghrib") ?? (mg - KData.DAY))
            let r1 = t > mg ? (x.d.day(x.now, 1)?.optLong("sunrise") ?? (sr + KData.DAY)) : sr
            return Double.pi - Double.pi * (Double(t - m0) / Double(max(1, r1 - m0))).coerceIn(0, 1)
        }
        let day = isDay(x), a0 = day ? 0.0 : Double.pi, a1 = angOf(x.now)
        let trail = APath(); for i in 0...40 { let q = at(a0 + (a1 - a0) * Double(i) / 40); if i == 0 { trail.moveTo(q[0], q[1]) } else { trail.lineTo(q[0], q[1]) } }
        cv.drawPath(trail, ap(paint(day ? (x.st.light ? GOLD_L : c("#F3D58C")) : x.st.sub)) { $0.style = .STROKE; $0.strokeWidth = x.dp(2.6); $0.strokeCap = .ROUND })
        for k in KData.SIX {
            var t = x.t(k); if k == "fajr" && t < x.now && !day && x.now > mg { t = x.d.day(x.now, 1)?.optLong("fajr") ?? t }
            let q = at(angOf(t)), on = k == x.next?.k
            if on { cv.drawCircle(q[0], q[1], x.dp(8), paint(a(0.3, x.st.light ? GOLD_L : GOLD))); cv.drawCircle(q[0], q[1], x.dp(5), paint(x.st.light ? GOLD_L : GOLD)) }
            else { cv.drawCircle(q[0], q[1], x.dp(3), paint(x.st.sub)) }
        }
        let s = at(a1)
        if day { sun(cv, x, s[0], s[1], 7.5) } else { cv.drawCircle(s[0], s[1], x.dp(4.5), ap(paint(x.st.sub)) { $0.style = .STROKE; $0.strokeWidth = x.dp(2) }) }
        guard let nx = nx0 else { return }
        let y = cy + x.dp(2), xs = cx + x.dir * tw / 2
        text(cv, x, name, xs, y, np); text(cv, x, tm, xs - x.dir * (np.measureText(name) + gap), y, tmP)
        var yy = y + x.dp(20)
        if x.style.has("count") { let ip = tp(x, x.plexB, 12, x.st.light ? c("#0B4D3B") : x.st.fg, false)
            pill(cv, x, x.d.inTxt(nx, x.now), cx, yy, ip, x.st.light ? a(0.09, c("#0B4D3B")) : a(0.14, AColor.WHITE)); yy += x.dp(20) }
        let extra = joinDot(x.style.has("hijri") ? hijriTxt(x) : "", x.style.has("exit") ? exitTxt(x) : "")
        if !extra.isEmpty { let ep = tp(x, x.plex, 10.5, x.st.sub); fit(ep, extra, x.w * 0.6); center(cv, extra, cx, yy + x.dp(2), ep) }
    }

    // ---------- 4. script (4×1, 2×2) and one line (4×1) ----------
    static func script(_ cv: ACanvas, _ x: X) {
        card(cv, x); guard let nx = x.next else { return }; let w = x.w, h = x.h, pad = x.dp(16)
        // the other side: a stack of the lines the user picked, sized from the widget's height
        var lines: [(String, APaint)] = []
        if x.style.has("time") { lines.append((x.d.clock(nx.at), ap(tp(x, x.plex, 1, x.st.fg)) { $0.textSize = h * 0.30 })) }
        if x.style.has("count") { lines.append((x.d.inTxt(nx, x.now), ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = h * 0.15 })) }
        if x.style.has("hijri") { lines.append((hijriTxt(x), ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = h * 0.13 })) }
        if x.style.has("exit") && x.cur != nil { lines.append((exitTxt(x), ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = h * 0.13 })) }
        let colW = lines.isEmpty ? 0 : (lines.map { $0.1.measureText($0.0) }.max() ?? 0).coerceAtMost(w * 0.38)
        let room = w - 2 * pad - (lines.isEmpty ? 0 : colW + x.dp(18))
        let np = ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = h * 0.62 }
        var name = x.d.name(nx.k); fit(np, name, room)
        if x.style.kash { name = stretch(np, name, room * (lines.isEmpty ? 0.92 : 0.97), x.style.kl) }
        let ny = h / 2 + np.textSize * 0.3
        if lines.isEmpty { center(cv, name, w / 2, ny, np); return }
        text(cv, x, name, startX(x, pad), ny, np)
        lines.forEach { fit($0.1, $0.0, w * 0.38) }
        let total = lines.reduce(CGFloat(0)) { $0 + $1.1.textSize * 1.2 }
        var y = h / 2 - total / 2
        for (t, p) in lines { y += p.textSize * 1.2; text(cv, x, t, endX(x, pad), y - p.textSize * 0.22, p, false) }
    }
    static func line(_ cv: ACanvas, _ x: X) {
        card(cv, x, x.dp(20))
        if x.style.ln != "pray" { lineText(cv, x); return }
        guard let nx = x.next else { return }
        let np = ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = x.h * 0.36 }, rp = ap(tp(x, x.plex, 1, x.st.fg)) { $0.textSize = x.h * 0.27 }
        var parts: [(String, APaint)] = [(x.d.name(nx.k), np)]
        let rest = [x.style.has("time") ? x.d.clock(nx.at) : "", x.style.has("count") ? x.d.inTxt(nx, x.now) : "", x.style.has("hijri") ? hijriTxt(x) : ""].filter { !$0.isEmpty }
        for (i, t) in rest.enumerated() { if i > 0 { parts.append(("·", rp)) }; parts.append((t, rp)) }
        let gap = x.dp(8), total = parts.reduce(CGFloat(0)) { $0 + $1.1.measureText($1.0) } + gap * CGFloat(parts.count - 1)
        let scale = min((x.w - x.dp(32)) / total, x.h * 0.62 / np.textSize); np.textSize *= scale; rp.textSize *= scale
        // stretched, the name takes what the height-bound size leaves free on the line (none when the width bounds it)
        let n0 = parts[0].0, others = parts.dropFirst().reduce(CGFloat(0)) { $0 + $1.1.measureText($1.0) } + gap * scale * CGFloat(parts.count - 1)
        parts[0] = (kashName(x, np, n0, x.w - x.dp(32) - 2 * kSafe(x) - others), np)
        var xs = x.w / 2 + x.dir * (total * scale + np.measureText(parts[0].0) - np.measureText(n0)) / 2
        let y = x.h / 2 + (rest.isEmpty ? np.textSize * 0.3 : rp.textSize * 0.35)
        for (s, p) in parts { text(cv, x, s, xs, y, p); xs -= x.dir * (p.measureText(s) + gap * scale) }
    }
    /// The one-line widget with the day or the date instead of the prayer: one line in the name's font, as large as the
    /// height allows and never past the frame, stretched with kashida like a name when the user picks it.
    static func lineText(_ cv: ACanvas, _ x: X) {
        let dd = x.day, ar = x.d.ar
        let hij = x.d.digits(dd.optString("hd") + " " + dd.optString("hm") + " " + dd.optString("hy")) + (ar ? "هـ" : " AH")
        let g0 = dd.optString("gr").isEmpty ? dd.optString("d") : dd.optString("gr"), greg = x.d.digits(g0) + (ar && !g0.isEmpty ? "م" : "")
        let s0: String
        switch x.style.ln { case "day": s0 = dd.optString("wd"); case "hijri": s0 = hij; case "greg": s0 = greg; default: s0 = dd.optString("wd") + "  " + hij }
        let s = s0.trimmingCharacters(in: .whitespaces)
        if s.isEmpty { return }
        let p = ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = x.h * 0.5 }, room = x.w - x.dp(32)
        p.textSize *= min(1, room / max(1, p.measureText(s))); p.textSize = min(p.textSize, x.h * 0.62)
        let out = kashName(x, p, s, room - 2 * kSafe(x))
        center(cv, out, x.w / 2, x.h / 2 + p.textSize * 0.3, p)
    }

    // ---------- 5. table (4×1 up to 4×4): rows the user picks ----------
    static func table(_ cv: ACanvas, _ x: X) {
        card(cv, x); guard let nx = x.next else { return }
        let rows = x.style.rows, showT = rows.contains("times") || !rows.contains(where: { ["city", "date", "count", "iqama"].contains($0) })
        let hasHead = rows.contains("city") || rows.contains("date"), hasCount = rows.contains("count"), hasIq = rows.contains("iqama")
        let subRow = rows.contains("exit") || !hasCount
        // the city and the date on one line: on top at the end (over the countdown, the default), on top in the middle, or at the bottom
        let headAt = !hasHead ? "" : (x.style.hp == "c" || x.style.hp == "b" ? x.style.hp : "l")
        let need: CGFloat = (hasHead ? 22 : 0) + (hasCount ? 34 : 0) + (showT ? (subRow ? 60 : 48) : 0) + (hasIq ? 32 : 0) + 8
        let k = min(1.8, x.h / x.u / need, x.w / x.u / 240), u = x.u * k
        let w = x.w, pad = 12 * u; var hb = Rect()
        // after Isha the row shows tomorrow
        let tday = KData.dateKey(nx.at) != KData.dateKey(x.now) ? (x.d.day(nx.at) ?? x.day) : x.day
        func tt(_ k: String) -> Int { tday.optLong(k) }
        // ---- measure: every block by its ink, so the margins look the same above and below
        // never smaller than 10.5dp: a short widget shortens the line instead of shrinking it to unreadable
        let hp = ap(tp(x, x.plex, 11.5, x.st.sub)) { $0.textSize = max(11.5 * u, 10.5 * x.u) }
        let dd = x.day, date = [dd.optString("wd"), dd.optString("hd") + " " + dd.optString("hm") + " " + dd.optString("hy")].joined(separator: " · ")
        // narrow: the year goes first, then the date, before the line would shrink past readable
        let cityS: String? = rows.contains("city") && !x.d.city.isEmpty ? x.d.city : nil
        let noYear = [dd.optString("wd"), dd.optString("hd") + " " + dd.optString("hm")].joined(separator: " · ")
        var heads: [String] = []
        for d in [date, noYear, dd.optString("wd")] { let s = [cityS, rows.contains("date") ? x.d.digits(d) : nil].compactMap { $0 }.joined(separator: " · "); if !heads.contains(s) { heads.append(s) } }
        let hs = heads.first { hp.measureText($0) <= (w - 2 * pad) / 0.85 } ?? heads.last!
        var hTop: CGFloat = 0, hH: CGFloat = 0
        if hasHead { fit(hp, hs, w - 2 * pad); hp.getTextBounds(hs, 0, hs.count, &hb); hTop = CGFloat(hb.top); hH = CGFloat(hb.bottom - hb.top) }
        let gH = 7 * u, gR = 6 * u, gI = 6 * u
        let rowH = subRow ? 52 * u : 40 * u, iqH = 28 * u
        // the gold cell of the times row reaches past its text; it counts half its padding
        let rowIn = 3.5 * u
        let below = [showT ? rowH - 2 * rowIn : nil, hasIq ? iqH : nil].compactMap { $0 }
        let fixed = (hasHead ? hH + gH : 0) + below.reduce(0, +) + (below.count > 1 ? gI - rowIn : 0) + (hasCount && !below.isEmpty ? gR - rowIn : 0)
        let margin = max(8 * u, kSafe(x))
        // the headline: the name sized by the five names' ink (one size for every prayer, so it doesn't jump)
        var bandH: CGFloat = 0; let np = tp(x, x.nameTf, 22, x.st.fg); var t0: CGFloat = 0, b0: CGFloat = 0
        let cp = tp(x, x.plexB, 21, x.st.light ? c("#0B4D3B") : x.st.acc)
        let inS = x.d.inTxt(nx, x.now) + (!showT ? "  " + x.d.clock(nx.at) : "")
        var name = "", ps = pad
        if hasCount {
            np.textSize = 100
            var ks: [String] = []; for kk in KData.PRAY + [nx.k] where !ks.contains(kk) { ks.append(kk) }
            for s in ks.map({ x.d.name($0) }) + [String(TATWEEL)] { np.getTextBounds(s, 0, s.count, &hb); t0 = min(t0, CGFloat(hb.top)); b0 = max(b0, CGFloat(hb.bottom)) }
            let inkH = b0 - t0 + (np.heavy ? 3.5 : 0)
            let avail = x.h - 2 * margin - fixed, nominal = 26 * u
            bandH = max(8 * u, avail > nominal ? nominal + min((avail - nominal) * 0.6, 12 * u) : avail)
            np.textSize = max(6, 100 * bandH / inkH)
            cp.textSize = max(21 * u, np.textSize * 0.5); fit(cp, inS, (w - 2 * pad) * 0.46)
            // the name's room: from the start padding (more if its ink pokes past its start) to the countdown, less the gap
            let n0 = x.d.name(nx.k); np.getTextBounds(n0, 0, n0.count, &hb)
            ps = max(pad, kSafe(x) + max(0, x.rtl ? CGFloat(hb.right) - np.measureText(n0) : -CGFloat(hb.left)))
            let room = w - ps - pad - cp.measureText(inS) - max(12 * u, x.dp(12))
            // too wide as it is: smaller, not stretched, and the band shrinks with it
            let wide = inkW(np, n0) > room
            while inkW(np, n0) > room && np.textSize > 6 { np.textSize *= 0.97 }
            if wide { bandH = inkH * np.textSize / 100 }
            name = wide ? n0 : kashName(x, np, n0, room)
        }
        let total = fixed + bandH
        // ---- place, from the top: the rest of the height is shared equally above and below
        var y = (x.h - total) / 2
        func head(_ top: CGFloat) { let base = top - hTop
            if headAt == "l" { text(cv, x, hs, endX(x, pad), base, hp, false) } else { center(cv, hs, w / 2, base, hp) } }
        if hasHead && headAt != "b" { head(y); y += hH + gH }
        if hasCount {
            // sized by all five names, placed by this one's own ink, in the middle of the band
            np.getTextBounds(name, 0, name.count, &hb); let base = y + bandH / 2 - CGFloat(hb.top + hb.bottom) / 2
            // the countdown on the same baseline, smaller should its ink ever pass the band
            cp.getTextBounds(inS, 0, inS.count, &hb)
            while (base + CGFloat(hb.top) < y - 2 * u || base + CGFloat(hb.bottom) > y + bandH + 2 * u) && cp.textSize > 6 { cp.textSize *= 0.95; cp.getTextBounds(inS, 0, inS.count, &hb) }
            text(cv, x, name, startX(x, ps), base, np)
            text(cv, x, inS, endX(x, pad), base, cp, false)
            y += bandH + (!below.isEmpty ? gR - rowIn : 0)
        }
        if showT {
            y -= rowIn
            // the five prayers; sunrise and Duha only if the user adds them
            let keys = ["fajr"] + (rows.contains("sunrise") ? ["sunrise"] : []) + (rows.contains("duha") ? ["duha"] : []) + ["dhuhr", "asr", "maghrib", "isha"]
            let n = keys.count, cw = (w - 16 * u) / CGFloat(n)
            let ex = tday.optJSONObject("ex")
            for (i, key) in keys.enumerated() {
                let col = x.rtl ? i : n - 1 - i, left = w - 8 * u - CGFloat(col + 1) * cw, ccx = left + cw / 2
                let on = key == nx.k, past = !on && tt(key) <= x.now
                if on { cv.drawRoundRect(RectF(left + 1 * u, y, left + cw - 1 * u, y + rowH), 14 * u, 14 * u, paint(x.st.light ? GOLD_L : GOLD)) }
                let fg = on ? INK : x.st.fg, alpha = past ? 115 : 255
                let p1 = ap(tp(x, x.plex, 11, fg, !on && x.st.shadow)) { $0.textSize = 11 * u; $0.alpha = alpha }
                let p2 = ap(tp(x, x.plexB, on ? 16 : 14, fg, !on && x.st.shadow)) { $0.textSize = (on ? 16 : 14) * u; $0.alpha = alpha }
                fit(p2, x.d.clock(tt(key)), cw - 4 * u); fit(p1, x.d.name(key), cw - 4 * u)
                center(cv, x.d.name(key), ccx, y + 15 * u, p1)
                center(cv, x.d.clock(tt(key)), ccx, y + 33 * u, p2)
                if rows.contains("exit") || (on && !hasCount) {
                    var sub = ""
                    if on && !hasCount { sub = x.d.inTxt(nx, x.now) }
                    else if let arr = ex?.optJSONArray(key), let f = arr.first.flatMap({ J($0) }) { sub = (x.d.ar ? "حتى " : "to ") + x.d.clock(f.optLong("at")) }
                    let p3 = ap(tp(x, on ? x.plexB : x.plex, 9.5, fg, !on && x.st.shadow)) { $0.textSize = 9.5 * u; $0.alpha = on ? 255 : past ? 90 : 180 }
                    fit(p3, sub, cw - 6 * u); center(cv, sub, ccx, y + 47 * u, p3)
                }
            }
            y += rowH - rowIn + (hasIq ? gI - rowIn : 0)
        }
        if hasIq {
            let iq = nx.at + x.d.iqMin(nx.k) * 60_000
            cv.drawRoundRect(RectF(8 * u, y, w - 8 * u, y + iqH), 12 * u, 12 * u, paint(x.st.line))
            let p = ap(tp(x, x.plex, 12.5, x.st.fg)) { $0.textSize = 12.5 * u }, pb = ap(tp(x, x.plexB, 12.5, x.st.fg)) { $0.textSize = 12.5 * u }
            text(cv, x, (x.d.ar ? "إقامة " : "Iqama ") + x.d.name(nx.k) + " " + x.d.clock(iq), startX(x, 18 * u), y + 19 * u, p)
            text(cv, x, x.d.since && x.now >= nx.at && x.now < iq ? x.d.adhanSince(x.now - nx.at) : x.d.inTxt(iq - x.now), endX(x, 18 * u), y + 19 * u, pb, false)
            y += iqH
        }
        if hasHead && headAt == "b" { head(y + gH) }
    }

    // ---------- 6. sky clock (2×2) and moon (2×2) ----------
    static func clock(_ cv: ACanvas, _ x: X) {
        card(cv, x); let s = min(x.w, x.h), cx = x.w / 2, cy = x.h / 2
        let ro = s * 0.42, ri = ro * 0.62, rm = (ro + ri) / 2, sw = ro - ri
        // the day's sky colours around the dial: midnight at the bottom, noon at the top
        let keys = skyKeys(x), d0 = KData.minuteOfDay(x.now), midnight = x.now - d0 * 60_000
        var pts: [(CGFloat, AInt)] = []
        for kk in keys { let f = CGFloat((kk.0 - midnight) / 60_000) / 1440; if f >= 0 && f <= 1 { pts.append((f, mix(kk.1, kk.2, 0.5))) } }
        pts.sort { $0.0 < $1.0 }
        let night = c("#0E152C")
        let pos: [CGFloat] = [0] + pts.map { $0.0 } + [1], cols: [AInt] = [night] + pts.map { $0.1 } + [night]
        let sh = ASweep(cx, cy, cols, pos, rotate: 90, mirror: x.rtl)
        cv.drawCircle(cx, cy, rm, ap(paint()) { $0.style = .STROKE; $0.strokeWidth = sw; $0.shader = sh })
        // midnight at the bottom; Arabic turns counter-clockwise (morning on the right), English clockwise
        func onDial(_ t: Int) -> [CGFloat] { let f = Double(t - midnight) / 60_000.0 / 1440.0, ang = Double.pi / 2 - Double(x.dir) * 2 * Double.pi * f
            return [cx + rm * CGFloat(cos(ang)), cy + rm * CGFloat(sin(ang))] }
        for k in KData.PRAY { let q = onDial(x.t(k)), on = k == x.next?.k
            cv.drawCircle(q[0], q[1], x.dp(on ? 5.5 : 3.6), paint(a(0.25, AColor.BLACK))); cv.drawCircle(q[0], q[1], x.dp(on ? 4.5 : 3), paint(c("#F3D58C"))) }
        let q = onDial(x.now)
        if isDay(x) { sun(cv, x, q[0], q[1], 7) } else { moon(cv, x, q[0], q[1], x.dp(6.5)) }
        guard let nx = x.next else { return }; let k = s / 150
        var l2: [(String, APaint)] = []
        if x.style.has("time") { l2.append((x.d.clock(nx.at), ap(tp(x, x.plex, 12, x.st.fg)) { $0.textSize = 12 * k })) }
        if x.style.has("count") { l2.append((x.d.count(nx, x.now), ap(tp(x, x.plex, 10.5, x.st.sub)) { $0.textSize = 10.5 * k })) }
        var y = cy + (l2.isEmpty ? 7 : l2.count == 1 ? 4 : 2) * k
        let np = ap(tp(x, x.nameTf, 17, x.st.fg)) { $0.textSize = 17 * k }, n0 = x.d.name(nx.k)
        // stretched, it stays inside the dial's inner edge, at the height of its letters
        center(cv, kashName(x, np, n0, chord(np, n0, y, cy, ri, ri) - 2 * kSafe(x)), cx, y, np)
        for (t, p) in l2 { y += 14 * k; center(cv, t, cx, y, p) }
    }

    static func moonW(_ cv: ACanvas, _ x: X) {
        card(cv, x); let s = min(x.w, x.h), k = s / 150, pad = 14 * k, dd = x.day
        let big = ap(tp(x, x.plexB, 26, x.st.fg)) { $0.textSize = 26 * k }
        text(cv, x, x.d.digits(dd.optString("hd")), startX(x, pad), pad + 24 * k, big)
        text(cv, x, dd.optString("hm"), startX(x, pad), pad + 40 * k, ap(tp(x, x.plex, 11, x.st.sub)) { $0.textSize = 11 * k })
        moon(cv, x, endX(x, pad) + x.dir * 23 * k, pad + 23 * k, 21 * k)
        text(cv, x, (x.style.has("wd") ? dd.optString("wd") + " · " : "") + x.d.digits(dd.optString("hy")), startX(x, pad), pad + 72 * k, ap(tp(x, x.plex, 11, x.st.sub)) { $0.textSize = 11 * k })
        // made tall, the empty middle takes today's five prayers as compact column rows, once each row gets 19 dp
        let nx = x.next, foot = x.style.has("next") && nx != nil
        let midTop = pad + 82 * k, midBot = foot ? x.h - pad - 38 * k : x.h - pad
        let rh = min((midBot - midTop) / 5, 28 * k)
        if rh >= x.dp(19) { _ = prayerRows(cv, x, KData.PRAY, midTop + (midBot - midTop - 5 * rh) / 2, rh, 0, pad * 0.55, x.w - pad * 0.55, min(rh * 0.48, 12.5 * k)) }
        guard let n = nx, foot else { return }
        cv.drawLine(pad, x.h - pad - 30 * k, x.w - pad, x.h - pad - 30 * k, ap(paint(x.st.line)) { $0.strokeWidth = x.dp(1) })
        text(cv, x, x.d.name(n.k), startX(x, pad), x.h - pad - 8 * k, ap(tp(x, x.nameTf, 16, x.st.fg)) { $0.textSize = 16 * k })
        text(cv, x, x.d.clock(n.at), endX(x, pad), x.h - pad - 8 * k, ap(tp(x, x.plex, 12, x.st.fg)) { $0.textSize = 12 * k }, false)
    }

    // ---------- 7. the day ribbon (4×1) ----------
    static func ribbon(_ cv: ACanvas, _ x: X) {
        card(cv, x); guard let nx = x.next else { return }; let w = x.w, pad = x.dp(14)
        let k = min(1.2, x.h / x.u / 96), u = x.u * k
        let np = ap(tp(x, x.nameTf, 16, x.st.fg)) { $0.textSize = 16 * u }
        text(cv, x, x.d.name(nx.k) + " " + x.d.clock(nx.at), startX(x, pad), 24 * u, np)
        if x.style.has("count") { text(cv, x, x.d.inTxt(nx, x.now), endX(x, pad), 24 * u, ap(tp(x, x.plex, 12, x.st.sub)) { $0.textSize = 12 * u }, false) }
        let top = x.style.has("labels") ? 34 * u : 40 * u, bh = 18 * u, l = pad, r = w - pad, bw = r - l
        let midnight = x.now - KData.minuteOfDay(x.now) * 60_000
        func xAt(_ t: Int) -> CGFloat { let f = (CGFloat(t - midnight) / 60_000 / 1440).coerceIn(0, 1); return x.rtl ? r - bw * f : l + bw * f }
        var keys: [(CGFloat, AInt)] = []
        for kk in skyKeys(x) { let f = CGFloat(kk.0 - midnight) / 60_000 / 1440; if f >= 0 && f <= 1 { keys.append((f, mix(kk.1, kk.2, 0.55))) } }
        let night = c("#0E152C")
        let pos: [CGFloat] = [0] + keys.map { $0.0 } + [1], cols: [AInt] = [night] + keys.map { $0.1 } + [night]
        let grad = x.rtl ? ALinear(r, 0, l, 0, cols, pos) : ALinear(l, 0, r, 0, cols, pos)
        cv.drawRoundRect(RectF(l, top, r, top + bh), bh / 2, bh / 2, ap(paint()) { $0.shader = grad })
        let lp = ap(tp(x, x.plex, 9.5, x.st.sub)) { $0.textSize = 9.5 * u }
        for (i, key) in KData.PRAY.enumerated() {
            let xx = xAt(x.t(key)); cv.drawRect(xx - u, top - 3 * u, xx + u, top + bh + 3 * u, paint(a(0.75, AColor.WHITE)))
            if !x.style.has("labels") { continue }
            let on = key == nx.k; lp.typeface = on ? x.plexB : x.plex; lp.color = on ? x.st.fg : x.st.sub
            center(cv, x.d.name(key) + " " + x.d.clock(on ? nx.at : x.t(key)), xx, top + bh + (i % 2 == 0 ? 14 : 27) * u, lp)
        }
        let nxp = xAt(x.now)
        cv.drawCircle(nxp, top + bh / 2, 7 * u, paint(AColor.WHITE)); cv.drawCircle(nxp, top + bh / 2, 7 * u, ap(paint(x.st.light ? c("#0B4D3B") : x.st.acc)) { $0.style = .STROKE; $0.strokeWidth = 3 * u })
    }

    // ---------- 8. time left (4×1) ----------
    static func exit(_ cv: ACanvas, _ x: X) {
        card(cv, x); let w = x.w, pad = x.dp(16)
        let cw = x.cur, nx = x.next
        let title: String, left: String, frac: CGFloat, until: String, urgent: Bool
        if let cw = cw {
            let bnd = x.now < cw.first ? cw.first : cw.end, nm = x.now < cw.first ? cw.names.first! : cw.names.last!
            title = nm.prefix(1).uppercased() + nm.dropFirst(); left = x.d.leftTxt(bnd - x.now); urgent = bnd - x.now <= 30 * 60_000
            let st0 = x.now < cw.first ? cw.start : cw.first
            frac = (CGFloat(bnd - x.now) / CGFloat(max(1, bnd - st0))).coerceIn(0, 1)
            until = (x.d.ar ? "ينتهي " : "Ends ") + x.d.clock(bnd) + " " + x.d.ampm(bnd)
        } else {
            // the next time that begins (Duha included)
            var nk = nx?.k ?? "", nt = nx?.at ?? 0
            for o in 0...1 { guard let dd = x.d.day(x.now, o) else { continue }
                for k in KData.EXK { let t = dd.optLong(k); if t > x.now && (nt == 0 || t < nt) { nk = k; nt = t } } }
            let nm = x.d.name(nk)
            title = nt == 0 ? "" : (x.d.ar ? "قبيل \(nm)" : "Just before \(nm)")
            left = nt == 0 ? "" : x.d.inTxt(nt - x.now); frac = 0; urgent = false
            until = nt == 0 ? "" : (x.d.ar ? "يدخل وقت \(nm) " + x.d.clock(nt) + " " + x.d.ampm(nt) : "\(nm) begins " + x.d.clock(nt) + " " + x.d.ampm(nt))
        }
        let bar = x.style.has("bar") && cw != nil, sub = x.style.has("time")
        // the lines as one block, as big as the widget's height allows and centred in it
        let ts: CGFloat = bar ? 17 : 21, ss: CGFloat = bar ? 11 : 12.5
        let ch = ts * 0.8 + (bar ? 22 : 0) + (sub ? 14 + ss * 0.8 : 0)
        let k2 = min(1.8, (x.h / x.u - 24) / ch, x.w / x.u / 220).coerceAtLeast(0.6), u2 = x.u * k2
        let tp1 = ap(tp(x, x.nameTf, ts, x.st.fg)) { $0.textSize = ts * u2 }; fit(tp1, title, w * 0.55)
        let ip = ap(tp(x, x.plexB, 12.5, urgent ? c("#241406") : x.st.light ? c("#0B4D3B") : x.st.fg, false)) { $0.textSize = 12.5 * u2 }
        let pw = pillW(ip, left); fit(ip, left, w * 0.4)
        var y = (x.h - ch * u2) / 2 + ts * 0.8 * u2
        // stretched, the title fills up to the pill
        text(cv, x, kashName(x, tp1, title, w - 2 * pad - pw - x.dp(8) - 2 * kSafe(x)), startX(x, pad), y, tp1)
        pill(cv, x, left, endX(x, pad) + x.dir * pw / 2, y - ts * 0.3 * u2, ip, urgent ? AMBER : x.st.light ? c("#E3ECE6") : a(0.16, AColor.WHITE))
        if bar {
            let bt = y + 14 * u2, bh = 8 * u2, l = pad, r = w - pad
            cv.drawRoundRect(RectF(l, bt, r, bt + bh), bh / 2, bh / 2, paint(x.st.line))
            if frac > 0 { let bw = (r - l) * frac
                // what is left stays on the starting side and empties towards it (Arabic: from the left to the right)
                let rect = x.rtl ? RectF(r - bw, bt, r, bt + bh) : RectF(l, bt, l + bw, bt + bh)
                cv.drawRoundRect(rect, bh / 2, bh / 2, paint(urgent ? AMBER : x.st.light ? c("#0B4D3B") : x.st.acc)) }
            y = bt + bh
        }
        if sub { y += 14 * u2 + ss * 0.8 * u2; let sp = ap(tp(x, x.plex, ss, x.st.sub)) { $0.textSize = ss * u2 }; fit(sp, until, w - 2 * pad); text(cv, x, until, startX(x, pad), y, sp) }
    }

    // ---------- 9. with buttons (4×2): the three buttons sit in the bottom 45% ----------
    static let BTN_TOP: CGFloat = 0.55
    static func actions(_ cv: ACanvas, _ x: X, _ b: Btns) {
        card(cv, x, x.dp(24)); guard let nx = x.next else { return }; let w = x.w, h = x.h, pad = x.dp(14)
        let topH = h * BTN_TOP - pad * 0.6
        let ns = topH * 0.5
        let np = ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = ns }, tm = ap(tp(x, x.plex, 1, x.st.fg)) { $0.textSize = ns * 0.6 }
        let cp = ap(tp(x, x.plexB, 1, x.st.light ? c("#0B4D3B") : x.st.acc)) { $0.textSize = ns * 0.4 }
        let cnt = x.style.has("count") ? x.d.inTxt(nx, x.now) : "", tms = x.style.has("time") ? x.d.clock(nx.at) : ""
        let side = (cnt.isEmpty ? 0 : cp.measureText(cnt) + x.dp(12)) + (tms.isEmpty ? 0 : tm.measureText(tms) + x.dp(10))
        var name = x.d.name(nx.k); fit(np, name, w - 2 * pad - side)
        if x.style.kash { name = stretch(np, name, (w - 2 * pad - side) * 0.97, x.style.kl) }
        let yy = pad * 0.6 + topH / 2 + np.textSize * 0.3
        text(cv, x, name, startX(x, pad), yy, np)
        if !tms.isEmpty { text(cv, x, tms, startX(x, pad) - x.dir * (np.measureText(name) + x.dp(10)), yy, tm) }
        if !cnt.isEmpty { text(cv, x, cnt, endX(x, pad), yy, cp, false) }
        let labels = [
            b.prayed ? "✓ " + (x.d.ar ? "صلّيت" : "Prayed") : (x.d.ar ? "صلّيت" : "I prayed"),
            b.muted ? "✓ " + (x.d.ar ? "الأذان مكتوم" : "Muted") : (x.d.ar ? "كتم الأذان القادم" : "Mute next adhan"),
            b.reminded ? "✓ " + (x.d.ar ? "سنذكّرك" : "Reminder set") : (x.d.ar ? "ذكّرني قبل خروج الوقت" : "Remind before it ends")]
        let on = [b.canPray || b.prayed, true, b.canRemind || b.reminded]
        let top = h * BTN_TOP, bh = h - top - pad, gap = x.dp(8), bw = (w - 2 * pad - 2 * gap) / 3
        for i in 0...2 {
            let col = x.rtl ? i : 2 - i, l = w - pad - CGFloat(col + 1) * bw - CGFloat(col) * gap
            let rect = RectF(l, top, l + bw, top + bh)
            let sc = cv.saveLayerAlpha(rect.left - 2, rect.top - 2, rect.right + 2, rect.bottom + 2, on[i] ? 255 : 100)
            cv.drawRoundRect(rect, x.dp(16), x.dp(16), paint(x.st.light ? a(0.07, c("#0B4D3B")) : a(0.1, AColor.WHITE)))
            cv.drawRoundRect(rect, x.dp(16), x.dp(16), ap(paint(x.st.line)) { $0.style = .STROKE; $0.strokeWidth = x.dp(1) })
            let ic = ap(paint(x.st.fg)) { $0.style = .STROKE; $0.strokeWidth = x.dp(1.8); $0.strokeCap = .ROUND; $0.strokeJoin = .ROUND }
            let icx = l + bw / 2, icy = top + bh * 0.36, s = x.dp(8)
            switch i {
            case 0: let p = APath(); p.moveTo(icx - s, icy); p.lineTo(icx - s * 0.25, icy + s * 0.7); p.lineTo(icx + s, icy - s * 0.6); cv.drawPath(p, ic)
            case 1: cv.drawArc(RectF(icx - s * 0.75, icy - s, icx + s * 0.75, icy + s * 0.5), 180, 180, false, ic); cv.drawLine(icx - s * 0.75, icy - s * 0.25, icx - s * 0.75, icy + s * 0.55, ic)
                cv.drawLine(icx + s * 0.75, icy - s * 0.25, icx + s * 0.75, icy + s * 0.55, ic); cv.drawLine(icx - s, icy + s * 0.55, icx + s, icy + s * 0.55, ic); cv.drawLine(icx - s, icy - s, icx + s, icy + s, ic)
            default: cv.drawCircle(icx, icy + s * 0.1, s * 0.8, ic); cv.drawLine(icx, icy - s * 0.3, icx, icy + s * 0.15, ic); cv.drawLine(icx, icy + s * 0.15, icx + s * 0.35, icy + s * 0.35, ic)
            }
            let lp = tp(x, x.plexB, 11.5, x.st.fg, false); fit(lp, labels[i], bw - x.dp(6))
            center(cv, labels[i], icx, top + bh * 0.8, lp)
            cv.restoreToCount(sc)
        }
    }

    // ---------- 10. the column (2×4): the next prayer big, the day's prayers under it, the Hijri date at the foot ----------
    /// The day the rows show: after Isha, tomorrow's (as the table).
    static func rowsDay(_ x: X) -> J { guard let nx = x.next else { return x.day }
        return KData.dateKey(nx.at) != KData.dateKey(x.now) ? (x.d.day(nx.at) ?? x.day) : x.day }
    /// The row of the time we are in, if it is among [keys] on the day shown: it gets the line «حتى 6:50».
    static func exitKey(_ x: X, _ keys: [String]) -> String? { guard let cw = x.cur else { return nil }
        return keys.contains(cw.k) && rowsDay(x).optLong(cw.k) == cw.start ? cw.k : nil }
    /// Prayer rows between [l] and [r]: the name at the start, the time at the end, the next one on a gold pill, the ones gone dimmed.
    /// With [exitH] > 0 the time we are in gets a second line «حتى 6:50» under its name. Returns the bottom.
    static func prayerRows(_ cv: ACanvas, _ x: X, _ keys: [String], _ top: CGFloat, _ rowH: CGFloat, _ exitH: CGFloat, _ l: CGFloat, _ r: CGFloat, _ ts: CGFloat) -> CGFloat {
        let tday = rowsDay(x), nx = x.next, cw = x.cur, exK = exitH > 0 ? exitKey(x, keys) : nil
        let inset = min(rowH * 0.5, x.dp(12)); var y = top
        for k in keys {
            let on = k == nx?.k, going = k == exK, t = tday.optLong(k), past = !on && !going && t <= x.now
            let hh = rowH + (going ? exitH : 0)
            if on { cv.drawRoundRect(RectF(l, y, r, y + hh), rowH / 2, rowH / 2, paint(x.st.light ? GOLD_L : GOLD)) }
            let fg = on ? INK : x.st.fg, al = past ? 115 : 255, sh = !on && x.st.shadow
            let np = ap(tp(x, on ? x.plexB : x.plex, 1, fg, sh)) { $0.textSize = ts; $0.alpha = al }
            let cp = ap(tp(x, x.plexB, 1, fg, sh)) { $0.textSize = ts * 1.08; $0.alpha = al }
            let nm = x.d.name(k), tm = x.d.clock(t)
            fit(cp, tm, (r - l - 2 * inset) * 0.42); fit(np, nm, (r - l - 2 * inset) * 0.54)
            let base = y + rowH / 2 + ts * 0.36, s0 = x.rtl ? r - inset : l + inset, e0 = x.rtl ? l + inset : r - inset
            text(cv, x, nm, s0, base, np); text(cv, x, tm, e0, base, cp, false)
            if going, let cw = cw {
                let b = x.now < cw.first ? cw.first : cw.end
                let ep = ap(tp(x, x.plex, 1, on ? INK : x.st.sub, sh)) { $0.textSize = ts * 0.8; if on { $0.alpha = 210 } }
                let es = (x.d.ar ? "حتى " : "until ") + x.d.clock(b); fit(ep, es, r - l - 2 * inset)
                text(cv, x, es, s0, base + exitH - (ts - ep.textSize) * 0.36, ep)
            }
            y += hh
        }
        return y
    }

    static func column(_ cv: ACanvas, _ x: X) {
        card(cv, x); guard let nx = x.next else { return }; let w = x.w, h = x.h, u0 = x.u
        let keys = ["fajr"] + (x.style.has("sunrise") ? ["sunrise"] : []) + (x.style.has("duha") ? ["duha"] : []) + ["dhuhr", "asr", "maghrib", "isha"]
        let cnt = x.style.has("count") ? x.d.inTxt(nx, x.now) : "", tms = x.style.has("time") ? clk(x, nx.at) : ""
        let sub = !cnt.isEmpty || !tms.isEmpty
        var moonOn = x.style.has("moon"), hijOn = x.style.has("hijri"), exOn = x.style.has("exit") && exitKey(x, keys) != nil
        var rowF: CGFloat = 1, nameB: CGFloat = 46
        // the parts in dp at scale 1 (laid out for 150 dp of width): margins, name, countdown line, rows, exit line, foot
        func foot() -> CGFloat { moonOn ? 22 : hijOn ? 16 : 0 }
        func need() -> CGFloat { 10 + nameB + (sub ? 21 : 0) + 10 + CGFloat(keys.count) * 26 * rowF + (exOn ? 15 : 0) + (foot() > 0 ? 8 + foot() : 0) + 10 }
        let kW = w / u0 / 150
        func kOf() -> CGFloat { min(kW, h / u0 / need(), 1.7) }
        // shorter: the moon goes first, then the date, then the exit line, then the rows close up, then the name gives way
        while h / u0 / need() < min(0.9, kW) {
            if moonOn { moonOn = false } else if hijOn { hijOn = false } else if exOn { exOn = false }
            else if rowF > 0.75 { rowF -= 0.05 } else if nameB > 32 { nameB -= 2 } else { break }
        }
        let k = kOf().coerceAtLeast(0.5), u = u0 * k
        // taller than the width lets it grow: rows open up a little, the name takes some, the rest goes to the gaps
        let free0 = max(0, h - need() * u), rowX = min(free0 * 0.5, CGFloat(keys.count) * 26 * rowF * u * 0.4) / CGFloat(keys.count)
        // the name: sized by the five names' ink so it doesn't jump when the prayer changes, stretched to the frame
        let pad = max(kSafe(x), 7 * u), room = w - 2 * pad; var hb = Rect()
        let np = ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = 100 }; var t0: CGFloat = 0, b0: CGFloat = 0
        var ks: [String] = []; for kk in KData.PRAY + [nx.k] where !ks.contains(kk) { ks.append(kk) }
        for s in ks.map({ x.d.name($0) }) + [String(TATWEEL)] { np.getTextBounds(s, 0, s.count, &hb); t0 = min(t0, CGFloat(hb.top)); b0 = max(b0, CGFloat(hb.bottom)) }
        let want = nameB * u + min((free0 - rowX * CGFloat(keys.count)) * 0.4, nameB * u * 0.6)
        np.textSize = 100 * want / (b0 - t0 + (np.heavy ? 3.5 : 0))
        let n0 = x.d.name(nx.k)
        while inkW(np, n0) > room && np.textSize > 6 { np.textSize *= 0.97 }
        let band = (b0 - t0) * np.textSize / 100
        let free = max(0, h - (need() - nameB) * u - band - rowX * CGFloat(keys.count))
        let gTop = 10 * u + free * 0.2, gMid = 10 * u + free * (foot() > 0 ? 0.3 : 0.4), gFoot = 8 * u + free * 0.3
        var y = gTop
        let name = kashName(x, np, n0, room)
        np.getTextBounds(name, 0, name.count, &hb)
        center(cv, name, w / 2, y + band / 2 - CGFloat(hb.top + hb.bottom) / 2, np)
        y += band
        if sub {
            // the countdown (or «الإقامة بعد …»), then the time if picked, as one centred line
            let cp = ap(tp(x, x.plexB, 1, x.st.light ? c("#0B4D3B") : x.st.acc)) { $0.textSize = 13.5 * u }
            let sp = ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = 13 * u }
            let dot = !cnt.isEmpty && !tms.isEmpty ? "  ·  " : ""
            let tot = { cp.measureText(cnt) + sp.measureText(dot + tms) }
            while tot() > w - 2 * pad && cp.textSize > 6 { cp.textSize *= 0.95; sp.textSize *= 0.95 }
            let base = y + 6 * u + cp.textSize * 0.76; var xs = w / 2 + x.dir * tot() / 2
            if !cnt.isEmpty { text(cv, x, cnt, xs, base, cp); xs -= x.dir * cp.measureText(cnt) }
            if !tms.isEmpty { text(cv, x, dot + tms, xs, base, sp) }
            y += 21 * u
        }
        y += gMid
        let rowH = 26 * rowF * u + rowX, ts = min(13.5 * u, rowH * 0.62)
        y = prayerRows(cv, x, keys, y, rowH, exOn ? 15 * u : 0, 8 * u, w - 8 * u, ts)
        if foot() <= 0 { return }
        // the foot: tonight's moon and the Hijri date, centred together
        y += gFoot; let cy = y + foot() * u / 2
        let hs = hijOn ? x.d.digits(x.day.optString("hd") + " " + x.day.optString("hm") + " " + x.day.optString("hy")) : ""
        let hp = ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = 12 * u }, mr = moonOn ? 8 * u : 0, mg = moonOn && !hs.isEmpty ? 7 * u : 0
        fit(hp, hs, w - 2 * pad - 2 * mr - mg)
        let gw = 2 * mr + mg + hp.measureText(hs), gs = w / 2 + x.dir * gw / 2
        if moonOn { moon(cv, x, gs - x.dir * mr, cy, mr) }
        if !hs.isEmpty { text(cv, x, hs, gs - x.dir * (2 * mr + mg), cy + hp.textSize * 0.36, hp) }
    }

    // ---------- extra widgets: shared bits ----------
    static let WD_AR = ["الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"]
    static let WD_EN = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
    static let WDS_AR = ["أحد", "اثن", "ثلا", "أرب", "خمي", "جمع", "سبت"]
    static let WDS_EN = ["S", "M", "T", "W", "T", "F", "S"]
    static func wdName(_ x: X, _ i: Int) -> String { (x.d.ar ? WD_AR : WD_EN)[((i % 7) + 7) % 7] }
    static func isWide(_ x: X) -> Bool { x.w / x.h > 1.8 }
    /// "غداً", "بعد يومين", "بعد ٤ أيام", "بعد ١٢ يوماً" (English: tomorrow, in 4 days).
    static func inDays(_ x: X, _ n: Int, _ about: Bool = false) -> String {
        let ar = x.d.ar, v = x.d.digits(String(n)), ab = !about ? "" : (ar ? "نحو " : "about ")
        if n <= 0 { return ar ? "اليوم" : "Today" }
        if n == 1 && !about { return ar ? "غداً" : "Tomorrow" }
        return ar ? "بعد " + ab + (n == 2 && !about ? "يومين" : (3...10).contains(n) ? "\(v) أيام" : "\(v) يوماً") : "In \(ab)\(v) days"
    }
    /// The time with its ص/م, as one string.
    static func clk(_ x: X, _ t: Int) -> String { (x.d.clock(t) + " " + x.d.ampm(t)).trimmingCharacters(in: .whitespaces) }
    /// A night sky card for the "live sky" look of the night widgets.
    static func nightCard(_ cv: ACanvas, _ x: X) {
        let r = square ? 0 : x.dp(22)
        cv.drawRoundRect(RectF(0, 0, x.w, x.h), r, r, ap(paint()) { $0.shader = ALinear(0, 0, 0, x.h, [c("#0B1430"), c("#1E2547"), c("#2A2F5A")], [0, 0.7, 1]) })
    }
    static func nightStars(_ cv: ACanvas, _ x: X, _ l: CGFloat, _ t: CGFloat, _ w: CGFloat, _ h: CGFloat) {
        for i in stride(from: 0, to: STARS.count, by: 2) { cv.drawCircle(l + w * (x.rtl ? 1 - STARS[i] : STARS[i]), t + h * STARS[i + 1], x.dp(i % 4 == 0 ? 1.3 : 0.9), paint(a(i % 6 == 0 ? 0.9 : 0.6, AColor.WHITE))) }
    }
    /// An eight-pointed star (never six).
    static func star8(_ cv: ACanvas, _ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ col: AInt) {
        let p = APath()
        for i in 0..<16 { let rr = i % 2 == 0 ? r : r * 0.62, an = -Double.pi / 2 + Double(i) * Double.pi / 8
            let px = cx + rr * CGFloat(cos(an)), py = cy + rr * CGFloat(sin(an)); if i == 0 { p.moveTo(px, py) } else { p.lineTo(px, py) } }
        p.close(); cv.drawPath(p, paint(col))
    }
    static func crescent(_ cv: ACanvas, _ x: X, _ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ col: AInt) {
        let p = ap(APath()) { $0.addCircle(cx, cy, r, .CW) }
        // the hollow faces the end side, as the young moon of Ramadan
        p.op(ap(APath()) { $0.addCircle(cx - x.dir * r * 0.42, cy - r * 0.18, r * 0.84, .CW) }, .DIFFERENCE)
        cv.drawPath(p, paint(col))
    }
    /// Text made of a big part and a small one ("٢" + " من ٥"), centred together, in reading order.
    static func pair(_ cv: ACanvas, _ x: X, _ big: String, _ small: String, _ cx: CGFloat, _ y: CGFloat, _ pb: APaint, _ ps: APaint) {
        thicken(pb); let wb = pb.measureText(big), ws = ps.measureText(small), l = cx - (wb + ws) / 2
        pb.textAlign = .LEFT; ps.textAlign = .LEFT
        if x.rtl { cv.drawText(big, l + ws, y, pb); cv.drawText(small, l, y, ps) } else { cv.drawText(big, l, y, pb); cv.drawText(small, l + wb, y, ps) }
    }

    // ---------- 13. my prayers today (4×1 and 2×2): a circle per prayer, filled by «صلّيت» ----------
    /// The marks of the day whose prayers count now (a bit per prayer, Fajr = 1) and the days in a row with all five.
    struct Marks { var done = 0, streak = 0 }
    static var marks = Marks()
    /// The wide one's layout, as fractions of its width: the title block, the end margin and each circle's cell.
    static let PR_TITLE: CGFloat = 0.32, PR_PAD: CGFloat = 0.03, PR_CELL: CGFloat = 0.13

    static func streakTxt(_ x: X, _ n: Int) -> String {
        let v = x.d.digits(String(n))
        if x.d.ar { return n == 1 ? "يوم كامل" : n == 2 ? "يومين متتاليين" : n <= 10 ? "\(v) أيام متتالية" : "\(v) يوماً متتالياً" }
        return n == 1 ? "1 full day" : "\(v) days in a row"
    }
    static func prayed(_ cv: ACanvas, _ x: X) {
        card(cv, x)
        let pd = x.d.prayerDay(x.now) ?? x.day, m = marks, cur = x.cur?.k
        let n = (0...4).filter { m.done & (1 << $0) != 0 }.count
        // 2 done, 1 its time now, 3 its time has passed, 0 still to come
        func state(_ i: Int) -> Int { let k = KData.PRAY[i], t = pd.optLong(k)
            if m.done & (1 << i) != 0 { return 2 }
            if t >= 1 && t <= x.now && k == cur && x.style.has("now") { return 1 }
            if t >= 1 && t <= x.now { return 3 }
            return 0 }
        let doneC = x.st.light ? GOLD_L : x.st.acc, nowC = x.st.light ? c("#0B4D3B") : x.st.acc
        let chkC = x.st.light ? AColor.WHITE : INK, goldT = x.st.light ? GOLD_L : GOLD
        let countTxt = x.d.digits("\(n)") + (x.d.ar ? " من " : " of ") + x.d.digits("5")
        func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ st: Int) {
            switch st {
            case 2: cv.drawCircle(cx, cy, r, paint(doneC))
                let p = APath(); p.moveTo(cx - r * 0.42, cy + r * 0.02); p.lineTo(cx - r * 0.1, cy + r * 0.34); p.lineTo(cx + r * 0.44, cy - r * 0.3)
                cv.drawPath(p, ap(paint(chkC)) { $0.style = .STROKE; $0.strokeWidth = r * 0.16; $0.strokeCap = .ROUND; $0.strokeJoin = .ROUND })
            case 1: cv.drawCircle(cx, cy, r * 1.13, ap(paint(a(0.18, nowC))) { $0.style = .STROKE; $0.strokeWidth = r * 0.3 })
                cv.drawCircle(cx, cy, r * 0.94, ap(paint(nowC)) { $0.style = .STROKE; $0.strokeWidth = r * 0.13 })
            case 3: cv.drawCircle(cx, cy, r * 0.96, ap(paint(a(0.6, x.st.fg))) { $0.style = .STROKE; $0.strokeWidth = r * 0.08 })
            default: cv.drawCircle(cx, cy, r * 0.96, ap(paint(a(0.45, x.st.fg))) { $0.style = .STROKE; $0.strokeWidth = r * 0.07; $0.dash = [r * 0.22, r * 0.18] })
            }
        }
        if isWide(x) {
            let u = x.u, pad = 14 * u, names = x.style.has("names")
            let tw = x.w * PR_TITLE - pad, tx = startX(x, pad)
            let tl = tp(x, x.nameTf, 15, x.st.fg), title = x.d.ar ? "صلواتي اليوم" : "My prayers today"; fit(tl, title, tw)
            text(cv, x, title, tx, x.h / 2 - 3 * u, tl)
            let sl = tp(x, x.plexB, 11, goldT), st2 = x.style.has("streak") && m.streak > 0 ? streakTxt(x, m.streak) : countTxt
            var lx = tx
            if x.style.has("streak") && m.streak > 0 { star8(cv, tx - x.dir * 5 * u, x.h / 2 + 11 * u, 5.5 * u, goldT); lx = tx - x.dir * 14 * u }
            fit(sl, st2, tw - 14 * u); text(cv, x, st2, lx, x.h / 2 + 15 * u, sl)
            let r = min(x.w * PR_CELL * 0.36, x.h * (names ? 0.2 : 0.27)), cy = names ? x.h * 0.41 : x.h / 2
            let lp = tp(x, x.plex, 10.5, x.st.fg); fit(lp, x.d.name("maghrib"), x.w * PR_CELL * 0.98)
            let left = x.rtl ? x.w * PR_PAD : x.w * PR_TITLE
            for i in 0...4 {
                let cell = x.rtl ? 4 - i : i, cx = left + x.w * PR_CELL * (CGFloat(cell) + 0.5), st = state(i)
                circle(cx, cy, r, st)
                if names { lp.typeface = st == 1 ? x.plexB : x.plex; lp.color = st == 0 ? a(0.65, x.st.fg) : x.st.fg
                    center(cv, x.d.name(KData.PRAY[i]), cx, cy + r + lp.textSize * 1.25, lp) }
            }
        } else {
            let s = min(x.w, x.h), k = s / 150, cx = x.w / 2, cy = x.h / 2, R = s * 0.34, sw = s * 0.066
            let oval = RectF(cx - R, cy - R, cx + R, cy + R), gap: CGFloat = 9, sweep = 72 - gap
            for i in 0...4 {
                let st = state(i)
                // Fajr at the top, round counter-clockwise in Arabic (time runs right to left), clockwise in English
                let start = x.rtl ? -90 - gap / 2 - CGFloat(i) * 72 : -90 + gap / 2 + CGFloat(i) * 72
                let col = st == 2 ? doneC : st == 1 ? (x.st.light ? c("#0B4D3B") : x.st.fg) : st == 3 ? a(0.4, x.st.fg) : x.st.line
                cv.drawArc(oval, start, x.rtl ? -sweep : sweep, false, ap(paint(col)) { $0.style = .STROKE; $0.strokeCap = .ROUND; $0.strokeWidth = st == 0 || st == 3 ? sw * 0.72 : sw })
            }
            var lines: [(String, APaint)] = []
            if x.style.has("now"), let cu = cur, KData.PRAY.contains(cu) { lines.append((x.d.name(cu) + (x.d.ar ? " الآن" : " now"), ap(tp(x, x.plex, 11, x.st.sub)) { $0.textSize = 11 * k })) }
            if x.style.has("streak") && m.streak > 0 { lines.append((streakTxt(x, m.streak), ap(tp(x, x.plexB, 10.5, goldT)) { $0.textSize = 10.5 * k })) }
            var y = cy + (10 - 7 * CGFloat(lines.count)) * k
            pair(cv, x, x.d.digits("\(n)"), (x.d.ar ? " من " : " of ") + x.d.digits("5"), cx, y, ap(tp(x, x.plexB, 30, x.st.fg)) { $0.textSize = 30 * k }, ap(tp(x, x.plex, 15, x.st.sub)) { $0.textSize = 15 * k })
            for (t, p) in lines { y += 15 * k; fit(p, t, R * 1.5); center(cv, t, cx, y, p) }
        }
    }

    // ---------- 14. the last third of the night (2×2 and 4×1) ----------
    static func nightW(_ cv: ACanvas, _ x: X) {
        let sky = x.st.kind == "sky"
        if sky { nightCard(cv, x) } else { card(cv, x) }
        guard let nt = x.d.night(x.now) else { return }; let (m, f) = nt
        let third = m + (f - m) * 2 / 3, mid = m + (f - m) / 2, inThird = x.now >= third && x.now < f
        let fg = x.st.fg, sub = x.st.sub, gold = x.st.light ? GOLD_L : GOLD
        let track = x.st.light ? a(0.14, c("#0B4D3B")) : a(0.2, AColor.WHITE)
        let cnt = inThird ? (x.d.ar ? "الآن · بقي " + x.d.dur(f - x.now) + " للفجر" : "Now · " + x.d.dur(f - x.now) + " to Fajr") : x.d.inTxt(third - x.now)
        if isWide(x) {
            let u = x.u, pad = 16 * u, l = pad, r = x.w - pad, bw = r - l
            func px(_ t: Int) -> CGFloat { let q = (CGFloat(t - m) / CGFloat(max(1, f - m))).coerceIn(0, 1); return x.rtl ? r - bw * q : l + bw * q }
            let tl = tp(x, x.nameTf, 15, fg), title = x.d.ar ? "الثلث الأخير" : "Last third"
            text(cv, x, title, startX(x, pad), 27 * u, tl)
            text(cv, x, clk(x, third), startX(x, pad) - x.dir * (tl.measureText(title) + 8 * u), 27 * u, tp(x, x.plexB, 14, fg))
            if x.style.has("count") { let cp = tp(x, x.plex, 11.5, inThird ? gold : sub); fit(cp, cnt, bw * 0.45); text(cv, x, cnt, endX(x, pad), 26 * u, cp, false) }
            let bt = 41 * u, bh = 8 * u
            cv.drawRoundRect(RectF(l, bt, r, bt + bh), bh / 2, bh / 2, paint(track))
            let g0 = px(third), g1 = px(f)
            cv.drawRoundRect(RectF(min(g0, g1), bt, max(g0, g1), bt + bh), bh / 2, bh / 2, paint(gold))
            if x.style.has("mid") { let mx = px(mid); cv.drawRect(mx - u, bt - 5 * u, mx + u, bt + bh + 5 * u, paint(a(0.6, fg))) }
            if x.now >= m && x.now <= f { let nx = px(x.now); cv.drawCircle(nx, bt + bh / 2, 9.5 * u, paint(a(0.25, fg))); cv.drawCircle(nx, bt + bh / 2, 7 * u, paint(x.st.light ? c("#0B4D3B") : AColor.WHITE)) }
            let lp = tp(x, x.plex, 10, sub), ly = bt + bh + 19 * u
            text(cv, x, x.d.name("maghrib") + " " + x.d.clock(m), startX(x, pad), ly, lp)
            text(cv, x, x.d.name("fajr") + " " + x.d.clock(f), endX(x, pad), ly, lp, false)
            if x.style.has("mid") { center(cv, (x.d.ar ? "منتصف الليل " : "Midnight ") + x.d.clock(mid), px(mid), ly, lp) }
        } else {
            let s = min(x.w, x.h), k = s / 150, pad = 14 * k
            if x.style.has("sky") { nightStars(cv, x, 0, 0, x.w, x.h * 0.5); moon(cv, x, endX(x, pad) + x.dir * 15 * k, pad + 15 * k, 15 * k) }
            if x.style.has("mid") { text(cv, x, (x.d.ar ? "منتصف الليل " : "Midnight ") + x.d.clock(mid), startX(x, pad), pad + 11 * k, ap(tp(x, x.plex, 10.5, sub)) { $0.textSize = 10.5 * k }) }
            let bottom = x.h - pad, segH = 4 * k, gap = 3 * k, sw3 = (x.w - 2 * pad - 2 * gap) / 3
            for i in 0...2 { let col = x.rtl ? i : 2 - i, l0 = x.w - pad - CGFloat(col + 1) * sw3 - CGFloat(col) * gap
                cv.drawRoundRect(RectF(l0, bottom - segH, l0 + sw3, bottom), segH / 2, segH / 2, paint(i == 2 ? gold : track)) }
            if x.now >= m && x.now <= f { let q = CGFloat(x.now - m) / CGFloat(f - m), nx = x.rtl ? x.w - pad - q * (x.w - 2 * pad) : pad + q * (x.w - 2 * pad)
                cv.drawCircle(nx, bottom - segH / 2, 3.6 * k, paint(x.st.light ? c("#0B4D3B") : AColor.WHITE)) }
            var y = bottom - segH - 7 * k
            if x.style.has("count") { let cp = ap(tp(x, x.plexB, 11.5, x.st.light ? c("#0B4D3B") : fg, false)) { $0.textSize = 11.5 * k }; fit(cp, cnt, x.w - 2 * pad - 2 * cp.textSize)
                let pw = pillW(cp, cnt), ph = cp.textSize * 1.55
                pill(cv, x, cnt, startX(x, pad) - x.dir * pw / 2, y - ph / 2, cp, x.st.light ? c("#E3ECE6") : a(0.14, AColor.WHITE)); y -= ph + 9 * k }
            let tb = ap(tp(x, x.plexB, 30, fg)) { $0.textSize = 30 * k }, tm = x.d.clock(third), am = x.d.ampm(third)
            text(cv, x, tm, startX(x, pad), y, tb)
            if !am.isEmpty { text(cv, x, am, startX(x, pad) - x.dir * (tb.measureText(tm) + 5 * k), y, ap(tp(x, x.plex, 14, fg)) { $0.textSize = 14 * k }) }
            y -= tb.textSize + 4 * k
            let lt = ap(tp(x, x.nameTf, 13, sub)) { $0.textSize = 13 * k }, title = x.d.ar ? "الثلث الأخير من الليل" : "Last third of the night"; fit(lt, title, x.w - 2 * pad)
            text(cv, x, title, startX(x, pad), y, lt)
        }
    }

    // ---------- 15. Ramadan (4×2 and 2×2): iftar before maghrib, imsak after it; outside Ramadan, how far it is ----------
    static func ramadan(_ cv: ACanvas, _ x: X) {
        card(cv, x)
        let d = x.d, now = x.now, today = x.day, tom = d.day(now, 1)
        let ramT = d.hmn(today) == 9, ramN = tom != nil && d.hmn(tom!) == 9, ims = d.imsak * 60_000
        let fj = today.optLong("fajr"), mg = today.optLong("maghrib")
        let ar = d.ar, gold = x.st.light ? GOLD_L : GOLD, big = x.st.light ? c("#0B4D3B") : gold
        // what to count down to
        var state = "wait", target = 0, dayNo = 0, nextIms = fj - ims, nextFajr = fj
        if ramT && now < fj - ims { state = "suhoor"; target = fj - ims; dayNo = d.hd(today) }
        else if ramT && now < fj { state = "imsak"; target = fj; dayNo = d.hd(today) }
        else if ramT && now < mg { state = "fast"; target = mg; dayNo = d.hd(today); nextIms = (tom?.optLong("fajr") ?? (fj + KData.DAY)) - ims; nextFajr = nextIms + ims }
        else if ramN, let tm = tom { state = "suhoor"; target = tm.optLong("fajr") - ims; dayNo = d.hd(tm); nextIms = target; nextFajr = tm.optLong("fajr") }
        else if (ramT && !ramN) || (d.hmn(today) == 10 && d.hd(today) <= 3) { state = "eid" }
        // outside Ramadan: days to the 1st (counted in the pushed days, else estimated from the Hijri date)
        var wait = d.daysTo(now, 9, 1), about = false
        if state == "wait" && wait < 0 { let hm = d.hmn(today), left = d.monthLen(now) - d.hd(today) + 1
            if (1...12).contains(hm) { wait = left + Int(Double(hm < 9 ? 9 - hm - 1 : 12 - hm + 8) * 29.53); about = true } }
        let y0 = today.optString("hy").filter { $0.isNumber }
        let yr = d.digits(state == "wait" && d.hmn(today) > 9 ? String((Int(y0) ?? -1) + 1) : y0)
        let nm = ar ? "رمضان" : "Ramadan"
        let label = state == "fast" ? (ar ? "الإفطار بعد" : "Iftar in") : state == "suhoor" ? (ar ? "الإمساك بعد" : "Imsak in") : state == "imsak" ? (ar ? "الفجر بعد" : "Fajr in") : nm
        let bigTxt = state == "eid" ? (ar ? "عيد مبارك" : "Eid Mubarak") : state == "wait" ? (wait >= 0 ? inDays(x, wait, about) : "") : d.dur(target - now)
        let atTxt: String
        switch state {
        case "fast": atTxt = d.name("maghrib") + " " + clk(x, mg)
        case "suhoor": atTxt = (ar ? "الإمساك " : "Imsak ") + clk(x, target)
        case "imsak": atTxt = d.name("fajr") + " " + clk(x, target)
        case "eid": atTxt = ar ? "تقبّل الله منا ومنكم" : "May Allah accept from us and you"
        default: atTxt = ar ? "اللهم بلّغنا رمضان" : "May we reach Ramadan"
        }
        let dayTxt = dayNo > 0 ? (ar ? (state == "suhoor" && now >= mg ? "سحور اليوم " : "اليوم ") + d.digits("\(dayNo)") : "Day " + d.digits("\(dayNo)")) : ""
        let len = d.monthLen(now), doneDays = state == "wait" || state == "eid" ? 0 : (dayNo - 1).coerceAtLeast(0)
        if isWide(x) {
            let u = x.u, pad = 16 * u
            text(cv, x, "\(nm) \(yr)".trimmingCharacters(in: .whitespaces), startX(x, pad), pad + 12 * u, tp(x, x.plex, 12, x.st.sub))
            let t2 = state == "eid" ? (ar ? "عيد الفطر" : "Eid al-Fitr") : !dayTxt.isEmpty ? dayTxt : (ar ? "شهر الصيام" : "The month of fasting")
            text(cv, x, t2, startX(x, pad), pad + 34 * u, tp(x, x.nameTf, 17, x.st.fg))
            crescent(cv, x, endX(x, pad) + x.dir * 20 * u, pad + 20 * u, 17 * u, gold)
            let prog = x.style.has("prog") && state != "wait" && state != "eid"
            let base = x.h - pad - (prog ? 16 * u : 0)
            text(cv, x, atTxt, startX(x, pad), base - 2 * u, tp(x, x.plex, 12, x.st.fg))
            let bp = tp(x, state == "eid" ? x.nameTf : x.plexB, 30, big); fit(bp, bigTxt, x.w * 0.6)
            text(cv, x, bigTxt, startX(x, pad), base - 20 * u, bp)
            if state != "eid" && state != "wait" { text(cv, x, label, startX(x, pad), base - 20 * u - bp.textSize - 2 * u, tp(x, x.plex, 12, x.st.sub)) }
            if ["fast", "suhoor", "imsak"].contains(state) {
                let rp = tp(x, x.plex, 11.5, x.st.sub); var ry = base - 2 * u
                if x.style.has("fajr") { text(cv, x, d.name("fajr") + " " + clk(x, nextFajr), endX(x, pad), ry, rp, false); ry -= 19 * u }
                // while counting down to imsak its time is already under the count
                if x.style.has("imsak") && state != "suhoor" { text(cv, x, (ar ? "الإمساك " : "Imsak ") + clk(x, nextIms), endX(x, pad), ry, rp, false) }
            }
            if prog { let gap = 2 * u, sh = 5 * u, sw0 = (x.w - 2 * pad - CGFloat(len - 1) * gap) / CGFloat(len), yy = x.h - pad - sh
                for i in 0..<len { let l0 = x.rtl ? x.w - pad - CGFloat(i + 1) * sw0 - CGFloat(i) * gap : pad + CGFloat(i) * (sw0 + gap)
                    cv.drawRoundRect(RectF(l0, yy, l0 + sw0, yy + sh), sh / 2, sh / 2, paint(i < doneDays ? gold : a(0.2, x.st.fg))) } }
        } else {
            let s = min(x.w, x.h), k = s / 150, pad = 14 * k
            if x.st.kind == "sky" && state != "fast" { nightStars(cv, x, 0, 0, x.w, x.h * 0.45) }
            crescent(cv, x, endX(x, pad) + x.dir * 13 * k, pad + 13 * k, 12 * k, gold)
            let title = state == "fast" ? (ar ? "الإفطار" : "Iftar") : state == "suhoor" ? (ar ? "السحور" : "Suhoor") : state == "imsak" ? (ar ? "الإمساك" : "Imsak") : state == "eid" ? (ar ? "العيد" : "Eid") : nm
            text(cv, x, title, startX(x, pad), pad + 13 * k, ap(tp(x, x.nameTf, 15, x.st.fg)) { $0.textSize = 15 * k })
            var y = x.h - pad
            if x.style.has("prog") && !["wait", "eid"].contains(state) { let bh = 3 * k; cv.drawRoundRect(RectF(pad, y - bh, x.w - pad, y), bh / 2, bh / 2, paint(a(0.2, x.st.fg)))
                let fw = (x.w - 2 * pad) * CGFloat(doneDays) / CGFloat(len), rr = x.rtl ? RectF(x.w - pad - fw, y - bh, x.w - pad, y) : RectF(pad, y - bh, pad + fw, y)
                if fw > 0 { cv.drawRoundRect(rr, bh / 2, bh / 2, paint(gold)) }; y -= bh + 7 * k }
            let l3 = dayNo > 0 ? (ar ? "اليوم " + d.digits("\(dayNo)") + " من رمضان" : "Day " + d.digits("\(dayNo)") + " of Ramadan") : state == "wait" ? "\(nm) \(yr)".trimmingCharacters(in: .whitespaces) : ""
            if !l3.isEmpty { text(cv, x, l3, startX(x, pad), y, ap(tp(x, x.plexB, 11, gold)) { $0.textSize = 11 * k }); y -= 18 * k }
            let bigLine = ["fast", "suhoor", "imsak"].contains(state) ? (ar ? "بعد " : "in ") + bigTxt : bigTxt
            let bp = ap(tp(x, state == "eid" ? x.nameTf : x.plexB, 26, x.st.fg)) { $0.textSize = 26 * k }; fit(bp, bigLine, x.w - 2 * pad)
            text(cv, x, bigLine, startX(x, pad), y, bp)
            y -= bp.textSize + 4 * k
            var l1 = atTxt
            if state == "suhoor" { let key = ar ? "الإمساك " : "Imsak "; let after = atTxt.range(of: key).map { String(atTxt[$0.upperBound...]) } ?? atTxt
                l1 = (ar ? "السحور · " : "Suhoor · ") + (ar ? "الإمساك " : "imsak ") + after }
            let p1 = ap(tp(x, x.plex, 11.5, x.st.sub)) { $0.textSize = 11.5 * k }; fit(p1, l1, x.w - 2 * pad)
            text(cv, x, l1, startX(x, pad), y, p1)
        }
    }

    // ---------- 16. the white days (4×2 and 2×2): the Hijri month with the 13th–15th lit like the full moon ----------
    static func white(_ cv: ACanvas, _ x: X) {
        card(cv, x)
        let d = x.d, ar = d.ar, h0 = d.hd(x.day).coerceAtLeast(1), len = d.monthLen(x.now), w0 = d.wdi(x.day)
        let wd1 = ((w0 - (h0 - 1)) % 7 + 7) % 7
        let toWhite = h0 < 13 ? 13 - h0 : h0 <= 15 ? 0 : len - h0 + 13
        let whiteWd = (w0 + toWhite) % 7
        let acc = x.st.light ? c("#0B4D3B") : x.st.acc, gold = x.st.light ? GOLD_L : GOLD
        let bigTxt = toWhite == 0 ? (ar ? "اليوم " + d.digits("\(h0)") : "Today, the " + d.digits("\(h0)") + "th") : inDays(x, toWhite)
        let wMonth = (d.day(x.now, toWhite) ?? x.day).optString("hm")
        let nums = d.digits("13") + " · " + d.digits("14") + " · " + d.digits("15")
        // Ashura and Arafah, when they are within ten days
        var special = ""
        if x.style.has("days") {
            let aa = d.daysTo(x.now, 1, 10, 10), r = d.daysTo(x.now, 12, 9, 10)
            let cands = [(aa, ar ? "عاشوراء" : "Ashura"), (r, ar ? "يوم عرفة" : "Arafah")].filter { $0.0 >= 0 }
            if let best = cands.min(by: { $0.0 < $1.0 }) { let (o, nm) = best
                special = "\(nm) " + (o == 0 ? (ar ? "اليوم" : "today") : o == 1 ? (ar ? "غداً" : "tomorrow") : inDays(x, o))
            }
        }
        if isWide(x) {
            let u = x.u, pad = 14 * u
            let gw = min(x.w * 0.5, 196 * u), gl = x.rtl ? pad : x.w - pad - gw, cell = gw / 7
            let head = 14 * u, rows = (wd1 + len + 6) / 7, rowH = (x.h - 2 * pad - head) / CGFloat(rows), dd = min(cell, rowH) * 0.9
            func colX(_ j: Int) -> CGFloat { x.rtl ? gl + gw - cell * (CGFloat(j) + 0.5) : gl + cell * (CGFloat(j) + 0.5) }
            let hp = tp(x, x.plex, 9, x.st.sub, false)
            let heads = ar ? WDS_AR : WDS_EN
            for j in 0...6 { center(cv, heads[j], colX(j), pad + 9 * u, hp) }
            let np = tp(x, x.plex, 10.5, x.st.fg, false); np.textSize = min(10.5 * u, dd * 0.5)
            for n in 1...max(1, len) {
                let idx = wd1 + n - 1, cx = colX(idx % 7), cy = pad + head + rowH * (CGFloat(idx / 7) + 0.5)
                let isW = (13...15).contains(n), today = n == h0
                if isW { cv.drawCircle(cx, cy, dd * 0.78, ap(paint()) { $0.shader = ARadial(cx, cy, dd * 0.78, [a(0.55, c("#FFF0C0")), 0], nil) })
                    cv.drawCircle(cx, cy, dd / 2, paint(c("#FFF6D8"))); if x.st.light { cv.drawCircle(cx, cy, dd / 2, ap(paint(a(0.7, GOLD_L))) { $0.style = .STROKE; $0.strokeWidth = u }) } }
                if today { cv.drawCircle(cx, cy, dd / 2, paint(acc)) }
                np.typeface = today || isW ? x.plexB : x.plex
                np.color = today ? (x.st.light ? c("#FFF8EA") : INK) : isW ? INK : n < h0 ? a(0.45, x.st.fg) : x.st.fg
                center(cv, d.digits("\(n)"), cx, cy + np.textSize * 0.36, np)
                if x.style.has("monthu") && (idx % 7 == 1 || idx % 7 == 4) { cv.drawCircle(cx, cy + dd * 0.44, 1.7 * u, paint(gold)) }
            }
            let tw = x.w - gw - 3 * pad, sx = startX(x, pad)
            let t1 = tp(x, x.nameTf, 15, x.st.fg), mt = (x.day.optString("hm") + " " + d.digits(x.day.optString("hy").filter { $0.isNumber })).trimmingCharacters(in: .whitespaces); fit(t1, mt, tw)
            text(cv, x, mt, sx, pad + 13 * u, t1)
            text(cv, x, (ar ? "اليوم " : "Today, ") + wdName(x, w0) + " " + d.digits("\(h0)"), sx, pad + 31 * u, tp(x, x.plex, 11.5, x.st.sub))
            let my = x.h * 0.5
            text(cv, x, ar ? "الأيام البيض" : "The white days", sx, my, tp(x, x.plex, 11.5, x.st.sub))
            let bp = tp(x, x.plexB, 20, x.st.fg); fit(bp, bigTxt, tw); text(cv, x, bigTxt, sx, my + 24 * u, bp)
            text(cv, x, nums, sx, my + 41 * u, tp(x, x.plex, 11, x.st.sub))
            let by = x.h - pad - 2 * u
            if !special.isEmpty { let sp = tp(x, x.plexB, 11, gold); fit(sp, special, tw); text(cv, x, special, sx, x.style.has("monthu") ? by - 16 * u : by, sp) }
            if x.style.has("monthu") { cv.drawCircle(sx - x.dir * 2.5 * u, by - 3.5 * u, 2.2 * u, paint(gold))
                text(cv, x, ar ? "الاثنين والخميس" : "Mondays and Thursdays", sx - x.dir * 9 * u, by, tp(x, x.plex, 10, x.st.sub)) }
        } else {
            let s = min(x.w, x.h), k = s / 150, pad = 14 * k
            let mx = endX(x, pad) + x.dir * 17 * k, my = pad + 17 * k
            cv.drawCircle(mx, my, 30 * k, ap(paint()) { $0.shader = ARadial(mx, my, 30 * k, [a(0.5, c("#FFF0C0")), 0], nil) })
            cv.drawCircle(mx, my, 15 * k, paint(c("#FFF6D8")))
            text(cv, x, ar ? "الأيام البيض" : "White days", startX(x, pad), pad + 13 * k, ap(tp(x, x.nameTf, 15, x.st.fg)) { $0.textSize = 15 * k })
            if !special.isEmpty { let sp = ap(tp(x, x.plexB, 10.5, gold)) { $0.textSize = 10.5 * k }; fit(sp, special, x.w - 2 * pad - 34 * k); text(cv, x, special, startX(x, pad), pad + 30 * k, sp) }
            var y = x.h - pad
            let l3 = "\(nums) \(wMonth)".trimmingCharacters(in: .whitespaces), p3 = ap(tp(x, x.plex, 11, x.st.sub)) { $0.textSize = 11 * k }; fit(p3, l3, x.w - 2 * pad)
            text(cv, x, l3, startX(x, pad), y, p3); y -= 17 * k
            if toWhite > 0 { text(cv, x, (ar ? "تبدأ " : "From ") + wdName(x, whiteWd), startX(x, pad), y, ap(tp(x, x.plex, 11.5, x.st.sub)) { $0.textSize = 11.5 * k }); y -= 18 * k }
            let bp = ap(tp(x, x.plexB, 22, x.st.fg)) { $0.textSize = 22 * k }; fit(bp, bigTxt, x.w - 2 * pad); text(cv, x, bigTxt, startX(x, pad), y, bp)
        }
    }

    // ---------- the iPhone's own two (0.32): the day and the date, and the next alarm, in the gallery with the others ----------
    /// The day and both dates, the day in the name's font (stretched like a name), the Hijri date under it, the Gregorian last.
    static func dateW(_ cv: ACanvas, _ x: X) {
        card(cv, x); let dd = x.day, ar = x.d.ar
        let hij = x.d.digits(dd.optString("hd") + " " + dd.optString("hm") + " " + dd.optString("hy")) + (ar ? "هـ" : " AH")
        let g0 = dd.optString("gr").isEmpty ? dd.optString("d") : dd.optString("gr"), greg = x.d.digits(g0) + (ar && !g0.isEmpty ? "م" : "")
        let wide = isWide(x), s = min(x.w, x.h), k = s / 150, pad = max(14 * k, kSafe(x)), room = x.w - 2 * pad
        var ls: [(String, APaint, Bool)] = []   // text, paint, stretched
        if x.style.has("wd") { ls.append((dd.optString("wd"), ap(tp(x, x.nameTf, 1, x.st.fg)) { $0.textSize = wide ? x.h * 0.36 : 38 * k }, true)) }
        if x.style.has("hijri") { ls.append((hij, ap(tp(x, x.nameTf, 1, x.st.light ? c("#0B4D3B") : x.st.acc)) { $0.textSize = wide ? x.h * 0.17 : 17 * k }, false)) }
        if x.style.has("greg") && !g0.isEmpty { ls.append((greg, ap(tp(x, x.plex, 1, x.st.sub)) { $0.textSize = wide ? x.h * 0.12 : 12.5 * k }, false)) }
        if ls.isEmpty { return }
        for l in ls { fit(l.1, l.0, room) }
        let gap: CGFloat = 0.28, tot = ls.reduce(CGFloat(0)) { $0 + $1.1.textSize } + CGFloat(ls.count - 1) * ls[0].1.textSize * gap
        var y = (x.h - tot) / 2
        for (i, l) in ls.enumerated() {
            y += l.1.textSize * (i == 0 ? 0.82 : 1)
            let t = l.2 ? kashName(x, l.1, l.0, room - 2 * kSafe(x)) : l.0
            center(cv, t, x.w / 2, y, l.1)
            y += l.1.textSize * (i == 0 ? 0.18 + gap : gap * 0.6)
        }
    }

    /// The next alarm, as the app's alarm widget on Android (AlarmWidget.kt): the night navy, its time large in Kufi and gold,
    /// what it is for, and when. Given before drawing: the next alarm, and how many are on.
    struct AlarmInfo { var at = 0, title = "", snoozed = false, count = 0, any = false }
    static var alarm = AlarmInfo()
    static func alarmW(_ cv: ACanvas, _ x: X) {
        let w = x.w, h = x.h, r = square ? 0 : x.dp(22), u = min(w, h) / 150, ar = x.d.ar
        let gold = c("#E9CD8A"), cream = c("#F3E6C4")
        cv.drawRoundRect(RectF(0, 0, w, h), r, r, ap(paint()) { $0.shader = ALinear(0, 0, w * 0.4, h, c("#1B2C52"), c("#0B1630")) })
        cv.drawRoundRect(RectF(0, 0, w, h), r, r, ap(paint()) { $0.shader = ARadial(w * 0.5, h * 0.2, w * 0.7, [AColor.argb(36, 233, 205, 138), 0], nil) })
        let st: [CGFloat] = [0.12, 0.2, 0.3, 0.1, 0.52, 0.16, 0.7, 0.28, 0.86, 0.14, 0.62, 0.08]
        for i in stride(from: 0, to: st.count, by: 2) { cv.drawCircle(w * (ar ? st[i] : 1 - st[i]), h * st[i + 1], 1.1 * u, paint(AColor.argb(i % 4 == 0 ? 120 : 70, 243, 230, 196))) }
        let pad = 14 * u
        // the alarm mark: a gold ring with a bell-clock, on the side away from the text
        let ic = min(h * 0.34, 30 * u), icx = ar ? pad + ic / 2 : w - pad - ic / 2, icy = pad + ic / 2
        let sp = ap(paint(gold)) { $0.style = .STROKE; $0.strokeWidth = 1.6 * u; $0.strokeCap = .ROUND }
        cv.drawCircle(icx, icy + ic * 0.06, ic * 0.36, sp)
        cv.drawLine(icx, icy + ic * 0.06, icx, icy - ic * 0.16, sp); cv.drawLine(icx, icy + ic * 0.06, icx + ic * 0.14, icy + ic * 0.14, sp)
        cv.drawLine(icx - ic * 0.42, icy - ic * 0.3, icx - ic * 0.24, icy - ic * 0.44, sp); cv.drawLine(icx + ic * 0.42, icy - ic * 0.3, icx + ic * 0.24, icy - ic * 0.44, sp)
        let sx = ar ? w - pad : pad, textW = w - pad * 3 - ic
        let a = alarm
        if a.at <= x.now {
            let p1 = ap(tp(x, x.f.plexB, 1, cream, false)) { $0.textSize = min(15 * u, h * 0.2) }, t1 = !a.any ? (ar ? "لا منبّهات بعد" : "No alarms yet") : (ar ? "لا منبّه مفعّل" : "No alarm on")
            fit(p1, t1, textW); text(cv, x, t1, sx, h / 2 - 2 * u, p1)
            let p2 = ap(tp(x, x.f.plex, 1, AColor.argb(170, 243, 230, 196), false)) { $0.textSize = min(11.5 * u, h * 0.15) }, t2 = ar ? "اضغط لإضافة منبّه" : "Tap to add one"
            fit(p2, t2, textW); text(cv, x, t2, sx, h / 2 + 16 * u, p2)
            return
        }
        let time = x.d.clock(a.at), mer = x.d.ampm(a.at)
        let dk = KData.dateKey(a.at), today = KData.dateKey(x.now), tom = KData.dateKey(x.now + KData.DAY)
        let dayWord = dk == today ? (ar ? "اليوم" : "Today") : dk == tom ? (ar ? "غداً" : "Tomorrow") : wdName(x, KData.cal().component(.weekday, from: KData.date(a.at)) - 1)
        let n = a.count, many = n < 2 ? "" : !ar ? " · \(n) alarms on" : n == 2 ? " · منبّهان مفعّلان" : " · " + x.d.digits(String(n)) + (n <= 10 ? " منبّهات مفعّلة" : " منبّهاً مفعّلاً")
        let sub = (a.snoozed ? (ar ? "غفوة · " : "Snoozed · ") : "") + dayWord + many
        let tpaint = ap(tp(x, x.f.name("kufi"), 1, gold, false)) { $0.textSize = min(h * 0.36, w * 0.22, 46 * u) }
        let ty = pad + tpaint.textSize * 0.86 + (isWide(x) ? 0 : ic * 0.25)
        text(cv, x, time, sx, ty, tpaint)
        if !mer.isEmpty { text(cv, x, mer, sx - x.dir * (tpaint.measureText(time) + 5 * u), ty, ap(tp(x, x.f.plexB, 1, gold, false)) { $0.textSize = 12 * u }) }
        let p2 = ap(tp(x, x.f.plexB, 1, cream, false)) { $0.textSize = min(13 * u, h * 0.14) }, y2 = ty + p2.textSize * 1.55
        fit(p2, a.title, w - pad * 2); text(cv, x, a.title, sx, y2, p2)
        let p3 = ap(tp(x, x.f.plex, 1, AColor.argb(175, 243, 230, 196), false)) { $0.textSize = min(11.5 * u, h * 0.12) }
        fit(p3, sub, w - pad * 2); text(cv, x, sub, sx, y2 + p3.textSize * 1.55, p3)
    }
}
