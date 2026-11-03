/* umq.js — Umm al-Qura prayer times (reconstruction), browser + Node, no dependencies.
   Reproduces the minute values of the official Umm al-Qura calendar service. Unofficial; not KACST code.
   Spec: ALGORITHM.md. Verified against 14,657,797 server answers (tools: test/verify.js). MIT licence. */
(function (root) {
'use strict';
const T = {"L0":[[1.75347,0.0,0.0],[0.033417,4.669257,6283.0758499914],[0.000349,4.626102,12566.1516999828],[3.497056e-05,2.74411800971,5753.3848848968],[3.417571e-05,2.82886579606,3.523118349],[3.135896e-05,3.62767041758,77713.7714681205],[2.676218e-05,4.41808351397,7860.4193924392],[2.342687e-05,6.13516237631,3930.2096962196],[1.324292e-05,0.74246356352,11506.7697697936],[1.273166e-05,2.03709655772,529.6909650946],[1.199167e-05,1.10962944315,1577.3435424478],[9.9025e-06,5.23268129594,5884.9268465832],[9.01855e-06,2.04505443513,26.2983197998],[8.57223e-06,3.50849156957,398.1490034082],[7.79786e-06,1.17882652114,5223.6939198022],[7.53141e-06,2.53339053818,5507.5532386674],[5.05264e-06,4.58292563052,18849.2275499742],[4.92379e-06,4.20506639861,775.522611324],[3.56655e-06,2.91954116867,0.0673103028],[3.17087e-06,5.84901952218,11790.6290886588],[2.84125e-06,1.89869034186,796.2980068164],[2.71039e-06,0.31488607649,10977.078804699],[2.4281e-06,0.34481140906,5486.777843175],[2.0616e-06,4.80646606059,2544.3144198834],[2.05385e-06,1.86947813692,5573.1428014331],[2.02261e-06,2.45767795458,6069.7767545534],[1.55516e-06,0.83306073807,213.299095438],[1.32212e-06,3.41118275555,2942.4634232916],[1.26184e-06,1.0830263021,20.7753954924],[1.15132e-06,0.64544911683,0.9803210682],[1.02851e-06,0.63599846727,4694.0029547076],[1.01895e-06,0.97569221824,15720.8387848784],[1.01724e-06,4.26679821365,7.1135470008],[9.9206e-07,6.20992940258,2146.1654164752],[9.7607e-07,0.6810127227,155.4203994342],[8.5803e-07,5.98322631256,161000.6857376741],[8.5128e-07,1.29870743025,6275.9623029906],[8.4711e-07,3.67080093025,71430.69561812909],[7.9637e-07,1.807913307,17260.1546546904],[7.8756e-07,3.03698313141,12036.4607348882],[7.4651e-07,1.75508916159,5088.6288397668],[7.3874e-07,3.50319443167,3154.6870848956],[7.3547e-07,4.67926565481,801.8209311238],[6.9627e-07,0.83297596966,9437.762934887],[6.2449e-07,3.97763880587,8827.3902698748],[6.1148e-07,1.81839811024,7084.8967811152],[5.6963e-07,2.78430398043,6286.5989683404],[5.6116e-07,4.38694880779,14143.4952424306],[5.5577e-07,3.47006009062,6279.5527316424],[5.1992e-07,0.18914945834,12139.5535091068],[5.1605e-07,1.33282746983,1748.016413067],[5.1145e-07,0.28306864501,5856.4776591154],[4.9e-07,0.48735065033,1194.4470102246],[4.1036e-07,5.36817351402,8429.2412664666],[4.0938e-07,2.39850881707,19651.048481098],[3.92e-07,6.16832995016,10447.3878396044],[3.677e-07,6.04133859347,10213.285546211],[3.6596e-07,2.56955238628,1059.3819301892],[3.5954e-07,1.70876111898,2352.8661537718],[3.5566e-07,1.77597314691,6812.766815086],[3.3291e-07,0.59309499459,17789.845619785],[3.0412e-07,0.44294464135,83996.84731811189],[3.0047e-07,2.73975123935,1349.8674096588],[2.5352e-07,3.16470953405,4690.4798363586]],"L1":[[6283.319667,0.0,0.0],[0.002061,2.678235,6283.0758499914],[4.30343e-05,2.63512650414,12566.1516999828],[4.25264e-06,1.59046980729,3.523118349],[1.19261e-06,5.79557487799,26.2983197998],[1.08977e-06,2.96618001993,1577.3435424478],[9.3478e-07,2.59212835365,18849.2275499742],[7.2122e-07,1.13846158196,529.6909650946],[6.7768e-07,1.87472304791,398.1490034082],[6.7327e-07,4.40918235168,5507.5532386674],[5.9027e-07,2.8879703846,5223.6939198022],[5.5976e-07,2.17471680261,155.4203994342],[4.5407e-07,0.39803079805,796.2980068164],[3.6369e-07,0.46624739835,775.522611324],[2.8958e-07,2.64707383882,7.1135470008],[2.0844e-07,5.34138275149,0.9803210682],[1.9097e-07,1.84628332577,5486.777843175],[1.8508e-07,4.96855124577,213.299095438],[1.7293e-07,2.99116864949,6275.9623029906],[1.6233e-07,0.03216483047,2544.3144198834],[1.5832e-07,1.43049285325,2146.1654164752],[1.4615e-07,1.20532366323,10977.078804699],[1.2461e-07,2.83432285512,1748.016413067],[1.1877e-07,3.25804815607,5088.6288397668],[1.1808e-07,5.2737979048,1194.4470102246],[1.1514e-07,2.07502418155,4694.0029547076],[1.0641e-07,0.76614199202,553.5694028424],[9.969e-08,1.30262991097,6286.5989683404],[9.721e-08,4.23925472239,1349.8674096588],[9.452e-08,2.69957062864,242.728603974],[8.577e-08,5.64475868067,951.7184062506],[7.576e-08,5.30062664886,2352.8661537718],[6.385e-08,2.65033984967,9437.762934887],[6.101e-08,4.66632584188,4690.4798363586]],"L2":[[0.000529,0.0,0.0],[8.719837e-05,1.07209665242,6283.0758499914],[3.09125e-06,0.86728818832,12566.1516999828],[2.7339e-07,0.05297871691,3.523118349],[1.6334e-07,5.18826691036,26.2983197998],[1.5752e-07,3.6845788943,155.4203994342],[9.541e-08,0.75742297675,18849.2275499742],[8.937e-08,2.05705419118,77713.7714681205],[6.952e-08,0.8267330541,775.522611324],[5.064e-08,4.66284525271,1577.3435424478],[4.061e-08,1.03057162962,7.1135470008],[3.81e-08,3.4405080349,5573.1428014331],[3.463e-08,5.14074632811,796.2980068164],[3.169e-08,6.05291851171,5507.5532386674],[3.02e-08,1.19246506441,242.728603974],[2.886e-08,6.11652627155,529.6909650946],[2.714e-08,0.30637881025,398.1490034082],[2.538e-08,2.27992810679,553.5694028424],[2.371e-08,4.38118838167,5223.6939198022],[2.079e-08,3.75435330484,0.9803210682]],"L3":[[2.89226e-06,5.84384198723,6283.0758499914],[3.4955e-07,0.0,0.0],[1.6819e-07,5.48766912348,12566.1516999828],[2.962e-08,5.19577265202,155.4203994342],[1.288e-08,4.72200252235,3.523118349],[7.14e-09,5.30045809128,18849.2275499742],[6.35e-09,5.96925937141,242.728603974]],"L4":[[1.14084e-06,3.14159265359,0.0],[7.717e-08,4.13446589358,6283.0758499914],[7.65e-09,3.83803776214,12566.1516999828]],"L5":[[8.78e-09,3.14159265359,0.0]],"B0":[[2.7962e-06,3.19870156017,84334.66158130829],[1.01643e-06,5.42248619256,5507.5532386674],[8.0445e-07,3.88013204458,5223.6939198022],[4.3806e-07,3.70444689758,2352.8661537718],[3.1933e-07,4.00026369781,1577.3435424478]],"B1":[[9.03e-08,3.8972906189,5507.5532386674],[6.177e-08,1.73038850355,5223.6939198022]]};
const L_TERMS = [T.L0, T.L1, T.L2, T.L3, T.L4, T.L5];
const B_TERMS = [T.B0, T.B1];
const DT_TABLE = [1240, 1150, 1060, 980, 910, 850, 790, 740, 700, 650, 620, 580, 550, 530, 500, 480, 460, 440, 420, 400, 370, 350, 330, 310, 280, 260, 240, 220, 200, 180, 160, 140, 130, 120, 110, 100, 90, 90, 90, 90, 90, 90, 90, 90, 100, 100, 100, 100, 100, 110, 110, 110, 110, 110, 110, 110, 110, 120, 120, 120, 120, 120, 130, 130, 130, 130, 140, 140, 140, 150, 150, 150, 150, 160, 160, 160, 160, 160, 170, 170, 170, 170, 170, 170, 170, 170, 160, 160, 150, 140, 137, 131, 127, 125, 125, 125, 125, 125, 125, 123, 120, 114, 106, 96, 86, 75, 66, 60, 57, 56, 57, 59, 62, 65, 68, 71, 73, 75, 77, 78, 79, 75, 64, 54, 29, 16, -10, -27, -36, -47, -54, -52, -55, -56, -58, -59, -62, -64, -61, -47, -27, 0, 26, 54, 77, 105, 134, 160, 182, 202, 212, 224, 235, 239, 243, 240, 239, 239, 237, 240, 243, 253, 262, 273, 282, 291, 300, 307, 314, 322, 331, 340, 350, 365, 383, 402, 422, 445, 465, 485, 505, 522, 538, 549, 558, 569, 580]; // Meeus, Astronomical Algorithms 1st ed., Table 9.A: dT x 10 s, 1620..1992
const D2R = Math.PI / 180, R2D = 180 / Math.PI;
const integer = Math.trunc;
function limitDeg(x) { x /= 360; let l = 360 * (x - Math.floor(x)); if (l < 0) l += 360; return l; }
function limit180(x) { x /= 360; let l = 360 * (x - Math.floor(x)); if (l < -180) l += 360; else if (l > 180) l -= 360; return l; }
function limit01(x) { let l = x - Math.floor(x); if (l < 0) l += 1; return l; }

// NREL-SPA julian_day, Gregorian correction always applied (step 1)
function julianDay(year, month, day, hour, minute, second, dut1, tz) {
  const dayDecimal = day + (hour - tz + (minute + (second + dut1) / 60) / 60) / 24;
  if (month < 3) { month += 12; year--; }
  let jd = integer(365.25 * (year + 4716)) + integer(30.6001 * (month + 1)) + dayDecimal - 1524.5;
  const a = integer(year / 100);
  jd += (2 - a + integer(a / 4));
  return jd;
}

// civil date <-> day number from 2000-01-01 (proleptic Gregorian)
function daysFromCivil(y, m, d) {
  y -= m <= 2 ? 1 : 0;
  const era = Math.floor(y / 400), yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468 - 10957;
}
function civilFromDays(z) {
  z += 719468 + 10957;
  const era = Math.floor(z / 146097), doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp + (mp < 10 ? 3 : -9);
  return [yoe + era * 400 + (m <= 2 ? 1 : 0), m, d];
}

// Delta T (step 3): T from the requested LOCAL midnight; Meeus AA 1st ed.: table 1620..1992 (last interval extended),
// Stephenson-Houlden before 1620, 102.3 + 123.5T + 32.5T^2 for y > 2000 (strictly).
function deltaT(y, m, d, z) {
  const T = (julianDay(y, m, d, 0, 0, 0, 0, z) - 2451545) / 36525;
  const Y = 2000 + T * 100;
  if (Y > 2000) return 102.3 + T * (123.5 + T * 32.5);
  if (Y < 1620) return Y < 948 ? 2715.6 + T * (573.36 + T * 46.5) : 50.6 + T * (67.5 + T * 22.5);
  const i = Math.min(Math.trunc((Y - 1620) / 2), DT_TABLE.length - 2);
  const f = Y / 2 - (i + 810);
  return (DT_TABLE[i] + (DT_TABLE[i + 1] - DT_TABLE[i]) * f) / 10;
}

function series(terms, tau) {
  let sum = 0;
  for (let i = 0; i < terms.length; i++) {
    let s = 0; const tt = terms[i];
    for (let j = 0; j < tt.length; j++) s += tt[j][0] * Math.cos(tt[j][1] + tt[j][2] * tau);
    sum += s * Math.pow(tau, i);
  }
  return sum;
}
// Sun alpha/delta (deg) at JDE, obliquity eps (deg); no nutation, no aberration (step 4)
function sunAD(jde, eps) {
  const tau = (jde - 2451545) / 365250;
  const L = limitDeg(series(L_TERMS, tau) * R2D);
  const B = series(B_TERMS, tau) * R2D;
  const lam = limitDeg(L + 180), beta = -B;
  const l = lam * D2R, b = beta * D2R, e = eps * D2R;
  const alpha = limitDeg(Math.atan2(Math.sin(l) * Math.cos(e) - Math.tan(b) * Math.sin(e), Math.cos(l)) * R2D);
  const delta = Math.asin(Math.sin(b) * Math.cos(e) + Math.cos(b) * Math.sin(e) * Math.sin(l)) * R2D;
  return [alpha, delta];
}
function laskar(jme) {
  const u = jme / 10;
  return 84381.448 + u * (-4680.93 + u * (-1.55 + u * (1999.25 + u * (-51.38 + u * (-249.67 + u * (-39.05 + u * (7.12 + u * (27.87 + u * (5.79 + u * 2.45)))))))));
}
function gmst(jd) {
  const jc = (jd - 2451545) / 36525;
  return limitDeg(280.46061837 + 360.98564736629 * (jd - 2451545) + jc * jc * (0.000387933 - jc / 38710000));
}
function interp(ad, n) {
  let a = ad[1] - ad[0], b = ad[2] - ad[1];
  if (Math.abs(a) >= 2) a = limit01(a);
  if (Math.abs(b) >= 2) b = limit01(b);
  return ad[1] + n * (a + b + (b - a) * n) / 2;
}

const GMST_OFF = 0.146457 / 3600, EPS_OFF = -0.039131 / 3600, SUNRISE_OFF = 0.0032001;
const H0 = -0.8333, FAJR_ALT = -18.5, EID_ALT = 4.2;
const FAJR_HA_LIMIT = 151.84401, TW_ADD = 58.99367;

function altHA(alt, phi, dec) { // closed-form hour angle (deg) or NaN
  const c = (Math.sin(alt * D2R) - Math.sin(phi * D2R) * Math.sin(dec * D2R)) / (Math.cos(phi * D2R) * Math.cos(dec * D2R));
  if (c < -1 || c > 1) return NaN;
  return Math.acos(c) * R2D;
}

// astronomy for a date/zone (latitude independent)
function dayAstro(y, mo, d, lon, z) {
  const jdReq = julianDay(y, mo, d, 0, 0, 0, 0, z);
  const t = (jdReq - 2451545) / 36525;
  const hours = Math.floor(t * 36525 * 24 + z);
  const cdays = Math.floor((12 + hours) / 24);
  const [cy, cm, cd] = civilFromDays(cdays);
  const jd0 = julianDay(cy, cm, cd, 0, 0, 0, 0, z);
  const err = (((jd0 - 2451545) - (cdays - 0.5)) + z / 24) * 86400;
  const dT = deltaT(y, mo, d, z);
  const eps = (laskar((jdReq + dT / 86400 - 2451545) / 365250) / 3600) + EPS_OFF;
  const alpha = [], delta = [];
  for (let k = -1; k <= 1; k++) { const r = sunAD(jd0 + k + dT / 86400, eps); alpha.push(r[0]); delta.push(r[1]); }
  const nu = gmst(jd0) + GMST_OFF;
  const A = { lon, z, dT, err, nu, alpha, delta };
  A.H = (m) => limit180(nu + 360.985647 * m + lon - interp(alpha, m + dT / 86400));
  let m0 = (alpha[1] - lon - nu) / 360;
  if (m0 < 0) m0 += 1;
  A.m0 = m0;
  let tr = m0;                                   // transit iterated like rise/set (|step| < 0.0002 day)
  for (let i = 0; i < 20; i++) { const st = -A.H(tr) / 360; tr += st; if (Math.abs(st) < 0.0002) break; }
  if (tr < 0) tr += 1;
  A.transit = tr;
  return A;
}

function newton(A, phi, m) {
  const sp = Math.sin(phi * D2R), cp = Math.cos(phi * D2R);
  for (let i = 0; i < 20; i++) {
    const n = m + A.dT / 86400;
    const dec = interp(A.delta, n) * D2R;
    const H = limit180(A.nu + 360.985647 * m + A.lon - interp(A.alpha, n)) * D2R;
    const h = Math.asin(sp * Math.sin(dec) + cp * Math.cos(dec) * Math.cos(H)) * R2D;
    const dm = (h - H0) * Math.cos(h * D2R) / (360 * Math.cos(dec) * cp * Math.sin(H));
    m += dm;
    if (Math.abs(dm) < 0.0002) return m;
    if (Math.abs(dm) > 5) return null;
  }
  return null;
}
function intoDay(s) { if (s < 0) return s + 86400; if (s >= 86400) return s - 86400; return s; }
const sec = (A, m) => m * 86400 + A.err;

// latitude-dependent events (seconds, raw)
function latEvents(A, phi) {
  const r = { ok: false };
  const H0d = altHA(H0, phi, A.delta[1]);
  if (!isNaN(H0d)) {
    let mr = A.m0 - H0d / 360; if (mr < 0) mr += 1;
    let ms = A.m0 + H0d / 360; if (ms >= 1) ms -= 1;
    const r1 = newton(A, phi, mr), r2 = newton(A, phi, ms);
    if (r1 !== null && r2 !== null) {
      r.sunrise = intoDay(sec(A, r1) + SUNRISE_OFF);
      r.maghrib = intoDay(sec(A, r2));
      const day = ((r.maghrib - r.sunrise) % 86400 + 86400) % 86400;
      r.ok = day >= 3600 && 86400 - day >= 3600;
    }
  }
  const d1 = A.delta[2];
  r.fajrHA = altHA(FAJR_ALT, phi, d1);
  r.fajr = sec(A, A.transit - r.fajrHA / 360);
  const asrAlt = Math.atan(1 / (1 + Math.tan(Math.abs(phi - d1) * D2R))) * R2D;
  r.asr = sec(A, A.transit + altHA(asrAlt, phi, d1) / 360);
  const eidHA = altHA(EID_ALT, phi, d1);
  r.eid = isNaN(eidHA) ? NaN : sec(A, A.transit - eidHA / 360);
  return r;
}

function fmt(M) {
  const h = integer(M / 60), mi = M - 60 * h;
  const f = (v) => (v < 0 ? '-' : '') + String(Math.abs(v)).padStart(2, '0');
  return f(h) + ':' + f(mi);
}
const up = (s) => { const t = integer(s); return integer((t + 59) / 60); };
const down = (s) => integer(integer(s) / 60);

const pCache = new Map();
function solsticeP(y, phi, lon, z) {
  const key = y + '|' + (phi < 0) + '|' + lon + '|' + z;
  if (pCache.has(key)) return pCache.get(key);
  const lat45 = phi < 0 ? -45 : 45;
  const A = dayAstro(y, phi < 0 ? 12 : 6, 21, lon, z);
  const e = latEvents(A, lat45);
  const night = 86400 - e.maghrib + e.sunrise; // GAP: not reduced mod 86400
  const p = (e.sunrise - e.fajr + TW_ADD) / night;
  pCache.set(key, p);
  return p;
}

function valid(y, mo, d, lat, lon, z) {
  if (!(Math.abs(lat) <= 90 && Math.abs(lon) <= 180)) return false;
  if (!(z >= -12 && z <= 14)) return false;
  const [a, b, c] = civilFromDays(daysFromCivil(y, mo, d));
  return a === y && b === mo && c === d;
}

function prayerTimes(dateISO, lat, lon, zone = 3, ramadan = 0) {
  const [y, mo, d] = dateISO.split('-').map(Number);
  if (!valid(y, mo, d, lat, lon, zone)) return null;
  const A = dayAstro(y, mo, d, lon, zone);
  const raw = latEvents(A, lat);
  let ev = raw;
  if (!raw.ok) ev = latEvents(A, lat < 0 ? -45 : 45);
  const dhuhr = sec(A, A.transit);
  const out = {};
  // Fajr
  if (Math.abs(lat) > 48 && (isNaN(ev.fajrHA) || ev.fajrHA > FAJR_HA_LIMIT)) { // GAP: HA tested on the latitude in use
    const p = solsticeP(y, lat, lon, zone);
    const night = ((86400 - ev.maghrib + ev.sunrise) % 86400 + 86400) % 86400;
    out.fajr = fmt(down(ev.sunrise - p * night));
  } else out.fajr = fmt(up(ev.fajr));
  out.sunrise = fmt(down(ev.sunrise));
  out.dhuhr = fmt(up(dhuhr));
  out.asr = fmt(up(ev.asr));
  out.maghrib = fmt(up(ev.maghrib));
  out.isha = fmt(up(ev.maghrib + (ramadan ? 7200 : 5400)));
  out.eid = isNaN(ev.eid) ? fmt(down(ev.sunrise + 900)) : fmt(up(ev.eid)); // GAP: sunrise rounding
  return out;
}


const api = { prayerTimes, deltaT, julianDay, siteCoordinate };
// the website rounds GPS coordinates to 6 decimals (shortest decimal form, ties away from zero)
function siteCoordinate(x) {
  let s = String(x); const neg = s[0] === '-'; let a = neg ? s.slice(1) : s;
  if (a.includes('e')) {                     // expand exponent form (shortest repr) to plain digits
    const [mant, ex] = a.split('e'); const e = Number(ex); const [mi, mf = ''] = mant.split('.'); const dg = mi + mf;
    const pt = mi.length + e;
    a = pt <= 0 ? '0.' + '0'.repeat(-pt) + dg : pt >= dg.length ? dg + '0'.repeat(pt - dg.length) : dg.slice(0, pt) + '.' + dg.slice(pt);
  }
  s = (neg ? '-' : '') + a;
  const [ip, fp = ''] = a.split('.');
  if (fp.length <= 6) return Number(s);
  let digits = (ip + fp.slice(0, 6)).split('').map(Number);
  if (fp.charCodeAt(6) >= 53) { let k = digits.length - 1; while (k >= 0) { if (digits[k] < 9) { digits[k]++; break; } digits[k] = 0; k--; } if (k < 0) digits.unshift(1); }
  const str = digits.join(''); const v = Number(str.slice(0, str.length - 6) + '.' + str.slice(-6));
  return neg ? -v : v;
}
if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.UMQ = api;
})(typeof self !== 'undefined' ? self : this);
