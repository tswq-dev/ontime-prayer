"""
Umm al-Qura prayer times — reconstructed algorithm (reference implementation, pure Python, no dependencies).

Reproduces the times the Umm al-Qura calendar service (ummulqura.org.sa / umqserv.kacst.gov.sa) returns for any
coordinates and time zone: Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha and the Eid prayer time.

This is a RECONSTRUCTION from public data, not KACST's source code. Verification (2026-11-01, 7,138,943 times):
  * 4,864,587 official Saudi times (173 cities, every day 2025-2035): 0 differences.
  * 873,810 times for 114 world cities in their own zones (incl. +5:45, +3:30, negative zones, Arctic),
    years 2026/2029/2033: 0 differences.
  * 480,830 times for 59 further cities/zones (+13, +14, +12:45, -3:30, poles) and years: 18 differences, all on
    3 "grazing" polar days where the server's own sunrise/sunset iteration is chaotic (see METHOD.md section 10).
  * official yearly PDFs, 5-year export, lag-day / longitude / latitude probes: 0 differences.
  * bug hunt: 260 days placed 0.6-30 ms from a minute change (1940-2100), Saudi whole years 1940-2150, 138 years
    (2001-2149) at one city, 166,222 world times 1940-2150: 0 differences. Lag days ("the server shows the previous
    day"): 10,641 facts, 0 wrong. Single days 1000-3600: 0. Server dT read directly at 35 dates 1900-1999: +-0.005 s.
  * Ramadan (Isha + 120): use the Umm al-Qura Hijri calendar, e.g. Java/Android HijrahDate (equal on every date
    checked 1940-2150, incl. 50,404 consecutive days 2001-2149).
  * ~1,700 millisecond/microsecond-level boundary measurements reproduced within their resolution (Saudi held-out
    set: 320/320).
Fitted constants (see METHOD.md): SID_OFFSET, EPS_OFFSET, SUNRISE_OFFSET; outside Saudi Arabia also HL_HMAX, HL_TW_ADD.

Usage:
    from datetime import date
    from umm_al_qura_times import prayer_times
    prayer_times(date(2027, 3, 20), 21.4225, 39.8262)                 # Makkah (zone 3)
    prayer_times(date(2027, 3, 20), 30.0444, 31.2357, tz=2)           # Cairo, API zone=2
    prayer_times_gps(date(2027, 3, 20), 21.42666712, 39.83166689)     # raw GPS, rounded like the website
"""
import math
from datetime import date, datetime, timedelta

TZ = 3.0                       # Saudi time, UTC+3
SUN_ALT = -0.8333              # sunrise / sunset altitude h0 (deg)
FAJR_ANGLE = 18.5              # deg below the horizon
EID_ANGLE = 4.2                # deg above the horizon
RS_MAXIT = 20                  # Newton steps at most (the data needs >= 16; 16 ... 50 fit)
RS_BIGSTEP = 5.0               # a larger step (days) means no valid sunrise/sunset (fits 2.75 ... 9.77)
RS_STOP = 0.0002               # Newton stop for sunrise/sunset: |dm| < 0.0002 day (17.28 s)
SID_OFFSET = 0.146457          # arcsec added to GMST            [fitted]
EPS_OFFSET = -0.039131         # arcsec added to mean obliquity  [fitted]
SUNRISE_OFFSET = 3.2001        # ms added to sunrise             [fitted]
# Outside Saudi Arabia only:
POLAR_LAT = 45.0               # polar day/night: events are taken at +-45 deg latitude
HL_LAT = 48.0                  # the high-latitude Fajr rule applies above this |latitude|
HL_HMAX = 151.84401            # deg; Fajr hour angle above this -> fallback   [measured in (151.8440087, 151.8440117)]
HL_REF_LAT = 45.0              # latitude of the twilight reference (nearest latitude where twilight always ends)
HL_TW_ADD = 58.99367           # s added to the 45-degree twilight in the server's ratio   [measured (58.99355, 58.99380]]

# NREL-SPA's selection of VSOP87D terms (A [rad], B [rad], C [rad/millennium]) at full VSOP87D precision, EXCEPT the
# six large rows (L0[0..2], L1[0..1], L2[0]) whose A and B are rounded to 6 decimals, as in the server's table.
L0 = [
    (1.75347, 0.0, 0.0),
    (0.033417, 4.669257, 6283.0758499914),
    (0.000349, 4.626102, 12566.1516999828),
    (3.497056e-05, 2.74411800971, 5753.3848848968),
    (3.417571e-05, 2.82886579606, 3.523118349),
    (3.135896e-05, 3.62767041758, 77713.7714681205),
    (2.676218e-05, 4.41808351397, 7860.4193924392),
    (2.342687e-05, 6.13516237631, 3930.2096962196),
    (1.324292e-05, 0.74246356352, 11506.7697697936),
    (1.273166e-05, 2.03709655772, 529.6909650946),
    (1.199167e-05, 1.10962944315, 1577.3435424478),
    (9.9025e-06, 5.23268129594, 5884.9268465832),
    (9.01855e-06, 2.04505443513, 26.2983197998),
    (8.57223e-06, 3.50849156957, 398.1490034082),
    (7.79786e-06, 1.17882652114, 5223.6939198022),
    (7.53141e-06, 2.53339053818, 5507.5532386674),
    (5.05264e-06, 4.58292563052, 18849.2275499742),
    (4.92379e-06, 4.20506639861, 775.522611324),
    (3.56655e-06, 2.91954116867, 0.0673103028),
    (3.17087e-06, 5.84901952218, 11790.6290886588),
    (2.84125e-06, 1.89869034186, 796.2980068164),
    (2.71039e-06, 0.31488607649, 10977.078804699),
    (2.4281e-06, 0.34481140906, 5486.777843175),
    (2.0616e-06, 4.80646606059, 2544.3144198834),
    (2.05385e-06, 1.86947813692, 5573.1428014331),
    (2.02261e-06, 2.45767795458, 6069.7767545534),
    (1.55516e-06, 0.83306073807, 213.299095438),
    (1.32212e-06, 3.41118275555, 2942.4634232916),
    (1.26184e-06, 1.0830263021, 20.7753954924),
    (1.15132e-06, 0.64544911683, 0.9803210682),
    (1.02851e-06, 0.63599846727, 4694.0029547076),
    (1.01895e-06, 0.97569221824, 15720.8387848784),
    (1.01724e-06, 4.26679821365, 7.1135470008),
    (9.9206e-07, 6.20992940258, 2146.1654164752),
    (9.7607e-07, 0.6810127227, 155.4203994342),
    (8.5803e-07, 5.98322631256, 161000.6857376741),
    (8.5128e-07, 1.29870743025, 6275.9623029906),
    (8.4711e-07, 3.67080093025, 71430.69561812909),
    (7.9637e-07, 1.807913307, 17260.1546546904),
    (7.8756e-07, 3.03698313141, 12036.4607348882),
    (7.4651e-07, 1.75508916159, 5088.6288397668),
    (7.3874e-07, 3.50319443167, 3154.6870848956),
    (7.3547e-07, 4.67926565481, 801.8209311238),
    (6.9627e-07, 0.83297596966, 9437.762934887),
    (6.2449e-07, 3.97763880587, 8827.3902698748),
    (6.1148e-07, 1.81839811024, 7084.8967811152),
    (5.6963e-07, 2.78430398043, 6286.5989683404),
    (5.6116e-07, 4.38694880779, 14143.4952424306),
    (5.5577e-07, 3.47006009062, 6279.5527316424),
    (5.1992e-07, 0.18914945834, 12139.5535091068),
    (5.1605e-07, 1.33282746983, 1748.016413067),
    (5.1145e-07, 0.28306864501, 5856.4776591154),
    (4.9e-07, 0.48735065033, 1194.4470102246),
    (4.1036e-07, 5.36817351402, 8429.2412664666),
    (4.0938e-07, 2.39850881707, 19651.048481098),
    (3.92e-07, 6.16832995016, 10447.3878396044),
    (3.677e-07, 6.04133859347, 10213.285546211),
    (3.6596e-07, 2.56955238628, 1059.3819301892),
    (3.5954e-07, 1.70876111898, 2352.8661537718),
    (3.5566e-07, 1.77597314691, 6812.766815086),
    (3.3291e-07, 0.59309499459, 17789.845619785),
    (3.0412e-07, 0.44294464135, 83996.84731811189),
    (3.0047e-07, 2.73975123935, 1349.8674096588),
    (2.5352e-07, 3.16470953405, 4690.4798363586),
]
L1 = [
    (6283.319667, 0.0, 0.0),
    (0.002061, 2.678235, 6283.0758499914),
    (4.30343e-05, 2.63512650414, 12566.1516999828),
    (4.25264e-06, 1.59046980729, 3.523118349),
    (1.19261e-06, 5.79557487799, 26.2983197998),
    (1.08977e-06, 2.96618001993, 1577.3435424478),
    (9.3478e-07, 2.59212835365, 18849.2275499742),
    (7.2122e-07, 1.13846158196, 529.6909650946),
    (6.7768e-07, 1.87472304791, 398.1490034082),
    (6.7327e-07, 4.40918235168, 5507.5532386674),
    (5.9027e-07, 2.8879703846, 5223.6939198022),
    (5.5976e-07, 2.17471680261, 155.4203994342),
    (4.5407e-07, 0.39803079805, 796.2980068164),
    (3.6369e-07, 0.46624739835, 775.522611324),
    (2.8958e-07, 2.64707383882, 7.1135470008),
    (2.0844e-07, 5.34138275149, 0.9803210682),
    (1.9097e-07, 1.84628332577, 5486.777843175),
    (1.8508e-07, 4.96855124577, 213.299095438),
    (1.7293e-07, 2.99116864949, 6275.9623029906),
    (1.6233e-07, 0.03216483047, 2544.3144198834),
    (1.5832e-07, 1.43049285325, 2146.1654164752),
    (1.4615e-07, 1.20532366323, 10977.078804699),
    (1.2461e-07, 2.83432285512, 1748.016413067),
    (1.1877e-07, 3.25804815607, 5088.6288397668),
    (1.1808e-07, 5.2737979048, 1194.4470102246),
    (1.1514e-07, 2.07502418155, 4694.0029547076),
    (1.0641e-07, 0.76614199202, 553.5694028424),
    (9.969e-08, 1.30262991097, 6286.5989683404),
    (9.721e-08, 4.23925472239, 1349.8674096588),
    (9.452e-08, 2.69957062864, 242.728603974),
    (8.577e-08, 5.64475868067, 951.7184062506),
    (7.576e-08, 5.30062664886, 2352.8661537718),
    (6.385e-08, 2.65033984967, 9437.762934887),
    (6.101e-08, 4.66632584188, 4690.4798363586),
]
L2 = [
    (0.000529, 0.0, 0.0),
    (8.719837e-05, 1.07209665242, 6283.0758499914),
    (3.09125e-06, 0.86728818832, 12566.1516999828),
    (2.7339e-07, 0.05297871691, 3.523118349),
    (1.6334e-07, 5.18826691036, 26.2983197998),
    (1.5752e-07, 3.6845788943, 155.4203994342),
    (9.541e-08, 0.75742297675, 18849.2275499742),
    (8.937e-08, 2.05705419118, 77713.7714681205),
    (6.952e-08, 0.8267330541, 775.522611324),
    (5.064e-08, 4.66284525271, 1577.3435424478),
    (4.061e-08, 1.03057162962, 7.1135470008),
    (3.81e-08, 3.4405080349, 5573.1428014331),
    (3.463e-08, 5.14074632811, 796.2980068164),
    (3.169e-08, 6.05291851171, 5507.5532386674),
    (3.02e-08, 1.19246506441, 242.728603974),
    (2.886e-08, 6.11652627155, 529.6909650946),
    (2.714e-08, 0.30637881025, 398.1490034082),
    (2.538e-08, 2.27992810679, 553.5694028424),
    (2.371e-08, 4.38118838167, 5223.6939198022),
    (2.079e-08, 3.75435330484, 0.9803210682),
]
L3 = [
    (2.89226e-06, 5.84384198723, 6283.0758499914),
    (3.4955e-07, 0.0, 0.0),
    (1.6819e-07, 5.48766912348, 12566.1516999828),
    (2.962e-08, 5.19577265202, 155.4203994342),
    (1.288e-08, 4.72200252235, 3.523118349),
    (7.14e-09, 5.30045809128, 18849.2275499742),
    (6.35e-09, 5.96925937141, 242.728603974),
]
L4 = [
    (1.14084e-06, 3.14159265359, 0.0),
    (7.717e-08, 4.13446589358, 6283.0758499914),
    (7.65e-09, 3.83803776214, 12566.1516999828),
]
L5 = [
    (8.78e-09, 3.14159265359, 0.0),
]
B0 = [
    (2.7962e-06, 3.19870156017, 84334.66158130829),
    (1.01643e-06, 5.42248619256, 5507.5532386674),
    (8.0445e-07, 3.88013204458, 5223.6939198022),
    (4.3806e-07, 3.70444689758, 2352.8661537718),
    (3.1933e-07, 4.00026369781, 1577.3435424478),
]
B1 = [
    (9.03e-08, 3.8972906189, 5507.5532386674),
    (6.177e-08, 1.73038850355, 5223.6939198022),
]


def _series(rows, tau):
    return sum(a * math.cos(b + c * tau) for a, b, c in rows)


def _sun(jde, eps_tau):
    """Geometric Sun (no nutation, no aberration), mean equinox of date: (alpha, delta) in degrees.
    eps_tau: millennia from J2000 at which the obliquity is evaluated (the requested date, once per date)."""
    tau = (jde - 2451545.0) / 365250.0
    L = sum(_series(s, tau) * tau ** i for i, s in enumerate((L0, L1, L2, L3, L4, L5)))
    B = sum(_series(s, tau) * tau ** i for i, s in enumerate((B0, B1)))
    lam = (math.degrees(L) % 360.0 + 180.0) % 360.0
    beta = -math.degrees(B)
    u = eps_tau / 10.0
    eps = (84381.448 - 4680.93 * u - 1.55 * u ** 2 + 1999.25 * u ** 3 - 51.38 * u ** 4 - 249.67 * u ** 5
           - 39.05 * u ** 6 + 7.12 * u ** 7 + 27.87 * u ** 8 + 5.79 * u ** 9 + 2.45 * u ** 10 + EPS_OFFSET) / 3600.0
    lr, er, br = math.radians(lam), math.radians(eps), math.radians(beta)
    alpha = math.degrees(math.atan2(math.sin(lr) * math.cos(er) - math.tan(br) * math.sin(er), math.cos(lr))) % 360.0
    delta = math.degrees(math.asin(math.sin(br) * math.cos(er) + math.cos(br) * math.sin(er) * math.sin(lr)))
    return alpha, delta


def _julian_day(y, m, d, tz):
    """NREL-SPA julian_day() for local midnight (same double arithmetic)."""
    day_decimal = d - tz / 24.0      # SPA: d + (hour - tz + (minute + second/60)/60)/24 with hour = minute = second = 0
                                     # (bit-identical to the longer SPA expression)
    if m < 3:
        m += 12
        y -= 1
    jd = math.floor(365.25 * (y + 4716.0)) + math.floor(30.6001 * (m + 1)) + day_decimal - 1524.5
    a = math.floor(y / 100)                  # Gregorian correction ALWAYS (SPA only applies it after 1582-10-15):
    return jd + (2 - a + math.floor(a / 4))  # the server's dates are proleptic Gregorian (checked 1000, 1500, 1582)


def server_date(d, tz=TZ):
    """The date whose times the server returns for date d. The server turns the SPA Julian day back into a date:
    hours = t * 36525 * 24 + tz (t in Julian centuries from J2000.0), then J2000.0 (2000-01-01 12:00) + floor(hours)
    hours, then the calendar date. The floating-point round trip occasionally lands on the previous day ("lag days");
    those days show the previous day's times. Checked on 10,641 lag/no-lag facts (37 zones, 1940-2150): 0 wrong."""
    t = (_julian_day(d.year, d.month, d.day, tz) - 2451545.0) / 36525.0
    return (datetime(2000, 1, 1, 12) + timedelta(hours=math.floor(t * 36525.0 * 24.0 + tz))).date()


DT_TABLE = (                   # Meeus, Astronomical Algorithms 1st ed. (1991), Table 9.A: dT x 10 s, 1620, 1622 ... 1992
    1240, 1150, 1060, 980, 910, 850, 790, 740, 700, 650,
    620, 580, 550, 530, 500, 480, 460, 440, 420, 400,
    370, 350, 330, 310, 280, 260, 240, 220, 200, 180,
    160, 140, 130, 120, 110, 100, 90, 90, 90, 90,
    90, 90, 90, 90, 100, 100, 100, 100, 100, 110,
    110, 110, 110, 110, 110, 110, 110, 120, 120, 120,
    120, 120, 130, 130, 130, 130, 140, 140, 140, 150,
    150, 150, 150, 160, 160, 160, 160, 160, 170, 170,
    170, 170, 170, 170, 170, 170, 160, 160, 150, 140,
    137, 131, 127, 125, 125, 125, 125, 125, 125, 123,
    120, 114, 106, 96, 86, 75, 66, 60, 57, 56,
    57, 59, 62, 65, 68, 71, 73, 75, 77, 78,
    79, 75, 64, 54, 29, 16, -10, -27, -36, -47,
    -54, -52, -55, -56, -58, -59, -62, -64, -61, -47,
    -27, 0, 26, 54, 77, 105, 134, 160, 182, 202,
    212, 224, 235, 239, 243, 240, 239, 239, 237, 240,
    243, 253, 262, 273, 282, 291, 300, 307, 314, 322,
    331, 340, 350, 365, 383, 402, 422, 445, 465, 485,
    505, 522, 538, 549, 558, 569, 580,
)


def _delta_t(d, tz):
    """TT-UT (s) for the requested date, as the server computes it (Meeus, Astronomical Algorithms, 1st ed., ch. 9).
    T = Julian centuries from J2000.0 of the requested LOCAL midnight (SPA julian_day, as for the date round trip);
    y = 2000 + 100 T.
      y > 2000:         102.3 + 123.5 T + 32.5 T^2  (Stephenson-Morrison 1984 around 2000)
      1620 <= y <= 2000: Table 9.A, linear interpolation; past its last entry (1992) the last interval is extended
                        (so 1990-1999 follow 56.9 + 0.55 (y - 1990)). Measured to +-0.003 s at 35 dates 1900-1999.
      y < 1620:         Stephenson-Houlden: 50.6 + 67.5 T + 22.5 T^2 (948-1620), 2715.6 + 573.36 T + 46.5 T^2 before."""
    T = (_julian_day(d.year, d.month, d.day, tz) - 2451545.0) / 36525.0
    y = 2000.0 + T * 100.0
    if y > 2000.0:                           # strictly: y = 2000.0 exactly (2000-01-01 in zone -12) still uses the
        return 102.3 + T * (123.5 + T * 32.5)  # table (measured)
    if y < 1620.0:
        return 2715.6 + T * (573.36 + T * 46.5) if y < 948.0 else 50.6 + T * (67.5 + T * 22.5)
    i = min(int((y - 1620.0) / 2.0), len(DT_TABLE) - 2)
    f = y / 2.0 - (i + 810)
    return (DT_TABLE[i] + (DT_TABLE[i + 1] - DT_TABLE[i]) * f) / 10.0


def _hour_angle(alt, lat, dec):
    c = ((math.sin(math.radians(alt)) - math.sin(math.radians(lat)) * math.sin(math.radians(dec)))
         / (math.cos(math.radians(lat)) * math.cos(math.radians(dec))))
    return math.degrees(math.acos(c)) if abs(c) <= 1.0 else None


def _events(d, lat, lon, tz=TZ):
    """Unrounded local times (seconds after local midnight) for requested date d."""
    cd = server_date(d, tz)
    dT = _delta_t(d, tz)                                       # once, for the requested date
    eps_tau = ((d - date(2000, 1, 1)).days + 2451544.5 - tz / 24.0 - 2451545.0) / 365250.0   # requested local midnight
    fr = -tz / 24.0                                            # the day frame starts at LOCAL midnight
    jd0 = (cd - date(2000, 1, 1)).days + 2451544.5 + fr
    jc = (jd0 - 2451545.0) / 36525.0
    nu = ((280.46061837 + 360.98564736629 * (jd0 - 2451545.0) + 0.000387933 * jc ** 2 - jc ** 3 / 38710000.0) % 360.0
          + SID_OFFSET / 3600.0)
    (am, d_m1), (a0, d0), (ap, dp) = (_sun(jd0 + k + dT / 86400.0, eps_tau) for k in (-1, 0, 1))   # nodes at TT
    a, b = a0 - am, ap - a0
    a = a % 1 if abs(a) > 2 else a
    b = b % 1 if abs(b) > 2 else b
    ad, bd = d0 - d_m1, dp - d0
    sl, cl = math.sin(math.radians(lat)), math.cos(math.radians(lat))
    n_ = lambda m: m + dT / 86400.0                            # SPA's n; dT is applied a second time here
    alpha_at = lambda m: a0 + n_(m) * (a + b + (b - a) * n_(m)) / 2.0
    delta_at = lambda m: d0 + n_(m) * (ad + bd + (bd - ad) * n_(m)) / 2.0

    def hour(m):
        hp = (nu + 360.985647 * m + lon - alpha_at(m)) % 360.0
        return hp - 360.0 if hp >= 180.0 else hp

    m0 = (a0 - lon - nu) / 360.0
    m0 = m0 + 1.0 if m0 < 0.0 else m0                          # the server adds ONE day if negative, nothing else:
    #   m0 stays negative when (a0 - lon - nu)/360 < -1 (time zones >= +12 around the March equinox),
    #   and stays >= 1 (Dhuhr "26:47") when the zone is ~14 h ahead of the longitude
    transit = m0                                               # iterated like sunrise/sunset: one step on
    for _ in range(RS_MAXIT):                                  #   ordinary dates (|step| < 0.0002 day); two when
        step = -hour(transit) / 360.0                          #   m0 is far off (zones far from the longitude,
        transit += step                                        #   large dT). Measured: 18/18 (2027-28).
        if abs(step) < RS_STOP:
            break
    off = tz * 3600.0                                          # (m + fr) * 86400 + off == m * 86400 algebraically; the
                                                               # round trip is kept to match the server bit for bit
    T = (transit + fr) * 86400.0 + off
    # The server's day origin jd0 is a double near 2.45e6 (step 4.66e-10 day = 40 us): its rounding error moves EVERY
    # event by the same amount (+13.4 us in zones 1/4/7, -13.4 us in zones 2/5, 0 in zones 0/3/6/9/12). Measured.
    jd0_err = ((jd0 - 2451545.0) - ((cd - date(2000, 1, 1)).days - 0.5 + fr)) * 86400.0
    T += jd0_err
    T = T + 86400.0 if T < 0.0 else T                          # Dhuhr: ONE day added if negative (never reduced);
    #   Fajr, Asr and Eid below are built on this Dhuhr and are NOT wrapped (the server prints "26:23", "-02:-51")
    ha0 = _hour_angle(SUN_ALT, lat, d0)          # sunrise/sunset hour angle (deg); NOT the altitude
    rs, conv = [], True
    for sign in (-1, 1):                                       # sunrise, sunset
        if ha0 is None:
            rs.append(None)
            continue
        m = m0 + sign * (ha0 % 180.0) / 360.0
        if sign < 0 and m < 0.0:                               # sunrise first guess: +1 day if negative
            m += 1.0
        elif sign > 0 and m >= 1.0:                            # sunset first guess: -1 day if past local midnight
            m -= 1.0
        ok = False
        for _ in range(RS_MAXIT):
            hp, de = hour(m), delta_at(m)
            D, H = math.radians(de), math.radians(hp)
            x = sl * math.sin(D) + cl * math.cos(D) * math.cos(H)
            h = math.asin(x) if abs(x) <= 1.0 else float("nan")
            den = 360.0 * math.cos(D) * cl * math.sin(H)
            step = (math.degrees(h) - SUN_ALT) * math.cos(h) / den if den != 0.0 else float("inf")
            if not abs(step) <= RS_BIGSTEP:                    # runaway step (grazing Sun): no valid event.
                #   Written as "not <=" on purpose: it is also true for NaN (a port must break on NaN too)
                break
            m += step
            if abs(step) < RS_STOP:
                ok = True
                break
        conv = conv and ok
        rs.append((m + fr) * 86400.0 + off + jd0_err)
    dec = dp                                                   # declination at the NEXT local midnight (node k=+1)
    sec = lambda deg: None if deg is None else deg / 360.0 * 86400.0
    asr_alt = math.degrees(math.atan(1.0 / (1.0 + math.tan(math.radians(abs(lat - dec))))))
    hf, ha, he = sec(_hour_angle(-FAJR_ANGLE, lat, dec)), sec(_hour_angle(asr_alt, lat, dec)), sec(_hour_angle(EID_ANGLE, lat, dec))
    return {"fajr": None if hf is None else T - hf,
            "sunrise": None if rs[0] is None else rs[0] + SUNRISE_OFFSET / 1000.0,
            "dhuhr": T,
            "asr": None if ha is None else T + ha,
            "maghrib": rs[1],
            "eid": None if he is None else T - he,
            "converged": conv}


def _hl_portion(d, lat, lon, tz):
    """Fajr portion of the night above 48 deg: the 45-degree ratio (sunrise - fajr + HL_TW_ADD) / night,
    taken on the summer solstice of the requested year (21 June north, 21 December south)."""
    ref = date(d.year, 6, 21) if lat >= 0 else date(d.year, 12, 21)
    e = _events(ref, math.copysign(HL_REF_LAT, lat), lon, tz)
    return (e["sunrise"] - e["fajr"] + HL_TW_ADD) / (86400.0 - e["maghrib"] + e["sunrise"])


def _check_inputs(d, lat, lon, ramadan, tz):
    if not isinstance(d, date) or isinstance(d, datetime):
        raise TypeError("d must be a datetime.date (not a datetime or a string)")
    for name, v, lim in (("lat", lat, 90.0), ("lon", lon, 180.0), ("tz", tz, None)):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
            raise ValueError(f"{name} must be a finite number")
        if lim is not None and abs(v) > lim:
            raise ValueError(f"{name} must be within +-{lim:g}")
    if not -12.0 <= tz <= 14.0:                                # the server answers 400 "Invalid Zone" (-13, 14.5 seen)
        raise ValueError("tz (hours east of UTC, as the API 'zone') must be within -12 .. +14")
    if not isinstance(ramadan, bool):
        raise TypeError("ramadan must be a bool")


def prayer_times(d, lat, lon, ramadan=False, tz=TZ, raw=False):
    """Prayer times for date d at (lat, lon), as the Umm al-Qura server returns them.

    d: datetime.date.  lat/lon: degrees, north/east positive.  tz: the API 'zone', hours east of UTC (default 3, no DST;
    the times are local time of that zone, not necessarily Saudi time).  ramadan: Isha = Maghrib + 120 min instead of 90
    (the caller must decide, e.g. from the Umm al-Qura Hijri calendar).
    Returns {'fajr','sunrise','dhuhr','asr','maghrib','eid','isha'} -> 'HH:MM' strings, or None if the server gives
    none. 'eid' is the Eid-prayer time of THAT day's sun (returned for every day). Strings may read '24:39' (hours >= 24)
    or '-01:-13' (negative parts): that is what the server prints for unusual zone/longitude pairs; do not "fix" it.
    Verified range: 1940-2150, |lat| <= 78.2, zones -12 .. +14. raw=True (debug) returns the unrounded events in seconds
    after local midnight instead (no 'isha', no >48 deg Fajr rule).
    """
    _check_inputs(d, lat, lon, ramadan, tz)
    ev = _events(d, lat, lon, tz)
    # NB: "%" is Python's floored modulo (result >= 0). The second test below is ALWAYS true on an ordinary day with C's
    # fmod / C# "%" (negative operand): a port must use x - 86400*floor(x/86400).
    polar = (ev["sunrise"] is None or ev["maghrib"] is None or not ev["converged"]
             or (ev["maghrib"] - ev["sunrise"]) % 86400.0 < 3600.0      # day shorter than one hour
             or (ev["sunrise"] - ev["maghrib"]) % 86400.0 < 3600.0)     # night shorter than one hour
    if polar:                                                  # polar day/night (not in Saudi Arabia): use latitude 45
        sub = _events(d, math.copysign(POLAR_LAT, lat), lon, tz)
        for k in ("fajr", "sunrise", "maghrib", "asr", "eid"):
            ev[k] = sub[k]
    del ev["converged"]
    hl_fallback = abs(lat) > HL_LAT and (ev["fajr"] is None or (ev["dhuhr"] - ev["fajr"]) / 240.0 > HL_HMAX)
    for k, s in ev.items():                                    # sunrise/Maghrib into the local day: ONE day
        if s is not None and k in ("sunrise", "maghrib"):
            ev[k] = s + 86400.0 if s < 0.0 else (s - 86400.0 if s >= 86400.0 else s)
    if raw:
        return ev
    sec = {k: (None if s is None else int(s)) for k, s in ev.items()}          # truncate to the second (toward zero)
    out = {k: (None if s is None else (int(s / 60) if k == "sunrise" else int((s + 59) / 60)))
           for k, s in sec.items()}   # sunrise: trunc(s/60); others: trunc((s+59)/60). Truncation TOWARD ZERO, i.e. floor/ceil
    #                                 # only for s >= 0 (Fajr/Asr/Eid/Dhuhr can be negative: ceil(-61/60) = -1 but this gives 0)
    if hl_fallback:                                            # |lat| > 48, also on polar days (latitude-45 events)
        night = ((86400.0 - ev["maghrib"]) + ev["sunrise"]) % 86400.0   # maghrib may fall after midnight
        out["fajr"] = int(int(ev["sunrise"] - _hl_portion(d, lat, lon, tz) * night) / 60)       # rounded like sunrise
    if out["eid"] is None and out["sunrise"] is not None:
        out["eid"] = out["sunrise"] + 15
    out["isha"] = None if sec["maghrib"] is None else int((sec["maghrib"] + (7200 if ramadan else 5400) + 59) / 60)
    return {k: (None if x is None else _hhmm(x)) for k, x in out.items()}


def _hhmm(x):
    """Server formatting (C#): minutes x -> hours = x / 60 and minutes = x % 60, truncated toward zero, each "00"
    (so Isha can read "24:39" and a negative time "-01:-13")."""
    h = int(x / 60)
    m = x - 60 * h
    two = lambda v: ("-" if v < 0 else "") + f"{abs(v):02d}"
    return f"{two(h)}:{two(m)}"


def site_coordinate(x):
    """Round a GPS coordinate exactly as the Umm al-Qura website does before calling its API ("locate me" button):
    6 decimals, decided on the number's shortest decimal representation, ties away from zero.
    Verified 2026-10-27 with 7 test positions (positive/negative, ties and non-ties)."""
    from decimal import Decimal, ROUND_HALF_UP
    return float(Decimal(repr(float(x))).quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP))


def prayer_times_gps(d, lat, lon, ramadan=False, tz=TZ):
    """Times for a raw GPS position, rounded the way the website rounds it."""
    return prayer_times(d, site_coordinate(lat), site_coordinate(lon), ramadan=ramadan, tz=tz)


if __name__ == "__main__":
    print(prayer_times(date(2027, 3, 20), 21.4225, 39.8262))
