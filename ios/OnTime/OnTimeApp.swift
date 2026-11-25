import SwiftUI

@main
struct OnTimeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @ObservedObject private var state = AppState.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            WebShell()
                .ignoresSafeArea()
                // the adhan, when its notification is tapped or it comes while the app is open
                .fullScreenCover(item: $state.adhan) { a in AdhanScreen(adhan: a) }
        }
        .onChange(of: phase) { _, p in
            if p == .background { AppDelegate.planRefresh() }
            if p == .active { Alarms.openedAwake(); Scheduler.rebuild(); Alarms.sync(); Alarms.openPending(); LiveNext.update() }
        }
    }
}

/// What the app shows over the page: the adhan screen.
final class AppState: ObservableObject {
    static let shared = AppState()
    @Published var adhan: Adhan?

    func show(_ ev: [String: Any], fromStart: Bool) {
        DispatchQueue.main.async { self.adhan = Adhan(ev: ev, fromStart: fromStart) }
    }
}

struct Adhan: Identifiable {
    let id = UUID()
    let ev: [String: Any]
    let fromStart: Bool
}

struct WebShell: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> WebViewController { WebViewController.shared }
    func updateUIViewController(_ uiViewController: WebViewController, context: Context) {}
}
