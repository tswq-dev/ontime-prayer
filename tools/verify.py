#!/usr/bin/env python3
"""Compare the reference implementation with every stored answer of the Umm al-Qura server.

    python3 tools/verify.py            # about 1-2 minutes on 4 cores
    python3 tools/verify.py --quick    # every 20th row

data/vectors.csv.gz: one row per (dataset, date, place, zone) with the server's seven times; the Ramadan flag is an
input (taken from the server's Hijri month). The run must reproduce exactly data/expected_mismatches.json.
"""
import csv, gzip, json, os, sys
from collections import defaultdict
from datetime import date
from multiprocessing import Pool
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
import umm_al_qura_times as S
K = ['fajr', 'sunrise', 'dhuhr', 'asr', 'maghrib', 'isha', 'eid']

def check(rows):
    n, bad = defaultdict(int), []
    for r in rows:
        tag, ds, lat, lon, z, ram = r[:6]
        c = S.prayer_times(date.fromisoformat(ds), float(lat), float(lon), ramadan=ram == '1', tz=float(z))
        for k, v in zip(K, r[6:13]):
            if v == '':
                continue
            n[tag] += 1
            if c[k] != v:
                bad.append([tag, ds, lat, lon, z, k, v, c[k]])
    return dict(n), bad

def chunks(step):
    with gzip.open(os.path.join(ROOT, 'data', 'vectors.csv.gz'), 'rt', newline='') as f:
        rd = csv.reader(f); next(rd); buf = []
        for i, r in enumerate(rd):
            if i % step == 0:
                buf.append(r)
            if len(buf) == 5000:
                yield buf; buf = []
        if buf:
            yield buf

if __name__ == '__main__':
    step = 20 if '--quick' in sys.argv else 1
    tot, bad = defaultdict(int), []
    with Pool(os.cpu_count()) as pool:
        for n, b in pool.imap_unordered(check, chunks(step)):
            for k, v in n.items():
                tot[k] += v
            bad += b
    per = defaultdict(int)
    for b in bad:
        per[b[0]] += 1
    print(f"{'dataset':52s} {'values':>11s} {'mismatches':>10s}")
    for k in sorted(tot):
        print(f'{k:52s} {tot[k]:>11,} {per[k]:>10}')
    print(f"{'TOTAL':52s} {sum(tot.values()):>11,} {len(bad):>10}")
    if step == 1:
        exp = json.load(open(os.path.join(ROOT, 'data', 'expected_mismatches.json')))
        key = lambda b: tuple(b[:7])
        new = sorted(set(map(key, bad)) - set(map(key, exp)))
        gone = sorted(set(map(key, exp)) - set(map(key, bad)))
        print('identical to data/expected_mismatches.json' if not new and not gone else f'NEW {len(new)} {new[:5]}  GONE {len(gone)} {gone[:5]}')
        if '--write-expected' in sys.argv:
            json.dump(sorted(bad), open(os.path.join(ROOT, 'data', 'expected_mismatches.json'), 'w'), indent=0)
        sys.exit(0 if not new and not gone else 1)
