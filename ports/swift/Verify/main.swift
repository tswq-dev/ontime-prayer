// Reads data/vectors.csv (plain CSV on stdin), prints one line per mismatch (ds|date|lat|lon|zone|field|want) and "values N".
let ev = ["fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha", "eid"]
var n = 0, out = [String](), header = true
while let line = readLine() {
    if header { header = false; continue }
    let f = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
    let t = UmmAlQura.prayerTimes(date: f[1], lat: Double(f[2])!, lon: Double(f[3])!, zone: Double(f[4])!, ramadan: f[5] == "1")
    let got = t.map { [$0.fajr, $0.sunrise, $0.dhuhr, $0.asr, $0.maghrib, $0.isha, $0.eid] }
    for i in 0..<7 where !f[6 + i].isEmpty {
        n += 1
        if got == nil || got![i] != f[6 + i] { out.append([f[0], f[1], f[2], f[3], f[4], ev[i], f[6 + i]].joined(separator: "|")) }
    }
}
for l in out { print(l) }
print("values \(n)")
