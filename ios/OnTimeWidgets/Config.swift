import AppIntents
import SwiftUI
import CoreText

/// The customise screen of each widget (long-press → Edit widget): what the user picks, never what we impose.
enum WBg: String, AppEnum {
    case green, navy, light, sky, auto
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "الخلفية"
    static let caseDisplayRepresentations: [WBg: DisplayRepresentation] = [
        .green: "أخضر", .navy: "كحلي", .light: "فاتح", .sky: "سماء", .auto: "حسب مظهر الجوال"
    ]
}

enum WFont: String, AppEnum {
    case kufi, ruqaa, naskh
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "الخط"
    static let caseDisplayRepresentations: [WFont: DisplayRepresentation] = [.kufi: "كوفي", .ruqaa: "رقعة", .naskh: "نسخ"]
    var ps: String { switch self { case .kufi: return "ReemKufi"; case .ruqaa: return "ArefRuqaa-Bold"; case .naskh: return "Amiri-Bold" } }
}

enum WCount: String, AppEnum {
    case timer, words, off
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "العد التنازلي"
    static let caseDisplayRepresentations: [WCount: DisplayRepresentation] = [.timer: "١:٢٤:٣٠", .words: "بالكلمات: بعد ١٠ دقائق", .off: "إخفاء"]
}

struct NextConfig: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "الصلاة القادمة"
    static let description = IntentDescription("الخلفية والخط والعد التنازلي")
    @Parameter(title: "الخلفية", default: .green) var bg: WBg
    @Parameter(title: "الخط", default: .kufi) var font: WFont
    @Parameter(title: "العد التنازلي", default: .timer) var count: WCount
}

struct DateConfig: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "التاريخ واليوم"
    static let description = IntentDescription("الخلفية والخط")
    @Parameter(title: "الخلفية", default: .navy) var bg: WBg
    @Parameter(title: "الخط", default: .kufi) var font: WFont
}

struct TableConfig: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "جدول اليوم"
    static let description = IntentDescription("الخلفية والخط والشروق والعد التنازلي")
    @Parameter(title: "الخلفية", default: .green) var bg: WBg
    @Parameter(title: "الخط", default: .kufi) var font: WFont
    @Parameter(title: "الشروق", default: false) var sunrise: Bool
    @Parameter(title: "العد التنازلي", default: .timer) var count: WCount
}

/// The choices as plain values, so one set of views serves every widget.
struct WCfg {
    var bg: WBg = .green
    var font: WFont = .kufi
    var count: WCount = .timer
    var sunrise = false
}

/// The app's identity: green and gold, cream and the sky.
struct Palette {
    let top: Color, bottom: Color, ink: Color, mute: Color, acc: Color, pill: Color
    static func of(_ bg: WBg, dark: Bool) -> Palette {
        switch bg {
        case .green: return Palette(top: .hex(0x11594A), bottom: .hex(0x0A382C), ink: .hex(0xF4ECDC), mute: Color.hex(0xF4ECDC).opacity(0.72), acc: .hex(0xE2C27A), pill: Color.hex(0xE2C27A).opacity(0.18))
        case .light: return Palette(top: .hex(0xF5EEE1), bottom: .hex(0xEBE1CE), ink: .hex(0x0B3B2E), mute: .hex(0x5B6B5F), acc: .hex(0x0B4D3B), pill: Color.hex(0x0B4D3B).opacity(0.10))
        case .navy: return Palette(top: .hex(0x1B2C52), bottom: .hex(0x0B1630), ink: .hex(0xF3E6C4), mute: Color.hex(0xF3E6C4).opacity(0.72), acc: .hex(0xE9CD8A), pill: Color.hex(0xE9CD8A).opacity(0.18))
        case .sky: return Palette(top: .hex(0x1E4E86), bottom: .hex(0x4A88CF), ink: .hex(0xFFF8EA), mute: Color.hex(0xFFF8EA).opacity(0.8), acc: .hex(0xFBE3A6), pill: Color.white.opacity(0.18))
        case .auto: return of(dark ? .green : .light, dark: dark)
        }
    }
}

extension Color {
    static func hex(_ v: UInt32) -> Color {
        Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

/// The fonts ship inside the extension (Reem Kufi, Aref Ruqaa, Amiri for the names; IBM Plex Sans Arabic for the rest).
enum WFonts {
    static let plex = "IBMPlexSansArabic-Regular", plexBold = "IBMPlexSansArabic-SemiBold"
    private static var done = false
    static func register() {
        if done { return }
        done = true
        for f in ["kufi", "ruqaa", "naskh", "plex400", "plex600"] {
            if let u = Bundle.main.url(forResource: f, withExtension: "ttf") { CTFontManagerRegisterFontsForURL(u as CFURL, .process, nil) }
        }
    }
}
