# C#/.NET specialist (round 2 panel) — summary saved by the coordinator
1. Date: no plain .NET call reproduces it. ms rounding (Framework, Core ≤6) excluded (491 lags wrong); .NET 7+
   AddHours / Core 3+ TimeSpan.FromHours truncation passes 10,641 facts but adds 8 full-verify misses (R15
   1988 lags) → excluded. Fits: explicit Math.Floor(t*36525*24+zone) (any unit ≥ tick), runtime-independent.
2. Year 3600 zones 2/5: the server iterates the TRANSIT with the 0.0002-day stop (one step on normal dates; two in
   3600 where ΔT≈2.9 h makes step1 3.4–3.6e-4). Residuals −0.29/−1.35 → +0.084/+0.105 ms; full verify 20, micro 1.
   Threshold window (1.83e-4, 3.38e-4) day. Secondary: ΔT from SPA julian-century of julian_day(d, zone) with
   SID 0.146457 → 3600 rms 0.014 ms; micro 2 misses (weaker).
3. No .NET SDK installable (proxy). 4. Grazing days not attempted.
Test: dotnet_test.js (30 requests): TL00–17 transit loop, DT00–11 ΔT argument.
Lead: 0.048195″ GMST residue = 3.213 ms ≈ SUNRISE_OFFSET 3.2001 ms.
