// umm_al_qura.dart — Umm al-Qura prayer times (reconstruction), Dart / Flutter, no dependencies.
// Reproduces, to the minute, the prayer times shown by the official Umm al-Qura calendar website.
// Unofficial; not KACST code. Spec: ALGORITHM.md. Port of js/umq.js, verified on the same 14,657,797 values
// (ports/dart/verify.dart). MIT licence. https://github.com/tswq-dev/ontime-prayer
import 'dart:math' as math;

/// Seven times as "HH:MM" strings in the requested time zone.
class PrayerTimes {
  final String fajr, sunrise, dhuhr, asr, maghrib, isha, eid;
  const PrayerTimes(this.fajr, this.sunrise, this.dhuhr, this.asr, this.maghrib, this.isha, this.eid);
  @override
  String toString() => 'fajr $fajr, sunrise $sunrise, dhuhr $dhuhr, asr $asr, maghrib $maghrib, isha $isha, eid $eid';
}

class UmmAlQura {
  /// Prayer times for a Gregorian date "YYYY-MM-DD"; lat/lon in decimal degrees (north, east positive);
  /// zone = UTC offset in hours (Saudi Arabia: 3); ramadan = true in Ramadan (Isha = Maghrib + 120 min).
  /// Returns null for an invalid date, coordinates or zone.
  static PrayerTimes? prayerTimes(String date, double lat, double lon, {double zone = 3, bool ramadan = false}) {
    final p = date.split('-');
    if (p.length != 3) return null;
    final y = int.tryParse(p[0]), mo = int.tryParse(p[1]), d = int.tryParse(p[2]);
    if (y == null || mo == null || d == null) return null;
    if (!_valid(y, mo, d, lat, lon, zone)) return null;
    final a = _dayAstro(y.toDouble(), mo.toDouble(), d.toDouble(), lon, zone);
    final raw = _latEvents(a, lat);
    final ev = raw.ok ? raw : _latEvents(a, lat < 0 ? -45.0 : 45.0);
    final dhuhr = _sec(a, a.transit);
    String fajr;
    if (lat.abs() > 48 && (ev.fajrHA.isNaN || ev.fajrHA > _fajrHALimit)) {
      final pp = _solsticeP(y.toDouble(), lat, lon, zone);
      final night = _mod86400(86400 - ev.maghrib + ev.sunrise);
      fajr = _fmt(_down(ev.sunrise - pp * night));
    } else {
      fajr = _fmt(_up(ev.fajr));
    }
    return PrayerTimes(fajr, _fmt(_down(ev.sunrise)), _fmt(_up(dhuhr)), _fmt(_up(ev.asr)), _fmt(_up(ev.maghrib)),
        _fmt(_up(ev.maghrib + (ramadan ? 7200 : 5400))), ev.eid.isNaN ? _fmt(_down(ev.sunrise + 900)) : _fmt(_up(ev.eid)));
  }

  /// ΔT in seconds as the server uses it (exposed for testing).
  static double deltaT(double y, double m, double d, double z) {
    final t = (_julianDay(y, m, d, 0, 0, 0, 0, z) - 2451545) / 36525;
    final yy = 2000 + t * 100;
    if (yy > 2000) return 102.3 + t * (123.5 + t * 32.5);
    if (yy < 1620) return yy < 948 ? 2715.6 + t * (573.36 + t * 46.5) : 50.6 + t * (67.5 + t * 22.5);
    final i = math.min(((yy - 1620) / 2).truncate(), _dtTable.length - 2);
    final f = yy / 2 - (i + 810);
    return (_dtTable[i] + (_dtTable[i + 1] - _dtTable[i]) * f) / 10;
  }

  /// The website rounds GPS coordinates to 6 decimals (shortest decimal form, ties away from zero).
  static double siteCoordinate(double x) {
    var s = x.toString();
    final neg = s.startsWith('-');
    var a = neg ? s.substring(1) : s;
    if (a.contains('e')) {                       // expand exponent form to plain digits
      final parts = a.split('e'), e = int.parse(parts[1]);
      final mant = parts[0].split('.'), mi = mant[0], mf = mant.length > 1 ? mant[1] : '', dg = mi + mf;
      final pt = mi.length + e;
      a = pt <= 0 ? '0.${'0' * -pt}$dg' : pt >= dg.length ? dg + '0' * (pt - dg.length) : '${dg.substring(0, pt)}.${dg.substring(pt)}';
    }
    final ip = a.split('.')[0], fp = a.contains('.') ? a.split('.')[1] : '';
    if (fp.length <= 6) return double.parse((neg ? '-' : '') + a);
    final digits = (ip + fp.substring(0, 6)).split('').map(int.parse).toList();
    if (fp.codeUnitAt(6) >= 53) {
      var k = digits.length - 1;
      while (k >= 0) { if (digits[k] < 9) { digits[k]++; break; } digits[k] = 0; k--; }
      if (k < 0) digits.insert(0, 1);
    }
    final str = digits.join();
    final v = double.parse('${str.substring(0, str.length - 6)}.${str.substring(str.length - 6)}');
    return neg ? -v : v;
  }

  // ---------------------------------------------------------------- tables
  static const List<List<List<double>>> _lTerms = [%L_TERMS%];
  static const List<List<List<double>>> _bTerms = [%B_TERMS%];
  static const List<double> _dtTable = [%DT_TABLE%];

  static const double _d2r = math.pi / 180, _r2d = 180 / math.pi;
  static const double _gmstOff = 0.146457 / 3600, _epsOff = -0.039131 / 3600, _sunriseOff = 0.0032001;
  static const double _h0 = -0.8333, _fajrAlt = -18.5, _eidAlt = 4.2;
  static const double _fajrHALimit = 151.84401, _twAdd = 58.99367;

  static double _trunc(double x) => x.truncateToDouble();
  static double _floor(double x) => x.floorToDouble();
  static double _mod86400(double x) => (x.remainder(86400) + 86400).remainder(86400);
  static double _limitDeg(double x0) { final x = x0 / 360; var l = 360 * (x - _floor(x)); if (l < 0) l += 360; return l; }
  static double _limit180(double x0) { final x = x0 / 360; var l = 360 * (x - _floor(x)); if (l < -180) { l += 360; } else if (l > 180) { l -= 360; } return l; }
  static double _limit01(double x) { var l = x - _floor(x); if (l < 0) l += 1; return l; }

  // NREL-SPA julian_day, Gregorian correction always applied
  static double _julianDay(double year, double month, double day, double hour, double minute, double second, double dut1, double tz) {
    final dayDecimal = day + (hour - tz + (minute + (second + dut1) / 60) / 60) / 24;
    if (month < 3) { month += 12; year--; }
    var jd = _trunc(365.25 * (year + 4716)) + _trunc(30.6001 * (month + 1)) + dayDecimal - 1524.5;
    final a = _trunc(year / 100);
    jd += (2 - a + _trunc(a / 4));
    return jd;
  }

  // civil date <-> day number from 2000-01-01 (proleptic Gregorian)
  static double _daysFromCivil(double y, double m, double d) {
    y -= m <= 2 ? 1 : 0;
    final era = _floor(y / 400), yoe = y - era * 400;
    final doy = _floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
    final doe = yoe * 365 + _floor(yoe / 4) - _floor(yoe / 100) + doy;
    return era * 146097 + doe - 719468 - 10957;
  }
  static List<double> _civilFromDays(double z) {
    z += 719468 + 10957;
    final era = _floor(z / 146097), doe = z - era * 146097;
    final yoe = _floor((doe - _floor(doe / 1460) + _floor(doe / 36524) - _floor(doe / 146096)) / 365);
    final doy = doe - (365 * yoe + _floor(yoe / 4) - _floor(yoe / 100));
    final mp = _floor((5 * doy + 2) / 153);
    final d = doy - _floor((153 * mp + 2) / 5) + 1;
    final m = mp + (mp < 10 ? 3 : -9);
    return [yoe + era * 400 + (m <= 2 ? 1 : 0), m, d];
  }

  static double _series(List<List<List<double>>> terms, double tau) {
    var sum = 0.0;
    for (var i = 0; i < terms.length; i++) {
      var s = 0.0;
      for (final t in terms[i]) { s += t[0] * math.cos(t[1] + t[2] * tau); }
      sum += s * math.pow(tau, i).toDouble();
    }
    return sum;
  }
  // Sun alpha/delta (deg) at JDE, obliquity eps (deg); no nutation, no aberration
  static List<double> _sunAD(double jde, double eps) {
    final tau = (jde - 2451545) / 365250;
    final ll = _limitDeg(_series(_lTerms, tau) * _r2d);
    final bb = _series(_bTerms, tau) * _r2d;
    final lam = _limitDeg(ll + 180), beta = -bb;
    final l = lam * _d2r, b = beta * _d2r, e = eps * _d2r;
    final alpha = _limitDeg(math.atan2(math.sin(l) * math.cos(e) - math.tan(b) * math.sin(e), math.cos(l)) * _r2d);
    final delta = math.asin(math.sin(b) * math.cos(e) + math.cos(b) * math.sin(e) * math.sin(l)) * _r2d;
    return [alpha, delta];
  }
  static double _laskar(double jme) {
    final u = jme / 10;
    return 84381.448 + u * (-4680.93 + u * (-1.55 + u * (1999.25 + u * (-51.38 + u * (-249.67 + u * (-39.05 + u * (7.12 + u * (27.87 + u * (5.79 + u * 2.45)))))))));
  }
  static double _gmst(double jd) {
    final jc = (jd - 2451545) / 36525;
    return _limitDeg(280.46061837 + 360.98564736629 * (jd - 2451545) + jc * jc * (0.000387933 - jc / 38710000));
  }
  static double _interp(List<double> ad, double n) {
    var a = ad[1] - ad[0], b = ad[2] - ad[1];
    if (a.abs() >= 2) a = _limit01(a);
    if (b.abs() >= 2) b = _limit01(b);
    return ad[1] + n * (a + b + (b - a) * n) / 2;
  }
  static double _altHA(double alt, double phi, double dec) {
    final c = (math.sin(alt * _d2r) - math.sin(phi * _d2r) * math.sin(dec * _d2r)) / (math.cos(phi * _d2r) * math.cos(dec * _d2r));
    if (c < -1 || c > 1) return double.nan;
    return math.acos(c) * _r2d;
  }

  // astronomy for a date/zone (latitude independent)
  static _Astro _dayAstro(double y, double mo, double d, double lon, double z) {
    final jdReq = _julianDay(y, mo, d, 0, 0, 0, 0, z);
    final t = (jdReq - 2451545) / 36525;
    final hours = _floor(t * 36525 * 24 + z);
    final cdays = _floor((12 + hours) / 24);
    final c = _civilFromDays(cdays);
    final jd0 = _julianDay(c[0], c[1], c[2], 0, 0, 0, 0, z);
    final err = (((jd0 - 2451545) - (cdays - 0.5)) + z / 24) * 86400;
    final dT = deltaT(y, mo, d, z);
    final eps = (_laskar((jdReq + dT / 86400 - 2451545) / 365250) / 3600) + _epsOff;
    final alpha = <double>[], delta = <double>[];
    for (var k = -1; k <= 1; k++) { final r = _sunAD(jd0 + k + dT / 86400, eps); alpha.add(r[0]); delta.add(r[1]); }
    final nu = _gmst(jd0) + _gmstOff;
    final a = _Astro(lon, dT, err, nu, alpha, delta);
    var m0 = (alpha[1] - lon - nu) / 360;
    if (m0 < 0) m0 += 1;
    a.m0 = m0;
    var tr = m0;                                   // transit iterated like rise/set (|step| < 0.0002 day)
    for (var i = 0; i < 20; i++) { final st = -a.h(tr) / 360; tr += st; if (st.abs() < 0.0002) break; }
    if (tr < 0) tr += 1;
    a.transit = tr;
    return a;
  }

  static double? _newton(_Astro a, double phi, double m) {
    final sp = math.sin(phi * _d2r), cp = math.cos(phi * _d2r);
    for (var i = 0; i < 20; i++) {
      final n = m + a.dT / 86400;
      final dec = _interp(a.delta, n) * _d2r;
      final hh = _limit180(a.nu + 360.985647 * m + a.lon - _interp(a.alpha, n)) * _d2r;
      final h = math.asin(sp * math.sin(dec) + cp * math.cos(dec) * math.cos(hh)) * _r2d;
      final dm = (h - _h0) * math.cos(h * _d2r) / (360 * math.cos(dec) * cp * math.sin(hh));
      m += dm;
      if (dm.abs() < 0.0002) return m;
      if (dm.abs() > 5) return null;
    }
    return null;
  }
  static double _intoDay(double s) => s < 0 ? s + 86400 : (s >= 86400 ? s - 86400 : s);
  static double _sec(_Astro a, double m) => m * 86400 + a.err;

  // latitude-dependent events (seconds, raw)
  static _Events _latEvents(_Astro a, double phi) {
    final r = _Events();
    final h0d = _altHA(_h0, phi, a.delta[1]);
    if (!h0d.isNaN) {
      var mr = a.m0 - h0d / 360; if (mr < 0) mr += 1;
      var ms = a.m0 + h0d / 360; if (ms >= 1) ms -= 1;
      final r1 = _newton(a, phi, mr), r2 = _newton(a, phi, ms);
      if (r1 != null && r2 != null) {
        r.sunrise = _intoDay(_sec(a, r1) + _sunriseOff);
        r.maghrib = _intoDay(_sec(a, r2));
        final day = _mod86400(r.maghrib - r.sunrise);
        r.ok = day >= 3600 && 86400 - day >= 3600;
      }
    }
    final d1 = a.delta[2];
    r.fajrHA = _altHA(_fajrAlt, phi, d1);
    r.fajr = _sec(a, a.transit - r.fajrHA / 360);
    final asrAlt = math.atan(1 / (1 + math.tan((phi - d1).abs() * _d2r))) * _r2d;
    r.asr = _sec(a, a.transit + _altHA(asrAlt, phi, d1) / 360);
    final eidHA = _altHA(_eidAlt, phi, d1);
    r.eid = eidHA.isNaN ? double.nan : _sec(a, a.transit - eidHA / 360);
    return r;
  }

  static String _fmt(double m) {
    if (m.isNaN) return 'NaN:NaN';
    final h = _trunc(m / 60), mi = m - 60 * h;
    String f(double v) => (v < 0 ? '-' : '') + v.abs().toInt().toString().padLeft(2, '0');
    return '${f(h)}:${f(mi)}';
  }
  static double _up(double s) { final t = _trunc(s); return _trunc((t + 59) / 60); }
  static double _down(double s) => _trunc(_trunc(s) / 60);

  static final Map<String, double> _pCache = {};
  static double _solsticeP(double y, double phi, double lon, double z) {
    final key = '$y|${phi < 0}|$lon|$z';
    final hit = _pCache[key];
    if (hit != null) return hit;
    final a = _dayAstro(y, phi < 0 ? 12 : 6, 21, lon, z);
    final e = _latEvents(a, phi < 0 ? -45.0 : 45.0);
    final night = 86400 - e.maghrib + e.sunrise;   // as the server: not reduced mod 86400
    final p = (e.sunrise - e.fajr + _twAdd) / night;
    _pCache[key] = p;
    return p;
  }

  static bool _valid(int y, int mo, int d, double lat, double lon, double z) {
    if (!(lat.abs() <= 90 && lon.abs() <= 180)) return false;
    if (!(z >= -12 && z <= 14)) return false;
    final c = _civilFromDays(_daysFromCivil(y.toDouble(), mo.toDouble(), d.toDouble()));
    return c[0] == y && c[1] == mo && c[2] == d;
  }
}

class _Astro {
  final double lon, dT, err, nu;
  final List<double> alpha, delta;
  double m0 = 0, transit = 0;
  _Astro(this.lon, this.dT, this.err, this.nu, this.alpha, this.delta);
  double h(double m) => UmmAlQura._limit180(nu + 360.985647 * m + lon - UmmAlQura._interp(alpha, m + dT / 86400));
}

class _Events {
  bool ok = false;
  double sunrise = double.nan, maghrib = double.nan, fajrHA = double.nan, fajr = double.nan, asr = double.nan, eid = double.nan;
}
