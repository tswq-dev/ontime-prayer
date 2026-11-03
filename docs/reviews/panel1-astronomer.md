# Astronomer report: origin of the fitted constants (saved by the coordinator; the agent's own write was blocked)

**Summary:** EPS_OFFSET and two-thirds of SID_OFFSET come from Meeus's standard correction from the VSOP87 frame to
FK5. A single ceil-rounding addend of 0.016388 h (58.9968 s) would explain SUNRISE_OFFSET, the rest of SID and
HL_TW_ADD with no fitted constants; Saudi rise/set bisections argue against it at about 6σ; one browser run decides.

## Findings
1. **EPS_OFFSET is the FK5 frame correction** (Meeus 25.9/32.3: Δλ = −0.09033″, Δβ = +0.03916″(cos λ′ − sin λ′)).
   Since 0.03916 = 0.09033·tan ε, it is a rigid rotation: RA shift −0.09033″/cos ε = −0.098453″ (≡ +0.098453″ on
   GMST) and obliquity −0.03916″ — degenerate with SID and EPS (the old "FK5 weight −30" test was not meaningful).
   With FK5 (λ′ = λ) and refit: EPS residual +0.000037 ± 0.000006″, SID 0.048195 ± 0.000002″, RMS unchanged 0.0033 ms
   (rounds 6–10, 714 points). With λ′ = λ − 1.397°·τ (τ in millennia, SPA's jme): EPS residual +0.000011″. Meeus's
   literal λ′ (T in centuries) rejected (RMS 0.0068 ms).
2. **No GMST/obliquity convention gives the remaining 0.048195″.** IAU 1982/Aoki −0.0001″; IAU 2000/2006 ERA-based
   −0.055…−0.083″ drifting; T² at TT < 1e-6″; coefficient rounding ≤ 0.0002″; drift 0.00004″/decade. Obliquity:
   IAU 2006 − Laskar −0.049…−0.052″ drifting; IAU 1980 −0.0017″.
3. **Candidate H\*: one rounding constant.** After FK5, the 0.048195″ shifts every event by −3.2130 ms; the sunrise
   offset (+3.2002 ms) nearly cancels it at sunrise. Rule: ceil done as floor(t + 0.016388 h) (59 s written to 6
   decimals of an hour = 58.9968 s); sunrise plain floor. Predicts a 3.2000 ms gap; the fit gives 3.2002 ± 0.0003 ms.
   H\* with FK5 + 0.016388 h and SID = EPS = SUNRISE = 0: Saudi export 3 / 4,864,587 (0 with an extra common
   +0.000195″ = 13.7 µs, unexplained); world 1: 0; world 2: the same 18 polar; London daily 0.
4. **HL_TW_ADD becomes the same constant.** Windows under H\*: world 1 (58.99676, 58.99869], world 2 (58.99675,
   58.99777], London 2029 (58.99663, 58.99704), Anchorage (58.99642, 58.99682); overlap (58.99676, 58.99682) contains
   0.016388 h = 58.99680 s — also resolving the London/Anchorage conflict (open problem 4).
5. **Counter-evidence.** Saudi rise/set bisections separate an hour-angle shift from a pure time shift (declination
   change makes GMST sensitivity vary ~0.15%): fitted 0.050 ± 0.008″ (sunrise), 0.046 ± 0.008″ (maghrib); H\* predicts
   0. Δχ² ≈ 119 favours the current model. Unresolved: either the 0.048″ is in the hour angle and the HL match is a
   coincidence, or a small declination-timing error mimics it.

## Next actions
1. Add FK5 (λ′ = λ) to the model, drop EPS_OFFSET, set SID = 0.048195″ (outputs unchanged, one constant derived).
2. Run the deciding bisection: `bisect_astro.js` / `targets_astro.json` (16 sunrise/maghrib bisections at 59.94°N
   30.3°E and 64.5°N 40.5°E, zone 3, on 2027-03-20, 06-21, 09-23, 12-21; ±25 ms, 13 halvings, ~240 requests). The two
   models differ by 0.006–0.016 ms against ~3 µs resolution. Score with `ev.py` for `uq` and `uq_h`.
3. If H\* wins, replace SUNRISE_OFFSET, the rest of SID and HL_TW_ADD with the single constant 0.016388 h.
