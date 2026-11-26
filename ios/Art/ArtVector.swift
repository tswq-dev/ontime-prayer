import SwiftUI

/// The recorded widget drawing (ARecorder) as SwiftUI shapes (0.31.2): each outline filled with its colour or gradient, in
/// groups for the clips; positions in points from the top left, as the drawing has them.
struct ArtVectorView: View {
    let rec: ARecorder
    var body: some View {
        AGroupView(g: rec.root, w: rec.w, h: rec.h).frame(width: rec.w, height: rec.h, alignment: .topLeading)
    }
}

struct AGroupView: View {
    let g: AGroup, w: CGFloat, h: CGFloat
    var body: some View {
        let stack = ZStack(alignment: .topLeading) {
            ForEach(0..<g.kids.count, id: \.self) { i in AGroupView.node(g.kids[i], w, h) }
        }.frame(width: w, height: h, alignment: .topLeading)
        if let c = g.clip {
            if g.alpha < 1 { stack.clipShape(Path(c)).compositingGroup().opacity(Double(g.alpha)) } else { stack.clipShape(Path(c)) }
        } else if g.alpha < 1 { stack.compositingGroup().opacity(Double(g.alpha)) } else { stack }
    }

    static func color(_ c: AInt) -> Color { Color(cgColor: AColor.cg(c)) }
    static func gradient(_ cs: [AInt], _ ps: [CGFloat]) -> Gradient {
        Gradient(stops: zip(cs, ps).map { Gradient.Stop(color: color($0.0), location: $0.1) })
    }
    static func node(_ n: ANode, _ w: CGFloat, _ h: CGFloat) -> AnyView {
        switch n {
        case .group(let gr): return AnyView(AGroupView(g: gr, w: w, h: h))
        case let .shape(p, f, sh):
            let path = Path(p)
            func u(_ q: CGPoint) -> UnitPoint { UnitPoint(x: q.x / max(w, 1), y: q.y / max(h, 1)) }
            var v: AnyView
            switch f {
            case .color(let c): v = AnyView(path.fill(color(c)))
            case let .linear(a, b, cs, ps, al): v = AnyView(path.fill(LinearGradient(gradient: gradient(cs, ps), startPoint: u(a), endPoint: u(b))).opacity(Double(al)))
            case let .radial(c, r, cs, ps, al): v = AnyView(path.fill(RadialGradient(gradient: gradient(cs, ps), center: u(c), startRadius: 0, endRadius: r)).opacity(Double(al)))
            case let .sweep(c, cs, ps, rot, mirror, al):
                // clockwise from 3 o'clock, turned by its rotation, as Android's sweep; mirrored about its centre in Arabic
                let s = AnyView(path.fill(AngularGradient(gradient: gradient(cs, ps), center: u(c), angle: .degrees(Double(rot)))).opacity(Double(al)))
                v = mirror ? AnyView(s.scaleEffect(x: -1, y: 1, anchor: u(c))) : s
            }
            if let s = sh { v = AnyView(v.shadow(color: color(s.c), radius: s.r * 0.55, x: s.dx, y: s.dy)) }
            return v
        }
    }
}
