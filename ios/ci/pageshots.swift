// Pictures of the page as the iPhone's WebKit draws it: a WKWebView on macOS at iPhone size, with the app's own
// `window.Android` shim, walked through a first opening (the opening, the welcome, every tour step, the questions,
// the quick setup) and a few screens after. For checking in CI (.github/workflows/ios-page.yml):
//   swiftc -O -parse-as-library ios/ci/pageshots.swift -o pageshots && ./pageshots <www dir> <shim.js> <out dir>
// The page's errors and what was on screen at each picture go to <out>/log.md.
import AppKit
import WebKit

@main struct PageShots {
    @MainActor static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        let r = Runner()
        app.delegate = r
        app.run()
    }
}

/// The app's ontime://app/… scheme: files from the www folder.
final class Scheme: NSObject, WKURLSchemeHandler {
    let root: URL
    init(_ root: URL) { self.root = root }
    func webView(_ w: WKWebView, start t: WKURLSchemeTask) {
        guard let u = t.request.url else { return }
        let f = root.appendingPathComponent(u.path)
        guard let d = try? Data(contentsOf: f) else { t.didFailWithError(URLError(.fileDoesNotExist)); return }
        let ext = f.pathExtension.lowercased()
        let mime = ["html": "text/html", "js": "text/javascript", "css": "text/css", "woff": "font/woff", "woff2": "font/woff2",
                    "ttf": "font/ttf", "png": "image/png", "svg": "image/svg+xml", "json": "application/json",
                    "ogg": "audio/ogg", "mp3": "audio/mpeg"][ext] ?? "application/octet-stream"
        let text = ["html", "js", "css", "json", "svg"].contains(ext)
        t.didReceive(HTTPURLResponse(url: u, statusCode: 200, httpVersion: "HTTP/1.1",
                                     headerFields: ["Content-Type": mime + (text ? "; charset=utf-8" : ""), "Content-Length": String(d.count)])!)
        t.didReceive(d)
        t.didFinish()
    }
    func webView(_ w: WKWebView, stop t: WKURLSchemeTask) {}
}

@MainActor final class Runner: NSObject, NSApplicationDelegate, WKScriptMessageHandler, WKUIDelegate {
    var web: WKWebView!
    var win: NSWindow!
    var out: URL!
    var n = 0
    var log: [String] = []

    func applicationDidFinishLaunching(_ note: Notification) {
        let a = CommandLine.arguments
        guard a.count >= 4 else { print("usage: pageshots <www> <shim.js> <out>"); exit(1) }
        let www = URL(fileURLWithPath: a[1])
        out = URL(fileURLWithPath: a[3])
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let cfg = WKWebViewConfiguration()
        cfg.setURLSchemeHandler(Scheme(www), forURLScheme: "ontime")
        cfg.websiteDataStore = .nonPersistent()
        cfg.userContentController.add(self, name: "ontime")
        cfg.userContentController.add(self, name: "log")
        let shim = (try? String(contentsOf: URL(fileURLWithPath: a[2]), encoding: .utf8)) ?? ""
        // an iPhone on iOS 26 with notifications allowed; the page's errors come back here
        let pre = """
        window.__iosInit = { notify: 'on', alarmKit: true, alarmAuth: 'ok', version: '1.0.1', textPct: 100 };
        (function(){ var L = function(k, a){ try { window.webkit.messageHandlers.log.postMessage(k + ': ' + [].map.call(a, String).join(' ')); } catch(e) {} };
          window.addEventListener('error', function(e){ L('error', [e.message + ' (line ' + e.lineno + ':' + e.colno + ')']); });
          var ce = console.error; console.error = function(){ L('console.error', arguments); ce.apply(console, arguments); };
          window.addEventListener('unhandledrejection', function(e){ L('rejection', [String(e.reason)]); }); })();
        """
        cfg.userContentController.addUserScript(WKUserScript(source: pre + "\n" + shim, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        let size = NSRect(x: 0, y: 0, width: 393, height: 852)
        web = WKWebView(frame: size, configuration: cfg)
        web.uiDelegate = self
        web.customUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
        win = NSWindow(contentRect: size, styleMask: [.borderless], backing: .buffered, defer: false)
        win.contentView = web
        win.setFrameOrigin(NSPoint(x: 20, y: 20))
        win.orderFrontRegardless()
        web.load(URLRequest(url: URL(string: "ontime://app/index.html")!))
        Task { @MainActor in
            await self.run()
            try? self.log.joined(separator: "\n").write(to: self.out.appendingPathComponent("log.md"), atomically: true, encoding: .utf8)
            exit(0)
        }
    }

    // the native side the page talks to: answers what a first run needs
    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        if m.name == "log" { log.append("- **" + String(describing: m.body) + "**"); return }
        guard let b = m.body as? [String: Any], let cmd = b["cmd"] as? String else { return }
        switch cmd {
        case "askNotify": web.evaluateJavaScript("window.__iosSet && __iosSet({notify:'on'}); setTimeout(function(){ window.__onNotify && __onNotify(); }, 100)")
        case "locate": web.evaluateJavaScript("setTimeout(function(){ window.__onLocation && __onLocation(21.4225, 39.8262, 25); }, 300)")
        default: break
        }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) { completionHandler(nil) }

    func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1e9)) }

    func js(_ s: String) async -> String {
        await withCheckedContinuation { k in
            web.evaluateJavaScript("(function(){ try { var v = (" + s + "); return v == null ? '' : String(v); } catch(e) { return 'ERR ' + e; } })()") { r, _ in
                k.resume(returning: (r as? String) ?? "")
            }
        }
    }

    func shot(_ name: String, _ say: String = "") async {
        let c = WKSnapshotConfiguration()
        c.afterScreenUpdates = true
        n += 1
        let file = String(format: "%02d-%@.png", n, name)
        if let img = try? await web.takeSnapshot(configuration: c), let tiff = img.tiffRepresentation,
           let rep = NSBitmapImageRep(data: tiff), let png = rep.representation(using: .png, properties: [:]) {
            try? png.write(to: out.appendingPathComponent(file))
        }
        log.append("\n### " + file + (say.isEmpty ? "" : "\n```\n" + say + "\n```"))
    }

    static let click = """
    (function(re){ re = new RegExp(re); var els = [].slice.call(document.querySelectorAll('button, [role=button], .opt, .srow, label, a')).filter(function(e){
      return e.offsetParent && e.getBoundingClientRect().height > 0 && re.test((e.textContent || '').trim())
        && (!e.closest('.sheet') || e.closest('.sheet').classList.contains('show')) && !e.closest('[hidden]'); });
      var onTop = function(e){ var r = e.getBoundingClientRect(), h = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2); return h && (e === h || e.contains(h)); };
      var top = els.filter(onTop);   // no scrollIntoView: a user can't scroll what the page doesn't let them (it scrolled the screen itself)
      var e = top[0]; if (!e) return ''; e.click(); return (e.textContent || '').trim().slice(0, 40); })
    """

    // where things are: the scroll of every box that can scroll, the ring, the alarm and settings icons, what is under the ring
    static let state = """
    (function(){ var c = document.getElementById('coach'), ring = document.getElementById('chRing'), o = [];
      if (c && !c.hidden) { var rr = ring.getBoundingClientRect(); var h = document.elementFromPoint(rr.left + rr.width/2, rr.top + rr.height/2);
        o.push('ring ' + Math.round(rr.left) + ',' + Math.round(rr.top) + ' under: ' + (h ? (h.id || h.className || h.tagName) : '-')); }
      ['almBtn', 'setBtn'].forEach(function(id){ var e = document.getElementById(id); if (e) { var r = e.getBoundingClientRect(); o.push(id + ' ' + Math.round(r.left) + ',' + Math.round(r.top)); } });
      var sc = []; [document.scrollingElement].concat([].slice.call(document.querySelectorAll('*'))).forEach(function(e){ if (e && (e.scrollTop || e.scrollLeft)) sc.push((e.id || e.className || e.tagName).toString().slice(0, 24) + ' ' + e.scrollLeft + ',' + e.scrollTop); });
      o.push('scrolled: ' + (sc.join('; ') || 'none')); o.push('intro ' + !!document.getElementById('intro'));
      o.push('sheets: ' + [].slice.call(document.querySelectorAll('.sheet.show')).map(function(e){ return e.id; }).join(','));
      return o.join(' | '); })()
    """

    func click(_ pat: String) async -> String {
        let q = String(data: try! JSONSerialization.data(withJSONObject: [pat]), encoding: .utf8)!.dropFirst().dropLast()
        return await js(Self.click + "(" + q + ")")
    }

    let onScreen = "[].slice.call(document.querySelectorAll('.sheet.show, #coach')).filter(function(e){ return e.offsetParent && getComputedStyle(e).visibility !== 'hidden' && +getComputedStyle(e).opacity > 0.1; }).map(function(e){ return e.innerText; }).join('\\n---\\n').slice(0, 900)"

    func run() async {
        // the opening, frame by frame
        var t = 0.0
        for at in [0.8, 1.6, 2.2, 2.6, 3.0, 3.4, 3.8, 4.4, 5.0, 5.6, 6.2, 7.0] {
            await wait(at - t); t = at
            await shot(String(format: "intro-%.1fs", at), await js("(function(){ var w = document.getElementById('iword'), b = document.getElementById('intro'); if (!b) return 'intro gone'; var r = w.getBoundingClientRect(), cs = getComputedStyle(w); return 'intro: ' + b.className + ' | word top ' + Math.round(r.top) + ' h ' + Math.round(r.height) + ' w ' + Math.round(r.width) + ' | clip ' + cs.clipPath + ' | font ' + cs.fontFamily + ' loaded ' + document.fonts.check('54px \"Aref Ruqaa\"'); })()"))
        }
        // the first run: through every screen, tapping on as a new user would (the tour starts a few seconds after the opening)
        var seen: [String: Int] = [:]
        for _ in 0..<45 {
            var txt = ""
            for _ in 0..<24 { txt = await js(onScreen); if !txt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { break }; await wait(0.5) }
            if txt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { break }
            await wait(0.9)   // a sheet slides in
            let key = String(txt.prefix(120)); seen[key, default: 0] += 1
            if seen[key]! > 3 { log.append("- the same screen 3 times, stopped"); break }
            let state = await js(Self.state)
            if txt.contains("مدن تقويم أم القرى") {
                _ = await js("(function(){ var q = document.querySelector('#cityQ'); q.value = 'مكه'; q.dispatchEvent(new Event('input')); return 1; })()")
                await wait(0.8)
                await shot("city", txt)
                _ = await js("(function(){ var b = document.querySelector('#cityList button'); if (b) b.click(); return 1; })()")
                await wait(1.5); continue
            }
            await shot("step", txt + "\n[" + state + "]")
            var r = await click("^(التالي|ابدأ|متابعة|موافق|تم|نعم|حسناً|ابدأ الآن|اسمح بالتنبيهات|استخدم موقعي)$")
            if r.isEmpty { r = await js("(function(){ var b = document.querySelector('.sheet.show button, #coach:not([hidden]) .ch-next'); if (!b) return ''; b.click(); return b.textContent.trim().slice(0, 40); })()") }
            log.append("- tapped: " + r)
            if r.isEmpty { break }
            await wait(1.6)
        }
        await wait(1.0)
        await shot("main", await js("document.body.innerText.slice(0, 300)"))
        // the tour again from the start, as from «شاهد الجولة التعريفية», to see the alarm step over a drawn screen
        _ = await js("(function(){ document.getElementById('setBtn').click(); return 1; })()")
        await wait(1.2)
        await shot("settings")
        let r = await js("(function(){ var b = document.getElementById('tourAgain'); if (!b) return ''; b.click(); return 'tourAgain'; })()")
        log.append("- replay: " + r)
        await wait(2.0)
        for _ in 0..<12 {
            let txt = await js(onScreen)
            if txt.isEmpty { break }
            let state = await js(Self.state)
            await shot("replay", txt + "\n[" + state + "]")
            let k = await click("^(التالي|ابدأ)$")
            if k.isEmpty { break }
            await wait(1.4)
        }
    }
}
