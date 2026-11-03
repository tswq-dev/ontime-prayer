# Numerics (IEEE-754 / C#) report (saved by the coordinator; the agent's own write was blocked)

## Findings
1. **Lag-day puzzle solved (verified by the coordinator).** The server floors to whole seconds from J2000.0 before
   adding the zone: `s = Math.Floor(t*36525.0*86400.0)`, then `cd = (2000-01-01 12:00 + s seconds + zone hours).Date`
   (equivalently: lag when fl(t·36525) < D − ½ − tz/24, compared exactly). Drop-in: `server_date_floor.py`.
   Over 37 zones and 77,063 days (1940–2150) it differs from the current double rule in only 9 (zone, day) pairs, all
   lags, at day counts D = 1, 9, 32, 64, 129, 512, 1024, 16384 (where y+½ crosses a power of two). Three of the 9 are
   the known server lags (2000-01-02 zones +3 and −6, 2000-03-05 zone −6); the other 6 have no data yet (test A).
   Floor in hours/minutes/seconds/ms/ticks are identical. Ruled out: truncation toward zero (R15 lags on 1988-10-27,
   11-11, 12-03, 12-25 contradict it), .NET Framework ms rounding (469 bad), t·(36525·K) folding (>2,500 bad),
   `decimal` (erases every 1-ulp lag).
   **Coordinator check (consult/numerics/verify_floor.py):** lagtruth 4,385 days 0 wrong; full verification
   7,138,943 values: 47 → **20** differences (18 polar grazing + 2 pre-2000 ΔT); Saudi far years 1940–2150: 21 → 0.
2. **Two double-to-integer conversions in the server.** Event seconds truncate toward zero (all 45 negative
   Fajr/Asr/Eid cases where floor and trunc differ match trunc, `negsec.py`); the date uses floor (separate path, e.g.
   `J2000.AddSeconds(Math.Floor(days*86400)).AddHours(zone).Date`). Minutes are integer arithmetic `(sec+59)/60`,
   `m/60`, `m%60` with `{0:00}` — explains "-11:-58"; not TimeSpan components (sec = −90 → data show 0, TimeSpan −1).
3. **HL_HMAX: no natural origin found.** Only the Fajr hour angle separates 40,179 normal from 5,819 fallback cases
   (`hmaxvars.py`). Values inside the window: 151.844 (shortest decimal, probably hard-coded), 4859/32 = 151.84375,
   −cos H = 0.88167. Geometry gives nothing round.

## Next actions
1. Replace `server_date` with the floor rule (done in a check, not yet in the reference file).
2. Owner runs test A (6 predicted lag days) — script below.
3. Owner runs test B to pin HL_HMAX to ±3e-6° (151.844 vs 151.84375).

## Scripts
A. Predicted lag days (expected sunrise/Maghrib if it lags | if not):
−9 2000-02-02 07:17/17:10 | 07:16/17:12; −9 2001-05-27 04:23/19:31 | 04:22/19:32; −6 2002-10-21 06:20/17:09 |
06:22/17:07; −3 2044-11-09 06:46/16:41 | 06:47/16:40; 12.75 2000-01-10 05:26/20:45 | 05:28/20:44; 12.75 2000-05-09
05:41/20:11 | 05:40/20:12. (Script in the agent's reply; points lat 44/−44, lon = 15·zone.)
B. HL_HMAX bisection on 2030-04-25, lon 10, zone 1, lat 53.9020–53.9027 (Fajr 02:11 normal / 01:58 fallback);
model flips at lat 53.902218 for 151.84375 and 53.902299 for 151.844.
