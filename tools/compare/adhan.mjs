// How often a popular open-source prayer-times library differs from the official Umm al-Qura times.
// Library: adhan-js 4.4.6 (github.com/batoulapps/adhan-js, commit e779a32), CalculationMethod.UmmAlQura()
// (Fajr 18.5°, Isha = Maghrib + 90 min; +120 min on Ramadan days), Shafi Asr.
// Data: data/vectors.csv.gz, dataset "Saudi full export" (173 official Saudi cities, every day 2025–2035).
//
//   git clone https://github.com/batoulapps/adhan-js && cp -r adhan-js/src adh
//   (in adh/*.ts: change "./X.js" imports to "./X.ts" and "import { ValueOf }" to "import type { ValueOf }")
//   node --experimental-transform-types tools/compare/adhan.mjs adh/Adhan.ts data/vectors.csv.gz            # library defaults
//   node --experimental-transform-types tools/compare/adhan.mjs adh/Adhan.ts data/vectors.csv.gz official   # + official rounding
//
// "official" rounding: whole seconds kept (truncated), sunrise to the previous minute, the other times to the next minute.
import fs from 'fs'; import zlib from 'zlib'; import readline from 'readline'; import path from 'path';
const [lib, vec, mode] = process.argv.slice(2), official = mode === 'official';
const M = await import(path.resolve(lib)), A = M.default || M;
const K = ['fajr', 'sunrise', 'dhuhr', 'asr', 'maghrib', 'isha'];
const P = A.CalculationMethod.UmmAlQura(), PR = A.CalculationMethod.UmmAlQura(); PR.ishaInterval = 120;
if (official) P.rounding = PR.rounding = 'none';
const diff = Object.fromEntries(K.map(k => [k, {}])); let rows = 0, days = 0, n = 0, head = true;
const rl = readline.createInterface({ input: fs.createReadStream(vec).pipe(zlib.createGunzip()) });
for await (const line of rl) {
  if (head) { head = false; continue; }
  const f = line.split(','); if (f[0] !== 'Saudi full export') continue;
  const [y, m, d] = f[1].split('-').map(Number);
  const t = new A.PrayerTimes(new A.Coordinates(+f[2], +f[3]), new Date(y, m - 1, d), f[5] === '1' ? PR : P);
  rows++; let any = false;
  K.forEach((k, i) => { if (!f[6 + i]) return;
    let mins;
    if (official) { const s = Math.floor((t[k].getTime() / 1000 + 3 * 3600) % 86400); mins = k === 'sunrise' ? Math.floor(s / 60) : Math.ceil(s / 60); }
    else { const g = new Date(t[k].getTime() + 3 * 3600e3); mins = g.getUTCHours() * 60 + g.getUTCMinutes(); }
    const [h, mi] = f[6 + i].split(':').map(Number); let dd = mins - (h * 60 + mi); if (dd > 720) dd -= 1440; if (dd < -720) dd += 1440;
    diff[k][dd] = (diff[k][dd] || 0) + 1; n++; if (dd) any = true; });
  if (any) days++;
}
let bad = 0; const per = {};
for (const k of K) { const c = diff[k], all = Object.values(c).reduce((a, b) => a + b, 0), b = all - (c[0] || 0); bad += b; per[k] = { pct_different: +(100 * b / all).toFixed(2), minutes_off: c }; }
console.log(JSON.stringify({ library: 'adhan-js 4.4.6 UmmAlQura', rounding: official ? 'official' : 'library default', days: rows, values: n,
  pct_values_different: +(100 * bad / n).toFixed(2), pct_days_with_a_difference: +(100 * days / rows).toFixed(1), per }, null, 1));
