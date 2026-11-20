import SwiftUI
import AVFoundation
import CoreMotion
import MediaPlayer

/// The adhan in full, on the app's green with the gold arch: the prayer, its time, «إيقاف الأذان», «صلّيت»,
/// and when the time ends. As on Android it also stops with a volume button, turning the phone over, or locking it
/// (the ways the user left on in settings).
struct AdhanScreen: View {
    let adhan: Adhan
    @Environment(\.dismiss) private var dismiss
    @StateObject private var player = AdhanPlayer()

    private var ev: [String: Any] { adhan.ev }
    private var ar: Bool { Fmt.ar(ev) }
    private let cream = Color(red: 0.957, green: 0.925, blue: 0.863), gold = Color(red: 0.886, green: 0.761, blue: 0.478)

    var body: some View {
        ZStack {
            RadialGradient(colors: [Color(red: 0.08, green: 0.38, blue: 0.31), Color(red: 0.04, green: 0.23, blue: 0.18)], center: .init(x: 0.5, y: 0.35), startRadius: 10, endRadius: 520)
                .ignoresSafeArea()
            Arch().stroke(gold.opacity(0.55), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                .frame(width: 250, height: 300).offset(y: -70)
            VStack(spacing: 10) {
                Spacer()
                Text(ar ? "حان الآن وقت صلاة" : "It is time for").font(.custom("IBM Plex Sans Arabic", size: 17)).foregroundStyle(cream.opacity(0.8))
                Text(ev["title"] as? String ?? "").font(.custom("Reem Kufi", size: 64)).foregroundStyle(cream)
                Text(Fmt.digits(clock, ev)).font(.custom("IBM Plex Sans Arabic", size: 26)).foregroundStyle(gold)
                Spacer()
                Button { player.stop(); dismiss() } label: {
                    Text(ar ? "إيقاف الأذان" : "Stop the adhan").font(.custom("IBM Plex Sans Arabic SemiBold", size: 18))
                        .frame(maxWidth: .infinity, minHeight: 60).foregroundStyle(Color(red: 0.04, green: 0.18, blue: 0.14)).background(gold, in: RoundedRectangle(cornerRadius: 20))
                }
                if Scheduler.hasEx(ev) {
                    Button { Reminders.prayed(ev); player.stop(); dismiss() } label: {
                        Text(ar ? "صلّيت" : "I prayed").font(.custom("IBM Plex Sans Arabic SemiBold", size: 15))
                            .frame(maxWidth: .infinity, minHeight: 52).foregroundStyle(cream)
                            .background(RoundedRectangle(cornerRadius: 18).fill(.white.opacity(0.08)).stroke(gold.opacity(0.35), lineWidth: 1))
                    }
                }
                if let x = Reminders.nextExit(ev) {
                    Text(Fmt.ends(ev, x)).font(.custom("IBM Plex Sans Arabic", size: 13)).foregroundStyle(cream.opacity(0.65)).padding(.top, 6)
                }
                Spacer().frame(height: 24)
            }
            .padding(.horizontal, 28)
        }
        .environment(\.layoutDirection, ar ? .rightToLeft : .leftToRight)
        .onAppear { player.start(ev, fromStart: adhan.fromStart) { dismiss() } }
        .onDisappear { player.stop() }
    }

    private var clock: String {
        let at = Date(timeIntervalSince1970: Scheduler.at(ev) / 1000)
        var cal = Calendar(identifier: .gregorian); cal.timeZone = .current
        var h = cal.component(.hour, from: at); let m = cal.component(.minute, from: at)
        let h12 = (ev["f12"] as? Int ?? 1) == 1
        var suffix = ""
        if h12 { suffix = h < 12 ? (ar ? " ص" : " AM") : (ar ? " م" : " PM"); h = h % 12 == 0 ? 12 : h % 12 }
        return "\(h):" + String(format: "%02d", locale: Locale(identifier: "en"), m) + suffix
    }
}

/// The mihrab's arch, as in the app's mark.
struct Arch: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path(); let w = r.width, h = r.height
        p.move(to: CGPoint(x: r.minX, y: r.maxY))
        p.addLine(to: CGPoint(x: r.minX, y: r.minY + h * 0.45))
        p.addQuadCurve(to: CGPoint(x: r.midX, y: r.minY), control: CGPoint(x: r.minX, y: r.minY + h * 0.08))
        p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.minY + h * 0.45), control: CGPoint(x: r.maxX, y: r.minY + h * 0.08))
        p.addLine(to: CGPoint(x: r.maxX, y: r.maxY))
        _ = w
        return p
    }
}

/// Plays the recording on the alarm-like "playback" route (it keeps going with the screen off), and stops it the ways the user chose.
final class AdhanPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    private var p: AVAudioPlayer?
    private var onEnd: (() -> Void)?
    private var volObs: NSKeyValueObservation?
    private let motion = CMMotionManager()
    private var lockObs: NSObjectProtocol?

    func start(_ ev: [String: Any], fromStart: Bool, onEnd: @escaping () -> Void) {
        self.onEnd = onEnd
        Sounds.stopPreview()
        guard let u = Sounds.fullURL(ev["sound"] as? String ?? "afasy") else { onEnd(); return }
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default)
        try? s.setActive(true)
        p = try? AVAudioPlayer(contentsOf: u)
        p?.delegate = self
        // tapped just after the notification played its 30 s: carry on from there
        if !fromStart, let d = p?.duration, d > 40 { p?.currentTime = 29.5 }
        p?.play()
        let ways = ev["stop"] as? String ?? "pvf"
        if ways.contains("v") { volObs = s.observe(\.outputVolume, options: [.new]) { [weak self] _, _ in DispatchQueue.main.async { self?.finish() } } }
        if ways.contains("f"), motion.isAccelerometerAvailable {
            motion.accelerometerUpdateInterval = 0.2
            motion.startAccelerometerUpdates(to: .main) { [weak self] d, _ in if let z = d?.acceleration.z, z > 0.85 { self?.finish() } }
        }
        if ways.contains("p") {
            lockObs = NotificationCenter.default.addObserver(forName: UIApplication.protectedDataWillBecomeUnavailableNotification, object: nil, queue: .main) { [weak self] _ in self?.finish() }
        }
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { finish() }

    private func finish() { let e = onEnd; stop(); e?() }

    func stop() {
        onEnd = nil
        p?.stop(); p = nil
        volObs?.invalidate(); volObs = nil
        motion.stopAccelerometerUpdates()
        if let o = lockObs { NotificationCenter.default.removeObserver(o); lockObs = nil }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
