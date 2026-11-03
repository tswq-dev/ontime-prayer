'use strict';
// node js/verify.js [path/to/vectors.csv.gz] [path/to/expected_mismatches.json]
const fs = require('fs'), zlib = require('zlib'), readline = require('readline'), path = require('path');
const { prayerTimes } = require('./umq.js');
const REPO = __dirname + '/../data/';
const vec = process.argv[2] || REPO + 'vectors.csv.gz', expf = process.argv[3] || REPO + 'expected_mismatches.json';
const EV = ['fajr', 'sunrise', 'dhuhr', 'asr', 'maghrib', 'isha', 'eid'];
const rl = readline.createInterface({ input: fs.createReadStream(vec).pipe(zlib.createGunzip()) });
let n = 0, header = true; const bad = [];
rl.on('line', (line) => {
  if (header) { header = false; return; }
  const f = line.split(',');
  const [ds, date, lat, lon, zone, ram] = f;
  const r = prayerTimes(date, +lat, +lon, +zone, +ram);
  for (let i = 0; i < 7; i++) {
    const v = f[6 + i]; if (v === '') continue; n++;
    if (!r || r[EV[i]] !== v) bad.push([ds, date, lat, lon, zone, EV[i], v].join('|'));
  }
});
rl.on('close', () => {
  const exp = new Set(JSON.parse(fs.readFileSync(expf, 'utf8')).map(b => b.slice(0, 7).join('|')));
  const got = new Set(bad), extra = bad.filter(b => !exp.has(b)), missing = [...exp].filter(b => !got.has(b));
  console.log('values', n, 'mismatches', bad.length, '| not in expected', extra.length, '| expected but matched', missing.length);
  if (extra.length) console.log(extra.slice(0, 10).join('\n'));
  process.exit(extra.length || missing.length ? 1 : 0);
});
