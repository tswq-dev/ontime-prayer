# ΔT historian (round 2 panel) — summary saved by the coordinator
- Post-2000 ΔT 102.3+123.5t+32.5t² = Stephenson–Morrison 1984 (−15+0.00325(y−1810)²) expanded at 2000; Meeus's
  "outside the table" fallback (AA1 ch. 9). Server = Meeus pattern: table up to 1999, quadratic after.
- cons.py: 245 binding ΔT bounds 1940–1999; server ≤ EM+0.05…0.20 s before 1990; 1994: [58.03, 59.61] (EM 60.69);
  1999: [62.13, 62.94] (EM 63.56). No candidate (EM variants, USNO/IERS, Meeus biennial approx., Schmadel–Zech,
  MS2004, SPA 67 s) passes all bounds; source unidentified.
- Test: bisect_deltat.js (35 Dhuhr flips, Riyadh lat, zone 3, ≤420 requests, ±0.003 s ΔT); analyze.py.
