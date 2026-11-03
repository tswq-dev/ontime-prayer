# Umm al-Qura prayer times: reconstructed method — FINAL = compute_v14 / umm_al_qura_times.py (section 7g; clean spec in publish/ALGORITHM.md). Sections 1–7f are the history.

This document describes an algorithm that reproduces, to the minute, the prayer times published by the
Umm al-Qura service (umqserv.kacst.gov.sa / ummulqura.org.sa) for the Saudi range. It is a **reconstruction**: we
inferred it from published timetables, the public API, and millisecond-level probing of the API's minute
boundaries. It is not KACST's source code. Every constant marked **[empirical]** was fitted to data, and we don't
know where it comes from. Reference implementation: `ummalqura.compute_v8` (`V8` holds the constants). v8 is v7 with
a different, empirical stop test for the sunrise/maghrib iteration and three re-fitted rise/set constants (section 7,
"v8 stop test"); everything else is identical. `compute_v7` is kept unchanged. v9 (`compute_v9`, `V9`) is v8 plus
two **empirical** correction terms found in the full 173-city export (section 7b). v8 stays the reference
without them.

## 1. Inputs, outputs, conventions

* Inputs: Gregorian date `d`, latitude φ (deg, north +), longitude L (deg, **east +**), time zone tz = +3 h,
  and a Ramadan flag (Hijri month 9).
* Outputs: local clock times HH:MM for Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha, and the Eid prayer time.
* Times are computed as fractions of a day, `m`, counted from 0h UT of the computed date. Local seconds are
  `s = 86400 m + 3600 tz`.

## 2. Computed date

The server computes the times for `cd = server_date(d)`, not always for `d`:

    jd  = JD(year, month, day, 00:00 local, tz)        (NREL-SPA julian_day, i.e. 0h UT − tz/24)
    t   = (jd − 2451545) / 36525
    n   = floor(t · 36525 + 0.5 + tz/24)
    cd  = 2000-01-01 + n days

In exact arithmetic `cd = d`. In double precision the round trip through `t` sometimes falls just below an
integer, and the server then returns the times of the previous day ("lag days").

## 3. Time scales

* ΔT (s) = 102.3 + 123.5 t + 32.5 t², with t = (y − 2000)/100 and y the **decimal** year of `cd`
  (y = year + day_of_year_index / days_in_year). This is the Chapront-Touzé & Chapront polynomial quoted by Meeus.
* jd0 = Julian day of 0h UT of `cd`.

## 4. Sun position (geometric, mean equinox of date)

For a dynamical Julian day `JDE`, with τ = (JDE − 2451545)/365250:

* Heliocentric Earth L, B (rad) = Σᵢ τⁱ Σ A cos(P + W τ). The terms are NREL-SPA's truncated VSOP87D selection
  (L0..L5, B0..B1), but with the full-precision VSOP87D coefficients. The main eccentricity term L0[1] has
  amplitude **0.033417** instead of 0.03341656 **[empirical]**, as if that one amplitude were rounded.
* λ = L + 180°, β = −B. No nutation, no aberration, no FK5 correction.
* ε = Laskar's mean obliquity (Meeus 22.3) + **0.0105″** **[empirical]**.
* α = atan2(sin λ cos ε − tan β sin ε, cos λ).
* δ = asin(sin β cos ε′ + cos β sin ε′ sin λ), with ε′ = ε **− 0.0550″** **[empirical]**. In other words, the
  declination behaves as if its obliquity were 0.055″ smaller than the one used for α.

## 5. Sidereal time and interpolation nodes

* ν = GMST at jd0 (SPA/Meeus 12.4, in degrees) **+ 0.0450″** **[empirical]**.
* Three nodes: (α₋, δ₋), (α₀, δ₀), (α₊, δ₊) = Sun at JDE = jd0 + k + (ΔT − **5.00 s** **[empirical]**)/86400,
  for k = −1, 0, +1. SPA puts its nodes at 0h UT with ΔT = 0. The server applies ΔT here **and again** in the
  interpolation, so the Sun is effectively evaluated at UT + 2ΔT − 5 s.
* Interpolation (Meeus 15 / SPA): n = m + ΔT/86400, with a = x₀ − x₋ and b = x₊ − x₀ (α differences wrapped as in
  SPA). Then x(m) = x₀ + n (a + b + (b − a) n)/2.
* Local hour angle: H(m) = ν + 360.985647 m + L − α(m), reduced to [−180°, 180°).

## 6. Dhuhr (transit)

    m0 = ((α₀ − L − ν)/360) mod 1
    transit = m0 − H(m0)/360          (one correction only, as in SPA)
    Dhuhr = transit

## 7. Sunrise and Maghrib

* h0 = −0.8333° (the fitted value is −0.8333032°, which is −0.011″ from −0.8333 **[empirical]**).
* First guess, x0 = m0 ∓ H0/360 with cos H0 = (sin h0 − sin φ sin δs)/(cos φ cos δs):
  * sunrise: δs = declination at **local midnight**, i.e. the Sun at jd0 − 0.125 + (ΔT − 5 s)/86400;
  * maghrib: δs = δ₀ (the 0h-UT node, as in SPA).
* Newton correction on the altitude, with the **exact** derivative of h with respect to the hour angle:

      h  = asin(sin φ sin δ + cos φ cos δ cos H)        H = H(m), δ = δ(m + 2.81 s) [empirical]
      dm = (h − h0) · cos h / (360 cos δ cos φ sin H)
      m ← m + dm,  repeated until |dm| < 15 s (the step that passes the test is applied; at most 12 steps)

  This is SPA's / Meeus' correction with an extra cos h factor. Equivalently, it is Newton's method on sin h
  rather than on h. The cos h factor (cos 0.833° = 1 − 1.06·10⁻⁴) replaces the empirical "first-step scale"
  `rs_kap` of the earlier candidate. Fitting the scale freely on top of cos h gives −0.19·10⁻⁴ (sunrise) and
  +0.15·10⁻⁴ (maghrib), both close to zero. A term in dδ/dt in the derivative is rejected (fitted weight 0.02),
  and so is a sidereal rate (360.985647 − α̇) in the denominator (RMS 0.74 → 1.81 ms).
  When |dm₁| < 15 s the result is the one-step value, which differs from the converged root by up to ~15 ms.
* The declination inside the iteration is taken 2.81 s later than α **[empirical]**.
* Offsets added to the result: sunrise **+4.22 ms**, maghrib **−0.83 ms** **[empirical]** (v8: +4.48 / −0.79 ms,
  δ epoch +2.72 s). These are needed in the converged regime too, so they are not an iteration effect. They are
  true time offsets: replacing the antisymmetric part by an h0 change (common offset + h0) raises the maghrib RMS on the
  clean points from 0.55 to 1.33 ms, and dropping the δ epoch (rs_tdelta = 0) raises it to 1.0 ms. So neither could
  be removed. Per date, the converged sunrise offset still varies between +3.6 and +5.6 ms and the maghrib one between
  −0.9 and +0.2 ms, so a constant is only an approximation.

**v8 stop test [empirical].** Rounds 1–5 (270 rise/set points with a clear regime) show that the switch between
the one-step and the converged result is not at a fixed |dm₁|. Maghrib switches at |dm₁| ≈ 14.4–14.5 s (Nov 5:
14.05–14.20 s). Sunrise switches at 15.0 s on 2027-03-21 but at 17.4–18.8 s or more on Feb 5, Aug 6, Sep 23,
Oct 15 and Nov 5. v8 uses

    sunrise:  stop when |dm + 0.41 · (transit − m0)·86400| < 17.62 s
    maghrib:  stop when |dm·86400| < 14.47 s

where transit − m0 is the Dhuhr correction of section 6 (−7…+12 s). With this term every sunrise date except
2027-01-15 (43–51°N) separates cleanly (0 of 118 points misclassified, gap 0.13 s; coefficient range 0.39–0.43). With
|dm₁| alone the best tolerance misclassifies 24. Maghrib keeps 1 misclassified point (2027-11-05, 18.24°N). We have
**no physical explanation** for the 0.41 coefficient or for the two different tolerances. Several things were tested
and fail. (1) A start shift on its own: the residual-implied start equals the model's first guess within ~0.3 s
(the local-midnight declination epoch minimises the one-step spread on every date), but the regime needs 0–5 s.
(2) Geometric scalings of |h − h0|. (3) A test on dm₂ in ms. (4) A joint rise/set test. (5) An exact Newton
derivative (δ̇, α̇). (6) A start placed where H(x) = ∓H0 exactly. (7) The transit as the centre of the first guess.

## 7b. v9: two empirical terms (2026-10-25, full export)

Data: `probe/full/all-cities.json.gz` holds official times for 173 Saudi cities and every day of 2025–2035
(4,864,587 values). v8 gives 27 minute mismatches (23 independent; Isha follows Maghrib), all within 1.3 ms of a
minute boundary. `probe/full/constraints_full.json` lists all 408 values whose v8 raw time is within 3 ms of a
boundary, with the side the official minute requires. v8 violates 23 of them. Both v9 terms were fitted **only on
the ms-bisection points at |φ| < 33°**. The full export was not used for fitting, so every full-data number below
is out of sample (2033–2035 were also kept as a separate check).

* **α-curvature term [empirical].** α(m) gets an extra `kc2 · ((b − a)² − c2_0)` (ms of time, converted to
  degrees at 360.985647°/day). b − a is the second difference of the three α nodes in millidegrees (0 to 3.6),
  kc2 = 0.0543 ms/mdeg², c2_0 = 4.5 (its 2025–2035 mean). GMST offset −0.00087″ keeps the mean unchanged. The
  Dhuhr, Asr, Eid and Fajr residuals share the same seasonal pattern: near zero at the solstices and
  equinoxes, +0.5…+0.8 ms about 45° of solar longitude either side of the December solstice, smaller in
  May/Aug. A cos 4L harmonic or EoT² fits it almost as well, so the functional form is not pinned down. Bisection
  RMS: Dhuhr 0.30 → 0.17, Eid 0.40 → 0.20, Asr 0.41 → 0.28, Fajr 0.40 → 0.23 ms. Rejected explanations: ΔT
  decimal-year conventions (mid-month, start of month), a quadratic interpolation error (computing α exactly is worse),
  VSOP87D terms omitted by SPA (would be ~5 ms RMS), the FK5 frame correction (fitted weight −30), interpolation
  nodes centred at local midnight, a time-scale factor on the interpolation variable, and a second transit
  correction.
* **Day-length term [empirical].** Sunrise −qday·q, Maghrib +qday·q, with q = transit − m0 in seconds
  (−7.5…+11.5 s, the Dhuhr correction of section 6) and qday = 0.0417 ms/s. Fitted freely, the two events gave
  −0.043 and +0.040 ms/s, i.e. antisymmetric. Converged maghrib points show it most clearly (Dec/Jan +0.9…+1.5 ms,
  elsewhere ≈ 0 to +0.3). ev_off re-fitted: sunrise +4.162, maghrib −0.674 ms. Origin unknown.
* **ΔT drift: none.** A linear-in-year epoch drift fitted on all bisection points (2005–2065) is −0.02 s per
  decade, i.e. zero. The ΔT polynomial of section 3 stands.

## 7c. v10: sunrise and maghrib in the local-midnight frame (2026-10-25, round-6 analysis)

**Finding.** The server computes sunrise and maghrib in a day frame that starts at **local (Saudi, UTC+3) midnight**,
i.e. with Julian day jd0 − tz/24, instead of 0h UT. Inside that frame everything is as in section 7: GMST at local
midnight, three Sun nodes at local midnight −1/0/+1 day (+ ΔT − 5 s), m0 from the local-midnight α node, the first guess
H0 from the local-midnight δ node (**both** events), the same Newton step, and the result converted back with + tz.
Dhuhr, Asr, Fajr and Eid stay in the 0h-UT frame (a local frame makes Dhuhr worse: 0.30 → 0.41 ms RMS).

**Stop rule.** In this frame the switch between the one-step and the iterated result is a single threshold for both
events: one step when |dm₁| ≤ 17.21 s, iterated when |dm₁| ≥ 17.29 s (270 labelled points, no exception below 45°).
That is **|dm| < 0.0002 day (17.28 s)**. This replaces, with nothing empirical left: the sunrise-only local-midnight
declination epoch, the maghrib 0h-UT declination, the v7 15 s tolerance, the v8 test |dm + 0.41(transit − m0)| < 17.62 s /
14.47 s (the 0.41(transit − m0) term was the frame difference), and the v9 day-length term qday.

**Evidence.** One-step sunrise residuals (|φ| < 40°) at fixed date no longer depend on latitude (within-date RMS 0.26 →
0.12 ms), and the frame offset is sharply optimal at exactly −3 h (−2.4 h: 0.24, −3.6 h: 0.35 ms). Rise/set RMS on the
bisection points: sunrise 1.24 → 0.40 ms, maghrib 0.63 → 0.28 ms (ev_off +4.49 / −0.44 ms still empirical). Full
173-city export: v8 27 mismatches → v10 20 (sunrise 9 → 6, maghrib 4 → 2). Official PDFs, 5-year export, lag, lonsweep and
highlat ≤ 58°: 0 mismatches. `compute_v10` / `V10`. A joint refit of the other constants in this structure (`V10B`) lowers
the bisection RMS (0.35 → 0.30 ms) but not the full-data count (22), so V10 stays the reference.

## 7d. v11: seasonal residual (round 6, 2026-10-25) — EMPIRICAL

Round 6 (192 targets, 24 dates of 2027 every ~15 days, all prayers at 21.43°N plus sunrise/maghrib at 28.4°N,
±0.09 ms) maps what v10 still misses. It is a smooth function of the season, the same at both latitudes:
* sunrise/maghrib: flat (−0.3 ms) from March to October, a bump in November–February that peaks at the December
  solstice (sunrise +1.3 ms, maghrib +0.75 ms above the flat part);
* Dhuhr (and with it Fajr/Asr/Eid): a four-wave pattern in solar longitude, +0.66 ms near the equation-of-time
  extremes (Feb, Nov), −0.47 ms at the December solstice and early September.
Tested and rejected as causes: exact (non-interpolated) α/δ in the rise/set iteration, Earth–Sun distance, α̇, a
local frame for Dhuhr. The cause is **not identified**.

`compute_v11` = v10 + `V11_FOURIER`: for each prayer a Fourier series with 4 harmonics in the Sun's mean longitude
(ms), fitted on rounds 1–6 at |φ| < 35°. Bisection RMS after the fit 0.08–0.12 ms (the measurement resolution).
Full 173-city export (not used in the fit): **8 mismatches** (7 independent) of 4,864,587, every one within 0.15 ms of
its minute boundary. All other datasets: 0 mismatches (highlat > 58°: the same 11 Fajr as before).

## 7e. Round 7 (µs precision): Dhuhr is in the local-midnight frame too (2026-10-26)

Round 7 (192 targets, ±0.003 ms) showed a ~30-day oscillation (0.03 ms) in Dhuhr/Asr/Fajr that rise/set did not have,
and a small Dhuhr difference between longitudes 36° and 50°. Both vanish when the transit is computed in the
local-midnight frame as well (node epoch scan: −3 h gives 0.004 ms RMS after a smooth seasonal term, 0 h gives 0.046,
±1.5 h 0.02). So **all events use the frame that starts at local (UTC+3) midnight**; the earlier preference for the 0h-UT
frame for Dhuhr was an artefact of fitting at ±0.2 ms. Lab implementation: `probe/r6/v12lab.py` (constants re-fitted,
`probe/r6/g12_out.json`). What remains is a smooth seasonal term common to all events (≈ 0.28 cos M ms, M = mean anomaly,
plus a 2L term) and small declination terms for rise/set and for Fajr/Asr/Eid (`probe/r6/phys12.py`, 3 harmonics:
round 7 RMS 0.008 ms). Full export near-boundary check: 6 of 287 still on the wrong side (Eid ×3, Fajr ×2, maghrib ×1,
all ≤ 0.075 ms); round 8 measures exactly those places, Eid at two latitudes, and other years (2031, 2035).

## 7f. v12: the server's VSOP table is rounded (round 8, 2026-10-26) — the empirical shifts disappear

Round 8 (158 targets, ±0.003 ms) showed a year-dependent drift (2031, 2035) that no seasonal term could absorb.
It is explained by the server's VSOP87D table: the **six large rows** (L0[0], L0[1], L0[2], L1[0], L1[1], L2[0]) have
amplitude and phase **rounded to 6 decimals** (L0[0] = 1.753470, L0[1] = 0.033417 — the old "empirical" amplitude —,
L1[0] = 6283.319667, …); every other row keeps full VSOP87D precision (rounding any other row costs milliseconds).
With that table and every event in the local-midnight frame (7e), a joint fit gives **dt_pos_add = 0, dt_dec_add = 0,
rs_tdelta = 0, deps_dec = 0 and h0 = −0.8333 exactly, maghrib offset 0** — all the old epoch/declination tweaks were
compensating the rounding. Constants still fitted: GMST offset +0.1466″, obliquity offset −0.0382″, sunrise offset
+3.197 ms. `compute_v12` / `V12`.

Accuracy: Dhuhr ±0.006 ms in 2027, 2031 and 2035 (no seasonal term), maghrib ±0.005, Asr/Fajr/Eid ±0.02,
sunrise ±0.035 ms. Full 173-city export: **2 mismatches of 4,864,587** (Eid 2032-01-10, a lag day, 0.02 ms; Fajr
2025-02-25, 0.000 ms from the boundary — round 8 measured +0.010 ms there). All other datasets 0 (highlat > 58°: the
same 11 Fajr).

## 7g. FINAL — v14 (2026-10-26, round 9): obliquity and ΔT per requested date; ΔT in Julian centuries

Round 9 (108 targets, lag days, southern Fajr/Eid, 2029) showed (a) lag days off by up to 0.1 ms and (b) a common
offset of 0 or +0.01 ms that depended on the year. Both are explained by two small changes:
* the mean obliquity is evaluated **once**, at the **requested** date's local midnight, for all Sun nodes;
* ΔT is computed once for the **requested** date with **t = (JD(0h UT) − 2451544.5)/36525** (Julian centuries), not
  from the calendar decimal year, which differs by up to 0.75 day over the leap-year cycle.
With these (`compute_v14`, standalone `umm_al_qura_times.py`, spec in `publish/ALGORITHM.md`):
**full 173-city export 0 / 4,864,587 differences** (closest margin to a minute boundary 0.003 ms); official PDFs,
5-year export, lag, longitude and latitude ≤ 58° probes all 0; ms-bisection rounds 1–9 each reproduced within the
measurement resolution (rounds 7–9 RMS 0.0018–0.0021 ms). The only fitted constants left: GMST offset +0.146657″,
obliquity offset −0.039131″, sunrise offset +3.2001 ms.

## 7h. Round 10 (2026-10-26): the three fitted constants are global

104 targets with the API's `zone` parameter (0–6 at 24°N 46.7°E) and far cities in their own zones (Rabat 34°N −6.8°E
zone 0, Jakarta 6.2°S 106.9°E zone 7; Cairo/Karachi targets fell on year-3600 dates because, in zones 2 and 5, the
server_date round trip lags on every day — see below). With the SAME three constants as for Saudi Arabia:
zone 0 +0.002 ms, zone 3 0.000, zone 6 −0.003, Rabat +0.002, zones 1/4/7 +0.009…+0.015 ms (these zones are not exact
binary fractions of a day; sub-0.02 ms floating-point differences, irrelevant for UTC+3). So the GMST offset, the
obliquity offset and the sunrise offset do NOT come from the time-zone handling, the longitude, the latitude or the
hemisphere: they are constants of the server's own astronomy code. Removing any of them breaks the full export
(GMST 769, obliquity 59, sunrise 36 mismatches).
Side result (model prediction, not separately verified): with zone=2 or zone=5 the server_date round trip falls on the
previous day for every date of 2027–3599, i.e. those zones always get the previous day's times.

## 8. Fajr, Asr, Eid

* One declination δn: the Sun at the next local midnight, JDE = jd0 + 1 − tz/24 + (ΔT − **2.38 s** **[empirical]**)/86400.
* Hour angle for altitude a: cos Ha = (sin a − sin φ sin δn)/(cos φ cos δn). Time = transit ± Ha/360.
  * Fajr: a = −18.5°, before transit.
  * Asr: a = atan(1/(1 + tan|φ − δn|)) (shadow factor 1), after transit.
  * Eid prayer: a = +4.2°, before transit.
* Isha = Maghrib + 90 min (120 min in Ramadan).

## 9. Rounding

The local time in seconds is **truncated** to an integer second s. Sunrise is shown as floor(s/60) minutes. Every
other time is shown as ceil(s/60) minutes.

## 10. Outside Saudi Arabia: high latitudes, polar days, time zones ≥ +12 (2026-10-27)

Data: world export 1 (114 cities, own zones, 2026/2029/2033, 873,810 values) — **0 differences**; world export 2
(out of sample: 59 new cities/zones incl. +13, +14, +12:45, −3:30, +9:30, +8:45, −9:30, −11, Arctic, Antarctic,
southern >48°, and new years 2027/2028/2031/2040 for the old high-latitude cities; 480,830 values) — the round-1 model
(before this section's last fixes) gave 76 differences, all on polar edge days, at Vienna (addend) and in negative
Fajr values; after the fixes 18 remain, all on 3 "grazing" days (below). Round 11: 16 ms-bisections, single-day and
by-year endpoints identical.

* **Sunrise/sunset iteration.** Newton steps as in section 7, at most **20** (data need ≥ 16; 16–50 fit). A step larger
  than **5 days** (fits 2.75–9.77) ends the iteration as failed. asin of a value outside ±1 gives NaN (no convergence).
* **Polar test.** Substitute latitude ±45° (same longitude and zone) for Fajr, sunrise, Maghrib, Asr and Eid if
  sunrise/sunset does not exist (H0 undefined at δ₀), **or either iteration did not converge**, **or the day
  (Maghrib − sunrise) or the night (sunrise − Maghrib), mod 24 h, is shorter than one hour** (converged days: largest
  substituted 0.9950 h, smallest kept 1.0043 h, ~150 days near the limit). Dhuhr stays local.
* **Into the day:** every raw time gets ONE day added if negative, ONE removed if ≥ 24 h (not mod), so a far-off
  iteration result stays negative (Longyearbyen 2030-04-18 Maghrib "-01:-13").
* **Rounding (C#).** seconds = trunc toward zero; sunrise M = trunc(s/60); other times M = trunc((s + 59)/60);
  Isha M = trunc((s_maghrib + 5400 [7200 in Ramadan] + 59)/60). Printed as trunc(M/60) ":" M − 60·trunc(M/60), each
  "00" with its own sign. Identical to floor/ceil for positive times; it explains "-01:-13" with Isha "00:16".
* **|φ| > 48° Fajr fallback.** Decided on the raw (unwrapped) times: if Fajr does not exist or its hour angle exceeds
  **151.8442°** (window 151.84339…151.84493°, from 39,001 normal and 4,211 fallback days), Fajr M = trunc(trunc(sunrise − p·night)/60) — rounded like sunrise —
  with the wrapped sunrise and night = (86400 − Maghrib + sunrise) mod 86400 ("00:-06", "00:-09" at Tromsø/Hammerfest).
* **The portion p** = (sunrise₄₅ − fajr₄₅ + **58.99367 s**) / night₄₅ at latitude ±45° (same longitude and zone) on
  **21 June** of the year (**21 December** south: Ushuaia 0 vs 1 difference). Round 11 gave p to 1e-8 for four
  city-years; the addend is the same in all four within 0.001 s. Feasible: world 1 (58.99340, 58.99549], world 2
  (58.99355, 58.99460], London-2029 bisect (58.99339, 58.99380]; Anchorage-2029 bisect needs ≤ 58.99304 (0.35 ms
  model noise; 1 of 224 bisect queries). Vienna 2027-05-25 (margin 0.1 ms) moved the addend from 58.99345.
  The API field `fajrExactRabita` uses the same 45° ratio of the SAME day (+58.99 s).
* **m0 gets ONE day added if negative, nothing else** (never reduced): zones ≥ +12 around 20–22 March keep m0 < 0,
  so Dhuhr and the sunset first guess fall one day early (Auckland, Suva); a zone ~14 h ahead of the longitude keeps
  m0 ≥ 1 (Dhuhr "26:47"). Dhuhr itself gets one day added if negative, never reduced. **Fajr, Asr and Eid are Dhuhr ±
  hour angle and are never wrapped** ("26:23", "-02:-51", "24:00"); only sunrise and Maghrib are brought into the day
  (one day added or removed). Sunrise first guess +1 day if negative, sunset first guess −1 day if ≥ 1 (Reykjavík).
* **Eid unreachable:** Eid = sunrise + 15 min. **Zones 2 and 5** lag every day (Cairo, Karachi confirmed).
* **Not reproducible: grazing days.** McMurdo 2027-04-25, Hammerfest 2030-11-22, Tromsø 2031-05-18. In the model the
  Sun misses h0 by 0.07°, 0.009° (no root) or barely dips below it; the server's Newton iteration bounces and stops
  by chance (e.g. Maghrib "-11:-58"). The trajectory is chaotic (errors grow ×2–10 per step), so these values cannot
  be reproduced without the exact code. They occur on 0–2 days per year per place, only above ~66° latitude.

## 10b. Round 12 — bug hunt (2026-10-27)

Adversarial data: 260 single days placed 0.6–30 ms from a minute flip by the model (180 Saudi points 1940–2100, 60
world, 20 fallback Fajr above 48°); whole years for 6 Saudi cities in 1940…2150; 50 random world points (own zone,
random year 1950–2100) and 15 points with a deliberately wrong zone (offset up to 18 h). 350,434 + 1,820 values.

Found and fixed:
* **ΔT before 2000.** 1940–1999 were 30–100 ms late: the server uses historical ΔT before 2000 (Espenak–Meeus
  polynomials fit: 337 → 2 differences in 93,577 values; the two, 1994-11-28 and 1999-03-06, need ΔT ~1 s below
  Espenak–Meeus). From 2000 on the 102.3 + 123.5t + 32.5t² formula is confirmed (2000–2150, incl. all hard points).
* **Wrong zones:** Fajr/Asr/Eid are not wrapped; Dhuhr and m0 only get a day added (section 10).
* **Fallback threshold** narrowed by a point at 151.84493° (R16, 2092-04-16).

After the fixes: all 260 hard points correct (0.6 ms from the flip, any year); Saudi 1940–2150 whole years correct
except 2000-01-02. Remaining, not explained: three dates next to J2000 — 2000-01-02 (zone 3 and zone −6) and
2000-03-05 (zone −6) — are lag days on the server. In double arithmetic t·36525 lands one ulp below the exact
value and the sum rounds back up; the server's does not. No arithmetic variant tested (operation order, FMA, x87
extended, millennia, hours) reproduces these three without breaking the 10,592 other determinable days.

Hijri date (for Ramadan Isha): Java/Android `HijrahDate` (Umm al-Qura) equals the server's Hijri date on all 23,471
dates checked (1940–2150).

## 10c. Round 13 — all of Saudi Arabia (2026-10-27)

142 whole years (2026–2030) at random 6-decimal GPS points inside the Kingdom plus 12 extreme points (Farasan, Qummah
island, At-Tiwal, Haql, Humaydah, Al-Haditha, Halat Ammar, Al-Batha, Empty Quarter east and south, Al-Khafji,
Al-Wadiah): 363,013 values, **0 differences**. 260 single days 2026–2035 at random Saudi points with the decisive
time placed 0.5–20 ms from a minute change: **0 differences** (1,820 values). Hijri dates: Java HijrahDate equal on
all 1,952 dates. (All 136 governorates were already among the 173 cities of the full export, 2025–2035: 0.)

## 10d. Round 14 — five consultants, a clean holdout, the last rules (2026-10-29 … 11-01)

Five independent reviews (statistician, code review, attacker, IEEE-754 numerics, astronomer; `consult/*/REPORT.md`)
and seven browser scripts (`data/r14/`). Code frozen at sha256 8c88655b… until all results were in.

* **Clean holdout (frozen code).** 400 values 0.01–0.10 ms from a minute change, 8-decimal longitudes
  (`micro.json`): Saudi zone 3 **320/320**; zones 1 and 4: 6 misses, all ≈ +12 µs → next item. 138 years at Arar
  (2001–2149, `t1.json.gz`): 352,828 values **0 differences**, server Hijri month = Java HijrahDate on all
  50,404 days. Website output formats (12/24 h, Arabic/English, `t2`): identical.
* **jd0 rounding error.** jd0 = days + 2451544.5 − z/24 is a double; its rounding error (±13.4 µs in zones 1/4/7
  and 2/5, 0 where z/24 is exact) moves every event. Adding it: micro misses 6 → 1 (−12.9 µs, at the noise floor),
  round-10 zones 1/4/7 from +9…+15 µs to −1…+4 µs.
* **Lag days, exactly.** The numerics review found the date round trip floors: date of
  (J2000.0 + floor(t·36525·24 + z) hours). (Its first form, floor of seconds before adding the zone, failed on
  2000-01-02 in zones +7:30 and +9:45; flooring after the zone is added fixes both.) 6 lag days predicted before
  measuring all lagged; 46 tie days and all other lag facts: **10,641 / 10,641**. The old rule got 22 wrong.
* **Pre-1860 and pre-1582 dates.** All Espenak–Meeus segments (−500 … 1860) and SPA `julian_day` with the
  Gregorian correction **always** applied (the server's dates are proleptic Gregorian): 1000, 1500, 1582-10-04,
  1582-10-15, 1600, 1700, 1800 now match (before: hours off).
* **HL_HMAX.** Bisection on 2030-04-25, 10°E, zone 1: flip at 53.902302–53.902303°N, i.e. 151.8440087–151.8440117°;
  set to 151.84401° (151.844 and 4859/32 are excluded).
* **Constants' origin.** EPS_OFFSET and two thirds of SID_OFFSET are Meeus's VSOP87→FK5 frame correction
  (−0.09033″ in λ, a rigid rotation). The single-constant hypothesis H* (ceil = floor(t + 0.016388 h)) was
  rejected by 16 sunrise/Maghrib bisections at 60–64.5°N (`astro.json`): the current model fits within the brackets.
* **Input validation.** The server answers 400 for |lat| > 90, |lon| > 180, zone −13 / +14.5 and beyond, and
  impossible dates. The reference now raises ValueError for the same inputs.
* **Full verification after all changes:** 7,138,943 values, **20 differences** (18 grazing polar values, 2 in
  1994/1999), down from 47.
* Open: year 3600 in zones 2/5 shows 0.3–1.3 ms residuals (round 10); dates 1994–1999 (server ΔT ≈ 1 s lower);
  grazing polar days.

## 10e. Round 15 — second panel; dT table and transit loop (2026-11-01)

Second panel (clean-room implementer, dT historian, C#/.NET specialist, journal referee; `consult2/`).
* **Clean room.** A JavaScript implementation written from ALGORITHM.md alone (no access to the code) reached the
  same 20 mismatches as the reference on 7,138,943 values (identical outputs on them). First run: 3,844, all above
  48° latitude; Saudi 0 from the start. Its 3 real spec gaps (Eid fallback rounding, the latitude the 48° test uses
  on polar days, unwrapped 45° night) and 4 silent assumptions were written into the spec.
* **dT read directly** (`data/r15/deltat.json`, 35 Dhuhr flips, ±0.003 s): Meeus *Astronomical Algorithms* 1st ed.
  Table 9.A with linear interpolation, the last interval (1990–1992, 0.55 s/yr) extended to 1999: all 35 within
  ±0.005 s (1900 and 1920 confirm the 1st-edition values −2.7/0.0 and 21.2/22.4). Table taken from a Pascal port of
  that code (github.com/omarreis/vsop2013, Om.DeltaT.pas, entries 1620–1992). The post-2000 quadratic is
  Stephenson–Morrison 1984 expanded at 2000 (Meeus's fallback outside the table). Fixes 1994-11-28 and 1999-03-06.
* **Transit iterated** (`data/r15/dotnet-test.json`): the server repeats transit += −H/360 until |step| < 0.0002 day,
  like sunrise/sunset. 18/18 targeted Dhuhr minutes (step1 1.85e-4 → one step, ≥ 2.05e-4 → two steps). Ordinary
  dates are unchanged (one step); year 3600 zones 2/5 residuals −0.3/−1.3 ms → ~0.01 ms.
* **dT time argument**: T from SPA julian_day of the requested local midnight (not days from 2000-01-01): 12/12
  targeted year-3600 minutes; SID_OFFSET refitted 0.146657 → 0.146457″. Cost: rounds 7–9 RMS 0.0019 → 0.0024 ms
  (seasonal µs pattern, not absorbed by refitting SID/EPS/sunrise), 3600 residual 0.015 ms remains → the exact
  argument may differ slightly (open).
* **Date round trip in C#**: no plain DateTime/TimeSpan call reproduces it (ms rounding: 491 lags wrong; .NET 7+
  truncation: +8 misses); an explicit Math.Floor of hours is needed — runtime-independent.
* **Referee** (major revision): scope claims to Saudi Arabia/zone 3 (all clean evidence), add a surrogate-server
  recovery experiment, prequential validation, AI-use statement,
  repository checklist (`consult2/referee/report.txt`).
* **Full verification: 7,138,943 values, 18 differences** (only the 3 grazing polar days). Micro holdout: 2 misses
  in zone 1, both within 1.6 µs of the boundary under the current model.
* **Round 16** (`data/r15/r16.json`, 13 Dhuhr flips): Table 9.A confirmed 1650–1880, Stephenson–Houlden formulas
  confirmed at 1000, 1500, 1610 (all within ±0.003 s of dT), table extrapolation on 1999-12-31 and 2000-01-01
  (zone 3), quadratic on 2000-01-03. 2000-01-01 in zone −12 (T = 0 exactly) still uses the table → the quadratic
  applies for y > 2000 strictly. After the fix all 13 within their brackets; full verification still 18.

## 10g. Doubling the verification set (2026-11-02)

`consult2/final/double_part*.js` → `data/double/double-{1..4}.json.gz`: 2,804 new city-years, none overlapping the old
data (173 official Saudi cities in 2020–2024 and 2036–2040; 500 new random Saudi points, one random year 1950–2150
each; 114 world cities in 2027/2030/2036/2045; 59 world-2 places in 2028/2035). 2 years lost to network errors.
`double_compare.py`: **7,166,026 values, 49 mismatches** — Saudi cities 4,424,994: 0; world 1 1,165,878: 0;
world 2: 24 = 4 new grazing polar days (McMurdo 2028-10-23 and 2035-10-23, Longyearbyen 2028-10-26, Tiksi
2028-05-09; 6 values each, chaotic like the known 3); Saudi random: 25 = two whole days, 1997-03-14 and 1999-12-29
(zone 3, 3 points each), where the floor rule predicts a lag day and the server does NOT lag. Both have x = jd − 2451545
negative with |x| just below a power of two (−1023.625, −3.625). Rounding to a unit (tick … second), reciprocal
division, Julian-day addition orders and x87 extended-precision emulations were tested on 66,521 lag/no-lag facts:
all worse (27 … 46,908 wrong); the floor rule stays best (2 wrong). Open.
Combined with the old set: 14,304,969 values, 67 mismatches (42 on 7 grazing polar days, 25 on these 2 days).

## 11. Constants

| constant | value | status |
|---|---|---|
| Fajr / Eid / rise-set altitude | −18.5°, +4.2°, −0.8333° | standard / published |
| ΔT polynomial | 102.3 + 123.5t + 32.5t² (decimal year) | textbook (Meeus) |
| VSOP87D/SPA term set, Laskar obliquity, SPA GMST, 360.985647 | | textbook |
| Newton step with cos h | | structural, from data |
| stop test (v7) | \|dm\| < 15 s, both events | structural guess; 35 of 270 points in the wrong regime |
| stop test (v8) | sunrise \|dm + 0.41·(transit − m0)\| < 17.62 s, maghrib \|dm\| < 14.47 s | **empirical** |
| L0[1] amplitude | 0.033417 | **empirical** (rounded-looking constant) |
| ε offset for α and δ / extra for δ | +0.0105″ / −0.0550″ | **empirical** |
| GMST offset | +0.0450″ (3.0 ms) | **empirical** |
| node epoch offset | −5.00 s | **empirical** |
| δ epoch in rise/set iteration | +2.81 s (v8 +2.72 s) | **empirical** |
| δ epoch for Fajr/Asr/Eid | −2.38 s | **empirical** |
| h0 fine offset | −0.011″ | **empirical** |
| sunrise / maghrib offsets | +4.22 / −0.83 ms (v8 +4.48 / −0.79) | **empirical** |
| α-curvature term kc2, c2_0 (v9) | 0.0543 ms/mdeg², 4.5 mdeg² (GMST −0.00087″) | **empirical** |
| day-length term qday (v9) | 0.0417 ms per s of (transit − m0); ev_off +4.162 / −0.674 ms | **empirical** |
| high-latitude rules (48°, 151.84°, 0.32137, 45°) | | **empirical** (outside Saudi range) |

Compared with the previous candidate (r4/cand.json), these were removed: the two first-step scale factors
(`rs_kap`, now explained by cos h), the four solar-longitude harmonics (sinM, cosM, sinL, cosL), and the
ecliptic-latitude offset (dbeta). Removing them costs 0.584 → 0.596 ms RMS on the regime-unambiguous points.

## 12. Evidence

All minute-level comparisons were made without any refitting to those datasets. v7, v8 and v9 give the same counts
in the first seven rows.

| dataset | values | mismatches |
|---|---|---|
| official PDF timetables (3 cities, 1 year) | 10,650 | 0 |
| five-year API export (5 cities, Hijri 1444–1448) | 53,160 | 0 |
| probes "lag" (Riyadh 2022–2040) | 48,580 | 0 |
| probes "lonsweep" (24.67°N, 40 longitudes) | 102,200 | 0 |
| probes "highlat" 45–58° regular values | 29,580 | 0 |
| probes "highlat" all (50–65°, −45…−55°) | 51,100 | 11 (Fajr fallback above 58° only) |
| near-boundary one-sided constraints | 185 | 0 violated (closest margin v7 0.15 ms, v8 0.37 ms, v9 0.29 ms) |
| full export, 173 cities × 2025–2035 (`probe/full/compare.py v8\|v9`) | 4,864,587 | v8 27 (23 independent), v9 16 (14 independent) |
| … of which held-out years 2033–2035 | | v8 8, v9 5 (independent) |
| full-export near-boundary constraints (`constraints_full.json`, ±3 ms) | 408 | v8 23 violated, v9 14 |

**Millisecond bisection.** 328 minute-flip points in four rounds, each with a bracket of ±0.19 ms. The residual is
server minus model:

| event | n | RMS (ms) | RMS without the 11 regime outliers |
|---|---|---|---|
| Dhuhr | 75 | 0.30 | 0.30 |
| Asr | 17 | 0.41 | 0.41 |
| Fajr | 26 | 0.52 | 0.52 |
| Eid | 18 | 0.40 | 0.40 |
| Maghrib | 96 | 1.02 | 0.54 (n = 92) |
| Sunrise | 96 | 2.77 | 0.85 (n = 89) |
| all | 328 | 1.61 | 0.59 (n = 317) |

The residual is within the ±0.19 ms bracket for 108 of 328 points, ≤ 1 ms for 290 and ≤ 2 ms for 317. The 3,304
individual bisection queries give 301 minute differences, all within a few ms of a boundary.

**Rounds 1–5 (413 points), v7 → v8.** The v8 stop test and constants were fitted on these same points, so the v8
numbers are in-sample. The minute-level datasets above are the out-of-sample check.

| event | n | v7 RMS (ms) | v8 RMS (ms) | v8 points > 3 ms |
|---|---|---|---|---|
| Sunrise | 138 | 4.67 | 1.24 | 5 (4 on 2027-01-15, 43–51°N; 1 on 2027-03-21) |
| Maghrib | 139 | 1.35 | 0.63 | 1 (2027-11-05) |
| other events | 136 | unchanged (0.30–0.52) | | 0 |
| all | 413 | 2.82 | 0.84 | 6 |

Round 5 alone: v7 5.34 ms, v8 1.30 ms. The 4,154 bisection queries of rounds 1–5 give 463 (v7) and 375 (v8) minute
differences, all within a few ms of a boundary.

## 13. Known limits (not exact)

* **Full export, v9 (`probe/full/mismatches_v9.json`).** 14 independent misses remain: Sunrise 5, Fajr 3, Dhuhr 2,
  Maghrib 2, Asr 1, Eid 1. All are within 0.6 ms of a boundary. Two of them are new relative to v8 (Asr
  2035-11-25, Eid 2032-01-10). The Saudi sunrise is almost always the one-step value (section 7). Its residual has
  a date pattern of about ±1 ms (Dec −1.4, Jun/Jul −0.8, Feb +0.9, Sep/Oct +0.5 ms) and also depends on |dm₁|.
  Neither v9 term nor a seasonal start shift captures it. This is the main remaining source of misses. Getting to
  0 would need ≲0.1 ms accuracy everywhere. That means finding the actual rise/set procedure, not adding more
  fitted terms (LP fits on the 2025–2032 constraints reach 4–6 training violations but do not improve 2033–2035).

* **Regime of the rise/set iteration (v7; see the v8 stop test in section 7 for the round-5 result).** Near
  |dm₁| ≈ 15 s, 11 points (out of 192 rise/set points) fall in the wrong regime: one-step versus converged, a 4–12 ms jump. On 2027-03-21 the switch sits at the model's
  |dm₁| = 14.87–15.18 s (sunrise) and 14.41–14.80 s (maghrib). But sunrise on 2027-02-05, 08-06 and 11-05 is still
  one-step at |dm₁| = 15.8 s, and maghrib on 11-05 already converges at 14.2 s (one-step at 12.8 s). None of these
  variants is consistent with the data: a tolerance on dm₁, dm₂ or |h − h0|, a declination epoch for the first
  guess between −0.375 and +0.5 day, the transit as centre, or a low-precision first guess. The likeliest cause is
  a first guess that differs from the model's by ~1–4 s in a seasonal way. An empirical start shift
  (sunrise 1.5 + 2.5 sin λ☉ − 1.5 cos λ☉ s, maghrib 0.5 − cos λ☉ s, with 15 s kept) brings the 328-point RMS
  to 0.61 ms, but it is not part of v7. Round 5 (`probe/bisect/targets5.json`, `bisect5.js`, 85 targets) maps the
  switch on 17 (date, event) cases. Its result is the empirical v8 test. The underlying rule is still unknown.
* Sunrise on 2027-01-15 at 43–51°N (outside the Saudi range) is one-step with an extra +3 to +6 ms that grows with |dm₁|.
  No model here explains it.
* The seasonal sunrise pattern seen in round 4 (Z group) sits in the one-step regime: −1.7 ms in March and
  −1.9 ms in December, +0.7 ms in February. Converged sunrise points are within ±0.6 ms. So the pattern belongs
  with the first-guess question, not with the solar theory.
* The non-rise/set events sit at 0.3–0.5 ms RMS, about 3× the bracket noise. The fitted epoch and obliquity
  offsets above stand in for something not yet identified.
* Because of these limits we do not claim exactness at the millisecond level. Every minute value in the Saudi
  range tested so far is reproduced.
