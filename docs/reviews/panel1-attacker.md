# Adversarial tester report (saved by the coordinator; the agent's own write was blocked)

**Summary:** weakest points are the unverified Hijri table behind Ramadan Isha (a 30-minute error on the days it
fails) and the never-compared website 12-hour / English paths. Three console scripts ready: T1 (138 requests),
T2 (144), T3 (133); all dry-run with a mocked fetch, none touched the server.

## Findings (Saudi users first)
1. **Ramadan Isha rests on a partly unverified Hijri table.** HijrahDate was checked on 23,499 dates, but only 133 of
   the 434 month-9 start/end days of 1940–2150 are among them (`ram_boundary.py`). 2025–2035 is covered (Isha-gap vote
   agrees with month 9 on all 4,017 dates). New fact: the Ramadan flag follows the REQUESTED date's Hijri month, not the
   lag-day computed date (3 Saudi lag boundaries 2028-01-28, 2029-01-16, 2029-02-14, plus 403 world rows incl. zones
   2/5; `ram_world.py`). T1: 138 whole years (2001–2024, 2036–2149) at Arar — server month vs HijrahDate, Isha gap,
   all 7 times, 2,163 determinable lag days.
2. **Website vs API never compared.** All 7.1M values are `format=24&lang=ar`. T2: 36 stored hard points × 4 variants
   (24/ar control, 12/ar, 12/en, 24/en); the control also detects server drift since 2026-10-27.
3. **How the website chooses the zone is unknown.** The server does no DST itself (15 wrong-zone points gave 0
   differences), so the formula is safe for any zone it is given; untested is what the page sends (Sydney user, Saudi
   user with a New York phone). `w_hook.js`: manual protocol logging the page's calls under DevTools sensor overrides.
4. **Garbage-in never probed:** (0,0), swapped lat/lon, −0, lat ±90/91, lon ±180 and 181–540, `1e1`, `+24.5`,
   15 decimals, zones 0.00001/24/100, years 1000/1582/3000, invalid dates. T3 groups E, Z, D: 87 requests.
5. **Open problem 2 is a structured family.** For tz=3, 1940–2150, the model lags exactly where fl(t·36525) is 1 ulp
   below jd−2451545 (3,387 of 3,388 days; `ties.py`); the exception is 2000-01-02. All three anomalies fall on day
   counts from 2000-01-01 that are powers of two (1, 1, 64), where the float spacing below is half that above.
   Hypothesis: the server lags on every such tie day. 46 (date, zone) pairs after 2000 where the model does not lag,
   e.g. 2002-10-21 zone −6, 2022-06-06 zone 4, 2005-08-10 zone 5.5, 2044-11-09 zone −3. Saudi exposure: only
   2000-01-02. T3 group L: 46 requests with both predictions.
6. Elsewhere low risk: addend conflict 0.35 ms (~1 in 2×10⁵ fallback Fajr values); grazing days, 1994/1999 known.

## Actions
1. Run T1 (~10 min), T2 and T3 (~3 min each); compare with `python3 -I tN_compare.py <file>.gz`.
2. ALGORITHM.md: Ramadan flag keyed to the requested date; name the Hijri source (HijrahDate, 1300–1600 AH).
3. If T3 confirms lag on every power-of-two tie day, add the rule.
4. If T2 shows any difference, define the target as what the website displays and re-measure.
5. Run `w_hook.js` (~10 min) and record the zone rule.
