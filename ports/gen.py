#!/usr/bin/env python3
"""Generate the Kotlin / Swift / Dart ports from ports/templates, filling in the tables of js/umq.js (exact decimal strings)."""
import json, re, pathlib
R = pathlib.Path(__file__).resolve().parent
js = (R.parent / 'js' / 'umq.js').read_text()
T = json.loads(re.search(r'const T = (\{.*?\});\n', js).group(1))
DT = [x.strip() for x in re.search(r'const DT_TABLE = \[(.*?)\];', js).group(1).split(',')]
num = lambda v: repr(float(v))                       # shortest round-trip decimal: same double in every language
def terms(keys, lang):
    out = []
    for k in keys:
        rows = T[k]
        if lang == 'kt':  out.append('arrayOf(' + ', '.join('doubleArrayOf(' + ', '.join(num(x) for x in r) + ')' for r in rows) + ')')
        else:             out.append('[' + ', '.join('[' + ', '.join(num(x) for x in r) + ']' for r in rows) + ']')
    return (',\n        ' if lang == 'kt' else ',\n    ').join(out)
L, B = ['L0', 'L1', 'L2', 'L3', 'L4', 'L5'], ['B0', 'B1']
dt = ', '.join(num(x) for x in DT)
for tpl, out, lang in [('UmmAlQura.kt.tpl', 'kotlin/UmmAlQura.kt', 'kt'), ('UmmAlQura.swift.tpl', 'swift/UmmAlQura.swift', 'sw'), ('umm_al_qura.dart.tpl', 'dart/umm_al_qura.dart', 'dart')]:
    p = R / 'templates' / tpl
    if not p.exists(): continue
    s = p.read_text().replace('%L_TERMS%', terms(L, lang)).replace('%B_TERMS%', terms(B, lang)).replace('%DT_TABLE%', dt)
    (R / out).write_text(s); print('wrote', out)
