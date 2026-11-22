import Foundation

/// The month of days the page sends for the widgets (`buildWidgetData()`), kept in the App Group so the widget
/// extension can read it. If the group is missing (a re-signed install that dropped or renamed it), there is no
/// container and the widgets show a calm "open the app" instead.
enum SharedStore {
    static let file = "widgetData.json"

    /// The group as built, then one derived from the bundle id (installers that rename the app also rename its group).
    static var groupIDs: [String] {
        var ids = ["group.com.itqan.ontime"]
        if var b = Bundle.main.bundleIdentifier {
            if b.hasSuffix(".widgets") { b = String(b.dropLast(".widgets".count)) }
            let g = "group." + b
            if !ids.contains(g) { ids.append(g) }
        }
        return ids
    }

    static var container: URL? {
        for id in groupIDs {
            if let u = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) { return u }
        }
        return nil
    }

    /// Saves the JSON; true when it changed (so the app reloads the widgets only then).
    @discardableResult
    static func write(_ json: String) -> Bool {
        guard !json.isEmpty, let u = container?.appendingPathComponent(file) else { return false }
        let data = Data(json.utf8)
        if let old = try? Data(contentsOf: u), old == data { return false }
        do { try data.write(to: u, options: .atomic) } catch { return false }
        return true
    }

    static func read() -> Data? {
        guard let u = container?.appendingPathComponent(file) else { return nil }
        return try? Data(contentsOf: u)
    }
}
