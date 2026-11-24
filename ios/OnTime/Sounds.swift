import Foundation
import AVFoundation
import UserNotifications
import AudioToolbox
import CoreText

/// The sounds: notification clips (at most 30 s, as Apple allows) and full recordings for the app,
/// the user's own files (copied into the app, with a clip for alerts), and short previews.
enum Sounds {
    static var library: URL { FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0].appendingPathComponent("Sounds") }
    static var files: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("files") }

    static func isRecording(_ s: String) -> Bool { !(["none", "notify", "vibrate"].contains(s) || s.hasPrefix("spk_")) }

    /// The alert's sound for a sound name from the page.
    static func notificationSound(_ s: String) -> UNNotificationSound? {
        switch s {
        case "none", "notify": return nil
        case "vibrate": return named("silence.caf")
        default: break
        }
        if s.hasPrefix("tone_") { return .default }
        if s.hasPrefix("file") {
            let f = "u_\(s).caf"
            return FileManager.default.fileExists(atPath: library.appendingPathComponent(f).path) ? named(f) : named("n_afasy.caf")
        }
        if s.hasPrefix("spk_") { return named("\(s).caf") }
        if Bundle.main.url(forResource: "n_\(s)", withExtension: "caf") != nil { return named("n_\(s).caf") }
        return .default
    }
    static func reminderSound(_ rs: String, spk: String?) -> UNNotificationSound? {
        if rs == "quiet" { return nil }
        if rs == "speak", let spk, Bundle.main.url(forResource: spk, withExtension: "caf") != nil { return named("\(spk).caf") }
        return .default
    }
    private static func named(_ f: String) -> UNNotificationSound { UNNotificationSound(named: UNNotificationSoundName(f)) }

    /// The full recording, to play in the app.
    static func fullURL(_ s: String) -> URL? {
        if s.hasPrefix("file"), let p = UserDefaults.standard.string(forKey: "filePath_\(s)") {
            let u = files.appendingPathComponent(p); if FileManager.default.fileExists(atPath: u.path) { return u }
        }
        if s.hasPrefix("spk_") || s.hasPrefix("al_") { return Bundle.main.url(forResource: s, withExtension: "caf") }   // al_: the alarm sounds (0.28)
        return Bundle.main.url(forResource: s, withExtension: "m4a")
            ?? Bundle.main.url(forResource: "adhan_\(s)", withExtension: "mp3")
            ?? Bundle.main.url(forResource: "n_\(s)", withExtension: "caf")
            ?? Bundle.main.url(forResource: "adhan_afasy", withExtension: "mp3")
    }

    static func names() -> [String: String] {
        var n: [String: String] = [:]
        for (k, v) in UserDefaults.standard.dictionaryRepresentation() where k.hasPrefix("soundName_") { if let s = v as? String { n[String(k.dropFirst(10))] = s } }
        return n
    }

    /// A file the user picked: kept in the app, and its first 30 s made into an alert sound (fading out at the end).
    static func keep(id: String, from src: URL, done: @escaping (Bool) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let fm = FileManager.default
            try? fm.createDirectory(at: files, withIntermediateDirectories: true)
            try? fm.createDirectory(at: library, withIntermediateDirectories: true)
            let ext = src.pathExtension.isEmpty ? "m4a" : src.pathExtension
            let name = "\(id).\(ext)", dst = files.appendingPathComponent(name)
            try? fm.removeItem(at: dst)
            do { try fm.copyItem(at: src, to: dst) } catch { DispatchQueue.main.async { done(false) }; return }
            let ok = clip(dst, to: library.appendingPathComponent("u_\(id).caf"))
            UserDefaults.standard.set(name, forKey: "filePath_\(id)")
            UserDefaults.standard.set(src.lastPathComponent, forKey: "soundName_\(id)")
            DispatchQueue.main.async { done(ok) }
        }
    }

    static func clear(_ id: String) {
        let d = UserDefaults.standard
        if let p = d.string(forKey: "filePath_\(id)") { try? FileManager.default.removeItem(at: files.appendingPathComponent(p)) }
        try? FileManager.default.removeItem(at: library.appendingPathComponent("u_\(id).caf"))
        d.removeObject(forKey: "filePath_\(id)"); d.removeObject(forKey: "soundName_\(id)")
    }

    /// The first 29.5 s of a recording as 16-bit PCM in a CAF file, the last 1.5 s faded out.
    private static func clip(_ src: URL, to dst: URL) -> Bool {
        guard let inFile = try? AVAudioFile(forReading: src) else { return false }
        let fmt = inFile.processingFormat, rate = fmt.sampleRate
        let frames = AVAudioFrameCount(min(Double(inFile.length), rate * 29.5))
        guard frames > 0, let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: frames), (try? inFile.read(into: buf, frameCount: frames)) != nil else { return false }
        if let ch = buf.floatChannelData, Double(buf.frameLength) > rate * 3 {
            let n = Int(buf.frameLength), fade = Int(rate * 1.5)
            for c in 0..<Int(fmt.channelCount) { for i in 0..<fade { ch[c][n - fade + i] *= Float(fade - i) / Float(fade) } }
        }
        try? FileManager.default.removeItem(at: dst)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate, AVNumberOfChannelsKey: fmt.channelCount,
                                       AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]
        guard let out = try? AVAudioFile(forWriting: dst, settings: settings, commonFormat: fmt.commonFormat, interleaved: fmt.isInterleaved) else { return false }
        return (try? out.write(from: buf)) != nil
    }

    // ---------- previews (12 s, as on Android) ----------
    private static var player: AVAudioPlayer?
    private static var stopWork: DispatchWorkItem?
    static func preview(_ s: String) {
        stopPreview()
        if s == "none" || s == "notify" { return }
        if s == "vibrate" { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate); return }
        if s.hasPrefix("tone_") { AudioServicesPlaySystemSound(1007); return }
        guard let u = fullURL(s) else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(contentsOf: u)
        player?.play()
        let w = DispatchWorkItem { stopPreview() }; stopWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 12, execute: w)
    }
    static func stopPreview() { stopWork?.cancel(); player?.stop(); player = nil }
}

/// The app's fonts (from the widgets' font folder): Reem Kufi for the prayer's name, IBM Plex for the rest.
enum Fonts {
    static func register() {
        for f in ["kufi", "plex400", "plex600"] {
            if let u = Bundle.main.url(forResource: f, withExtension: "ttf", subdirectory: "wfonts") { CTFontManagerRegisterFontsForURL(u as CFURL, .process, nil) }
        }
    }
}
