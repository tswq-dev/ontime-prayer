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
  static const List<List<List<double>>> _lTerms = [[[1.75347, 0.0, 0.0], [0.033417, 4.669257, 6283.0758499914], [0.000349, 4.626102, 12566.1516999828], [3.497056e-05, 2.74411800971, 5753.3848848968], [3.417571e-05, 2.82886579606, 3.523118349], [3.135896e-05, 3.62767041758, 77713.7714681205], [2.676218e-05, 4.41808351397, 7860.4193924392], [2.342687e-05, 6.13516237631, 3930.2096962196], [1.324292e-05, 0.74246356352, 11506.7697697936], [1.273166e-05, 2.03709655772, 529.6909650946], [1.199167e-05, 1.10962944315, 1577.3435424478], [9.9025e-06, 5.23268129594, 5884.9268465832], [9.01855e-06, 2.04505443513, 26.2983197998], [8.57223e-06, 3.50849156957, 398.1490034082], [7.79786e-06, 1.17882652114, 5223.6939198022], [7.53141e-06, 2.53339053818, 5507.5532386674], [5.05264e-06, 4.58292563052, 18849.2275499742], [4.92379e-06, 4.20506639861, 775.522611324], [3.56655e-06, 2.91954116867, 0.0673103028], [3.17087e-06, 5.84901952218, 11790.6290886588], [2.84125e-06, 1.89869034186, 796.2980068164], [2.71039e-06, 0.31488607649, 10977.078804699], [2.4281e-06, 0.34481140906, 5486.777843175], [2.0616e-06, 4.80646606059, 2544.3144198834], [2.05385e-06, 1.86947813692, 5573.1428014331], [2.02261e-06, 2.45767795458, 6069.7767545534], [1.55516e-06, 0.83306073807, 213.299095438], [1.32212e-06, 3.41118275555, 2942.4634232916], [1.26184e-06, 1.0830263021, 20.7753954924], [1.15132e-06, 0.64544911683, 0.9803210682], [1.02851e-06, 0.63599846727, 4694.0029547076], [1.01895e-06, 0.97569221824, 15720.8387848784], [1.01724e-06, 4.26679821365, 7.1135470008], [9.9206e-07, 6.20992940258, 2146.1654164752], [9.7607e-07, 0.6810127227, 155.4203994342], [8.5803e-07, 5.98322631256, 161000.6857376741], [8.5128e-07, 1.29870743025, 6275.9623029906], [8.4711e-07, 3.67080093025, 71430.69561812909], [7.9637e-07, 1.807913307, 17260.1546546904], [7.8756e-07, 3.03698313141, 12036.4607348882], [7.4651e-07, 1.75508916159, 5088.6288397668], [7.3874e-07, 3.50319443167, 3154.6870848956], [7.3547e-07, 4.67926565481, 801.8209311238], [6.9627e-07, 0.83297596966, 9437.762934887], [6.2449e-07, 3.97763880587, 8827.3902698748], [6.1148e-07, 1.81839811024, 7084.8967811152], [5.6963e-07, 2.78430398043, 6286.5989683404], [5.6116e-07, 4.38694880779, 14143.4952424306], [5.5577e-07, 3.47006009062, 6279.5527316424], [5.1992e-07, 0.18914945834, 12139.5535091068], [5.1605e-07, 1.33282746983, 1748.016413067], [5.1145e-07, 0.28306864501, 5856.4776591154], [4.9e-07, 0.48735065033, 1194.4470102246], [4.1036e-07, 5.36817351402, 8429.2412664666], [4.0938e-07, 2.39850881707, 19651.048481098], [3.92e-07, 6.16832995016, 10447.3878396044], [3.677e-07, 6.04133859347, 10213.285546211], [3.6596e-07, 2.56955238628, 1059.3819301892], [3.5954e-07, 1.70876111898, 2352.8661537718], [3.5566e-07, 1.77597314691, 6812.766815086], [3.3291e-07, 0.59309499459, 17789.845619785], [3.0412e-07, 0.44294464135, 83996.84731811189], [3.0047e-07, 2.73975123935, 1349.8674096588], [2.5352e-07, 3.16470953405, 4690.4798363586]],
    [[6283.319667, 0.0, 0.0], [0.002061, 2.678235, 6283.0758499914], [4.30343e-05, 2.63512650414, 12566.1516999828], [4.25264e-06, 1.59046980729, 3.523118349], [1.19261e-06, 5.79557487799, 26.2983197998], [1.08977e-06, 2.96618001993, 1577.3435424478], [9.3478e-07, 2.59212835365, 18849.2275499742], [7.2122e-07, 1.13846158196, 529.6909650946], [6.7768e-07, 1.87472304791, 398.1490034082], [6.7327e-07, 4.40918235168, 5507.5532386674], [5.9027e-07, 2.8879703846, 5223.6939198022], [5.5976e-07, 2.17471680261, 155.4203994342], [4.5407e-07, 0.39803079805, 796.2980068164], [3.6369e-07, 0.46624739835, 775.522611324], [2.8958e-07, 2.64707383882, 7.1135470008], [2.0844e-07, 5.34138275149, 0.9803210682], [1.9097e-07, 1.84628332577, 5486.777843175], [1.8508e-07, 4.96855124577, 213.299095438], [1.7293e-07, 2.99116864949, 6275.9623029906], [1.6233e-07, 0.03216483047, 2544.3144198834], [1.5832e-07, 1.43049285325, 2146.1654164752], [1.4615e-07, 1.20532366323, 10977.078804699], [1.2461e-07, 2.83432285512, 1748.016413067], [1.1877e-07, 3.25804815607, 5088.6288397668], [1.1808e-07, 5.2737979048, 1194.4470102246], [1.1514e-07, 2.07502418155, 4694.0029547076], [1.0641e-07, 0.76614199202, 553.5694028424], [9.969e-08, 1.30262991097, 6286.5989683404], [9.721e-08, 4.23925472239, 1349.8674096588], [9.452e-08, 2.69957062864, 242.728603974], [8.577e-08, 5.64475868067, 951.7184062506], [7.576e-08, 5.30062664886, 2352.8661537718], [6.385e-08, 2.65033984967, 9437.762934887], [6.101e-08, 4.66632584188, 4690.4798363586]],
    [[0.000529, 0.0, 0.0], [8.719837e-05, 1.07209665242, 6283.0758499914], [3.09125e-06, 0.86728818832, 12566.1516999828], [2.7339e-07, 0.05297871691, 3.523118349], [1.6334e-07, 5.18826691036, 26.2983197998], [1.5752e-07, 3.6845788943, 155.4203994342], [9.541e-08, 0.75742297675, 18849.2275499742], [8.937e-08, 2.05705419118, 77713.7714681205], [6.952e-08, 0.8267330541, 775.522611324], [5.064e-08, 4.66284525271, 1577.3435424478], [4.061e-08, 1.03057162962, 7.1135470008], [3.81e-08, 3.4405080349, 5573.1428014331], [3.463e-08, 5.14074632811, 796.2980068164], [3.169e-08, 6.05291851171, 5507.5532386674], [3.02e-08, 1.19246506441, 242.728603974], [2.886e-08, 6.11652627155, 529.6909650946], [2.714e-08, 0.30637881025, 398.1490034082], [2.538e-08, 2.27992810679, 553.5694028424], [2.371e-08, 4.38118838167, 5223.6939198022], [2.079e-08, 3.75435330484, 0.9803210682]],
    [[2.89226e-06, 5.84384198723, 6283.0758499914], [3.4955e-07, 0.0, 0.0], [1.6819e-07, 5.48766912348, 12566.1516999828], [2.962e-08, 5.19577265202, 155.4203994342], [1.288e-08, 4.72200252235, 3.523118349], [7.14e-09, 5.30045809128, 18849.2275499742], [6.35e-09, 5.96925937141, 242.728603974]],
    [[1.14084e-06, 3.14159265359, 0.0], [7.717e-08, 4.13446589358, 6283.0758499914], [7.65e-09, 3.83803776214, 12566.1516999828]],
    [[8.78e-09, 3.14159265359, 0.0]]];
  static const List<List<List<double>>> _bTerms = [[[2.7962e-06, 3.19870156017, 84334.66158130829], [1.01643e-06, 5.42248619256, 5507.5532386674], [8.0445e-07, 3.88013204458, 5223.6939198022], [4.3806e-07, 3.70444689758, 2352.8661537718], [3.1933e-07, 4.00026369781, 1577.3435424478]],
    [[9.03e-08, 3.8972906189, 5507.5532386674], [6.177e-08, 1.73038850355, 5223.6939198022]]];
  static const List<double> _dtTable = [1240.0, 1150.0, 1060.0, 980.0, 910.0, 850.0, 790.0, 740.0, 700.0, 650.0, 620.0, 580.0, 550.0, 530.0, 500.0, 480.0, 460.0, 440.0, 420.0, 400.0, 370.0, 350.0, 330.0, 310.0, 280.0, 260.0, 240.0, 220.0, 200.0, 180.0, 160.0, 140.0, 130.0, 120.0, 110.0, 100.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 90.0, 100.0, 100.0, 100.0, 100.0, 100.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 110.0, 120.0, 120.0, 120.0, 120.0, 120.0, 130.0, 130.0, 130.0, 130.0, 140.0, 140.0, 140.0, 150.0, 150.0, 150.0, 150.0, 160.0, 160.0, 160.0, 160.0, 160.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 170.0, 160.0, 160.0, 150.0, 140.0, 137.0, 131.0, 127.0, 125.0, 125.0, 125.0, 125.0, 125.0, 125.0, 123.0, 120.0, 114.0, 106.0, 96.0, 86.0, 75.0, 66.0, 60.0, 57.0, 56.0, 57.0, 59.0, 62.0, 65.0, 68.0, 71.0, 73.0, 75.0, 77.0, 78.0, 79.0, 75.0, 64.0, 54.0, 29.0, 16.0, -10.0, -27.0, -36.0, -47.0, -54.0, -52.0, -55.0, -56.0, -58.0, -59.0, -62.0, -64.0, -61.0, -47.0, -27.0, 0.0, 26.0, 54.0, 77.0, 105.0, 134.0, 160.0, 182.0, 202.0, 212.0, 224.0, 235.0, 239.0, 243.0, 240.0, 239.0, 239.0, 237.0, 240.0, 243.0, 253.0, 262.0, 273.0, 282.0, 291.0, 300.0, 307.0, 314.0, 322.0, 331.0, 340.0, 350.0, 365.0, 383.0, 402.0, 422.0, 445.0, 465.0, 485.0, 505.0, 522.0, 538.0, 549.0, 558.0, 569.0, 580.0];

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
