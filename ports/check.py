#!/usr/bin/env python3
"""Compare a port's verifier output (stdin) with data/expected_mismatches.json. Exit 1 on any difference."""
import json, sys, pathlib
exp = {'|'.join(b[:7]) for b in json.load(open(pathlib.Path(__file__).resolve().parent.parent / 'data' / 'expected_mismatches.json'))}
got, n = set(), None
for line in sys.stdin:
    line = line.rstrip('\n')
    if line.startswith('values '): n = int(line.split()[1])
    elif line: got.add(line)
extra, missing = sorted(got - exp), sorted(exp - got)
print(f'values {n} | mismatches {len(got)} | not in expected {len(extra)} | expected but matched {len(missing)}')
for b in extra[:10]: print('  extra:', b)
for b in missing[:10]: print('  missing:', b)
sys.exit(0 if n == 14657797 and not extra and not missing else 1)
