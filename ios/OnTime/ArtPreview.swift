import Foundation
import UIKit
import WidgetKit

/// «ويدجت الشاشة الرئيسية» on the iPhone (0.31): the page's widget gallery and its customise screen, as on Android. The
/// previews are drawn here by the widgets' own art (ios/Art), and each design's look is saved in the App Group
/// (wstyles.json), where the widgets read it. The page asks synchronously, through prompt() (WebViewController).
enum ArtPreview {
    static let file = "wstyles.json"
    static var types: [String] { KArt.IOS.map { $0.0 } }
    private static func saved() -> [String: Any] {
        (SharedStore.read(file: file).flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any]) ?? [:]
    }
    private static func month() -> KData? { KData.load(SharedStore.read()) }
    private static func style(_ t: String, _ j: [String: Any]?, _ font: String?) -> KStyle {
        if let j = j { return KStyle.from(t, j, font) }
        return KArt.iosDefault(t, font, KArt.IOS.first { $0.0 == t }?.1.first ?? "m")
    }

    /// What the page's `Android.widgets()` gives: no widgets known on the screen (the iPhone doesn't say which), each
    /// design's look, what each can show, and no way to add one from the app.
    static func info() -> String {
        let s = saved(), font = month()?.o.optString("font")
        var styles: [String: Any] = [:], show: [String: Any] = [:]
        for t in types { styles[t] = style(t, s[t] as? [String: Any], font).toJson(); show[t] = KStyle.SHOW[t]?.0 ?? [] }
        let o: [String: Any] = ["placed": [], "styles": styles, "pin": false, "show": show, "dyn": false]
        return (try? JSONSerialization.data(withJSONObject: o)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
    }

    /// A design drawn with a look the page is trying, at its main iPhone size, as a PNG data URL.
    static func preview(_ arg: String) -> String {
        guard let a = (try? JSONSerialization.jsonObject(with: Data(arg.utf8))) as? [String: Any], let t = a["t"] as? String else { return "" }
        let sj = (a["s"] as? String).flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) } as? [String: Any]
        let d = month(), sz = KArt.iosSize(KArt.IOS.first { $0.0 == t }?.1.first ?? "m"), k: CGFloat = 2
        KArt.square = false
        guard let img = KArt.render(t, style(t, sj, d?.o.optString("font")), d, Int(Date().timeIntervalSince1970 * 1000), Int(sz.width * k), Int(sz.height * k), k),
              let png = UIImage(cgImage: img).pngData() else { return "" }
        return "data:image/png;base64," + png.base64EncodedString()
    }

    /// A look picked on the customise screen: saved for the design, and its widgets drawn again.
    static func save(_ t: String, _ json: String) {
        guard types.contains(t), let j = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return }
        var s = saved(); s[t] = j
        guard let data = try? JSONSerialization.data(withJSONObject: s), let str = String(data: data, encoding: .utf8) else { return }
        if SharedStore.write(str, file: file) { WidgetCenter.shared.reloadTimelines(ofKind: "art.\(t)") }
    }
}
