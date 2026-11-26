// Draws every widget with the iPhone's port of the Android art (ios/Art), on macOS in CI, for checking the designs:
//   swiftc -O -parse-as-library ios/Art/*.swift ios/ci/render.swift -o render && ./render <wdata.json> <fonts dir> <out dir>
// The sample month is the Android tests' (android/app/src/test/resources/wdata.json), in Saudi time.
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

@main struct Render {
    static func main() {
        let args = CommandLine.arguments
        guard args.count >= 4, let raw = try? Data(contentsOf: URL(fileURLWithPath: args[1])),
              let all = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any],
              let arJ = all["ar"] as? [String: Any], let enJ = all["en"] as? [String: Any] else { print("usage: render wdata.json fonts out"); exit(1) }
        NSTimeZone.default = TimeZone(identifier: "Asia/Riyadh")!
        Typeface.dir = URL(fileURLWithPath: args[2])
        let out = URL(fileURLWithPath: args[3]); try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let ar = KData(arJ), en = KData(enJ)
        let day = J(((arJ["days"] as? [Any]) ?? [])[1])!
        let mn = 60_000
        let asrEnd = J((day.optJSONObject("ex")?.optJSONArray("asr") ?? [])[0])!.optLong("at")
        let moments: [(String, Int)] = [("1-fajr", day.optLong("fajr") + 25 * mn), ("2-duha", day.optLong("duha") + 120 * mn), ("3-asr", asrEnd - 20 * mn),
                                        ("4-maghrib", day.optLong("maghrib") + 12 * mn), ("5-night", day.optLong("isha") + 180 * mn)]
        // iPhone 16 Pro widget sizes in points, drawn at 3×
        let S = (w: 158, h: 158), M = (w: 338, h: 158), L = (w: 338, h: 354)
        let fam: [String: [(String, (w: Int, h: Int))]] = [
            "pano": [("m", M), ("l", L)], "window": [("s", S)], "ring": [("s", S)], "orbit": [("m", M)], "script": [("m", M), ("s", S)],
            "table": [("m", M), ("l", L)], "clock": [("s", S)], "moon": [("s", S)], "ribbon": [("m", M)], "exit": [("m", M)],
            "actions": [("m", M)], "line": [("m", M)], "prayed": [("m", M), ("s", S)], "night": [("s", S), ("m", M)],
            "ramadan": [("m", M), ("s", S)], "white": [("m", M), ("s", S)], "column": [("l", L)]]
        func png(_ img: CGImage?, _ name: String) {
            guard let i = img, let d = CGImageDestinationCreateWithURL(out.appendingPathComponent(name + ".png") as CFURL, UTType.png.identifier as CFString, 1, nil) else { print("failed", name); return }
            CGImageDestinationAddImage(d, i, nil); CGImageDestinationFinalize(d)
        }
        var n = 0
        let t0 = Date()
        for (label, now) in moments {
            for t in KArt.TYPES { for (f, sz) in fam[t] ?? [] {
                png(KArt.render(t, KStyle.defaultFor(t), ar, now, sz.w * 3, sz.h * 3, 3), "\(label)_\(t)_\(f)"); n += 1 } }
        }
        // the other looks, at asr: every background, the fonts, English
        let asr = moments[2].1
        for bg in ["light", "dark", "navy", "clear"] { for t in ["pano", "table", "ring", "column", "orbit"] { for (f, sz) in fam[t] ?? [] {
            var st = KStyle.defaultFor(t); st.bg = bg
            png(KArt.render(t, st, ar, asr, sz.w * 3, sz.h * 3, 3), "bg-\(bg)_\(t)_\(f)"); n += 1 } } }
        for font in ["ruqaa", "naskh"] { for t in ["pano", "script", "table"] { let sz = fam[t]![0].1
            png(KArt.render(t, KStyle.defaultFor(t, font), ar, asr, sz.w * 3, sz.h * 3, 3), "font-\(font)_\(t)"); n += 1 } }
        for t in KArt.TYPES { let sz = fam[t]![0].1
            png(KArt.render(t, KStyle.defaultFor(t), en, asr, sz.w * 3, sz.h * 3, 3), "en_\(t)"); n += 1 }
        print("drew \(n) pictures in \(Int(Date().timeIntervalSince(t0) * 1000)) ms")
    }
}
