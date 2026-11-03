# Declination epoch for Fajr/Asr/Eid — fresh-eyes check (summary saved by the coordinator)

Verdict: the server's single declination for Fajr, Asr and the Eid prayer belongs to the local midnight at the END
of the displayed day (24:00 of the requested zone), pinned to well under a second; no conversion makes it the previous
night.
- Saudi export, 2,084,823 Fajr/Asr/Eid values: next local midnight 0 mismatches; 0h UT of next date 104,856; local
  noon ~418,000; 0h UT of the date 731,846; start-of-day midnight 835,832; previous midnight 1,397,707; the event's
  own moment 581,697. Epoch shift ±0.9 s already gives ~10 mismatches.
- 380,031 world values in 30 zones (−11 … +14): local-midnight epoch 0 mismatches; a fixed UT epoch fails in every
  zone except 0.
- Millisecond bisection (329 Fajr/Asr/Eid points): epoch offset 0.00 ± 0.04 s.
- Lag days move the whole frame (node included) back one day, consistently.
- Physics: with the server's own ingredients evaluated at the event moment, Riyadh 2026-03-20 Fajr is 04:38:49 on the
  server vs 04:39:32 true (43 s early); 2026 Fajr minute differs from the true one on 31–52 % of days (Abha–Tabuk).
