# معادلة أوقات الصلاة في موقع تقويم أم القرى الرسمي (إعادة بناء)

هذه معادلة تعيد إنتاج أوقات الصلاة التي يحسبها خادم موقع تقويم أم القرى الرسمي (ummulqura.org.sa) لأي إحداثيات وأي منطقة زمنية:
الفجر، والشروق، والظهر، والعصر، والمغرب، والعشاء، ووقت صلاة العيد.

**ما هي وما ليست:** المعادلة أُعيد بناؤها من البيانات المنشورة ومن قياسات دقيقة لخادم موقع تقويم أم القرى الرسمي. ليست الكود الأصلي من
مدينة الملك عبدالعزيز للعلوم والتقنية.

**نتائج التحقق (1 نوفمبر 2026، 7,138,943 وقتاً):**
- **4,864,587 وقتاً رسمياً** (173 مدينة سعودية، كل يوم من 2025 إلى 2035): **صفر اختلاف**.
- **873,810 وقتاً لـ114 مدينة حول العالم** بمناطقها الزمنية (منها +5:45 و+3:30 والسالبة، ومدن القطب الشمالي) للأعوام
  2026 و2029 و2033: **صفر اختلاف**.
- **480,830 وقتاً لـ59 مدينة جديدة** (مناطق +13 و+14 و+12:45 و−3:30، والقطبان، وسنوات جديدة): 18 اختلافاً فقط، كلها في
  ثلاثة أيام قطبية تكاد الشمس فيها تلامس الأفق، وحساب الخادم فيها نفسه غير مستقر (مثل مغرب بوقت سالب).
- **تقاويم PDF الرسمية وبيانات الخمس سنوات واختبارات أيام التأخر وخطوط الطول والعرض:** صفر اختلاف.
- **اختبارات لم تُستعمل في بناء المعادلة (عمياء):** 363,013 وقتاً لـ142 موقع GPS في السعودية؛ و352,828 وقتاً لمدينة واحدة
  على مدى 138 سنة (2001–2149)؛ و320 وقتاً سعودياً على بعد 0.01–0.1 جزء من الألف من الثانية من تغيّر الدقيقة: **كلها مطابقة**.
- **أيام التأخر** (الخادم يعرض أوقات اليوم السابق): 10,641 حالة في 37 منطقة زمنية، **صفر خطأ**.
- **تواريخ بعيدة** من سنة 1000 إلى 3600: مطابقة.
- **الباقي:** 18 قيمة فقط، كلها في الأيام القطبية الثلاثة. وقيم فرق الزمن الفلكي عند الخادم قيست مباشرة في 35 تاريخاً من 1900 إلى 1999 وطابقت جدول Meeus.

**التنفيذ المرجعي:** `umm_al_qura_times.py` (بايثون فقط، بلا مكتبات خارجية). لإحداثيات GPS خام استعمل
`prayer_times_gps` لأنها تقرّب الإحداثيات إلى 6 خانات عشرية كما يفعل الموقع.

---

# Umm al-Qura prayer times — reconstructed algorithm

Reproduces, to the minute, the prayer times the Umm al-Qura calendar service (KACST) returns for any coordinates and
time zone. This is a reconstruction from public timetables and millisecond-level probing of the public API. It is not
KACST's source code.

## Verification (2026-11-01)

Status: **tuned** = the data shaped the rules or constants (in-sample); **clean** = run against frozen code before
any change it could suggest (out-of-sample).

| Data set | Values | Differences | Status |
|---|---|---|---|
| All 173 Umm al-Qura (Saudi) cities, every day 2025–2035 | 4,864,587 | **0** | tuned |
| 114 world cities in their own zones (incl. +5:45, +3:30, negative zones, Arctic), 2026/2029/2033 | 873,810 | **0** | tuned |
| Official yearly PDFs | 10,650 | 0 | tuned |
| 5-year export (Hijri 1444–1448) | 53,160 | 0 | tuned |
| Lag-day probes 2022–2040 | 48,580 | 0 | tuned |
| Longitude sweep | 102,200 | 0 | tuned |
| 59 new cities and zones (+13, +14, +12:45, −3:30, +9:30, +8:45, −9:30, −11, Arctic, Antarctic), new years | 480,830 | 18, all on 3 grazing polar days (see the end) | tuned |
| London, single-day endpoint, May–Aug 2029 | 749 | 0 (identical to the by-year endpoint) | tuned |
| Bug hunt: 260 days 0.6–30 ms from a minute change (1940–2100); Saudi whole years 1940–2150; world points | 352,254 | 0 | tuned |
| Random GPS points all over Saudi Arabia + 12 extreme points, 2026–2030; 260 Saudi days 0.5–20 ms from a minute change | 364,833 | **0** | **clean** |
| One city (Arar), every day 2001–2149, Hijri date and Ramadan flag from the server | 352,828 | **0** | **clean** |
| Saudi times 0.01–0.10 ms from a minute change (zone 3) | 320 | **0** | **clean** |
| Same, zones 1 and 4 | 80 | 6 → 1 after the jd0 fix (step 2) | tuned |
| Lag / no-lag facts, 37 zones, 1940–2150 (incl. 6 lag days predicted before measuring) | 10,641 | 0 | tuned (6 clean) |
| Website: 12 h / 24 h, Arabic / English output | — | identical | clean |
| Single dates 1000–3600, edge zones and coordinates (68 days) + 19 inputs the server rejects | 476 | 0 | tuned |

About 1,600 measurements of where a minute changes (resolution ±0.003–0.19 ms) are each reproduced within their
resolution. Most of them are in-sample: they shaped the constants. The 320 clean Saudi values bound the model's
timing error to under about 0.007 ms there.

## The algorithm

Inputs: date, latitude φ, longitude L (east positive), zone z in hours (the API's `zone`, default 3).

1. **Computed date.** The server converts the requested date (proleptic Gregorian, also before 1582) to Julian
   centuries t at local midnight with NREL-SPA's `julian_day` (the Gregorian correction always applied), and back:
   date of (2000-01-01 12:00 + floor(t·36525·24 + z) hours). Double-precision rounding sometimes gives the previous
   day. On those "lag days" the server shows the previous day's times. In zones 2 and 5 this happens on every day.
2. **Day frame.** Every event is computed in a day that starts at local midnight: `jd0 = 0h UT − z h` of the
   computed date. Event times `m` are fractions of that day. jd0 is a double (step 4.66·10⁻¹⁰ day): its rounding
   error, ((jd0 − 2451545) − (days − ½ − z/24))·86400 s, is added to every event (+13.4 µs in zones 1/4/7, −13.4 µs
   in zones 2/5, 0 in zones 0/3/6/9/12).
3. **Time scales (Meeus, *Astronomical Algorithms*, 1st ed., ch. 9).** T = Julian centuries from J2000.0 of the
   requested local midnight (SPA `julian_day`, as in step 1), y = 2000 + 100T.
   - y > 2000 (strictly; y = 2000.0 exactly, i.e. 2000-01-01 in zone −12, uses the table): ΔT = 102.3 + 123.5T + 32.5T² s (Stephenson–Morrison 1984 expanded at 2000).
   - 1620 ≤ y ≤ 2000: Table 9.A (biennial, 1620…1992, values in 0.1 s; `DT_TABLE` in the reference code), linear
     interpolation: i = min(trunc((y − 1620)/2), last − 1), ΔT = (tab[i] + (tab[i+1] − tab[i])·(y/2 − (i + 810)))/10.
     Past 1992 the last interval is extended, so 1990–1999 follow 56.9 + 0.55(y − 1990).
   - y < 1620 (Stephenson–Houlden): 50.6 + 67.5T + 22.5T² (from 948), 2715.6 + 573.36T + 46.5T² before.
   It is applied twice: the Sun nodes are taken at jd0 + k + ΔT, and the interpolation uses
   n = m + ΔT/86400.
4. **Sun.** Geometric heliocentric Earth from NREL-SPA's VSOP87D term selection, with full VSOP87D coefficients. The
   exception is the six large rows (L0[0..2], L1[0..1], L2[0]), whose amplitude and phase are **rounded to 6 decimals**,
   for example 0.033417 and 6283.319667.
   - No nutation and no aberration.
   - Mean obliquity: Laskar's formula, evaluated **once** at the requested local midnight, plus −0.039131″.
   - The Sun's right ascension α and declination δ come from this longitude and obliquity.
5. **Sidereal time.** ν = GMST at jd0 (Meeus 12.4) + 0.146457″. Three nodes (α, δ) at k = −1, 0, +1, with Meeus/SPA
   quadratic interpolation (α differences larger than 2° are replaced by their fractional part). Local hour angle
   H(m) = ν + 360.985647·m + L − α(m), brought into [−180°, 180°): x mod 360 (floored modulo, result in [0, 360) —
   not C/JS `%`), then minus 360 if ≥ 180. All "mod" in this spec are floored (Python `%`).
6. **Dhuhr.** m0 = (α₀ − L − ν)/360, then **one** day is added if m0 < 0 — never mod 1, never reduced. Then
   transit: start at m0 and repeat transit += −H(transit)/360 until the step is below 0.0002 day (at most
   20 steps — one step on ordinary dates, two when m0 is far off), and Dhuhr gets one day added if negative. In zones ≥ +12 around
   20–22 March m0 stays negative (the previous day's Dhuhr is shown); with a zone far ahead of the longitude Dhuhr
   can read "26:47".
7. **Sunrise and Maghrib.**
   - h0 = −0.8333°. H0 is computed from the δ node at local midnight.
   - First guess: sunrise m0 − H0/360, plus one day if negative; sunset m0 + H0/360, minus one day if ≥ 1 (so a sunset
     after local midnight returns the previous evening's sunset).
   - Newton steps: dm = (h − h0)·cos h / (360·cos δ·cos φ·sin H), with δ interpolated at m.
   - Stop when |dm| < 0.0002 day. The step that passes the test is applied. At most 20 steps; a step larger than
     5 days ends the iteration (no convergence).
   - Sunrise gets +3.2001 ms.
8. **Fajr, Asr, Eid.** These use δ at the **next** local midnight and the closed-form hour angle, added to or
   subtracted from Dhuhr (the final Dhuhr, after its "+1 day if negative"). They are **never wrapped** (e.g. Asr "26:23", Fajr "-02:-51" with an unusual zone).
   - Fajr: −18.5°, before transit.
   - Asr: altitude atan(1/(1 + tan|φ − δ|)), after transit.
   - Eid prayer: +4.2°, before transit. If unreachable: the sunrise **minute** + 15, i.e. rounded like sunrise
     (step 10), not like the other times.
9. **Isha.** Maghrib + 90 min, or + 120 min in Ramadan, added to Maghrib's seconds before rounding (not wrapped
   past 24:00, e.g. "24:39").
10. **Rounding (C# integer arithmetic).** Bring sunrise and Maghrib into the day by adding or removing ONE day (not mod),
    truncate to the second s (toward zero). Sunrise: M = trunc(s/60). Every other time: M = trunc((s + 59)/60).
    Print trunc(M/60) and M − 60·trunc(M/60) as "00" each, so a negative value prints like "-01:-13".

### Outside Saudi Arabia

11. **Polar days.** If sunrise or sunset does not exist, or either iteration does not converge, or the day or the
    night is shorter than one hour — (Maghrib − sunrise) mod 86400 < 3600 s or (sunrise − Maghrib) mod 86400 < 3600 s,
    on the Newton results before step 10's one-day shift — Fajr, sunrise, Maghrib, Asr and Eid are computed for latitude ±45° (same
    longitude and zone).
12. **Fajr above 48°.** If the real |φ| > 48° and the Fajr in use (on a step-11 polar day: the ±45° Fajr) does not
    exist or its hour angle (Dhuhr − Fajr)/240 exceeds 151.84401° (tested before step 10's shift): Fajr
    M = trunc(trunc(sunrise − p·night)/60), with the sunrise in use, brought into the day (+3.2001 ms included),
    night = (86400 − Maghrib + sunrise) mod 86400 s on the in-day values, and
    `p = (sunrise₄₅ − fajr₄₅ + 58.99367 s) / (86400 − Maghrib₄₅ + sunrise₄₅)` with the RAW latitude-±45° values (no
    shift, no mod, fajr₄₅ may be negative), computed with the full pipeline (lag rule included) at latitude ±45°
    (same longitude and zone) on 21 June of the requested year (21 December south of the equator). This is the
    "nearest latitude" rule taken on the solstice.
13. **Inputs the server rejects** (HTTP 400): |φ| > 90 or |L| > 180 ("Invalid latitude or longitude."), zone outside
    about −12…+14 ("Invalid Zone"; −13 and +14.5 rejected), impossible dates (29 Feb 2027).
14. **GPS input.** The website rounds coordinates to 6 decimals (shortest decimal form, ties away from zero) before it
    calls the API.

**Fitted constants (not derived):**
- GMST offset +0.146457″
- obliquity offset −0.039131″
- sunrise offset +3.2001 ms
- high latitudes: Fajr hour-angle limit 151.84401° (window 151.8440087–151.8440117°), twilight addend 58.99367 s (window
  58.99355–58.99380 s), one-hour polar limit, at most 20 Newton steps (16–50 fit), runaway step 5 days (2.75–9.77 fit)

**Known limits.**
- *Microsecond level:* the ΔT time argument (step 3) fits year-3600 data and decides 30/30 targeted minutes, but
  leaves 0.015 ms residuals there and raises the 2025–2035 bisection RMS from 0.0019 to 0.0024 ms; the exact argument
  may differ slightly. No minute is affected in any data set.
- ΔT was read directly (±0.005 s) at 48 dates from 1000 to 2000, including the switch on 1 January 2000.
- *Two historical lag days:* on 1997-03-14 and 1999-12-29 (zone 3) the server does not show the lag the floor rule
  predicts (|jd − 2451545| just below a power of two); cause unknown. 25 values in the doubled data set.
- *Grazing polar days* (0–2 days a year, only above about 66°): when the Sun barely touches the horizon, the server's
  sunrise/sunset iteration bounces chaotically and sometimes stops on a meaningless value (e.g. Maghrib "-11:-58").
  Such values cannot be reproduced without the original code; the model gives the latitude-45° values there.
  Found: McMurdo 2027-04-25, Hammerfest 2030-11-22, Tromsø 2031-05-18.
- *Addend:* the London-2029 and Anchorage-2029 measurements disagree by 0.0004 s, so a fallback Fajr above 48° can be
  one minute off when it lies within about 0.0003 s of a minute boundary.
