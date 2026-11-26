import Foundation
import CoreGraphics
import CoreText

// The small part of Android's drawing API that the widget art uses (Canvas, Paint, Path, the gradients), over CoreGraphics
// and CoreText, so the art itself (KArt.swift) reads line for line like WidgetArt.kt and the iPhone widgets come out as the
// Android ones do. Coordinates are Android's: pixels, y down, angles in degrees clockwise from 3 o'clock.
// No UIKit here: the same files build for macOS, where CI draws every widget to a picture for review.

typealias AInt = Int   // a colour as Android has it: 0xAARRGGBB

enum Color {
    static let WHITE: AInt = 0xFFFFFFFF
    static let BLACK: AInt = 0xFF000000
    static let TRANSPARENT: AInt = 0
    static func alpha(_ c: AInt) -> Int { (c >> 24) & 0xFF }
    static func red(_ c: AInt) -> Int { (c >> 16) & 0xFF }
    static func green(_ c: AInt) -> Int { (c >> 8) & 0xFF }
    static func blue(_ c: AInt) -> Int { c & 0xFF }
    static func argb(_ a: Int, _ r: Int, _ g: Int, _ b: Int) -> AInt { (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF) }
    static func rgb(_ r: Int, _ g: Int, _ b: Int) -> AInt { argb(255, r, g, b) }
    static func parseColor(_ s: String) -> AInt {
        var h = s.hasPrefix("#") ? String(s.dropFirst()) : s
        if h.count == 6 { h = "FF" + h }
        return AInt(UInt32(h, radix: 16) ?? 0)
    }
    static func cg(_ c: AInt) -> CGColor {
        CGColor(srgbRed: CGFloat(red(c)) / 255, green: CGFloat(green(c)) / 255, blue: CGFloat(blue(c)) / 255, alpha: CGFloat(alpha(c)) / 255)
    }
}

struct RectF {
    var left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat
    init(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat) { left = l; top = t; right = r; bottom = b }
    func width() -> CGFloat { right - left }
    func height() -> CGFloat { bottom - top }
    func centerX() -> CGFloat { (left + right) / 2 }
    func centerY() -> CGFloat { (top + bottom) / 2 }
    var cg: CGRect { CGRect(x: min(left, right), y: min(top, bottom), width: abs(right - left), height: abs(bottom - top)) }
}
/// Android's whole-pixel text bounds: top is negative above the baseline.
struct Rect { var left = 0, top = 0, right = 0, bottom = 0
    func width() -> Int { right - left }
    func height() -> Int { bottom - top }
}

/// A typeface: a font file (and a weight on the variable Kufi), made at any size.
final class Typeface {
    let file: String, wght: CGFloat?
    private var cache: [CGFloat: CTFont] = [:]
    init(_ file: String, wght: CGFloat? = nil) { self.file = file; self.wght = wght }
    private static var descs: [String: CTFontDescriptor] = [:]
    private static let lock = NSLock()
    /// Where the .ttf files are: the bundle (root or wfonts/), or a folder given (CI).
    static var dir: URL? = nil
    private static func base(_ file: String) -> CTFontDescriptor? {
        lock.lock(); defer { lock.unlock() }
        if let d = descs[file] { return d }
        let url = dir.map { $0.appendingPathComponent(file + ".ttf") }
            ?? Bundle.main.url(forResource: file, withExtension: "ttf")
            ?? Bundle.main.url(forResource: file, withExtension: "ttf", subdirectory: "wfonts")
        guard let u = url, let data = try? Data(contentsOf: u) as CFData,
              let d = CTFontManagerCreateFontDescriptorFromData(data) else { return nil }
        descs[file] = d; return d
    }
    func font(_ size: CGFloat) -> CTFont {
        if let f = cache[size] { return f }
        var f: CTFont
        if let d = Typeface.base(file) {
            var desc = d
            if let w = wght { desc = CTFontDescriptorCreateCopyWithAttributes(d, [kCTFontVariationAttribute: [0x77676874: w]] as CFDictionary) }
            f = CTFontCreateWithFontDescriptor(desc, size, nil)
        } else { f = CTFontCreateWithName("Helvetica" as CFString, size, nil) }
        if cache.count > 40 { cache.removeAll() }
        cache[size] = f; return f
    }
}

enum Shader {
    case linear(CGFloat, CGFloat, CGFloat, CGFloat, [AInt], [CGFloat]?)
    case radial(CGFloat, CGFloat, CGFloat, [AInt], [CGFloat]?)
    case sweep(CGFloat, CGFloat, [AInt], [CGFloat]?, CGFloat, Bool)   // its rotation in degrees, then mirrored (Android's local matrix)
}
func LinearGradient(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ c0: AInt, _ c1: AInt) -> Shader { .linear(x0, y0, x1, y1, [c0, c1], nil) }
func LinearGradient(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ cs: [AInt], _ ps: [CGFloat]?) -> Shader { .linear(x0, y0, x1, y1, cs, ps) }
func RadialGradient(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ cs: [AInt], _ ps: [CGFloat]?) -> Shader { .radial(cx, cy, r, cs, ps) }
func RadialGradient(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ c0: AInt, _ c1: AInt) -> Shader { .radial(cx, cy, r, [c0, c1], nil) }
func SweepGradient(_ cx: CGFloat, _ cy: CGFloat, _ cs: [AInt], _ ps: [CGFloat]?, rotate: CGFloat = 0, mirror: Bool = false) -> Shader { .sweep(cx, cy, cs, ps, rotate, mirror) }

final class Paint {
    enum Style { case FILL, STROKE, FILL_AND_STROKE }
    enum Align { case LEFT, CENTER, RIGHT }
    enum Cap { case BUTT, ROUND, SQUARE }
    enum Join { case MITER, ROUND, BEVEL }
    var color: AInt = Color.BLACK
    var style: Style = .FILL
    var strokeWidth: CGFloat = 0
    var strokeCap: Cap = .BUTT
    var strokeJoin: Join = .MITER
    var textAlign: Align = .LEFT
    var textSize: CGFloat = 12
    var typeface: Typeface? = nil
    var shader: Shader? = nil
    var dash: [CGFloat]? = nil   // DashPathEffect(intervals, 0)
    var shadow: (r: CGFloat, dx: CGFloat, dy: CGFloat, c: AInt)? = nil
    /// Ruqaa and Naskh have nothing past Bold: "bold" thickens their strokes (NPaint in WidgetArt.kt).
    var heavy = false
    var alpha: Int { get { Color.alpha(color) } set { color = (color & 0xFFFFFF) | (max(0, min(255, newValue)) << 24) } }
    init(_ color: AInt = Color.BLACK) { self.color = color }
    init(copy p: Paint) {
        color = p.color; style = p.style; strokeWidth = p.strokeWidth; strokeCap = p.strokeCap; strokeJoin = p.strokeJoin
        textAlign = p.textAlign; textSize = p.textSize; typeface = p.typeface; shader = p.shader; dash = p.dash; shadow = p.shadow; heavy = p.heavy
    }
    func setShadowLayer(_ r: CGFloat, _ dx: CGFloat, _ dy: CGFloat, _ c: AInt) { shadow = (r, dx, dy, c) }
    func clearShadowLayer() { shadow = nil }

    var font: CTFont { (typeface ?? Typeface("plex400")).font(textSize) }
    func line(_ s: String) -> CTLine {
        let a = NSAttributedString(string: s, attributes: [kCTFontAttributeName as NSAttributedString.Key: font,
                                                           kCTForegroundColorFromContextAttributeName as NSAttributedString.Key: true])
        return CTLineCreateWithAttributedString(a)
    }
    func measureText(_ s: String) -> CGFloat { s.isEmpty ? 0 : CGFloat(CTLineGetTypographicBounds(line(s), nil, nil, nil)) }
    func getTextBounds(_ s: String, _ start: Int, _ end: Int, _ r: inout Rect) {
        let b = CTLineGetBoundsWithOptions(line(s), .useGlyphPathBounds)
        if b.isNull || b.isEmpty { r = Rect(); return }
        r = Rect(left: Int(floor(b.minX)), top: Int(floor(-b.maxY)), right: Int(ceil(b.maxX)), bottom: Int(ceil(-b.minY)))
    }
    var ascent: CGFloat { CTFontGetAscent(font) }
    var descent: CGFloat { CTFontGetDescent(font) }
}

final class Path {
    enum Direction { case CW, CCW }
    enum Op { case DIFFERENCE, UNION, INTERSECT }
    var cg = CGMutablePath()
    init() {}
    func moveTo(_ x: CGFloat, _ y: CGFloat) { cg.move(to: CGPoint(x: x, y: y)) }
    func lineTo(_ x: CGFloat, _ y: CGFloat) { if cg.isEmpty { moveTo(x, y) } else { cg.addLine(to: CGPoint(x: x, y: y)) } }
    func quadTo(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat) { cg.addQuadCurve(to: CGPoint(x: x2, y: y2), control: CGPoint(x: x1, y: y1)) }
    func cubicTo(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, _ x3: CGFloat, _ y3: CGFloat) {
        cg.addCurve(to: CGPoint(x: x3, y: y3), control1: CGPoint(x: x1, y: y1), control2: CGPoint(x: x2, y: y2))
    }
    func close() { if !cg.isEmpty { cg.closeSubpath() } }
    private func arc(_ o: RectF, _ start: CGFloat, _ sweep: CGFloat, move: Bool) {
        let rx = o.width() / 2, ry = o.height() / 2, cx = o.centerX(), cy = o.centerY(), a = start * .pi / 180
        let p = CGPoint(x: cx + rx * cos(a), y: cy + ry * sin(a))
        if move || cg.isEmpty { cg.move(to: p) } else { cg.addLine(to: p) }
        // the unit circle stretched to the oval: y down, so a growing angle turns clockwise as on Android
        cg.addRelativeArc(center: .zero, radius: 1, startAngle: a, delta: sweep * .pi / 180,
                          transform: CGAffineTransform(a: rx, b: 0, c: 0, d: ry, tx: cx, ty: cy))
    }
    func addArc(_ o: RectF, _ start: CGFloat, _ sweep: CGFloat) { arc(o, start, sweep, move: true) }
    func arcTo(_ o: RectF, _ start: CGFloat, _ sweep: CGFloat, _ forceMoveTo: Bool = false) { arc(o, start, sweep, move: forceMoveTo) }
    func addOval(_ o: RectF, _ d: Direction = .CW) { cg.addEllipse(in: o.cg) }
    func addCircle(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat, _ d: Direction = .CW) { cg.addEllipse(in: CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)) }
    func addRect(_ o: RectF, _ d: Direction = .CW) { cg.addRect(o.cg) }
    func addRoundRect(_ o: RectF, _ rx: CGFloat, _ ry: CGFloat, _ d: Direction = .CW) {
        let r = o.cg; cg.addRoundedRect(in: r, cornerWidth: min(rx, r.width / 2), cornerHeight: min(ry, r.height / 2))
    }
    func addPath(_ p: Path) { cg.addPath(p.cg) }
    @discardableResult func op(_ other: Path, _ op: Op) -> Bool {
        let r: CGPath
        switch op {
        case .DIFFERENCE: r = cg.subtracting(other.cg, using: .winding)
        case .UNION: r = cg.union(other.cg, using: .winding)
        case .INTERSECT: r = cg.intersection(other.cg, using: .winding)
        }
        cg = r.mutableCopy() ?? CGMutablePath(); return true
    }
}

/// A canvas over a bitmap, y down. `image` gives what was drawn.
final class Canvas {
    let ctx: CGContext, w: Int, h: Int
    private var layers: [Bool] = []   // per save(): whether it opened a transparency layer
    init?(_ w: Int, _ h: Int) {
        self.w = max(1, w); self.h = max(1, h)
        guard let c = CGContext(data: nil, width: self.w, height: self.h, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx = c
        c.translateBy(x: 0, y: CGFloat(self.h)); c.scaleBy(x: 1, y: -1)
        c.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        c.setShouldAntialias(true); c.setAllowsAntialiasing(true)
        c.setShouldSmoothFonts(false)
    }
    var image: CGImage? { ctx.makeImage() }

    @discardableResult func save() -> Int { ctx.saveGState(); layers.append(false); return layers.count }
    @discardableResult func saveLayerAlpha(_ r: RectF?, _ alpha: Int) -> Int {
        ctx.saveGState(); ctx.setAlpha(CGFloat(alpha) / 255); ctx.beginTransparencyLayer(auxiliaryInfo: nil); layers.append(true); return layers.count
    }
    @discardableResult func saveLayerAlpha(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat, _ alpha: Int) -> Int { saveLayerAlpha(RectF(l, t, r, b), alpha) }
    func restore() { guard let l = layers.popLast() else { return }; if l { ctx.endTransparencyLayer() }; ctx.restoreGState() }
    func restoreToCount(_ n: Int) { while layers.count >= n && !layers.isEmpty { restore() } }
    func clipPath(_ p: Path) { ctx.addPath(p.cg); ctx.clip() }
    func clipRect(_ r: RectF) { ctx.clip(to: r.cg) }
    func clipRect(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat) { clipRect(RectF(l, t, r, b)) }

    private func shadowOn(_ p: Paint) {
        if let s = p.shadow { ctx.setShadow(offset: CGSize(width: s.dx, height: -s.dy), blur: s.r * 1.15, color: Color.cg(s.c)) }
    }
    private func strokeSet(_ p: Paint) {
        ctx.setLineWidth(max(p.strokeWidth, 0.0001))
        ctx.setLineCap(p.strokeCap == .ROUND ? .round : p.strokeCap == .SQUARE ? .square : .butt)
        ctx.setLineJoin(p.strokeJoin == .ROUND ? .round : p.strokeJoin == .BEVEL ? .bevel : .miter)
        if let d = p.dash { ctx.setLineDash(phase: 0, lengths: d) } else { ctx.setLineDash(phase: 0, lengths: []) }
    }
    private func gradient(_ cs: [AInt], _ ps: [CGFloat]?) -> CGGradient? {
        let locs: [CGFloat] = ps ?? (cs.count > 1 ? (0..<cs.count).map { CGFloat($0) / CGFloat(cs.count - 1) } : [0])
        return CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB)!, colors: cs.map(Color.cg) as CFArray, locations: locs)
    }
    /// Fills the current clip with the paint's shader (its alpha as the paint's).
    private func fillShader(_ s: Shader, _ p: Paint) {
        ctx.setAlpha(CGFloat(p.alpha) / 255)
        let opts: CGGradientDrawingOptions = [.drawsBeforeStartLocation, .drawsAfterEndLocation]
        switch s {
        case let .linear(x0, y0, x1, y1, cs, ps):
            if let g = gradient(cs, ps) { ctx.drawLinearGradient(g, start: CGPoint(x: x0, y: y0), end: CGPoint(x: x1, y: y1), options: opts) }
        case let .radial(cx, cy, r, cs, ps):
            if let g = gradient(cs, ps) { ctx.drawRadialGradient(g, startCenter: CGPoint(x: cx, y: cy), startRadius: 0, endCenter: CGPoint(x: cx, y: cy), endRadius: r, options: opts) }
        case let .sweep(cx, cy, cs, ps, rot, mirror):
            if mirror { ctx.translateBy(x: cx, y: cy); ctx.scaleBy(x: -1, y: 1); ctx.translateBy(x: -cx, y: -cy) }
            // CoreGraphics has no sweep: thin wedges round the centre, each in the colour of its angle. Android sweeps clockwise
            // from 3 o'clock with y down, and so does a growing angle here (y down too); then its rotation
            let stops = ps ?? (cs.count > 1 ? (0..<cs.count).map { CGFloat($0) / CGFloat(cs.count - 1) } : [0])
            func colorAt(_ f: CGFloat) -> AInt {
                guard cs.count > 1 else { return cs.first ?? 0 }
                if f <= stops[0] { return cs[0] }
                for i in 1..<cs.count where f <= stops[i] {
                    let k = (f - stops[i - 1]) / max(0.0001, stops[i] - stops[i - 1]), x = cs[i - 1], y = cs[i]
                    func m(_ a: Int, _ b: Int) -> Int { Int(CGFloat(a) + CGFloat(b - a) * k) }
                    return Color.argb(m(Color.alpha(x), Color.alpha(y)), m(Color.red(x), Color.red(y)), m(Color.green(x), Color.green(y)), m(Color.blue(x), Color.blue(y)))
                }
                return cs[cs.count - 1]
            }
            let n = 360, big = CGFloat(w + h) * 2, r0 = rot * .pi / 180
            for i in 0..<n {
                let a0 = r0 + 2 * .pi * CGFloat(i) / CGFloat(n), a1 = r0 + 2 * .pi * CGFloat(i + 1) / CGFloat(n) + 0.004
                let wedge = CGMutablePath(); wedge.move(to: CGPoint(x: cx, y: cy))
                wedge.addLine(to: CGPoint(x: cx + big * cos(a0), y: cy + big * sin(a0))); wedge.addLine(to: CGPoint(x: cx + big * cos(a1), y: cy + big * sin(a1))); wedge.closeSubpath()
                ctx.setFillColor(Color.cg(colorAt((CGFloat(i) + 0.5) / CGFloat(n)))); ctx.addPath(wedge); ctx.fillPath()
            }
        }
    }
    /// Draws a shape: filled, stroked or both, with the paint's colour or shader and shadow.
    private func shape(_ path: CGPath, _ p: Paint, forceStroke: Bool = false) {
        let fill = !forceStroke && (p.style == .FILL || p.style == .FILL_AND_STROKE)
        let stroke = forceStroke || p.style == .STROKE || p.style == .FILL_AND_STROKE
        ctx.saveGState(); shadowOn(p)
        if let s = p.shader {
            var area = CGMutablePath()
            if fill { area.addPath(path) }
            if stroke {
                let st = path.copy(strokingWithWidth: max(p.strokeWidth, 0.0001), lineCap: p.strokeCap == .ROUND ? .round : p.strokeCap == .SQUARE ? .square : .butt,
                                   lineJoin: p.strokeJoin == .ROUND ? .round : p.strokeJoin == .BEVEL ? .bevel : .miter, miterLimit: 4)
                if fill { area = (area.union(st, using: .winding).mutableCopy() ?? area) } else { area.addPath(st) }
            }
            if p.shadow != nil { ctx.beginTransparencyLayer(auxiliaryInfo: nil) }
            ctx.saveGState(); ctx.addPath(area); ctx.clip(); fillShader(s, p); ctx.restoreGState()
            if p.shadow != nil { ctx.endTransparencyLayer() }
        } else {
            ctx.setFillColor(Color.cg(p.color)); ctx.setStrokeColor(Color.cg(p.color)); strokeSet(p)
            ctx.addPath(path)
            ctx.drawPath(using: fill && stroke ? .fillStroke : fill ? .fill : .stroke)
        }
        ctx.restoreGState()
    }

    func drawPath(_ path: Path, _ p: Paint) { shape(path.cg, p) }
    func drawCircle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ p: Paint) {
        guard r > 0 else { return }
        shape(CGPath(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r), transform: nil), p)
    }
    func drawOval(_ o: RectF, _ p: Paint) { shape(CGPath(ellipseIn: o.cg, transform: nil), p) }
    func drawRect(_ o: RectF, _ p: Paint) { shape(CGPath(rect: o.cg, transform: nil), p) }
    func drawRect(_ l: CGFloat, _ t: CGFloat, _ r: CGFloat, _ b: CGFloat, _ p: Paint) { drawRect(RectF(l, t, r, b), p) }
    func drawRoundRect(_ o: RectF, _ rx: CGFloat, _ ry: CGFloat, _ p: Paint) {
        let r = o.cg; guard r.width > 0, r.height > 0 else { return }
        shape(CGPath(roundedRect: r, cornerWidth: max(0, min(rx, r.width / 2)), cornerHeight: max(0, min(ry, r.height / 2)), transform: nil), p)
    }
    func drawLine(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ p: Paint) {
        let m = CGMutablePath(); m.move(to: CGPoint(x: x0, y: y0)); m.addLine(to: CGPoint(x: x1, y: y1)); shape(m, p, forceStroke: true)
    }
    func drawArc(_ o: RectF, _ start: CGFloat, _ sweep: CGFloat, _ useCenter: Bool, _ p: Paint) {
        let path = Path()
        if useCenter { path.moveTo(o.centerX(), o.centerY()); path.arcTo(o, start, sweep, false); path.close() } else { path.addArc(o, start, sweep) }
        shape(path.cg, p)
    }
    /// Text on baseline [y], placed by the paint's alignment; heavy names drawn filled and stroked.
    func drawText(_ s: String, _ x: CGFloat, _ y: CGFloat, _ p: Paint) {
        guard !s.isEmpty else { return }
        let line = p.line(s), w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        let x0 = p.textAlign == .LEFT ? x : p.textAlign == .CENTER ? x - w / 2 : x - w
        ctx.saveGState(); shadowOn(p)
        let stroke = p.style == .STROKE || p.style == .FILL_AND_STROKE
        if let sh = p.shader {
            if p.shadow != nil { ctx.beginTransparencyLayer(auxiliaryInfo: nil) }
            ctx.saveGState(); ctx.setTextDrawingMode(.clip); ctx.textPosition = CGPoint(x: x0, y: y); CTLineDraw(line, ctx)
            fillShader(sh, p); ctx.restoreGState()
            if p.shadow != nil { ctx.endTransparencyLayer() }
        } else {
            ctx.setFillColor(Color.cg(p.color)); ctx.setStrokeColor(Color.cg(p.color))
            if stroke { ctx.setLineWidth(p.strokeWidth); ctx.setLineJoin(p.strokeJoin == .ROUND ? .round : .miter) }
            ctx.setTextDrawingMode(p.style == .FILL_AND_STROKE ? .fillStroke : p.style == .STROKE ? .stroke : .fill)
            ctx.textPosition = CGPoint(x: x0, y: y); CTLineDraw(line, ctx)
        }
        ctx.restoreGState()
    }
}

// Kotlin's numbers and helpers, so the port reads as the original.
extension Comparable {
    func coerceIn(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
    func coerceAtLeast(_ lo: Self) -> Self { max(self, lo) }
    func coerceAtMost(_ hi: Self) -> Self { min(self, hi) }
}
extension String {
    func padStart(_ n: Int, _ c: Character) -> String { count >= n ? self : String(repeating: c, count: n - count) + self }
}
