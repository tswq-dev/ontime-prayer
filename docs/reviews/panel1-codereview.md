# Code review report (saved by the coordinator; the agent's own write was blocked)

**Summary:** no real bug. The full verification reproduces exactly (7,138,943 values, 47 differences; about 1 min on
2 cores) and publish/umm_al_qura_times.py is byte-identical to the root file. Issues: Python-only semantics the spec
never states, missing input validation, doc/number errors — all addressed in `suggested.patch`
(patched copy `umm_al_qura_times.reviewed.py`, verified on all data and on 15,000 random inputs: bit-identical).

## Findings
1. **Python-only semantics (porter traps).** The polar test `(sunrise - maghrib) % 86400 < 3600` relies on floored
   `%`; with C/C#/Java `%`/fmod it is true on every ordinary day (Makkah: every day would take the 45° substitution).
   `if not abs(step) <= RS_BIGSTEP` is deliberately true for NaN. Comments say "sunrise floor / others ceil" but the
   code truncates toward zero (agrees only for s ≥ 0). `a % 1` and `hp % 360` assume floored modulo.
2. **Spec gaps.** The geocentric conversion λ = L+180, β = −B is not described; the lag rule needs SPA's exact
   `julian_day` (integer parts, Jan/Feb as months 13/14); polar substitution runs before the 48° test and Isha uses the
   substituted Maghrib (implicit); `_hl_portion` uses unwrapped 45° times and no mod for night₄₅ (equal to the wrapped
   form for |tz − lon/15| ≤ 3; untested beyond); no test vectors.
3. **No input validation.** lat 95 → plausible strings; lat NaN → silently 45° values; lon −400 → "34:28";
   tz 30 accepted; `ramadan="false"` → treated as True; year 1500 → ΔT = −34,056,790 s; datetime → obscure TypeError.
   Verified envelope: years 1940–2150, |lat| ≤ 78.2, |lon| ≤ 179.3, tz −12..14.
4. **Docstring/number errors.** `prayer_times` says "Saudi local time" (it is the local time of `tz`); return values
   ("24:39", "-01:-13", None) undocumented; `raw=True` undocumented (no Isha, no 48° rule); module docstring cites
   METHOD.md, not in publish/. Hunt-12 diffs split 27 near J2000 / 2 in 1994–1999 (ALGORITHM.md says 26 / 3).
5. **Leftovers/names.** Module `H0` (altitude) shadowed by local `H0` (hour angle); `H0_ALT = H0` defined below its
   use; tuning constants scattered; `dm` means declination at node −1 while the spec's dm is the Newton step;
   `dec = _sun(jd0+1+…)` recomputes node `dp`; `_julian_day` carries `(0+(0.0+0.0)/60)/60`; `converged` travels in the
   events dict.

## Next actions
1. Apply the patch (at least validation, docstring, comments).
2. Add finding 2 to ALGORITHM.md plus 20–30 test vectors (lag day, zone 2, polar fallback, "26:xx", "-01:-13",
   Ramadan, site_coordinate ties).
3. Fix 26/3 → 27/2; drop METHOD.md references or publish it.
4. State the supported range.
5. Optional owner script: GetPrayers for lat=95; lon=−400 and 400; zone=30; yg=1850, 1500, 2300 at Makkah — to mimic
   the server's behaviour on bad input instead of rejecting.
