# Statistician report (saved by the coordinator; the agent's own write was blocked)

**Verdict:** the structure is probably right for Saudi Arabia in 2025–2035. The evidence is mis-weighted: the only
sub-millisecond evidence is in-sample bisection, and the bulk and "hard point" tests are much weaker than the report
implies.

## Findings
1. **"0 in 4.86M" shows structure, not accuracy.** A minute flips with probability E|e|/60 s. The ablations confirm
   it: removing SID_OFFSET (9.78 ms) predicts 793 differences, 769 were observed; the 3.2 ms sunrise offset predicts
   about 43, 36 were observed. Zero flips therefore gives a 95% bound of mean|e| < 0.04 ms (about 4.05M effective
   values, since Isha follows Maghrib; `calc.py`).
2. **Hard points are weak precision tests.** Margins in targets12/13 run 0.38–30 ms, median 3.7 ms; only 119 are
   ≤ 1 ms. Zero failures bound σ < 0.31 ms (95%) and bias < 0.38 ms; the natural 4.86M values are about 6× tighter.
   They catch gross or regime errors (the pre-2000 ΔT error was 30–100 ms). The table's 3,080 and 560 "values" are
   really 440 and 80 decisive points.
3. **The 0.002 ms RMS is mostly bracket noise.** Re-run on rounds 7–9 (458 points, `resid.py`): RMS 0.0019 ms, max
   0.0044 ms; uniform bracket noise alone gives 0.0017 ms, so the excess sd is about 0.0009 ms. It is in-sample
   (Saudi, zone 3, latitude 10–40°, 2025–2035, these rounds shaped v12–v14). The only out-of-sample µs data (round 10,
   `resid10.py`): zones 1/4/7 are off by +0.009 to +0.017 ms. Outside 2025–2035 precision is about 1 ms at best.
4. **The risk is adaptivity, not parameter count.** About 7 fitted constants and 15–20 discrete choices against
   about 460 µs points, chosen over 11 adaptive rounds. Round-8 targets were picked where the export disagreed, so the
   export's near-boundary values are partly in-sample. World-2 went from 76 to 18 after fixes; round 12 found the
   ΔT bug. The clean holdout for the frozen code is round 13 alone (363,013 values, 260 hard points). "All 47
   explained" means located: lag days, 1994/1999 and grazing days are not understood.
5. **User-year risk.** With 5 independent events a day, uniform fractional position and Gaussian error:
   σ = 0.002 ms → one flip per about 20,000 user-years; σ = 0.001 ms → about 41,000. Model-free, the counts alone give
   at least about 740 user-years (95%). Zones 1/4/7 (0.012 ms) → about 2,700 years; Fajr fallback above 48° → about
   1,400–2,500. Across 30M Saudi users that is roughly 1,500 people a year seeing one wrong minute. "Several thousand"
   is defensible for Saudi Arabia but is not derived from the 0.002 figure; a server change is not covered.

## Claims in the report
- Overstated: "measured the error directly… average 2 µs" (noise floor, in-sample); "all explained"; the hard-point
  paragraph; "all non-polar world cities match" (tuned on those data); the 7,138,943 total mixes in-sample and clean.
- Understated: the Saudi 2025–2035 residual is indistinguishable from zero; missing that precision falls to about
  1 ms after 2035 and to about 0.01 ms in other zones.

## Actions
1. Frozen-code micro-margin holdout: `micro.js` + `micro_targets.json` (400 requests, margins 0.01–0.10 ms,
   8-decimal longitudes, about 3 minutes, code sha256 starts 8c88655baf3eca9f). Zero mismatches would bound
   σ < 0.007 ms out-of-sample. Do not edit the reference code before the run.
2. Rewrite the claims above; add a "fitted / tuned / clean" column per dataset.
3. Bisect about 30 µs points each for 2040, 2060, 2100 and zones 1/4/7.
4. Offline: histogram the v14 margins on the export.
