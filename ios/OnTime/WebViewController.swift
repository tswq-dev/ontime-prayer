import UIKit
import WidgetKit
import WebKit
import UniformTypeIdentifiers
import UserNotifications

/// The same page as on Android, from the app bundle under its own scheme (ontime://app/…), with a `window.Android`
/// made for the iPhone: calls that return a value read a small state the app keeps up to date (`__iosSet`).
final class WebViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, UIDocumentPickerDelegate {
    static let shared = WebViewController()
    private var web: WKWebView!
    private var loaded = false
    private var pickingId = ""

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(named: "LaunchBg")
        guard let root = Bundle.main.url(forResource: "www", withExtension: nil) else { return }
        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(BundleSchemeHandler(root: root), forURLScheme: "ontime")
        cfg.userContentController.add(WeakHandler(self), name: "ontime")
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = []
        cfg.userContentController.addUserScript(WKUserScript(source: "window.__iosInit = \(Self.json(state()));\n" + Self.shim,
                                                             injectionTime: .atDocumentStart, forMainFrameOnly: true))
        web = WKWebView(frame: view.bounds, configuration: cfg)
        web.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        web.isOpaque = false
        web.backgroundColor = UIColor(named: "LaunchBg")
        web.scrollView.contentInsetAdjustmentBehavior = .never
        web.scrollView.bounces = false
        web.navigationDelegate = self
        view.addSubview(web)
        if let start = URL(string: "ontime://app/index.html") { web.load(URLRequest(url: start)) }
        NotificationCenter.default.addObserver(self, selector: #selector(active), name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded = true; pushState() }
    @objc private func active() { pushState() }

    // Links to the outside open in their own apps; the page itself never goes online.
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url, let scheme = url.scheme, !["ontime", "about", "blob", "data"].contains(scheme) {
            UIApplication.shared.open(url); decisionHandler(.cancel); return
        }
        decisionHandler(.allow)
    }

    // ---------- the state the page reads without waiting ----------
    private func state() -> [String: Any] {
        let d = UserDefaults.standard
        return ["notify": d.string(forKey: "notifyState") ?? "off",
                "names": Sounds.names(),
                "reminders": Reminders.all(),
                "version": Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""]
    }

    func pushState() {
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let on = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional || s.authorizationStatus == .ephemeral
            UserDefaults.standard.set(on ? "on" : "off", forKey: "notifyState")
            DispatchQueue.main.async {
                guard self.loaded else { return }
                self.js("window.__iosSet && __iosSet(\(Self.json(self.state())))")
            }
        }
    }

    private func js(_ s: String) { DispatchQueue.main.async { self.web?.evaluateJavaScript(s, completionHandler: nil) } }

    static func json(_ o: Any) -> String {
        guard JSONSerialization.isValidJSONObject(o), let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    // ---------- calls from the page ----------
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let b = message.body as? [String: Any], let cmd = b["cmd"] as? String else { return }
        switch cmd {
        case "schedule": Scheduler.save(b["json"] as? String ?? "[]")
        case "widgetData":
            let j = b["json"] as? String ?? ""
            UserDefaults.standard.set(j, forKey: "widgetData")
            // the widgets read it from the App Group; reload them only when it changed
            if SharedStore.write(j) { WidgetCenter.shared.reloadAllTimelines() }
        case "askNotify": askNotify()
        case "preview": Sounds.preview(b["sound"] as? String ?? "")
        case "pickSound": pickSound(b["id"] as? String ?? "file")
        case "clearSound": Sounds.clear(b["id"] as? String ?? ""); pushState()
        case "locate": Locator.shared.locate { r in
            switch r {
            case .success(let l): self.js("window.__onLocation && __onLocation(\(l.coordinate.latitude), \(l.coordinate.longitude), \(l.horizontalAccuracy))")
            case .failure(let code): self.js("window.__onLocationError && __onLocationError('\(code.rawValue)')")
            }
        }
        case "remind":
            if let ev = Scheduler.parse(b["json"] as? String ?? "") { Reminders.before(ev, mins: ev["mins"] as? Int ?? 20) { self.pushState() } }
        case "cancelRemind": Reminders.cancel(b["pk"] as? String ?? ""); pushState()
        default: break
        }
    }

    private func askNotify() {
        let c = UNUserNotificationCenter.current()
        c.getNotificationSettings { s in
            if s.authorizationStatus == .denied {
                // refused before: only the phone's settings can turn it back on
                DispatchQueue.main.async { if let u = URL(string: UIApplication.openNotificationSettingsURLString) { UIApplication.shared.open(u) } }
                return
            }
            c.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
                self.pushState()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.js("window.__onNotify && __onNotify()"); Scheduler.rebuild() }
            }
        }
    }

    // ---------- the user's own adhan files ----------
    private func pickSound(_ id: String) {
        pickingId = id
        let p = UIDocumentPickerViewController(forOpeningContentTypes: [.audio], asCopy: true)
        p.delegate = self
        p.allowsMultipleSelection = false
        present(p, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let u = urls.first else { return }
        Sounds.keep(id: pickingId, from: u) { ok in
            self.pushState()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { self.js("window.__onSound && __onSound()") }
            if ok { Sounds.preview(self.pickingId) }
        }
    }

    // The page's `window.Android`, for the iPhone. Values the page reads at once come from `__ios`.
    static let shim = """
    (function(){
      var H = window.webkit.messageHandlers.ontime;
      var st = window.__ios = Object.assign({ notify: 'off', names: {}, reminders: {}, version: '' }, window.__iosInit || {});
      var post = function(cmd, o){ var m = o || {}; m.cmd = cmd; H.postMessage(m); };
      window.__host = 'ios';
      var mark = function(){ document.documentElement.classList.add('ios'); };
      if (document.documentElement) mark(); else document.addEventListener('readystatechange', mark, { once: true });
      window.__iosSet = function(o){ Object.assign(st, o); };
      window.__appVersion = st.version;
      window.Android = {
        schedule: function(j){ post('schedule', { json: j }); },
        widgetData: function(j){ post('widgetData', { json: j }); },
        notifyState: function(){ return st.notify; },
        askNotify: function(){ post('askNotify'); },
        sheet: function(){},
        preview: function(v){ post('preview', { sound: v }); },
        pickSound: function(v){ post('pickSound', { id: v }); },
        soundName: function(v){ return st.names[v] || ''; },
        clearSound: function(v){ delete st.names[v]; post('clearSound', { id: v }); },
        locate: function(){ post('locate'); },
        remind: function(j){ post('remind', { json: j }); },
        reminder: function(pk){ return String(st.reminders[pk] || ''); },
        cancelRemind: function(pk){ delete st.reminders[pk]; post('cancelRemind', { pk: pk }); },
        pendingOpen: function(){ return ''; },
        pendingWidget: function(){ return ''; }
      };
    })();
    """
}

/// Avoids a retain cycle between the web view's content controller and its handler.
final class WeakHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

/// Serves files from the bundled www folder, and nothing outside it.
final class BundleSchemeHandler: NSObject, WKURLSchemeHandler {
    private let root: URL
    init(root: URL) { self.root = root.standardizedFileURL }

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        guard let url = urlSchemeTask.request.url else { return }
        let path = url.path.isEmpty || url.path == "/" ? "index.html" : String(url.path.dropFirst())
        let file = root.appendingPathComponent(path).standardizedFileURL
        guard file.path.hasPrefix(root.path), let data = try? Data(contentsOf: file) else {
            urlSchemeTask.didFailWithError(URLError(.fileDoesNotExist)); return
        }
        let mime = UTType(filenameExtension: file.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        let headers = ["Content-Type": mime, "Content-Length": String(data.count)]
        if let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: headers) { urlSchemeTask.didReceive(response) }
        urlSchemeTask.didReceive(data)
        urlSchemeTask.didFinish()
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {}
}
