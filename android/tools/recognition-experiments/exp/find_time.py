"""Как быстро подсветка находит место, если читать начали «в другом месте»: время от начала речи до попадания подсветки в читаемый участок."""
import sys, os, json, glob, statistics
sys.path.insert(0, 'exp')
from common import *
d = sys.argv[1]; variants = sys.argv[2].split(',')
def run(v):
    ts = []; fails = 0; n = 0
    for p in sorted(glob.glob(f'{d}/{v}__elsewhere__*.txt')):
        name = os.path.basename(p)[:-4]; clip = name.split('__')[2]
        if 'foreign' in clip or clip.startswith('fl') != (DS == 'fl'): continue
        tr = [int(x) for x in open(p).read().split()]
        ev = [json.loads(l) for l in open(f'{EV}/{name}.jsonl')]
        start = next(e['pos'] for e in ev if e['k'] == 'start')
        pe = [e for e in ev if e['k'] in ('p', 'f')]
        lo = pe[0]['ls']
        n += 1
        hit = None
        for k, (c, e) in enumerate(zip(tr, pe)):
            if c != start and lo - 3 <= c <= e['le'] + 3 and c >= lo:
                hit = k * 0.1; break
        if hit is None: fails += 1
        else: ts.append(hit)
    ts.sort()
    q = lambda p: ts[int(p * (len(ts) - 1))] if ts else float('nan')
    return n, fails, (sum(ts) / len(ts) if ts else float('nan')), q(0.5), q(0.9)
print(f'{"вариант":18s}{"клипов":>8s}{"не нашёл":>10s}{"сред, с":>10s}{"мед, с":>9s}{"p90, с":>9s}')
for v in variants:
    n, f, m, md, p9 = run(v)
    print(f'{v:18s}{n:8d}{f:10d}{m:10.2f}{md:9.2f}{p9:9.2f}')
