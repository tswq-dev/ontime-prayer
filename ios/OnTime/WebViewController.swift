import UIKit
import WidgetKit
import WebKit
import UniformTypeIdentifiers
import UserNotifications

/// The same page as on Android, from the app bundle under its own scheme (ontime://app/…), with a `window.Android`
/// made for the iPhone: calls that return a value read a small state the app keeps up to date (`__iosSet`).
final class WebViewController: UIViewController, WKScriptMessageHandler, WKNavigationDelegate, WKUIDelegate, UIDocumentPickerDelegate {
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
        web.uiDelegate = self   // prompt(): the page's few calls that need an answer at once (the widget previews, 0.31)
        view.addSubview(web)
        if let start = URL(string: "ontime://app/index.html") { web.load(URLRequest(url: start)) }
        NotificationCenter.default.addObserver(self, selector: #selector(active), name: UIApplication.didBecomeActiveNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(textSize), name: UIContentSizeCategory.didChangeNotification, object: nil)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loaded = true; pushState() }
    @objc private func active() { pushState(); Alarms.openPending() }
    // iOS may end the page's process in the background (memory): load it again instead of leaving a blank screen
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { loaded = false; webView.reload() }

    /// The text follows the iPhone's text size (Settings › Display › Text Size), as Android's does.
    static func textPct() -> Int {
        switch UIApplication.shared.preferredContentSizeCategory {
        case .extraSmall: return 82
        case .small: return 88
        case .medium: return 94
        case .large: return 100
        case .extraLarge: return 110
        case .extraExtraLarge: return 120
        case .extraExtraExtraLarge: return 130
        case .accessibilityMedium: return 140
        case .accessibilityLarge: return 150
        case .accessibilityExtraLarge, .accessibilityExtraExtraLarge, .accessibilityExtraExtraExtraLarge: return 160
        default: return 100
        }
    }
    @objc private func textSize() { pushState() }

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
                "textPct": Self.textPct(),
                "alarmKit": Alarms.supported, "alarmAuth": Alarms.auth(), "alarmState": Alarms.state(),
                "pendingOpen": d.string(forKey: "pendingOpen") ?? "",
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

    /// The page's calls that return a value at once: prompt(name, argument) answered here, while the page waits.
    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        switch prompt {
        case "wpv": completionHandler(ArtPreview.preview(defaultText ?? ""))
        case "wget": completionHandler(ArtPreview.info())
        default: completionHandler(nil)
        }
    }

    /// A notification tapped that belongs to a screen of the app (the alarms, 0.32): open it now, or once the page is there.
    func openFrom(_ k: String) {
        if loaded { UserDefaults.standard.removeObject(forKey: "pendingOpen"); js("window.__openFrom && __openFrom('\(k)')") }
        else { UserDefaults.standard.set(k, forKey: "pendingOpen") }
    }

    /// "Try now" was set for this time (0: it was not); the page counts down to it.
    func tried(_ at: Double) { js("window.__onAlarmTry && __onAlarmTry(\(Int(at)))") }

    private func js(_ s: String) { DispatchQueue.main.async { self.web?.evaluateJavaScript(s, completionHandler: nil) } }

    static func json(_ o: Any) -> String {
        guard JSONSerialization.isValidJSONObject(o), let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return "{}" }
        return s
    }

    // ---------- calls from the page ----------
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let b = message.body as? [String: Any], let cmd = b["cmd"] as? String else { return }
        switch cmd {
        case "schedule": Scheduler.save(b["json"] as? String ?? "[]"); Alarms.sync()
        case "pendingDone": UserDefaults.standard.removeObject(forKey: "pendingOpen")
        case "widgetStyle": ArtPreview.save(b["t"] as? String ?? "", b["json"] as? String ?? "")
        case "widgetData":
            let j = b["json"] as? String ?? ""
            UserDefaults.standard.set(j, forKey: "widgetData")
            // the widgets read it from the App Group; reload them only when it changed
            if SharedStore.write(j) { WidgetCenter.shared.reloadAllTimelines(); Alarms.sync() }
            LiveNext.update()
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
        // the prayer alarms (iOS 26, AlarmKit)
        case "alarms": Alarms.save(b["json"] as? String ?? "[]"); pushState()
        case "alarmSkip": Alarms.skip((b["id"] as? NSNumber)?.intValue ?? 0, (b["on"] as? NSNumber)?.boolValue ?? true); pushState()
        case "alarmTry": Alarms.tryNow(b["json"] as? String ?? "{}")
        case "alarmUnsnooze": let al = (b["id"] as? NSNumber)?.intValue ?? 0; Alarms.unsnooze(UserDefaults.standard.string(forKey: "alSnzU_\(al)") ?? "", al: al); pushState()
        case "openSettings": if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
        case "openUrl": if let u = URL(string: b["url"] as? String ?? ""), ["https", "http", "mailto"].contains(u.scheme ?? "") { UIApplication.shared.open(u) }
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
      var st = window.__ios = Object.assign({ notify: 'off', names: {}, reminders: {}, version: '', textPct: 100, alarmKit: false, alarmAuth: 'ask', alarmState: {} }, window.__iosInit || {});
      var post = function(cmd, o){ var m = o || {}; m.cmd = cmd; H.postMessage(m); };
      window.__host = 'ios';
      var size = function(){ try { document.documentElement.style.webkitTextSizeAdjust = (st.textPct || 100) + '%'; } catch(e) {} };
      var mark = function(){ document.documentElement.classList.add('ios'); if (st.alarmKit) document.documentElement.classList.add('ak'); size(); };
      if (document.documentElement) mark(); else document.addEventListener('readystatechange', mark, { once: true });
      window.__iosSet = function(o){ Object.assign(st, o); size(); if (window.__onIosState) { try { window.__onIosState(); } catch(e) {} } };
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
        pendingOpen: function(){ var p = st.pendingOpen || ''; if (p) { st.pendingOpen = ''; post('pendingDone'); } return p; },
        pendingWidget: function(){ return ''; },
        openUrl: function(u){ post('openUrl', { url: u }); },
        // the home screen widgets (0.31): previews drawn by the app, each design's look saved for its widgets
        widgetPreview: function(t, j){ try { return prompt('wpv', JSON.stringify({ t: t, s: j })) || ''; } catch(e) { return ''; } },
        widgets: function(){ try { return prompt('wget', '') || ''; } catch(e) { return ''; } },
        widgetStyle: function(t, id, j){ post('widgetStyle', { t: t, json: j }); },
        openSettings: function(){ post('openSettings'); }
      };
      // real alarms from iOS 26 (AlarmKit); before it the page shows no alarms
      if (st.alarmKit) Object.assign(window.Android, {
        alarms: function(j){ post('alarms', { json: j }); },
        alarmState: function(){ return JSON.stringify(st.alarmState || {}); },
        alarmSkip: function(id, on){ post('alarmSkip', { id: id, on: !!on }); },
        alarmTry: function(j){ post('alarmTry', { json: j }); },
        alarmUnsnooze: function(id){ post('alarmUnsnooze', { id: id }); },
        alarmAuth: function(){ return st.alarmAuth || 'ask'; }
      });
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
