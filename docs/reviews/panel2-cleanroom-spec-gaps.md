# Spec gaps: clean-room run of ALGORITHM.md (JavaScript, Node, no packages)

- **First full run (code written from the spec, nothing fixed yet): 3,844 mismatches** out of 7,138,943 values
  (eid 2,547, fajr 1,284, plus 13 sunrise/asr/maghrib/isha). All of them are at |φ| > 48° or on the 2 known ΔT dates.
  Zero mismatches in every Saudi dataset, the lag probes, the longitude sweep and London.
- **Final: 20 mismatches.** This is exactly `reference_mismatches.json`: the same 20 values, and my output equals the
  reference output on each one (3 grazing polar days, plus 1994-11-28 and 1999-03-06).
- Each count below = extra mismatches when only that one choice is reverted in the final code.

## Gaps that needed a fix (found through the data)
1. **Eid fallback rounding** (step 8, "If unreachable: sunrise + 15 min"). *Severity: high, 2,543 values.*
   I assumed that, like every non-sunrise time, it rounds up: trunc((s+59)/60) of sunrise_s + 900. The server
   floors it like sunrise: M = trunc(trunc(sunrise_s + 900)/60), i.e. sunrise's minute + 15. *Found:* every eid
   mismatch above about 64° was exactly 1 minute late.
2. **The Fajr-above-48° test uses the latitude in use** (step 12, "its hour angle exceeds 151.84401° (tested on the
   raw times)"). *Severity: high, 1,193 values.* I read "raw" as "at the real φ", so on polar days the fallback fired
   (Fajr does not exist at φ) and gave 01:18 instead of the server's 01:57 (Tromsø, May). What is needed: the
   |φ| > 48 test uses the real φ, but the "does not exist / > 151.84401°" test uses the hour angle at the latitude
   actually used (±45° on step-11 polar days). The fallback then uses the 45° sunrise and Maghrib. *Found:* in
   polar days the server shows the plain 45° Fajr in May and mid-January, and fallback-like values in June and early
   January. That fits the 45° hour angle passing 151.84°.
3. **night₄₅ is NOT reduced mod 86400** (step 12, which defines night with "mod 86400" and then reuses the name
   night₄₅ for p). *Severity: medium, 88 values* (one hunt point: 49.83° N, 95.2° E, zone −4, 2024, where Maghrib
   comes before sunrise in the day). What is needed: p = (sunrise₄₅ − fajr₄₅ + 58.99367)/(86400 − Maghrib₄₅ +
   sunrise₄₅), with fajr₄₅ raw (here −8.8 h, not brought into the day). The current day's night does stay mod 86400.
   *Found:* I enumerated the 8 wrap/no-wrap combinations. Only this one gives the server's 10:41.

## Silent assumptions (my first guess was right, but the spec does not say; a wrong guess would cost N)
4. **Step 7/11, "the day or the night is shorter than one hour": the spec does not define it.** I used the in-day
   values: day = (Maghrib − sunrise) mod 86400, night = 86400 − day. Using the raw Newton results instead costs 18,550.
5. **H is normalised to ±180°** (SPA limit_degrees180pm) in H(m) for Dhuhr and Newton. The spec writes H(m) with no
   range, and "never mod 1" invites leaving it raw. Unlimited H costs 291.
6. **"Added to or subtracted from Dhuhr" means the Dhuhr after the "+1 day if negative" step.** Using the transit
   before that step costs 217 (zones far ahead of the longitude).
7. **The fallback Fajr's "in-day sunrise" includes the +3.2001 ms.** Without it: 12.
8. Minor or harmless (0 values in this data):
   - the ΔT t origin is the requested date at 0h UT (not local midnight);
   - Espenak–Meeus decimal year is y = year + (month − 0.5)/12 (an exact day fraction also gives 0);
   - the order of operations in floor(t·36525·24 + z) is left to right;
   - jd0 is recomputed with SPA julian_day for the computed date;
   - interpolation uses |diff| ≥ 2 (SPA) rather than "> 2°";
   - the 21 June / 21 Dec p-day uses the requested year and the full pipeline, lag rule included.

## Not resolvable from the spec (stopped here)
- The 18 grazing-polar-day values and the 2 ΔT values (1994, 1999). The spec itself declares them irreproducible.
